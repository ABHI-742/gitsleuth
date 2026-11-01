"""The real Anthropic SDK against a scripted HTTP transport. Nothing leaves this process.

This proves the request the SDK builds and how its stream is parsed. It cannot prove that the API
accepts the request: that needs a real call, which this suite never makes.
"""

from __future__ import annotations

import json

import anthropic
import httpx2
import pytest

from gitsleuth.agent import Agent, AgentLimits, StopReason
from gitsleuth.dispatcher import ToolDispatcher, ToolOutput, ToolRegistry
from gitsleuth.model import AnthropicModelClient, ModelError
from gitsleuth.notebook import Notebook

SCHEMA = {"type": "object", "properties": {"q": {"type": "string"}}}


def sse(*events: dict) -> bytes:
    return "".join(
        f"event: {event['type']}\ndata: {json.dumps(event)}\n\n" for event in events
    ).encode()


def stream_events(blocks: list[dict], stop_reason: str, stop_details: dict | None = None) -> bytes:
    events = [
        {
            "type": "message_start",
            "message": {
                "id": "msg_1",
                "type": "message",
                "role": "assistant",
                "model": "claude-opus-5-5",
                "content": [],
                "stop_reason": None,
                "stop_sequence": None,
                "usage": {
                    "input_tokens": 11,
                    "output_tokens": 1,
                    "cache_read_input_tokens": 5,
                    "cache_creation_input_tokens": 3,
                },
            },
        }
    ]
    for index, block in enumerate(blocks):
        if block["type"] == "tool_use":
            start = {**block, "input": {}}
            delta = {"type": "input_json_delta", "partial_json": json.dumps(block["input"])}
        elif block["type"] == "thinking":
            start = {"type": "thinking", "thinking": "", "signature": ""}
            delta = {"type": "signature_delta", "signature": block["signature"]}
        else:
            start = {"type": "text", "text": ""}
            delta = {"type": "text_delta", "text": block["text"]}
        events += [
            {"type": "content_block_start", "index": index, "content_block": start},
            {"type": "content_block_delta", "index": index, "delta": delta},
            {"type": "content_block_stop", "index": index},
        ]
    events += [
        {
            "type": "message_delta",
            "delta": {
                "stop_reason": stop_reason,
                "stop_sequence": None,
                "stop_details": stop_details,
            },
            "usage": {"output_tokens": 7},
        },
        {"type": "message_stop"},
    ]
    return sse(*events)


class ScriptedApi:
    def __init__(self, *responses: httpx2.Response) -> None:
        self.responses = list(responses)
        self.requests: list[dict] = []
        self.headers: list[httpx2.Headers] = []

    def __call__(self, request: httpx2.Request) -> httpx2.Response:
        self.requests.append(json.loads(request.content))
        self.headers.append(request.headers)
        return self.responses.pop(0)

    def client(self) -> anthropic.AsyncAnthropic:
        return anthropic.AsyncAnthropic(
            api_key="test-key-not-real",
            max_retries=0,
            http_client=anthropic.DefaultAsyncHttpxClient(transport=httpx2.MockTransport(self)),
        )


def ok(body: bytes) -> httpx2.Response:
    return httpx2.Response(200, headers={"content-type": "text/event-stream"}, content=body)


TOOLS = [{"name": "srv__find", "description": "Find", "input_schema": SCHEMA}]


async def test_request_uses_the_documented_defaults_and_the_response_is_parsed():
    api = ScriptedApi(
        ok(
            stream_events(
                [
                    {"type": "text", "text": "Looking."},
                    {"type": "tool_use", "id": "toolu_1", "name": "srv__find", "input": {"q": "x"}},
                ],
                "tool_use",
            )
        )
    )
    model = AnthropicModelClient(api.client())
    turn = await model.complete(
        system="sys", messages=[{"role": "user", "content": "hi"}], tools=TOOLS, allow_tools=True
    )

    body = api.requests[0]
    assert body["model"] == "claude-opus-5-5"
    assert body["max_tokens"] == 64000
    assert body["stream"] is True
    assert body["thinking"] == {"type": "adaptive"}
    assert body["output_config"] == {"effort": "high"}
    assert body["tool_choice"] == {"type": "auto"}
    assert body["fallbacks"] == "default"
    assert body["cache_control"] == {"type": "ephemeral"}
    assert body["tools"][0]["eager_input_streaming"] is True
    assert "temperature" not in body
    assert "server-side-fallback-2026-07-01" in api.headers[0]["anthropic-beta"]

    assert turn.stop_reason == "tool_use"
    assert turn.text == "Looking."
    assert [(c.id, c.name, c.arguments) for c in turn.tool_calls] == [
        ("toolu_1", "srv__find", {"q": "x"})
    ]
    assert turn.usage.input_tokens == 11
    assert turn.usage.output_tokens == 7
    assert turn.usage.cache_read_input_tokens == 5
    assert turn.usage.cache_creation_input_tokens == 3


async def test_final_turn_disables_tools_without_forcing_a_call():
    api = ScriptedApi(ok(stream_events([{"type": "text", "text": "done"}], "end_turn")))
    model = AnthropicModelClient(api.client())
    await model.complete(
        system="s", messages=[{"role": "user", "content": "hi"}], tools=TOOLS, allow_tools=False
    )
    assert api.requests[0]["tool_choice"] == {"type": "none"}


