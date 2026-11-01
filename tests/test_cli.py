"""The command line, run as a user would run it. No command here calls Claude."""

from __future__ import annotations

import json
import os
import subprocess
import sys


def run_cli(*args: str) -> subprocess.CompletedProcess:
    env = {k: v for k, v in os.environ.items() if not k.startswith("ANTHROPIC_")}
    return subprocess.run(
        [sys.executable, "-c", "from gitsleuth.cli import main; main()", *args],
        capture_output=True,
        text=True,
        env=env,
        timeout=90,
    )


def test_tools_lists_every_discovered_tool(sample_repo):
    result = run_cli("tools", "--repo", str(sample_repo["path"]))
    assert result.returncode == 0, result.stderr
    names = [line.split(":")[0] for line in result.stdout.splitlines()]
    assert "code__read_file" in names
    assert "history__blame_lines" in names
    assert "notes__record_finding" in names


def test_call_runs_one_tool_through_the_dispatcher(sample_repo):
    result = run_cli("call", "--repo", str(sample_repo["path"]), "history__git_log", '{"limit": 1}')
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout)["commits"][0]["subject"] == "Add logo"


def test_call_reports_invalid_input_with_a_failing_exit_code(sample_repo):
    result = run_cli("call", "--repo", str(sample_repo["path"]), "code__read_file", "{}")
    assert result.returncode == 1
    assert "'path' is a required property" in result.stdout


def test_verbose_writes_json_logs_to_stderr_only(sample_repo):
    result = run_cli("--verbose", "call", "--repo", str(sample_repo["path"]), "code__list_files")
    assert result.returncode == 0
    json.loads(result.stdout)
    events = [json.loads(line) for line in result.stderr.splitlines() if line.startswith("{")]
    tool_call = next(e for e in events if e["event"] == "tool_call")
    assert tool_call["tool"] == "code__list_files"
    assert tool_call["attempts"] == 1


def test_bad_json_arguments_get_a_clear_message(sample_repo):
    result = run_cli("call", "--repo", str(sample_repo["path"]), "code__read_file", "{nope")
    assert result.returncode == 2
    assert "not valid JSON" in result.stderr


def test_ask_with_an_unreachable_api_exits_cleanly_without_a_real_call(sample_repo):
    # The base URL points at a closed local port, so this cannot spend credits.
    env = {
        **os.environ,
        "ANTHROPIC_API_KEY": "test-key-not-real",
        "ANTHROPIC_BASE_URL": "http://127.0.0.1:9",
    }
    result = subprocess.run(
        [
            sys.executable,
            "-c",
            "from gitsleuth.cli import main; main()",
            "ask",
            "--repo",
            str(sample_repo["path"]),
            "why?",
        ],
        capture_output=True,
        text=True,
        env=env,
        timeout=90,
    )
    assert result.returncode == 1, result.stderr
    assert "Stopped: model_error" in result.stdout
    assert "could not reach the API" in result.stdout


def test_a_directory_that_is_not_a_repository_gets_a_clear_message(tmp_path):
    result = run_cli("tools", "--repo", str(tmp_path))
    assert result.returncode == 2
    assert "could not connect" in result.stderr
