"""Test doubles. No test in this suite calls the Claude API."""

from __future__ import annotations

from collections.abc import Callable

import anyio

from gitsleuth.dispatcher import ConnectionLost, ToolCall, ToolOutput
from gitsleuth.model import ModelError, ModelTurn, Usage


class ScriptedModel:
    """Plays back turns in order and records the requests it received."""

    def __init__(self, turns: list[ModelTurn | Exception | Callable[[list[dict]], ModelTurn]]):
        self.turns = list(turns)
        self.requests: list[dict] = []

    async def complete(self, *, system, messages, tools, allow_tools) -> ModelTurn:
        # Copy the list, because the agent keeps appending to the one it passes in.
        self.requests.append(
            {
                "system": system,
                "messages": list(messages),
                "tools": tools,
                "allow_tools": allow_tools,
            }
        )
        turn = self.turns.pop(0)
        if isinstance(turn, Exception):
            raise turn
        return turn(messages) if callable(turn) else turn


class SlowModel:
    async def complete(self, **_) -> ModelTurn:
        await anyio.sleep(60)
        raise ModelError("unreachable")


def tool_turn(*calls: tuple[str, dict], tokens: int = 10) -> ModelTurn:
    tool_calls = [
        ToolCall(id=f"toolu_{index}", name=name, arguments=arguments)
        for index, (name, arguments) in enumerate(calls)
    ]
    return ModelTurn(
        stop_reason="tool_use",
        assistant_content=[
            {"type": "tool_use", "id": call.id, "name": call.name, "input": call.arguments}
            for call in tool_calls
        ],
        tool_calls=tool_calls,
        usage=Usage(input_tokens=tokens, output_tokens=tokens),
    )


def text_turn(text: str, stop_reason: str = "end_turn", tokens: int = 10) -> ModelTurn:
    return ModelTurn(
        stop_reason=stop_reason,
        assistant_content=[{"type": "text", "text": text}],
        text=text,
        usage=Usage(input_tokens=tokens, output_tokens=tokens),
    )


class FakeBackend:
    """A tool backend whose calls follow a script of outputs and exceptions."""

    def __init__(self, *script: ToolOutput | Exception) -> None:
        self.script = list(script)
        self.calls: list[tuple[str, dict, float]] = []
        self.recoveries = 0

    async def call(self, tool: str, arguments: dict, timeout: float) -> ToolOutput:
        self.calls.append((tool, arguments, timeout))
        item = self.script.pop(0) if self.script else ToolOutput("ok")
        if isinstance(item, Exception):
            raise item
        return item

    async def recover(self, lost: ConnectionLost) -> None:
        self.recoveries += 1
