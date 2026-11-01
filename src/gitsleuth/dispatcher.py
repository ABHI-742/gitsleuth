"""Route the model's tool calls to the right backend, and keep failures away from the agent loop.

``ToolDispatcher.dispatch`` never raises for a tool failure. Every problem becomes a
``DispatchOutcome`` that the agent returns to the model as an error result, so the model can correct
itself and the loop keeps a valid conversation.
"""

from __future__ import annotations

import logging
import random
import re
import time
from collections.abc import Awaitable, Callable, Iterable
from dataclasses import dataclass, field
from typing import Literal, Protocol

import anyio
from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError

from gitsleuth.logs import log_event

logger = logging.getLogger(__name__)

ErrorKind = Literal[
    "unknown_tool", "invalid_input", "tool_error", "timeout", "connection", "protocol", "internal"
]

_CLAUDE_TOOL_NAME = re.compile(r"[^a-zA-Z0-9_-]")
_MAX_TOOL_NAME_LENGTH = 64


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    arguments: dict


@dataclass(frozen=True)
class ToolOutput:
    text: str
    is_error: bool = False


class ToolTimeout(Exception):
    """The call got no answer in time. It may still have run, so only retry-safe tools retry."""


class ConnectionLost(Exception):
    """The session with the backend ended."""

    def __init__(self, message: str, generation: int = 0) -> None:
        super().__init__(message)
        self.generation = generation


class ToolProtocolError(Exception):
    """The backend answered with a protocol error, which a retry would not fix."""


class ToolBackend(Protocol):
    async def call(self, tool: str, arguments: dict, timeout: float) -> ToolOutput: ...

    async def recover(self, lost: ConnectionLost) -> None: ...


class ToolRegistryError(Exception):
    """A tool could not be registered: a bad schema, or a name that collides."""


@dataclass(frozen=True)
class ToolRoute:
    name: str
    server: str
    remote_name: str
    description: str
    input_schema: dict
    retry_safe: bool
    local: bool
    backend: ToolBackend
    validator: Draft202012Validator = field(compare=False, repr=False)


class ToolRegistry:
    """Tools exposed to the model as ``<server>__<tool>``, so two servers can reuse a name."""

    def __init__(self) -> None:
        self._routes: dict[str, ToolRoute] = {}

    def add(
        self,
        *,
        server: str,
        tool: str,
        description: str,
        input_schema: dict,
        retry_safe: bool,
        backend: ToolBackend,
        local: bool = False,
    ) -> ToolRoute:
        name = _CLAUDE_TOOL_NAME.sub("_", f"{server}__{tool}")
        if len(name) > _MAX_TOOL_NAME_LENGTH:
            raise ToolRegistryError(
                f"tool name {name!r} exceeds {_MAX_TOOL_NAME_LENGTH} characters"
            )
        if name in self._routes:
            raise ToolRegistryError(f"duplicate tool name {name!r}")
        try:
            Draft202012Validator.check_schema(input_schema)
        except SchemaError as error:
            raise ToolRegistryError(f"{name} has an invalid schema: {error.message}") from error
        route = ToolRoute(
            name=name,
            server=server,
            remote_name=tool,
            description=description,
            input_schema=input_schema,
            retry_safe=retry_safe,
            local=local,
            backend=backend,
            validator=Draft202012Validator(input_schema),
        )
        self._routes[name] = route
        return route

    def add_mcp_server(self, connection) -> None:
        """Register everything a connected ``McpConnection`` discovered.

        MCP tool annotations default to not read-only and not idempotent, so a tool is only
        retried when its server declares one of those hints.
        """
        for tool in connection.tools:
            hints = tool.annotations
            retry_safe = bool(hints and (hints.read_only_hint or hints.idempotent_hint))
            self.add(
                server=connection.name,
                tool=tool.name,
                description=tool.description or "",
                input_schema=tool.input_schema,
                retry_safe=retry_safe,
                backend=connection,
            )

    def get(self, name: str) -> ToolRoute | None:
        return self._routes.get(name)

    def names(self) -> list[str]:
        return sorted(self._routes)

    def definitions(self) -> list[dict]:
        """Definitions in a fixed order, because a change to the list breaks prompt caching."""
        return [
            {
                "name": route.name,
                "description": route.description,
                "input_schema": route.input_schema,
            }
            for _, route in sorted(self._routes.items())
        ]


@dataclass(frozen=True)
class RetryPolicy:
    """Exponential backoff with jitter: base, 2x base, and so on up to ``max_delay``."""

    max_attempts: int = 3
    base_delay: float = 0.25
    max_delay: float = 4.0
    jitter: float = 0.5

    def delay(self, failed_attempts: int, rng: random.Random) -> float:
        ceiling = min(self.max_delay, self.base_delay * 2 ** (failed_attempts - 1))
        return ceiling * (1 - self.jitter * rng.random())


@dataclass(frozen=True)
class DispatchOutcome:
    tool_use_id: str
    name: str
    text: str
    is_error: bool
    error_kind: ErrorKind | None
    attempts: int
    duration_ms: int
    truncated: bool
    local: bool


