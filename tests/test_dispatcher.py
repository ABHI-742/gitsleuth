"""Dispatcher rules, using fake backends so each failure can be scripted exactly."""

from __future__ import annotations

import random

import anyio
import pytest

from fakes import FakeBackend
from gitsleuth.dispatcher import (
    ConnectionLost,
    RetryPolicy,
    ToolCall,
    ToolDispatcher,
    ToolOutput,
    ToolProtocolError,
    ToolRegistry,
    ToolRegistryError,
    ToolTimeout,
)

SCHEMA = {
    "type": "object",
    "properties": {"path": {"type": "string"}, "limit": {"type": "integer", "minimum": 1}},
    "required": ["path"],
    "additionalProperties": False,
}


def make(backend, *, retry_safe=True, **options):
    registry = ToolRegistry()
    registry.add(
        server="srv",
        tool="read",
        description="Read",
        input_schema=SCHEMA,
        retry_safe=retry_safe,
        backend=backend,
    )
    sleeps: list[float] = []

    async def sleep(seconds: float) -> None:
        sleeps.append(seconds)

    options.setdefault("rng", random.Random(1))
    return ToolDispatcher(registry, sleep=sleep, **options), sleeps


GOOD_CALL = ToolCall("t", "srv__read", {"path": "a.py"})


async def test_routes_to_the_backend_under_the_servers_own_tool_name():
    first, second = FakeBackend(ToolOutput("from one")), FakeBackend(ToolOutput("from two"))
    registry = ToolRegistry()
    for server, backend in (("one", first), ("two", second)):
        registry.add(
            server=server,
            tool="read",
            description="",
            input_schema=SCHEMA,
            retry_safe=True,
            backend=backend,
        )
    dispatcher = ToolDispatcher(registry, call_timeout=7.0)
    outcome = await dispatcher.dispatch(ToolCall("t", "two__read", {"path": "a"}))
    assert outcome.text == "from two"
    assert second.calls == [("read", {"path": "a"}, 7.0)]
    assert first.calls == []


async def test_unknown_tool_lists_what_exists_and_calls_nothing():
    backend = FakeBackend()
    dispatcher, _ = make(backend)
    outcome = await dispatcher.dispatch(ToolCall("t", "srv__nope", {}))
    assert outcome.error_kind == "unknown_tool"
    assert "srv__read" in outcome.text
    assert outcome.attempts == 0
    assert backend.calls == []


@pytest.mark.parametrize(
    ("arguments", "expected"),
    [
        ({}, "'path' is a required property"),
        ({"path": 3}, "path: 3 is not of type 'string'"),
        ({"path": "a", "limit": 0}, "limit: 0 is less than the minimum"),
        ({"path": "a", "extra": 1}, "Additional properties"),
    ],
)
async def test_input_is_validated_before_any_backend_call(arguments, expected):
    backend = FakeBackend()
    dispatcher, _ = make(backend)
    outcome = await dispatcher.dispatch(ToolCall("t", "srv__read", arguments))
    assert outcome.error_kind == "invalid_input"
    assert expected in outcome.text
    assert backend.calls == []


async def test_all_validation_problems_are_reported_together():
    dispatcher, _ = make(FakeBackend())
    outcome = await dispatcher.dispatch(ToolCall("t", "srv__read", {"limit": "x", "other": 1}))
    assert "required" in outcome.text
    assert "limit" in outcome.text
    assert "Additional properties" in outcome.text


async def test_timeouts_back_off_exponentially_then_succeed():
    backend = FakeBackend(ToolTimeout("slow"), ToolTimeout("slow"), ToolOutput("done"))
    dispatcher, sleeps = make(
        backend, retry=RetryPolicy(max_attempts=3, base_delay=1.0, max_delay=10.0, jitter=0.0)
    )
    outcome = await dispatcher.dispatch(GOOD_CALL)
    assert outcome.text == "done"
    assert outcome.is_error is False
    assert outcome.attempts == 3
    assert sleeps == [1.0, 2.0]


async def test_backoff_is_capped_and_jitter_only_shortens_it():
    policy = RetryPolicy(base_delay=1.0, max_delay=4.0, jitter=0.5)
    rng = random.Random(7)
    for failed_attempts, ceiling in [(1, 1.0), (2, 2.0), (3, 4.0), (9, 4.0)]:
        delay = policy.delay(failed_attempts, rng)
        assert ceiling / 2 <= delay <= ceiling


