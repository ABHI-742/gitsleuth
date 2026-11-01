"""Wire the MCP connections, local tools and dispatcher together for one repository."""

from __future__ import annotations

import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path

from mcp.client.stdio import StdioServerParameters

from gitsleuth.dispatcher import ToolDispatcher, ToolRegistry
from gitsleuth.mcp_client import McpConnection, McpTarget
from gitsleuth.notebook import Notebook


def stdio_target(server: str, repo: Path | str) -> StdioServerParameters:
    """Launch a bundled server as a subprocess with this interpreter."""
    return StdioServerParameters(
        command=sys.executable,
        args=["-m", "gitsleuth.servers.run", server, "--repo", str(repo)],
    )


@dataclass
class Toolbox:
    registry: ToolRegistry
    dispatcher: ToolDispatcher
    notebook: Notebook


@asynccontextmanager
async def open_toolbox(
    code_target: McpTarget, history_target: McpTarget, **dispatcher_options
) -> AsyncIterator[Toolbox]:
    async with McpConnection("code", code_target) as code:
        async with McpConnection("history", history_target) as history:
            registry = ToolRegistry()
            registry.add_mcp_server(code)
            registry.add_mcp_server(history)
            notebook = Notebook()
            notebook.register(registry)
            yield Toolbox(registry, ToolDispatcher(registry, **dispatcher_options), notebook)