async def test_fallbacks_can_be_turned_off():
    api = ScriptedApi(ok(stream_events([{"type": "text", "text": "done"}], "end_turn")))
    model = AnthropicModelClient(api.client(), use_fallbacks=False)
    await model.complete(
        system="s", messages=[{"role": "user", "content": "hi"}], tools=TOOLS, allow_tools=True
    )
    assert "fallbacks" not in api.requests[0]


async def test_real_sdk_blocks_round_trip_through_the_agent_loop():
    thinking = {"type": "thinking", "thinking": "", "signature": "sig-abc"}
    tool_use = {"type": "tool_use", "id": "toolu_1", "name": "srv__find", "input": {"q": "x"}}
    api = ScriptedApi(
        ok(stream_events([thinking, tool_use], "tool_use")),
        ok(stream_events([{"type": "text", "text": "All done."}], "end_turn")),
    )

    class Backend:
        async def call(self, tool, arguments, timeout):
            return ToolOutput("result text")

        async def recover(self, lost):
            raise lost

    registry = ToolRegistry()
    registry.add(
        server="srv",
        tool="find",
        description="Find",
        input_schema=SCHEMA,
        retry_safe=True,
        backend=Backend(),
    )
    agent = Agent(
        model=AnthropicModelClient(api.client()),
        dispatcher=ToolDispatcher(registry),
        notebook=Notebook(),
        limits=AgentLimits(),
    )
    result = await agent.run("question")

    assert result.stop_reason is StopReason.COMPLETED
    assert result.answer == "All done."
    history = api.requests[1]["messages"]
    assert [m["role"] for m in history] == ["user", "assistant", "user"]
    echoed = history[1]["content"]
    assert echoed[0]["type"] == "thinking"
    assert echoed[0]["signature"] == "sig-abc"
    assert echoed[1]["type"] == "tool_use"
    assert echoed[1]["id"] == "toolu_1"
    assert echoed[1]["input"] == {"q": "x"}
    assert history[2]["content"][0] == {
        "type": "tool_result",
        "tool_use_id": "toolu_1",
        "content": "result text",
    }


class UnparseableThenFine:
    """Stands in for the SDK raising ValueError from its stream parser on broken tool input."""

    def __init__(self, failures: int) -> None:
        self.failures = failures
        self.calls = 0

    async def _stream_once(self, request):
        self.calls += 1
        if self.calls <= self.failures:
            raise ValueError("bad tool JSON")
        return _FINAL_MESSAGE


_FINAL_MESSAGE = anthropic.types.beta.BetaMessage.model_construct(
    id="msg_1",
    role="assistant",
    type="message",
    model="claude-opus-5-5",
    stop_reason="end_turn",
    content=[anthropic.types.beta.BetaTextBlock.model_construct(type="text", text="ok")],
    usage=anthropic.types.beta.BetaUsage.model_construct(
        input_tokens=1,
        output_tokens=1,
        cache_read_input_tokens=0,
        cache_creation_input_tokens=0,
    ),
)


@pytest.mark.parametrize(("failures", "succeeds"), [(1, True), (2, False)])
async def test_unparseable_tool_input_reissues_the_request_once(failures, succeeds):
    stub = UnparseableThenFine(failures)
    model = AnthropicModelClient(ScriptedApi().client())
    model._stream_once = stub._stream_once
    request = dict(
        system="s", messages=[{"role": "user", "content": "hi"}], tools=TOOLS, allow_tools=True
    )
    if succeeds:
        assert (await model.complete(**request)).text == "ok"
    else:
        with pytest.raises(ModelError, match="unparseable"):
            await model.complete(**request)
    assert stub.calls == 2


async def test_refusal_details_are_read():
    api = ScriptedApi(
        ok(
            stream_events(
                [],
                "refusal",
                {"type": "refusal", "category": "cyber", "explanation": "declined"},
            )
        )
    )
    model = AnthropicModelClient(api.client())
    turn = await model.complete(
        system="s", messages=[{"role": "user", "content": "hi"}], tools=TOOLS, allow_tools=True
    )
    assert turn.stop_reason == "refusal"
    assert turn.refusal_category == "cyber"


@pytest.mark.parametrize(
    ("status", "expected"),
    [(401, "authentication failed"), (429, "rate limited"), (500, "API error 500")],
)
async def test_api_errors_become_model_errors(status, expected):
    api = ScriptedApi(
        httpx2.Response(
            status,
            json={"type": "error", "error": {"type": "api_error", "message": "boom"}},
        )
    )
    model = AnthropicModelClient(api.client())
    with pytest.raises(ModelError, match=expected):
        await model.complete(
            system="s", messages=[{"role": "user", "content": "hi"}], tools=TOOLS, allow_tools=True
        )


async def test_connection_failure_becomes_a_model_error():
    def refuse(request):
        raise httpx2.ConnectError("refused")

    client = anthropic.AsyncAnthropic(
        api_key="test-key-not-real",
        max_retries=0,
        http_client=anthropic.DefaultAsyncHttpxClient(transport=httpx2.MockTransport(refuse)),
    )
    with pytest.raises(ModelError, match="could not reach"):
        await AnthropicModelClient(client).complete(
            system="s", messages=[{"role": "user", "content": "hi"}], tools=TOOLS, allow_tools=True
        )
