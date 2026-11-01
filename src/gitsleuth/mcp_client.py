"""MCP client: connect to one server, discover its tools, call them, and reconnect after a crash.

``mcp.Client`` holds anyio task groups, which must be entered and exited by the same task. A retry
can come from any task, so one supervisor task owns the ``Client`` and other tasks only ask it to
reopen the session.
"""

from __future__ import annotations

import logging
import math
from types import TracebackType

import anyio
from anyio.abc import TaskGroup
from mcp import Client
from mcp.client.stdio import StdioServerParameters
from mcp.shared.exceptions import MCPError
from mcp_types import ImageContent, TextContent, Tool
from pydantic import ValidationError

from gitsleuth.dispatcher import ConnectionLost, ToolOutput, ToolProtocolError, ToolTimeout
from gitsleuth.logs import log_event

logger = logging.getLogger(__name__)

_REQUEST_TIMEOUT = -32001
_CONNECTION_CLOSED = -32000
_MAX_TOOL_PAGES = 50

McpTarget = StdioServerParameters | str


class McpConnectError(Exception):
    """The server could not be reached, or its handshake or tool listing failed."""


def _content_to_text(result_content: list) -> str:
    parts = []
    for block in result_content:
        if isinstance(block, TextContent):
            parts.append(block.text)
        elif isinstance(block, ImageContent):
            parts.append(f"[{block.mime_type} image omitted]")
        else:
            parts.append(f"[{block.type} content omitted]")
    return "\n".join(parts)


class McpConnection:
    """A supervised session with one MCP server. Use it with ``async with``."""

    def __init__(self, name: str, target: McpTarget, *, connect_timeout: float = 20.0) -> None:
        self.name = name
        self.target = target
        self.connect_timeout = connect_timeout
        self.tools: list[Tool] = []
        self.generation = 0
        self._client: Client | None = None
        self._error: BaseException | None = None
        self._closing = False
        self._wake = anyio.Event()
        self._settled = anyio.Event()
        self._reconnect_lock = anyio.Lock()
        self._group: TaskGroup | None = None

    async def __aenter__(self) -> McpConnection:
        self._group = anyio.create_task_group()
        await self._group.__aenter__()
        self._group.start_soon(self._supervise)
        await self._settled.wait()
        if self._client is None:
            error = self._error
            await self._shutdown()
            raise McpConnectError(f"could not connect to MCP server {self.name!r}: {error}")
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self._shutdown()

    async def _shutdown(self) -> None:
        self._closing = True
        self._wake.set()
        group, self._group = self._group, None
        if group is not None:
            await group.__aexit__(None, None, None)

    async def _supervise(self) -> None:
        while not self._closing:
            self._wake = anyio.Event()
            self._error = None
            try:
                await self._run_one_session()
            except Exception as error:  # ExceptionGroup from a transport crash lands here too.
                self._error = error
            self._client = None
            if self._error is not None:
                log_event(logger, "mcp_session_failed", server=self.name, error=str(self._error))
                self._settled.set()
                await self._wake.wait()

    async def _run_one_session(self) -> None:
        with anyio.CancelScope(deadline=anyio.current_time() + self.connect_timeout) as scope:
            async with Client(self.target, cache=None) as client:
                tools = await _list_all_tools(client)
                scope.deadline = math.inf
                self.tools = tools
                self.generation += 1
                self._client = client
                log_event(
                    logger,
                    "mcp_connected",
                    server=self.name,
                    generation=self.generation,
                    protocol=client.protocol_version,
                    tools=[tool.name for tool in tools],
                )
                self._settled.set()
                await self._wake.wait()
        if scope.cancelled_caught:
            self._error = TimeoutError(f"timed out after {self.connect_timeout:g}s")

    async def call(self, tool: str, arguments: dict, timeout: float) -> ToolOutput:
        client = self._client
        generation = self.generation
        if client is None:
            raise ConnectionLost(f"not connected to {self.name!r}", generation)
        try:
            result = await client.call_tool(tool, arguments, read_timeout_seconds=timeout)
        except MCPError as error:
            if error.code == _REQUEST_TIMEOUT:
                raise ToolTimeout(f"{tool} timed out after {timeout:g}s") from error
            if error.code == _CONNECTION_CLOSED:
                raise ConnectionLost(f"connection to {self.name!r} closed", generation) from error
            raise ToolProtocolError(f"MCP error {error.code}: {error.message}") from error
        except ValidationError as error:
            raise ToolProtocolError(f"{self.name!r} sent a malformed result") from error
        except Exception as error:
            raise ConnectionLost(f"transport failure to {self.name!r}: {error}", generation) from (
                error
            )
        return ToolOutput(text=_content_to_text(result.content), is_error=result.is_error)

    async def recover(self, lost: ConnectionLost) -> None:
        """Reopen the session unless another caller already replaced the one that failed."""
        async with self._reconnect_lock:
            if self._client is not None and self.generation != lost.generation:
                return
            self._settled = anyio.Event()
            self._wake.set()
            await self._settled.wait()
            if self._client is None:
                raise ConnectionLost(f"reconnect to {self.name!r} failed", self.generation)


async def _list_all_tools(client: Client) -> list[Tool]:
    tools: list[Tool] = []
    cursor = None
    for _ in range(_MAX_TOOL_PAGES):
        page = await client.list_tools(cursor=cursor)
        tools.extend(page.tools)
        cursor = page.next_cursor
        if cursor is None:
            return tools
    raise McpConnectError(f"tool listing did not finish within {_MAX_TOOL_PAGES} pages")
