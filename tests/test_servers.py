"""Server behaviour in detail, connected in-process so edge cases stay fast."""

from __future__ import annotations

import pytest
from mcp import Client

from conftest import commit_file, git
from gitsleuth.servers.code_server import build_code_server
from gitsleuth.servers.git import GitError, GitRepo, validate_ref, validate_repo_path
from gitsleuth.servers.history_server import build_history_server


@pytest.mark.parametrize(
    "ref", ["HEAD", "main", "v1.2.3", "HEAD~2", "HEAD^", "feature/x", "abc1234", "main@{1}"]
)
def test_valid_refs_pass(ref):
    assert validate_ref(ref) == ref


@pytest.mark.parametrize("ref", ["", "-p", "--all", "a..b", "a b", "a;b", "$(x)", "a:b", "\n"])
def test_dangerous_refs_are_refused(ref):
    with pytest.raises(GitError):
        validate_ref(ref)


@pytest.mark.parametrize(
    ("path", "expected"),
    [("a/b.py", "a/b.py"), ("./a.py", "a.py"), ("a\\b.py", "a/b.py"), ("dir/", "dir")],
)
def test_valid_paths_are_normalized(path, expected):
    assert validate_repo_path(path) == expected


@pytest.mark.parametrize("path", ["", "/etc/passwd", "../x", "a/../../x", "C:/x", "-x", "a\0b"])
def test_paths_outside_the_repository_are_refused(path):
    with pytest.raises(GitError):
        validate_repo_path(path)


def test_repo_must_be_a_repository_root(tmp_path):
    with pytest.raises(ValueError, match="not the root"):
        GitRepo(tmp_path)


async def test_output_cap_stops_git_and_marks_the_result_truncated(sample_repo):
    repo = GitRepo(sample_repo["path"], max_output_bytes=50)
    output = await repo.run(["log", "--format=%H%n%H%n%H"])
    assert output.truncated
    assert len(output.stdout) == 50


async def test_slow_git_commands_time_out(sample_repo):
    repo = GitRepo(sample_repo["path"], timeout_seconds=0.0001)
    with pytest.raises(GitError, match="timed out"):
        await repo.run(["log"])


async def test_failed_git_commands_report_the_last_error_line(sample_repo):
    repo = GitRepo(sample_repo["path"])
    with pytest.raises(GitError, match="git show failed"):
        await repo.run(["show", "deadbeefdeadbeef"])


async def test_read_file_pages_through_lines(sample_repo):
    path = sample_repo["path"]
    commit_file(
        path,
        "long.txt",
        "".join(f"line {n}\n" for n in range(1, 11)),
        "Add long file",
        "2026-06-01T00:00:00+00:00",
    )
    async with Client(build_code_server(GitRepo(path))) as client:
        first = await client.call_tool("read_file", {"path": "long.txt", "max_lines": 4})
        assert first.structured_content["text"].splitlines()[-1] == "line 4"
        assert first.structured_content["truncated"] is True
        assert first.structured_content["total_lines"] == 10

        last = await client.call_tool(
            "read_file", {"path": "long.txt", "start_line": 9, "max_lines": 4}
        )
        assert last.structured_content["text"] == "line 9\nline 10"
        assert last.structured_content["end_line"] == 10
        assert last.structured_content["truncated"] is False


async def test_limits_mark_results_truncated(sample_repo):
    path = sample_repo["path"]
    async with Client(build_code_server(GitRepo(path))) as client:
        listed = await client.call_tool("list_files", {"limit": 1})
        assert listed.structured_content["files"] == ["README.md"]
        assert listed.structured_content["total"] == 3
        assert listed.structured_content["truncated"] is True

    async with Client(build_history_server(GitRepo(path))) as client:
        log = await client.call_tool("git_log", {"limit": 2})
        assert len(log.structured_content["commits"]) == 2
        assert log.structured_content["truncated"] is True


