"""Timeouts, crashes and retries against a real server process that misbehaves on request."""

from __future__ import annotations

from pathlib import Path

import anyio

from gitsleuth.dispatcher import RetryPolicy, ToolCall, ToolDispatcher, ToolRegistry
from gitsleuth.mcp_client import McpConnection

FAST_RETRY = RetryPolicy(max_attempts=3, base_delay=0.01, max_delay=0.02)


def count(state: Path, name: str) -> int:
    path = state / f"{name}.count"
    return int(path.read_text()) if path.exists() else 0


def dispatcher_for(connection: McpConnection, **options) -> ToolDispatcher:
    registry = ToolRegistry()
    registry.add_mcp_server(connection)
    options.setdefault("retry", FAST_RETRY)
    return ToolDispatcher(registry, **options)


async def test_timed_out_call_is_retried_and_succeeds(chaos_target):
    target, state = chaos_target
    async with McpConnection("chaos", target) as connection:
        dispatcher = dispatcher_for(connection, call_timeout=1.0)
        outcome = await dispatcher.dispatch(ToolCall("t", "chaos__slow_once", {"seconds": 30}))
    assert outcome.is_error is False
    assert outcome.text == "finished"
    assert outcome.attempts == 2
    assert count(state, "slow_once") == 2


async def test_timeouts_give_up_after_max_attempts(chaos_target):
    target, state = chaos_target
    async with McpConnection("chaos", target) as connection:
        dispatcher = dispatcher_for(connection, call_timeout=0.5)
        outcome = await dispatcher.dispatch(ToolCall("t", "chaos__always_slow", {"seconds": 30}))
        # The server is still healthy for the next call.
        after = await dispatcher.dispatch(ToolCall("t", "chaos__echo", {"message": "hi"}))
    assert outcome.is_error
    assert outcome.error_kind == "timeout"
    assert outcome.attempts == 3
    assert "gave up after 3 attempts" in outcome.text
    assert after.text == "echo: hi"


async def test_crashed_server_is_restarted_and_the_call_retried(chaos_target):
    target, state = chaos_target
    async with McpConnection("chaos", target) as connection:
        assert connection.generation == 1
        dispatcher = dispatcher_for(connection, call_timeout=20.0)
        outcome = await dispatcher.dispatch(ToolCall("t", "chaos__crash_once", {}))
        assert outcome.is_error is False
        assert outcome.text == "survived"
        assert outcome.attempts == 2
        assert connection.generation == 2
        after = await dispatcher.dispatch(ToolCall("t", "chaos__echo", {"message": "hi"}))
    assert after.text == "echo: hi"


async def test_tool_with_side_effects_is_not_retried_after_a_crash(chaos_target):
    target, state = chaos_target
    async with McpConnection("chaos", target) as connection:
        dispatcher = dispatcher_for(connection, call_timeout=20.0)
        route = dispatcher.registry.get("chaos__write_then_crash")
        assert route.retry_safe is False
        outcome = await dispatcher.dispatch(ToolCall("t", "chaos__write_then_crash", {}))
        assert outcome.is_error
        assert outcome.error_kind == "connection"
        assert outcome.attempts == 1
        assert "may or may not have taken effect" in outcome.text
        assert count(state, "write_then_crash") == 1
        # The connection was still repaired for later calls.
        after = await dispatcher.dispatch(ToolCall("t", "chaos__echo", {"message": "hi"}))
    assert after.text == "echo: hi"


async def test_tool_errors_reach_the_model_without_a_retry(chaos_target):
    target, state = chaos_target
    async with McpConnection("chaos", target) as connection:
        dispatcher = dispatcher_for(connection)
        outcome = await dispatcher.dispatch(ToolCall("t", "chaos__tool_failure", {}))
    assert outcome.error_kind == "tool_error"
    assert "found nothing" in outcome.text
    assert outcome.attempts == 1
    assert count(state, "tool_failure") == 1


async def test_concurrent_calls_share_one_reconnect(chaos_target):
    target, state = chaos_target
    async with McpConnection("chaos", target) as connection:
        dispatcher = dispatcher_for(connection, call_timeout=20.0, max_concurrency=4)
        outcomes = await dispatcher.dispatch_many(
            [
                ToolCall("a", "chaos__crash_once", {}),
                ToolCall("b", "chaos__always_slow", {"seconds": 0.5}),
                ToolCall("c", "chaos__echo", {"message": "c"}),
            ]
        )
        assert connection.generation == 2
    assert [o.tool_use_id for o in outcomes] == ["a", "b", "c"]
    assert all(not o.is_error for o in outcomes)


async def test_connection_recovers_when_the_server_comes_back(chaos_target, tmp_path):
    target, state = chaos_target
    async with McpConnection("chaos", target) as connection:
        dispatcher = dispatcher_for(connection, call_timeout=20.0)

        # While the server cannot start, attempts run out and the outcome reports it.
        good_command = connection.target
        connection.target = good_command.model_copy(update={"args": ["-c", "raise SystemExit(3)"]})
        failed = await dispatcher.dispatch(ToolCall("t", "chaos__crash_once", {}))
        assert failed.is_error
        assert failed.error_kind == "connection"
        assert failed.attempts == 3

        # Once it can start again, the next call reconnects.
        connection.target = good_command
        with anyio.fail_after(30):
            recovered = await dispatcher.dispatch(ToolCall("t", "chaos__echo", {"message": "hi"}))
    assert recovered.text == "echo: hi"
    assert recovered.attempts == 2