class ToolDispatcher:
    def __init__(
        self,
        registry: ToolRegistry,
        *,
        call_timeout: float = 30.0,
        retry: RetryPolicy | None = None,
        max_result_chars: int = 60_000,
        max_concurrency: int = 4,
        sleep: Callable[[float], Awaitable[None]] = anyio.sleep,
        rng: random.Random | None = None,
    ) -> None:
        self.registry = registry
        self.call_timeout = call_timeout
        self.retry = retry or RetryPolicy()
        self.max_result_chars = max_result_chars
        self.max_concurrency = max_concurrency
        self._sleep = sleep
        self._rng = rng or random.Random()

    async def dispatch(self, call: ToolCall) -> DispatchOutcome:
        started = time.monotonic()
        attempts = 0
        route = self.registry.get(call.name)
        kind: ErrorKind | None = None
        truncated = False

        if route is None:
            kind = "unknown_tool"
            text = (
                f"Unknown tool {call.name!r}. Available tools: {', '.join(self.registry.names())}"
            )
        elif problems := self._validation_problems(route, call.arguments):
            kind = "invalid_input"
            text = f"Invalid input for {call.name}: " + "; ".join(problems)
        else:
            attempts, text, kind = await self._call_with_retries(route, call)
            text, truncated = self._truncate(text)

        outcome = DispatchOutcome(
            tool_use_id=call.id,
            name=call.name,
            text=text,
            is_error=kind is not None,
            error_kind=kind,
            attempts=attempts,
            duration_ms=int((time.monotonic() - started) * 1000),
            truncated=truncated,
            local=bool(route and route.local),
        )
        log_event(
            logger,
            "tool_call",
            tool=call.name,
            server=route.server if route else None,
            is_error=outcome.is_error,
            error_kind=kind,
            attempts=attempts,
            duration_ms=outcome.duration_ms,
            result_chars=len(text),
            truncated=truncated,
        )
        return outcome

    async def dispatch_many(self, calls: Iterable[ToolCall]) -> list[DispatchOutcome]:
        """Run calls concurrently, up to the limit, and return outcomes in request order."""
        calls = list(calls)
        outcomes: list[DispatchOutcome | None] = [None] * len(calls)
        limiter = anyio.Semaphore(self.max_concurrency)

        async def run(index: int, call: ToolCall) -> None:
            async with limiter:
                outcomes[index] = await self.dispatch(call)

        async with anyio.create_task_group() as group:
            for index, call in enumerate(calls):
                group.start_soon(run, index, call)
        return [outcome for outcome in outcomes if outcome is not None]

    def _validation_problems(self, route: ToolRoute, arguments: dict) -> list[str]:
        problems = []
        for error in sorted(route.validator.iter_errors(arguments), key=lambda e: list(e.path)):
            where = "/".join(str(part) for part in error.path) or "(root)"
            problems.append(f"{where}: {error.message}")
        return problems

    async def _call_with_retries(
        self, route: ToolRoute, call: ToolCall
    ) -> tuple[int, str, ErrorKind | None]:
        attempt = 0
        while True:
            attempt += 1
            try:
                output = await route.backend.call(
                    route.remote_name, call.arguments, self.call_timeout
                )
            except ToolTimeout as error:
                kind: ErrorKind = "timeout"
                message = str(error)
            except ConnectionLost as error:
                kind = "connection"
                message = str(error)
                await self._recover(route, error)
            except ToolProtocolError as error:
                return attempt, str(error), "protocol"
            except Exception as error:
                log_event(
                    logger,
                    "tool_internal_error",
                    level=logging.ERROR,
                    tool=route.name,
                    error=repr(error),
                )
                return attempt, f"{route.name} failed unexpectedly", "internal"
            else:
                return attempt, output.text, ("tool_error" if output.is_error else None)

            if not route.retry_safe:
                return (
                    attempt,
                    f"{message}. Not retried, because {route.name} may have side effects; "
                    "the call may or may not have taken effect.",
                    kind,
                )
            if attempt >= self.retry.max_attempts:
                return attempt, f"{message} (gave up after {attempt} attempts)", kind

            delay = self.retry.delay(attempt, self._rng)
            log_event(
                logger,
                "tool_retry",
                tool=route.name,
                attempt=attempt,
                reason=kind,
                delay_ms=int(delay * 1000),
            )
            await self._sleep(delay)

    async def _recover(self, route: ToolRoute, lost: ConnectionLost) -> None:
        try:
            await route.backend.recover(lost)
        except ConnectionLost as error:
            # The next attempt fails fast and asks to recover again, until attempts run out.
            log_event(logger, "tool_recover_failed", level=logging.WARNING, error=str(error))

    def _truncate(self, text: str) -> tuple[str, bool]:
        if len(text) <= self.max_result_chars:
            return text, False
        omitted = len(text) - self.max_result_chars
        marker = f"\n[output truncated by dispatcher: {omitted} characters omitted]"
        return text[: self.max_result_chars] + marker, True