async def test_search_handles_regex_case_and_paths_with_colons_and_spaces(sample_repo):
    path = sample_repo["path"]
    # Windows cannot create this name on disk, so it goes straight into the index.
    blob_source = path.parent / "blob.txt"
    blob_source.write_bytes(b"needle one\nNEEDLE two\n")
    blob = git(path, "hash-object", "-w", str(blob_source))
    git(
        path,
        "-c",
        "core.protectNTFS=false",
        "update-index",
        "--add",
        "--cacheinfo",
        f"100644,{blob},odd name:v2.txt",
    )
    git(
        path,
        "-c",
        "core.protectNTFS=false",
        "commit",
        "-m",
        "Add oddly named file",
        date="2026-06-01T00:00:00+00:00",
    )
    async with Client(build_code_server(GitRepo(path))) as client:
        literal = await client.call_tool("search_code", {"pattern": "needle"})
        assert [(m["path"], m["line"]) for m in literal.structured_content["matches"]] == [
            ("odd name:v2.txt", 1)
        ]
        folded = await client.call_tool("search_code", {"pattern": "needle", "ignore_case": True})
        assert len(folded.structured_content["matches"]) == 2
        regex = await client.call_tool("search_code", {"pattern": "^NEE.*two$", "regex": True})
        assert regex.structured_content["matches"][0]["line"] == 2
        scoped = await client.call_tool(
            "search_code", {"pattern": "ROUNDING", "path_glob": "README.md"}
        )
        assert scoped.structured_content["matches"] == []

    async with Client(build_history_server(GitRepo(path))) as client:
        commit = await client.call_tool("show_commit", {"sha": "HEAD", "include_patch": False})
        assert commit.structured_content["changed_files"] == ["odd name:v2.txt"]


async def test_symlinks_are_returned_as_their_target_text_and_not_followed(sample_repo):
    path = sample_repo["path"]
    blob_source = path.parent / "link-target.txt"
    blob_source.write_bytes(b"../.env")
    blob = git(path, "hash-object", "-w", str(blob_source))
    git(path, "update-index", "--add", "--cacheinfo", f"120000,{blob},link-to-env")
    git(path, "commit", "-m", "Add symlink", date="2026-06-01T00:00:00+00:00")
    async with Client(build_code_server(GitRepo(path))) as client:
        result = await client.call_tool("read_file", {"path": "link-to-env"})
        assert result.structured_content["text"] == "../.env"


async def test_git_log_filters_by_date_and_refuses_bad_dates(sample_repo):
    path, shas = sample_repo["path"], sample_repo["shas"]
    async with Client(build_history_server(GitRepo(path))) as client:
        recent = await client.call_tool("git_log", {"since": "2026-04-15"})
        assert [c["sha"] for c in recent.structured_content["commits"]] == [shas["binary"]]
        bad = await client.call_tool("git_log", {"since": "$(whoami)"})
        assert bad.is_error


async def test_show_commit_only_accepts_commits(sample_repo):
    async with Client(build_history_server(GitRepo(sample_repo["path"]))) as client:
        tree = await client.call_tool("show_commit", {"sha": "HEAD^{tree}"})
        assert tree.is_error
        assert "git show failed" in tree.content[0].text


async def test_show_commit_can_scope_and_limit_the_patch(sample_repo):
    path, shas = sample_repo["path"], sample_repo["shas"]
    async with Client(build_history_server(GitRepo(path))) as client:
        limited = await client.call_tool("show_commit", {"sha": shas["fix"], "max_patch_lines": 2})
        assert limited.structured_content["patch_truncated"] is True
        assert len(limited.structured_content["patch"].splitlines()) == 2
        scoped = await client.call_tool("show_commit", {"sha": shas["fix"], "path": "README.md"})
        assert scoped.structured_content["patch"] == ""


async def test_blame_rejects_backwards_ranges_and_reports_iso_dates(sample_repo):
    async with Client(build_history_server(GitRepo(sample_repo["path"]))) as client:
        backwards = await client.call_tool(
            "blame_lines", {"path": "README.md", "start_line": 3, "end_line": 1}
        )
        assert backwards.is_error
        good = await client.call_tool(
            "blame_lines", {"path": "README.md", "start_line": 1, "end_line": 1}
        )
        assert good.structured_content["lines"][0]["date"].startswith("2026-03-01T00:00:00")
        assert good.structured_content["lines"][0]["summary"] == "Add readme"


async def test_servers_declare_their_tools_read_only(sample_repo):
    repo = GitRepo(sample_repo["path"])
    for build in (build_code_server, build_history_server):
        async with Client(build(repo)) as client:
            for tool in (await client.list_tools()).tools:
                assert tool.annotations.read_only_hint is True
                assert tool.description
