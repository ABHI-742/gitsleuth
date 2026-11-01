"""MCP server for the repository's history: commits, diffs, blame and pickaxe search."""

from __future__ import annotations

import re
from datetime import UTC, datetime

from mcp.server import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp_types import ToolAnnotations
from pydantic import BaseModel

from gitsleuth.servers.git import GitError, GitRepo, clamp, validate_ref, validate_repo_path

READ_ONLY = ToolAnnotations(readOnlyHint=True, idempotentHint=True)

_FIELD = "\x1f"
_RECORD = "\x1e"
_LOG_FORMAT = f"--format={_RECORD}%H{_FIELD}%an{_FIELD}%aI{_FIELD}%s"
_DATE = re.compile(r"^[0-9A-Za-z:.+ -]{1,40}$")


class Commit(BaseModel):
    sha: str
    author: str
    date: str
    subject: str


class CommitList(BaseModel):
    commits: list[Commit]
    truncated: bool


class CommitDetail(BaseModel):
    sha: str
    author: str
    date: str
    message: str
    changed_files: list[str]
    patch: str | None
    patch_truncated: bool


class BlameLine(BaseModel):
    sha: str
    author: str
    date: str
    summary: str
    line: int
    text: str


class BlameResult(BaseModel):
    ref: str
    path: str
    lines: list[BlameLine]


def _parse_log(stdout: str) -> list[Commit]:
    commits = []
    for record in stdout.split(_RECORD):
        fields = record.strip().split(_FIELD)
        if len(fields) == 4:
            commits.append(
                Commit(sha=fields[0], author=fields[1], date=fields[2], subject=fields[3])
            )
    return commits


def _parse_porcelain_blame(stdout: str) -> list[BlameLine]:
    """Parse ``git blame --line-porcelain``, where each line repeats its commit's metadata."""
    lines: list[BlameLine] = []
    current: dict[str, str] = {}
    for row in stdout.splitlines():
        if row.startswith("\t"):
            lines.append(
                BlameLine(
                    sha=current["sha"],
                    author=current.get("author", ""),
                    date=datetime.fromtimestamp(int(current["author-time"]), UTC).isoformat(),
                    summary=current.get("summary", ""),
                    line=int(current["line"]),
                    text=row[1:],
                )
            )
            current = {}
        elif not current:
            parts = row.split(" ")
            current = {"sha": parts[0], "line": parts[2]}
        else:
            key, _, value = row.partition(" ")
            current[key] = value
    return lines


def build_history_server(repo: GitRepo) -> MCPServer:
    server = MCPServer(
        "gitsleuth-history",
        version="0.1.0",
        instructions="Read-only access to the commit history of one git repository.",
    )

    @server.tool(annotations=READ_ONLY)
    async def git_log(
        ref: str = "HEAD",
        path: str | None = None,
        message_contains: str | None = None,
        since: str | None = None,
        limit: int = 30,
    ) -> CommitList:
        """List commits newest first, filtered by path, message text or a since date."""
        try:
            validate_ref(ref)
            limit = clamp(limit, 1, 200)
            args = ["log", f"--max-count={limit + 1}", _LOG_FORMAT, "--no-show-signature"]
            if message_contains:
                args += ["--fixed-strings", f"--grep={message_contains}"]
            if since:
                if not _DATE.match(since):
                    raise GitError(f"invalid since value {since!r}: expected a date")
                args.append(f"--since={since}")
            args += ["--end-of-options", ref]
            if path:
                args += ["--", validate_repo_path(path)]
            output = await repo.run(args)
        except GitError as error:
            raise ToolError(str(error)) from error
        commits = _parse_log(output.stdout)
        return CommitList(
            commits=commits[:limit], truncated=output.truncated or len(commits) > limit
        )

    @server.tool(annotations=READ_ONLY)
    async def show_commit(
        sha: str, include_patch: bool = True, path: str | None = None, max_patch_lines: int = 400
    ) -> CommitDetail:
        """Show a commit's message and changed files, and optionally its patch."""
        try:
            validate_ref(sha)
            commit = f"{sha}^{{commit}}"
            max_patch_lines = clamp(max_patch_lines, 1, 2000)
            header = await repo.run(
                [
                    "show",
                    "--no-patch",
                    "--no-show-signature",
                    f"--format=%H{_FIELD}%an{_FIELD}%aI{_FIELD}%B",
                    "--end-of-options",
                    commit,
                ]
            )
            names = await repo.run(
                [
                    "show",
                    "--name-only",
                    "--no-show-signature",
                    "--format=",
                    "--end-of-options",
                    commit,
                ]
            )
            patch_output = None
            if include_patch:
                args = [
                    "show",
                    "--patch",
                    "--no-ext-diff",
                    "--no-textconv",
                    "--no-show-signature",
                    "--format=",
                    "--end-of-options",
                    commit,
                ]
                if path:
                    args += ["--", validate_repo_path(path)]
                patch_output = await repo.run(args)
        except GitError as error:
            raise ToolError(str(error)) from error

        full_sha, author, date, message = header.stdout.split(_FIELD, 3)
        patch = None
        patch_truncated = False
        if patch_output is not None:
            patch_lines = patch_output.stdout.splitlines()
            patch = "\n".join(patch_lines[:max_patch_lines])
            patch_truncated = patch_output.truncated or len(patch_lines) > max_patch_lines
        return CommitDetail(
            sha=full_sha.strip(),
            author=author,
            date=date,
            message=message.strip(),
            changed_files=names.stdout.splitlines(),
            patch=patch,
            patch_truncated=patch_truncated,
        )

    @server.tool(annotations=READ_ONLY)
    async def blame_lines(
        path: str, start_line: int, end_line: int, ref: str = "HEAD"
    ) -> BlameResult:
        """Show which commit last changed each line in a range. Covers at most 200 lines."""
        if end_line < start_line or start_line < 1:
            raise ToolError("expected 1 <= start_line <= end_line")
        try:
            validate_ref(ref)
            normalized = validate_repo_path(path)
            end_line = min(end_line, start_line + 199)
            output = await repo.run(
                [
                    "blame",
                    "--line-porcelain",
                    "--no-textconv",
                    f"-L{start_line},{end_line}",
                    # git blame rejects --end-of-options; validate_ref refused a leading "-".
                    ref,
                    "--",
                    normalized,
                ]
            )
        except GitError as error:
            raise ToolError(str(error)) from error
        return BlameResult(ref=ref, path=normalized, lines=_parse_porcelain_blame(output.stdout))

    @server.tool(annotations=READ_ONLY)
    async def find_string_changes(
        text: str, ref: str = "HEAD", path: str | None = None, limit: int = 20
    ) -> CommitList:
        """Find commits that added or removed a literal string (git log -S), newest first."""
        if not text:
            raise ToolError("text must not be empty")
        try:
            validate_ref(ref)
            limit = clamp(limit, 1, 100)
            args = [
                "log",
                f"--max-count={limit + 1}",
                _LOG_FORMAT,
                "--no-show-signature",
                "--no-ext-diff",
                "--no-textconv",
                f"-S{text}",
                "--end-of-options",
                ref,
            ]
            if path:
                args += ["--", validate_repo_path(path)]
            output = await repo.run(args)
        except GitError as error:
            raise ToolError(str(error)) from error
        commits = _parse_log(output.stdout)
        return CommitList(
            commits=commits[:limit], truncated=output.truncated or len(commits) > limit
        )

    return server
