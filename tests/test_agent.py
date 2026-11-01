"""The agent loop's stopping rules, with a scripted model and a local tool backend."""

from __future__ import annotations

import anyio
import pytest

from fakes import FakeBackend, ScriptedModel, SlowModel, text_turn, tool_turn
from gitsleuth.agent import Agent, AgentLimits, StopReason
from gitsleuth.dispatcher import ToolDispatcher, ToolOutput, ToolRegistry
from gitsleuth.model import ModelError, ModelTurn
from gitsleuth.notebook import Notebook

SCHEMA = {"type": "object", "properties": {"q": {"type": "string"}}, "required": ["q"]}
SHA = "a" * 40


def make_agent(model, backend=None, **limits) -> tuple[Agent, FakeBackend]:
    backend = backend or FakeBackend()
    registry = ToolRegistry()
    registry.add(
        server="srv",
        tool="find",
        description="Find",
        input_schema=SCHEMA,
        retry_safe=True,
        backend=backend,
    )
    notebook = Notebook()
    notebook.register(registry)
    agent = Agent(
        model=model,
        dispatcher=ToolDispatcher(registry),
        notebook=notebook,
        limits=AgentLimits(**limits),
    )
    return agent, backend


async def test_loop_feeds_tool_results_back_until_the_model_answers():
    model = ScriptedModel([tool_turn(("srv__find", {"q": "bug"})), text_turn("The answer.")])
    agent, backend = make_agent(model, FakeBackend(ToolOutput("evidence")))
    result = await agent.run("Where is the bug?")

    assert result.stop_reason is StopReason.COMPLETED
    assert result.answer == "The answer."
    assert result.steps == 2
    assert [c.name for c in result.tool_calls] == ["srv__find"]
    assert result.usage.input_tokens == 20
    assert backend.calls[0][1] == {"q": "bug"}

    final = model.requests[1]["messages"]
    assert final[0] == {"role": "user", "content": "Where is the bug?"}
    assert final[1]["role"] == "assistant"
    assert final[2] == {
        "role": "user",
        "content": [{"type": "tool_result", "tool_use_id": "toolu_0", "content": "evidence"}],
    }


async def test_assistant_content_is_echoed_back_unchanged():
    turn = tool_turn(("srv__find", {"q": "x"}))
    turn.assistant_content.insert(0, {"type": "thinking", "thinking": "", "signature": "sig"})
    model = ScriptedModel([turn, text_turn("done")])
    agent, _ = make_agent(model)
    await agent.run("q")
    assert model.requests[1]["messages"][1]["content"] is turn.assistant_content


async def test_parallel_calls_return_in_one_user_message_in_order():
    model = ScriptedModel(
        [
            tool_turn(
                ("srv__find", {"q": "1"}), ("srv__find", {"q": "2"}), ("srv__find", {"q": "3"})
            ),
            text_turn("done"),
        ]
    )
    agent, _ = make_agent(model)
    await agent.run("q")
    assert len(model.requests[1]["messages"]) == 3
    results = model.requests[1]["messages"][2]["content"]
    assert [r["tool_use_id"] for r in results] == ["toolu_0", "toolu_1", "toolu_2"]


async def test_tool_errors_go_back_to_the_model_with_is_error():
    model = ScriptedModel(
        [
            tool_turn(("srv__find", {"wrong": 1}), ("srv__missing", {})),
            text_turn("recovered"),
        ]
    )
    agent, backend = make_agent(model)
    result = await agent.run("q")
    assert result.stop_reason is StopReason.COMPLETED
    assert backend.calls == []
    results = model.requests[1]["messages"][2]["content"]
    assert all(r["is_error"] for r in results)
    assert [c.error_kind for c in result.tool_calls] == ["invalid_input", "unknown_tool"]


async def test_step_limit_gives_a_final_turn_without_tools():
    model = ScriptedModel(
        [
            tool_turn(("srv__find", {"q": "1"})),
            tool_turn(("srv__find", {"q": "2"})),
            text_turn("best answer so far"),
        ]
    )
    agent, _ = make_agent(model, max_steps=3)
    result = await agent.run("q")
    assert [r["allow_tools"] for r in model.requests] == [True, True, False]
    assert result.stop_reason is StopReason.STEP_LIMIT
    assert result.answer == "best answer so far"
    assert result.steps == 3


async def test_tools_requested_on_the_final_turn_do_not_run():
    model = ScriptedModel([tool_turn(("srv__find", {"q": "1"}))])
    agent, backend = make_agent(model, max_steps=1)
    result = await agent.run("q")
    assert result.stop_reason is StopReason.STEP_LIMIT
    assert backend.calls == []


