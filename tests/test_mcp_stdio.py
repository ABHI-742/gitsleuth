"""The real client talking to the real servers, as subprocesses, over the stdio transport."""

from __future__ import annotations

import json
import subprocess

import pytest

from gitsleuth.dispatcher import ToolCall
from gitsleuth.mcp_client import McpConnectError, McpConnection
from gitsleuth.toolbox import open_toolbox, stdio_target


def call(name: str, **arguments) -> ToolCall:
    return ToolCall(id="t1", name=name, arguments=arguments)


async def test_discovers_tools_from_both_servers_with_their_schemas(sample_repo):
    repo = sample_repo["path"]
    async with open_toolbox(stdio_target("code", repo), stdio_target("history", repo)) as box:
        assert box.registry.names() == [
            "code__list_files",
            "code__read_file",
            "code__search_code",
            "history__blame_lines",
            "history__find_string_changes",
            "history__git_log",
            "history__show_commit",
            "notes__record_finding",
        ]
        read_file = box.registry.get("code__read_file")
        assert read_file.input_schema["required"] == ["path"]
        assert read_file.retry_safe is True
        assert box.registry.get("notes__record_finding").retry_safe is False


async def test_every_tool_returns_real_data_from_the_repository(sample_repo):
    repo, shas = sample_repo["path"], sample_repo["shas"]
    async with open_toolbox(stdio_target("code", repo), stdio_target("history", repo)) as box:
        dispatch = box.dispatcher.dispatch

        listed = json.loads((await dispatch(call("code__list_files"))).text)
        assert listed["files"] == ["README.md", "billing/invoice.py", "logo.bin"]

        read = json.loads(
            (
                await dispatch(
                    call("code__read_file", path="billing/invoice.py", ref=shas["regression"])
                )
            ).text
        )
        assert "ROUNDING - 1" in read["text"]
        assert read["total_lines"] == 5

        found = json.loads((await dispatch(call("code__search_code", pattern="ROUNDING"))).text)
        assert {(m["path"], m["line"]) for m in found["matches"]} == {
            ("billing/invoice.py", 1),
            ("billing/invoice.py", 5),
        }

        log = json.loads((await dispatch(call("history__git_log", path="billing/invoice.py"))).text)
        assert [c["sha"] for c in log["commits"]] == [
            shas["fix"],
            shas["regression"],
            shas["initial"],
        ]

        filtered = json.loads(
            (await dispatch(call("history__git_log", message_contains="off-by-one"))).text
        )
        assert [c["sha"] for c in filtered["commits"]] == [shas["fix"]]

        commit = json.loads(
            (await dispatch(call("history__show_commit", sha=shas["regression"]))).text
        )
        assert commit["message"] == "Round invoice totals for display"
        assert commit["changed_files"] == ["billing/invoice.py"]
        assert "+    return round(sum(items), ROUNDING - 1)" in commit["patch"]

        blame = json.loads(
            (
                await dispatch(
                    call(
                        "history__blame_lines", path="billing/invoice.py", start_line=1, end_line=5
                    )
                )
            ).text
        )
        assert blame["lines"][0]["sha"] == shas["regression"]
        assert blame["lines"][4]["sha"] == shas["fix"]

        pickaxe = json.loads(
            (await dispatch(call("history__find_string_changes", text="ROUNDING - 1"))).text
        )
        assert [c["sha"] for c in pickaxe["commits"]] == [shas["fix"], shas["regression"]]


async def test_search_with_no_matches_is_an_empty_answer_not_an_error(sample_repo):
    repo = sample_repo["path"]
    async with open_toolbox(stdio_target("code", repo), stdio_target("history", repo)) as box:
        outcome = await box.dispatcher.dispatch(call("code__search_code", pattern="zzz-not-here"))
        assert outcome.is_error is False
        assert json.loads(outcome.text)["matches"] == []


