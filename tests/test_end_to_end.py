"""One whole agent run: scripted model, real dispatcher, real client, real servers, real git."""

from __future__ import annotations

import json

from fakes import ScriptedModel, text_turn, tool_turn
from gitsleuth.agent import Agent, StopReason
from gitsleuth.toolbox import open_toolbox, stdio_target


async def test_agent_traces_a_regression_through_real_mcp_servers(sample_repo):
    repo, shas = sample_repo["path"], sample_repo["shas"]

    def record_finding_from_tool_output(messages):
        # Decide from what the servers actually returned, as a real model would have to.
        results = messages[-1]["content"]
        changes = json.loads(results[1]["content"])["commits"]
        introducing = changes[-1]["sha"]
        return tool_turn(
            (
                "notes__record_finding",
                {
                    "claim": "The off-by-one rounding arrived in this commit",
                    "commits": [introducing],
                    "confidence": "high",
                },
            ),
            ("history__show_commit", {"sha": introducing, "include_patch": False}),
        )

    model = ScriptedModel(
        [
            tool_turn(
                ("code__read_file", {"path": "billing/invoice.py"}),
                ("history__find_string_changes", {"text": "ROUNDING - 1"}),
            ),
            record_finding_from_tool_output,
            text_turn("The bug came from the display rounding commit."),
        ]
    )

    async with open_toolbox(stdio_target("code", repo), stdio_target("history", repo)) as box:
        agent = Agent(model=model, dispatcher=box.dispatcher, notebook=box.notebook)
        result = await agent.run("When did invoice rounding break?")

    assert result.stop_reason is StopReason.COMPLETED
    assert result.steps == 3
    assert [c.name for c in result.tool_calls] == [
        "code__read_file",
        "history__find_string_changes",
        "notes__record_finding",
        "history__show_commit",
    ]
    assert not any(c.is_error for c in result.tool_calls)
    assert [f.commits for f in result.findings] == [[shas["regression"]]]

    # The model saw the discovered tools and the commit details from the real server.
    assert {t["name"] for t in model.requests[0]["tools"]} >= {
        "code__read_file",
        "history__blame_lines",
        "notes__record_finding",
    }
    detail = json.loads(model.requests[2]["messages"][-1]["content"][1]["content"])
    assert detail["message"] == "Round invoice totals for display"
