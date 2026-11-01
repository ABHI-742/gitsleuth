"""The real client talking to real server processes over the Streamable HTTP transport."""

from __future__ import annotations

import json
import socket
import subprocess
import sys
import time

import pytest

from gitsleuth.dispatcher import ToolCall, ToolDispatcher, ToolRegistry
from gitsleuth.mcp_client import McpConnectError, McpConnection
from gitsleuth.toolbox import open_toolbox


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def start_server(kind: str, repo, port: int) -> subprocess.Popen:
    process = subprocess.Popen(
        [
            sys.executable,
            "-m",
            "gitsleuth.servers.run",
            kind,
            "--repo",
            str(repo),
            "--transport",
            "streamable-http",
            "--port",
            str(port),
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    deadline = time.monotonic() + 30
    while True:
        with socket.socket() as sock:
            if sock.connect_ex(("127.0.0.1", port)) == 0:
                return process
        if time.monotonic() > deadline:
            process.kill()
            raise TimeoutError(f"{kind} server did not start")
        time.sleep(0.1)


def stop_server(process: subprocess.Popen) -> None:
    process.kill()
    process.wait()


async def test_tools_work_over_streamable_http(sample_repo):
    repo, shas = sample_repo["path"], sample_repo["shas"]
    code_port, history_port = free_port(), free_port()
    code = start_server("code", repo, code_port)
    history = start_server("history", repo, history_port)
    try:
        async with open_toolbox(
            f"http://127.0.0.1:{code_port}/mcp", f"http://127.0.0.1:{history_port}/mcp"
        ) as box:
            assert "history__git_log" in box.registry.names()
            log = await box.dispatcher.dispatch(ToolCall("t", "history__git_log", {"limit": 1}))
            assert json.loads(log.text)["commits"][0]["sha"] == shas["binary"]
            error = await box.dispatcher.dispatch(
                ToolCall("t", "code__read_file", {"path": "../x"})
            )
            assert error.error_kind == "tool_error"
    finally:
        stop_server(code)
        stop_server(history)


async def test_http_server_that_died_is_reported_then_picked_up_after_restart(sample_repo):
    repo = sample_repo["path"]
    port = free_port()
    process = start_server("code", repo, port)
    try:
        async with McpConnection("code", f"http://127.0.0.1:{port}/mcp", connect_timeout=5) as conn:
            registry = ToolRegistry()
            registry.add_mcp_server(conn)
            dispatcher = ToolDispatcher(registry, call_timeout=5.0)
            call = ToolCall("t", "code__list_files", {})
            assert (await dispatcher.dispatch(call)).is_error is False

            stop_server(process)
            failed = await dispatcher.dispatch(call)
            assert failed.is_error
            assert failed.error_kind in ("connection", "timeout")
            assert failed.attempts == 3

            process = start_server("code", repo, port)
            recovered = await dispatcher.dispatch(call)
            assert recovered.is_error is False
    finally:
        stop_server(process)


async def test_connecting_to_nothing_raises_a_clear_error():
    with pytest.raises(McpConnectError, match="could not connect"):
        async with McpConnection("code", f"http://127.0.0.1:{free_port()}/mcp", connect_timeout=5):
            pass
