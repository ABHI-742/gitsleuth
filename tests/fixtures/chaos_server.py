"""A real MCP server that misbehaves on request, for testing the client and dispatcher."""

from __future__ import annotations

import os
import sys
from pathlib import Path

import anyio
from mcp.server import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp_types import ToolAnnotations

state_dir = Path(sys.argv[1])
server = MCPServer("chaos", version="0.0.1")
READ_ONLY = ToolAnnotations(readOnlyHint=True, idempotentHint=True)


def bump(name: str) -> int:
    """Count calls in a file, so the count survives this process being killed."""
    path = state_dir / f"{name}.count"
    count = int(path.read_text()) + 1 if path.exists() else 1
    path.write_text(str(count))
    return count


@server.tool(annotations=READ_ONLY)
async def echo(message: str) -> str:
    """Return the message."""
    bump("echo")
    return f"echo: {message}"


@server.tool(annotations=READ_ONLY)
async def slow_once(seconds: float) -> str:
    """Hang on the first call, then answer immediately."""
    if bump("slow_once") == 1:
        await anyio.sleep(seconds)
    return "finished"


@server.tool(annotations=READ_ONLY)
async def always_slow(seconds: float) -> str:
    """Hang on every call."""
    bump("always_slow")
    await anyio.sleep(seconds)
    return "finished"


@server.tool(annotations=READ_ONLY)
async def crash_once() -> str:
    """Kill the server process on the first call, then answer on later ones."""
    if bump("crash_once") == 1:
        os._exit(1)
    return "survived"


@server.tool(annotations=ToolAnnotations(readOnlyHint=False, idempotentHint=False))
async def write_then_crash() -> str:
    """Stands in for a tool with side effects: it must not be retried after a lost connection."""
    bump("write_then_crash")
    os._exit(1)


@server.tool(annotations=READ_ONLY)
async def tool_failure() -> str:
    """Report a failure the model should read."""
    bump("tool_failure")
    raise ToolError("the tool looked and found nothing")


server.run("stdio")