async def test_connection_loss_recovers_before_retrying():
    backend = FakeBackend(ConnectionLost("gone"), ToolOutput("back"))
    dispatcher, _ = make(backend)
    outcome = await dispatcher.dispatch(GOOD_CALL)
    assert outcome.text == "back"
    assert backend.recoveries == 1


@pytest.mark.parametrize(
    ("failure", "kind"), [(ToolTimeout("slow"), "timeout"), (ConnectionLost("gone"), "connection")]
)
async def test_tools_not_declared_safe_are_never_retried(failure, kind):
    backend = FakeBackend(failure)
    dispatcher, sleeps = make(backend, retry_safe=False)
    outcome = await dispatcher.dispatch(GOOD_CALL)
    assert outcome.error_kind == kind
    assert outcome.attempts == 1
    assert len(backend.calls) == 1
    assert sleeps == []


async def test_protocol_errors_are_not_retried():
    backend = FakeBackend(ToolProtocolError("MCP error -32602: bad params"))
    dispatcher, _ = make(backend)
    outcome = await dispatcher.dispatch(GOOD_CALL)
    assert outcome.error_kind == "protocol"
    assert len(backend.calls) == 1


async def test_tool_reported_errors_are_not_retried():
    backend = FakeBackend(ToolOutput("no such file", is_error=True))
    dispatcher, _ = make(backend)
    outcome = await dispatcher.dispatch(GOOD_CALL)
    assert outcome.error_kind == "tool_error"
    assert outcome.text == "no such file"
    assert len(backend.calls) == 1


async def test_unexpected_exceptions_become_an_internal_error_without_leaking_details():
    backend = FakeBackend(RuntimeError("secret internals"))
    dispatcher, _ = make(backend)
    outcome = await dispatcher.dispatch(GOOD_CALL)
    assert outcome.error_kind == "internal"
    assert "secret internals" not in outcome.text


async def test_long_results_are_truncated_with_a_marker():
    dispatcher, _ = make(FakeBackend(ToolOutput("x" * 500)), max_result_chars=100)
    outcome = await dispatcher.dispatch(GOOD_CALL)
    assert outcome.truncated
    assert outcome.text.startswith("x" * 100)
    assert "400 characters omitted" in outcome.text


async def test_dispatch_many_keeps_order_and_respects_the_concurrency_limit():
    running = 0
    peak = 0

    class Tracking(FakeBackend):
        async def call(self, tool, arguments, timeout):
            nonlocal running, peak
            running += 1
            peak = max(peak, running)
            # Later calls finish first, so an order bug would show.
            await anyio.sleep(0.05 / (1 + int(arguments["path"])))
            running -= 1
            return ToolOutput(arguments["path"])

    dispatcher, _ = make(Tracking(), max_concurrency=2)
    calls = [ToolCall(str(i), "srv__read", {"path": str(i)}) for i in range(6)]
    outcomes = await dispatcher.dispatch_many(calls)
    assert [o.tool_use_id for o in outcomes] == [str(i) for i in range(6)]
    assert [o.text for o in outcomes] == [str(i) for i in range(6)]
    assert peak == 2


def test_registry_rejects_bad_schemas_and_collisions():
    registry = ToolRegistry()
    backend = FakeBackend()
    options = dict(description="", retry_safe=True, backend=backend)
    registry.add(server="a", tool="t", input_schema=SCHEMA, **options)
    with pytest.raises(ToolRegistryError, match="duplicate"):
        registry.add(server="a", tool="t", input_schema=SCHEMA, **options)
    with pytest.raises(ToolRegistryError, match="invalid schema"):
        registry.add(server="a", tool="u", input_schema={"type": "nonsense"}, **options)
    with pytest.raises(ToolRegistryError, match="exceeds"):
        registry.add(server="a", tool="x" * 80, input_schema=SCHEMA, **options)


def test_tool_names_are_made_valid_and_definitions_sorted():
    registry = ToolRegistry()
    options = dict(description="", input_schema=SCHEMA, retry_safe=True, backend=FakeBackend())
    registry.add(server="zeta", tool="read.file", **options)
    registry.add(server="alpha", tool="read", **options)
    assert [d["name"] for d in registry.definitions()] == ["alpha__read", "zeta__read_file"]
