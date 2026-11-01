"""MCP server for the repository's contents at any revision."""

from __future__ import annotations

import re

from mcp.server import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp_types import ToolAnnotations
from pydantic import BaseModel

from gitsleuth.servers.git import GitError, GitRepo, clamp, validate_ref, validate_repo_path

READ_ONLY = ToolAnnotations(readOnlyHint=True, idempotentHint=True)
_GREP_ROW = re.compile(r"^(.*?):(\d+):(.*)$")


class FileList(BaseModel):
    ref: str
    files: list[str]
    total: int
    truncated: bool


class FileSlice(BaseModel):
    ref: str
    path: str
    start_line: int
    end_line: int
    total_lines: int
    text: str
    truncated: bool


class SearchMatch(BaseModel):
    path: str
    line: int
    text: str


class SearchResult(BaseModel):
    ref: str
    matches: list[SearchMatch]
    truncated: bool


def build_code_server(repo: GitRepo) -> MCPServer:
    server = MCPServer(
        "gitsleuth-code",
        version="0.1.0",
        instructions="Read-only access to the files of one git repository at any revision.",
    )

    @server.tool(annotations=READ_ONLY)
    async def list_files(ref: str = "HEAD", path_prefix: str = "", limit: int = 200) -> FileList:
        """List tracked files at a revision, optionally under a directory. Returns up to 1000."""
        try:
            validate_ref(ref)
            limit = clamp(limit, 1, 1000)
            args = ["ls-tree", "-r", "--name-only", "--end-of-options", ref]
            if path_prefix:
                args += ["--", validate_repo_path(path_prefix)]
            output = await repo.run(args)
        except GitError as error:
            raise ToolError(str(error)) from error
        files = output.stdout.splitlines()
        return FileList(
            ref=ref,
            files=files[:limit],
            total=len(files),
            truncated=output.truncated or len(files) > limit,
        )

    @server.tool(annotations=READ_ONLY)
    async def read_file(
        path: str, ref: str = "HEAD", start_line: int = 1, max_lines: int = 200
    ) -> FileSlice:
        """Read committed lines of a file at a revision. Returns up to 1000 lines per call."""
        try:
            validate_ref(ref)
            normalized = validate_repo_path(path)
            start_line = clamp(start_line, 1)
            max_lines = clamp(max_lines, 1, 1000)
            output = await repo.run(["cat-file", "blob", f"{ref}:{normalized}"])
        except GitError as error:
            # git's wording would reveal that an uncommitted file exists on disk.
            if "exists on disk" in str(error):
                raise ToolError(f"{normalized} does not exist at {ref}") from error
            raise ToolError(str(error)) from error
        if "\0" in output.stdout:
            raise ToolError(f"{normalized} is a binary file at {ref}")
        lines = output.stdout.splitlines()
        selected = lines[start_line - 1 : start_line - 1 + max_lines]
        return FileSlice(
            ref=ref,
            path=normalized,
            start_line=start_line,
            end_line=start_line + len(selected) - 1 if selected else start_line,
            total_lines=len(lines),
            text="\n".join(selected),
            truncated=output.truncated or start_line - 1 + max_lines < len(lines),
        )

    @server.tool(annotations=READ_ONLY)
    async def search_code(
        pattern: str,
        ref: str = "HEAD",
        path_glob: str | None = None,
        regex: bool = False,
        ignore_case: bool = False,
        limit: int = 100,
    ) -> SearchResult:
        """Search tracked text files at a revision. Literal by default; regex=True for a regex."""
        if not pattern:
            raise ToolError("pattern must not be empty")
        try:
            validate_ref(ref)
            limit = clamp(limit, 1, 500)
            args = ["grep", "-n", "-I", "--no-color", "--full-name", "--no-textconv"]
            args.append("-E" if regex else "-F")
            if ignore_case:
                args.append("-i")
            args += ["-e", pattern, "--end-of-options", ref]
            if path_glob:
                args += ["--", validate_repo_path(path_glob)]
            # git grep exits 1 when nothing matches, which is an answer rather than a failure.
            output = await repo.run(args, ok_codes=(0, 1))
        except GitError as error:
            raise ToolError(str(error)) from error

        matches: list[SearchMatch] = []
        for row in output.stdout.splitlines():
            # Each row is "<ref>:<path>:<line>:<text>".
            if parsed := _GREP_ROW.match(row.removeprefix(f"{ref}:")):
                matches.append(
                    SearchMatch(path=parsed[1], line=int(parsed[2]), text=parsed[3]),
                )
        return SearchResult(
            ref=ref,
            matches=matches[:limit],
            truncated=output.truncated or len(matches) > limit,
        )

    return server