async def test_tool_call_budget_answers_every_call_and_winds_down():
    model = ScriptedModel(
        [
            tool_turn(
                ("srv__find", {"q": "1"}), ("srv__find", {"q": "2"}), ("srv__find", {"q": "3"})
            ),
            text_turn("partial answer"),
        ]
    )
    agent, backend = make_agent(model, max_tool_calls=2)
    result = await agent.run("q")
    assert result.stop_reason is StopReason.TOOL_CALL_LIMIT
    assert result.answer == "partial answer"
    assert len(backend.calls) == 2
    assert model.requests[1]["allow_tools"] is False
    results = model.requests[1]["messages"][2]["content"]
    assert [r["tool_use_id"] for r in results] == ["toolu_0", "toolu_1", "toolu_2"]
    assert results[2]["is_error"] is True


async def test_token_limit_stops_without_another_model_call():
    model = ScriptedModel([tool_turn(("srv__find", {"q": "1"}), tokens=600)])
    agent, _ = make_agent(model, max_total_tokens=1000)
    result = await agent.run("q")
    assert result.stop_reason is StopReason.TOKEN_LIMIT
    assert len(model.requests) == 1


async def test_deadline_cancels_a_stuck_model_call():
    agent, _ = make_agent(SlowModel(), deadline_seconds=0.1)
    result = await agent.run("q")
    assert result.stop_reason is StopReason.DEADLINE
    assert result.duration_ms < 5000


async def test_deadline_cancels_a_stuck_tool_call_and_keeps_earlier_findings():
    class Stuck(FakeBackend):
        async def call(self, tool, arguments, timeout):
            if arguments["q"] == "evidence":
                return ToolOutput(f"commit {SHA}")
            await anyio.sleep(60)

    model = ScriptedModel(
        [
            tool_turn(("srv__find", {"q": "evidence"})),
            tool_turn(
                (
                    "notes__record_finding",
                    {"claim": "It broke here", "commits": [SHA], "confidence": "high"},
                )
            ),
            tool_turn(("srv__find", {"q": "stuck"})),
        ]
    )
    agent, _ = make_agent(model, Stuck(), deadline_seconds=1.0)
    result = await agent.run("q")
    assert result.stop_reason is StopReason.DEADLINE
    assert [f.claim for f in result.findings] == ["It broke here"]


@pytest.mark.parametrize(
    ("stop_reason", "expected"),
    [("max_tokens", StopReason.TRUNCATED), ("refusal", StopReason.REFUSED)],
)
async def test_tools_in_cut_off_turns_never_run(stop_reason, expected):
    turn = tool_turn(("srv__find", {"q": "partial"}))
    turn.stop_reason = stop_reason
    model = ScriptedModel([turn])
    agent, backend = make_agent(model)
    result = await agent.run("q")
    assert result.stop_reason is expected
    assert backend.calls == []


async def test_refusal_reports_its_category_and_no_answer():
    model = ScriptedModel(
        [
            ModelTurn(
                stop_reason="refusal",
                assistant_content=[],
                text="partial",
                refusal_category="cyber",
            )
        ]
    )
    agent, _ = make_agent(model)
    result = await agent.run("q")
    assert result.stop_reason is StopReason.REFUSED
    assert result.answer == ""
    assert "cyber" in result.detail


async def test_model_error_ends_the_run_with_the_reason():
    model = ScriptedModel([tool_turn(("srv__find", {"q": "1"})), ModelError("API error 500")])
    agent, _ = make_agent(model)
    result = await agent.run("q")
    assert result.stop_reason is StopReason.MODEL_ERROR
    assert result.detail == "API error 500"
    assert len(result.tool_calls) == 1


async def test_pause_turn_continues_with_the_paused_content_in_history():
    model = ScriptedModel([text_turn("thinking", stop_reason="pause_turn"), text_turn("finished")])
    agent, _ = make_agent(model)
    result = await agent.run("q")
    assert result.stop_reason is StopReason.COMPLETED
    assert result.answer == "finished"
    assert model.requests[1]["messages"][1]["role"] == "assistant"


async def test_findings_must_cite_commits_seen_in_earlier_tool_results():
    finding = {"claim": "c", "commits": ["1234567"], "confidence": "low"}
    model = ScriptedModel(
        [
            tool_turn(("notes__record_finding", finding)),
            tool_turn(("srv__find", {"q": "x"})),
            tool_turn(("notes__record_finding", finding)),
            text_turn("done"),
        ]
    )
    agent, _ = make_agent(model, FakeBackend(ToolOutput("commit 1234567abcdef")))
    result = await agent.run("q")
    refused = model.requests[1]["messages"][2]["content"][0]
    assert refused["is_error"] is True
    assert "1234567" in refused["content"]
    assert [f.commits for f in result.findings] == [["1234567"]]


async def test_hashes_in_failed_tool_results_do_not_count_as_seen():
    finding = {"claim": "c", "commits": ["1234567"], "confidence": "low"}
    model = ScriptedModel(
        [
            tool_turn(("srv__find", {"q": "x"})),
            tool_turn(("notes__record_finding", finding)),
            text_turn("done"),
        ]
    )
    agent, _ = make_agent(model, FakeBackend(ToolOutput("bad revision 1234567", is_error=True)))
    result = await agent.run("q")
    assert result.findings == []
