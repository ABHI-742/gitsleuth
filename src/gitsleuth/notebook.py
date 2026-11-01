"""A local (in-process) tool: the agent's notebook of findings.

A finding must cite the commits behind it, and a cited commit must have appeared in an earlier MCP
tool result in this run. The check stops an invented hash from reaching the report. It does not
prove that the claim follows from the commit.
"""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass

from gitsleuth.dispatcher import ConnectionLost, ToolOutput, ToolProtocolError, ToolRegistry

_HEX = re.compile(r"\b[0-9a-f]{7,40}\b")

RECORD_FINDING_SCHEMA = {
    "type": "object",
    "properties": {
        "claim": {"type": "string", "minLength": 1, "description": "One specific factual claim."},
        "commits": {
            "type": "array",
            "items": {"type": "string", "pattern": "^[0-9a-f]{7,40}$"},
            "minItems": 1,
            "description": "Commit hashes, seen in earlier tool results, that support the claim.",
        },
        "confidence": {"type": "string", "enum": ["high", "medium", "low"]},
    },
    "required": ["claim", "commits", "confidence"],
    "additionalProperties": False,
}


@dataclass(frozen=True)
class Finding:
    claim: str
    commits: list[str]
    confidence: str


class Notebook:
    SERVER = "notes"

    def __init__(self) -> None:
        self.findings: list[Finding] = []
        self._seen_hashes: set[str] = set()

    def observe(self, tool_output: str) -> None:
        """Remember hash-like tokens from an MCP tool result."""
        self._seen_hashes.update(_HEX.findall(tool_output))

    def _was_seen(self, commit: str) -> bool:
        return any(seen.startswith(commit) or commit.startswith(seen) for seen in self._seen_hashes)

    def register(self, registry: ToolRegistry) -> None:
        registry.add(
            server=self.SERVER,
            tool="record_finding",
            description=(
                "Record one evidence-backed finding for the final report. Every commit you cite "
                "must have appeared in an earlier tool result."
            ),
            input_schema=RECORD_FINDING_SCHEMA,
            # Retrying would record the finding twice.
            retry_safe=False,
            backend=self,
            local=True,
        )

    async def call(self, tool: str, arguments: dict, timeout: float) -> ToolOutput:
        if tool != "record_finding":
            raise ToolProtocolError(f"unknown local tool {tool!r}")
        unseen = [commit for commit in arguments["commits"] if not self._was_seen(commit)]
        if unseen:
            return ToolOutput(
                f"Not recorded: {', '.join(unseen)} did not appear in any earlier tool result. "
                "Look the commit up first, or cite one you have seen.",
                is_error=True,
            )
        self.findings.append(
            Finding(arguments["claim"], list(arguments["commits"]), arguments["confidence"])
        )
        return ToolOutput(f"Recorded finding #{len(self.findings)}.")

    async def recover(self, lost: ConnectionLost) -> None:
        raise lost

    def as_dicts(self) -> list[dict]:
        return [asdict(finding) for finding in self.findings]
