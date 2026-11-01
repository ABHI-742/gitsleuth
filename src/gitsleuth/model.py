"""The agent's view of a model, and the Anthropic implementation of it."""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any, Protocol

import anthropic

from gitsleuth.dispatcher import ToolCall
from gitsleuth.logs import log_event

logger = logging.getLogger(__name__)

DEFAULT_MODEL = "claude-opus-5-5"
DEFAULT_MAX_TOKENS = 64_000
DEFAULT_EFFORT = "high"
_FALLBACK_BETA = "server-side-fallback-2026-07-01"


class ModelError(Exception):
    """A model request failed after the SDK's own retries."""


@dataclass
class Usage:
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_input_tokens: int = 0
    cache_creation_input_tokens: int = 0

    @property
    def total(self) -> int:
        return (
            self.input_tokens
            + self.output_tokens
            + self.cache_read_input_tokens
            + self.cache_creation_input_tokens
        )

    def add(self, other: Usage) -> None:
        self.input_tokens += other.input_tokens
        self.output_tokens += other.output_tokens
        self.cache_read_input_tokens += other.cache_read_input_tokens
        self.cache_creation_input_tokens += other.cache_creation_input_tokens


@dataclass
class ModelTurn:
    stop_reason: str
    # Echoed back unchanged on the next request, because thinking blocks must not be edited.
    assistant_content: list[Any]
    text: str = ""
    tool_calls: list[ToolCall] = field(default_factory=list)
    usage: Usage = field(default_factory=Usage)
    refusal_category: str | None = None
    request_id: str | None = None


class ModelClient(Protocol):
    async def complete(
        self, *, system: str, messages: list[dict], tools: list[dict], allow_tools: bool
    ) -> ModelTurn: ...


class AnthropicModelClient:
    """Messages API through the official SDK, streamed so long turns cannot hit an HTTP timeout."""

    def __init__(
        self,
        client: anthropic.AsyncAnthropic | None = None,
        *,
        model: str = DEFAULT_MODEL,
        max_tokens: int = DEFAULT_MAX_TOKENS,
        effort: str = DEFAULT_EFFORT,
        use_fallbacks: bool = True,
    ) -> None:
        self._client = client or anthropic.AsyncAnthropic()
        self.model = model
        self.max_tokens = max_tokens
        self.effort = effort
        self.use_fallbacks = use_fallbacks

    def _request(self, system: str, messages: list[dict], tools: list[dict], allow_tools: bool):
        request: dict[str, Any] = {
            "model": self.model,
            "max_tokens": self.max_tokens,
            "system": system,
            "messages": messages,
            # The dispatcher validates every input, which eager streaming requires of the client.
            "tools": [{**tool, "eager_input_streaming": True} for tool in tools],
            "tool_choice": {"type": "auto" if allow_tools else "none"},
            "thinking": {"type": "adaptive"},
            "output_config": {"effort": self.effort},
            "cache_control": {"type": "ephemeral"},
            "betas": [],
        }
        if self.use_fallbacks:
            request["betas"] = [_FALLBACK_BETA]
            request["fallbacks"] = "default"
        return request

    async def complete(
        self, *, system: str, messages: list[dict], tools: list[dict], allow_tools: bool
    ) -> ModelTurn:
        request = self._request(system, messages, tools, allow_tools)
        try:
            return _to_turn(await self._stream_once(request))
        except ValueError:
            # The SDK's stream parser raises this for tool input it cannot parse at all. There is
            # no tool_use block to answer yet, so the request is sent again, once.
            log_event(logger, "model_unparseable_tool_input", level=logging.WARNING)
        try:
            return _to_turn(await self._stream_once(request))
        except ValueError as error:
            raise ModelError(f"model produced unparseable tool input: {error}") from error

    async def _stream_once(self, request: dict[str, Any]) -> Any:
        try:
            async with self._client.beta.messages.stream(**request) as stream:
                return await stream.get_final_message()
        except anthropic.AuthenticationError as error:
            raise ModelError(f"authentication failed: {error.message}") from error
        except anthropic.RateLimitError as error:
            raise ModelError(f"rate limited after SDK retries: {error.message}") from error
        except anthropic.APIStatusError as error:
            raise ModelError(f"API error {error.status_code}: {error.message}") from error
        except anthropic.APIConnectionError as error:
            raise ModelError(f"could not reach the API: {error}") from error


def _to_turn(message: Any) -> ModelTurn:
    text = "".join(block.text for block in message.content if block.type == "text")
    calls = [
        ToolCall(id=block.id, name=block.name, arguments=block.input)
        for block in message.content
        if block.type == "tool_use"
    ]
    usage = message.usage
    details = getattr(message, "stop_details", None)
    return ModelTurn(
        stop_reason=message.stop_reason or "end_turn",
        assistant_content=list(message.content),
        text=text,
        tool_calls=calls,
        usage=Usage(
            input_tokens=usage.input_tokens or 0,
            output_tokens=usage.output_tokens or 0,
            cache_read_input_tokens=usage.cache_read_input_tokens or 0,
            cache_creation_input_tokens=usage.cache_creation_input_tokens or 0,
        ),
        refusal_category=details.category if details else None,
        request_id=getattr(message, "_request_id", None),
    )
