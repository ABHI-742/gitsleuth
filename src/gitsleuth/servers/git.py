"""Run read-only git commands against one repository.

Every tool goes through :class:`GitRepo`, which is where the servers' safety rules live:

- Tools read committed objects (``git show ref:path``), never the filesystem, so untracked files
  such as ``.env`` are out of reach and a symlink cannot lead outside the repository.
- Refs and paths from the model are validated so they cannot start with ``-``. They and search
  patterns are also passed where git cannot read them as flags: after ``-e``, ``--end-of-options``
  or ``--``.
- Repository config can name programs for git to run. Those settings are overridden on the command
  line, and the commands that could use them pass ``--no-ext-diff`` and ``--no-textconv``.
- Every command has a timeout and an output cap, and is killed when it goes over either.
"""

from __future__ import annotations

import math
import os
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

import anyio

DEFAULT_TIMEOUT_SECONDS = 20.0
DEFAULT_MAX_OUTPUT_BYTES = 2_000_000

_REF = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/@{}~^+-]*$")
_HARDENING_CONFIG = (
    "core.fsmonitor=false",
    "core.hooksPath=/dev/null",
    "core.pager=cat",
    "core.quotePath=false",
    "diff.external=",
    "protocol.allow=never",
)


class GitError(Exception):
    """A git command failed or its arguments were refused. The model may read the message."""


@dataclass(frozen=True)
class GitOutput:
    stdout: str
    truncated: bool


def validate_ref(ref: str) -> str:
    """Accept branch names, tags, hashes and suffixes like ``HEAD~2``; refuse anything flag-like."""
    if not _REF.match(ref) or ".." in ref:
        raise GitError(f"invalid ref {ref!r}: expected a branch, tag or commit such as HEAD~2")
    return ref


def validate_repo_path(path: str) -> str:
    """Accept a path relative to the repository root, using forward slashes."""
    normalized = path.replace("\\", "/")
    if (
        not normalized
        or normalized.startswith(("/", "-"))
        or re.match(r"^[A-Za-z]:", normalized)
        or ".." in normalized.split("/")
        or "\0" in normalized
    ):
        raise GitError(f"invalid path {path!r}: expected a path relative to the repository root")
    return normalized.removeprefix("./").rstrip("/")


class GitRepo:
    def __init__(
        self,
        root: Path | str,
        *,
        timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
        max_output_bytes: int = DEFAULT_MAX_OUTPUT_BYTES,
    ) -> None:
        self.root = Path(root).resolve()
        self.timeout_seconds = timeout_seconds
        self.max_output_bytes = max_output_bytes
        if not (self.root / ".git").exists():
            raise ValueError(f"{self.root} is not the root of a git repository")

    def _command(self, args: list[str]) -> list[str]:
        command = ["git", "--no-pager", "--no-optional-locks"]
        for setting in _HARDENING_CONFIG:
            command += ["-c", setting]
        return [*command, *args]

    def _environment(self) -> dict[str, str]:
        env = dict(os.environ)
        env.update(
            GIT_CONFIG_NOSYSTEM="1",
            GIT_TERMINAL_PROMPT="0",
            GIT_NO_LAZY_FETCH="1",
            LC_ALL="C",
        )
        return env

    async def run(self, args: list[str], *, ok_codes: tuple[int, ...] = (0,)) -> GitOutput:
        """Run git, keeping at most ``max_output_bytes`` of stdout."""
        chunks: list[bytes] = []
        stderr_chunks: list[bytes] = []
        size = 0
        truncated = False

        process = await anyio.open_process(
            self._command(args),
            cwd=self.root,
            env=self._environment(),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

        async def read_stderr() -> None:
            async for chunk in process.stderr:
                if sum(map(len, stderr_chunks)) < 8192:
                    stderr_chunks.append(chunk)

        try:
            with anyio.fail_after(self.timeout_seconds):
                async with anyio.create_task_group() as group:
                    group.start_soon(read_stderr)
                    async for chunk in process.stdout:
                        remaining = self.max_output_bytes - size
                        chunks.append(chunk[:remaining])
                        size += min(len(chunk), remaining)
                        if len(chunk) > remaining:
                            truncated = True
                            process.kill()
                            break
                    returncode = await process.wait()
        except TimeoutError:
            raise GitError(f"git {args[0]} timed out after {self.timeout_seconds:g}s") from None
        finally:
            if process.returncode is None:
                process.kill()
            with anyio.CancelScope(shield=True):
                await process.aclose()

        if not truncated and returncode not in ok_codes:
            detail = b"".join(stderr_chunks).decode("utf-8", "replace").strip()
            lines = detail.splitlines()
            raise GitError(f"git {args[0]} failed: {lines[-1] if lines else f'exit {returncode}'}")
        return GitOutput(b"".join(chunks).decode("utf-8", "replace"), truncated)


def clamp(value: int, low: int, high: int | float = math.inf) -> int:
    return int(max(low, min(value, high)))