@pytest.mark.parametrize(
    ("tool", "arguments", "expected"),
    [
        ("code__read_file", {"path": ".env"}, "does not exist"),
        ("history__blame_lines", {"path": ".env", "start_line": 1, "end_line": 1}, "no such path"),
        ("code__read_file", {"path": "../../etc/passwd"}, "invalid path"),
        ("code__read_file", {"path": "/etc/passwd"}, "invalid path"),
        ("code__read_file", {"path": "C:/Windows/win.ini"}, "invalid path"),
        ("code__read_file", {"path": "logo.bin"}, "binary file"),
        ("code__read_file", {"path": "README.md", "ref": "--output=/tmp/x"}, "invalid ref"),
        ("history__git_log", {"ref": "--all"}, "invalid ref"),
        ("history__show_commit", {"sha": "-p"}, "invalid ref"),
        ("history__show_commit", {"sha": "deadbeef"}, "failed"),
        ("code__search_code", {"pattern": "x", "path_glob": "../.."}, "invalid path"),
    ],
)
async def test_unsafe_or_impossible_requests_come_back_as_tool_errors(
    sample_repo, tool, arguments, expected
):
    repo = sample_repo["path"]
    async with open_toolbox(stdio_target("code", repo), stdio_target("history", repo)) as box:
        outcome = await box.dispatcher.dispatch(ToolCall(id="t", name=tool, arguments=arguments))
        assert outcome.is_error
        assert outcome.error_kind == "tool_error"
        assert expected in outcome.text
        assert outcome.attempts == 1
        assert "do-not-read" not in outcome.text


async def test_flag_like_search_pattern_is_treated_as_text(sample_repo):
    repo = sample_repo["path"]
    async with open_toolbox(stdio_target("code", repo), stdio_target("history", repo)) as box:
        outcome = await box.dispatcher.dispatch(
            call("code__search_code", pattern="--open-files-in-pager=calc")
        )
        assert outcome.is_error is False
        assert json.loads(outcome.text)["matches"] == []


async def test_uncommitted_files_are_invisible_to_search(sample_repo):
    repo = sample_repo["path"]
    async with open_toolbox(stdio_target("code", repo), stdio_target("history", repo)) as box:
        outcome = await box.dispatcher.dispatch(call("code__search_code", pattern="do-not-read"))
        assert json.loads(outcome.text)["matches"] == []


async def test_repository_config_cannot_run_a_diff_program(sample_repo, tmp_path):
    repo, shas = sample_repo["path"], sample_repo["shas"]
    marker = tmp_path / "pwned"
    script = tmp_path / "evil.py"
    script.write_text(f"open({str(marker)!r}, 'w').write('ran')\n")
    command = f'python "{script.as_posix()}"'
    config = repo / ".git" / "config"
    with config.open("a") as handle:
        handle.write(f"[diff]\n\texternal = {command}\n")
        handle.write(f'[diff "evil"]\n\ttextconv = {command}\n')
    (repo / ".git" / "info" / "attributes").write_text("* diff=evil\n")

    # Control: plain git really does run the configured program in this repository.
    subprocess.run(["git", "show", shas["fix"]], cwd=repo, capture_output=True)
    assert marker.exists()
    marker.unlink()

    async with open_toolbox(stdio_target("code", repo), stdio_target("history", repo)) as box:
        outcome = await box.dispatcher.dispatch(call("history__show_commit", sha=shas["fix"]))
        assert outcome.is_error is False
        await box.dispatcher.dispatch(call("history__find_string_changes", text="ROUNDING - 1"))
        await box.dispatcher.dispatch(
            call("history__blame_lines", path="billing/invoice.py", start_line=1, end_line=2)
        )
        await box.dispatcher.dispatch(call("code__search_code", pattern="ROUNDING"))
    assert not marker.exists()


async def test_server_refuses_to_start_outside_a_git_repository(tmp_path):
    with pytest.raises(McpConnectError):
        async with McpConnection("code", stdio_target("code", tmp_path), connect_timeout=15):
            pass
