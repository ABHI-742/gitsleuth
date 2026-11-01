# GitSleuth architecture

## 1. Goals and constraints

| Goal | How the design addresses it |
| --- | --- |
| The agent cannot harm the repository | Every MCP tool is read-only and reads committed git objects. The only writable state is the in-memory notebook |
| A tool failure does not end a run | The dispatcher turns each failure into an error result the model can read and act on |
| Retries do not repeat side effects | A call is retried only if its tool declares `readOnlyHint` or `idempotentHint` |
| A run always ends | Limits on steps, tool calls, tokens and wall-clock time, each with its own stop reason |
| A limit still produces a useful result | Step and tool-call limits allow one last turn without tools, so the model can write up what it found. Findings survive every kind of stop |
| Findings point at real commits | A finding can only cite a hash that appeared in an earlier MCP tool result |
| Testable without spending credits | The loop depends on a small `ModelClient` protocol, so tests can script Claude's turns |

## 2. System context

```mermaid
flowchart TB
    Dev([Developer])
    subgraph Machine["Developer's machine"]
        GS[GitSleuth]
        Repo[("Git repository")]
    end
    Claude[[Anthropic Claude API]]

    Dev -->|"asks a question"| GS
    GS -->|"reads committed objects"| Repo
    GS -->|"question, tool definitions, tool results"| Claude
```

Repository content that the model asks to see is sent to the Claude API. Everything else stays on the machine.

## 3. Containers

```mermaid
flowchart LR
    subgraph Host["gitsleuth process"]
        CLI[CLI]
        Agent[Agent loop]
        Model[AnthropicModelClient]
        Dispatcher[ToolDispatcher]
        Notebook[Notebook]
        Code[McpConnection: code]
        History[McpConnection: history]
    end
    subgraph P1["Server process"]
        CodeServer["gitsleuth-code"]
    end
    subgraph P2["Server process"]
        HistoryServer["gitsleuth-history"]
    end
    Git[("git subprocess<br/>per tool call")]
    Claude[[Claude API]]

    CLI --> Agent
    Agent --> Model
    Model --> Claude
    Agent --> Dispatcher
    Dispatcher --> Notebook
    Dispatcher --> Code
    Dispatcher --> History
    Code <-->|"MCP"| CodeServer
    History <-->|"MCP"| HistoryServer
    CodeServer --> Git
    HistoryServer --> Git
```

By default the client starts each server as a subprocess and talks over stdio. Given `--code-url` and `--history-url`, it connects to running servers over Streamable HTTP instead. The rest of the system is the same either way.

## 4. Components

### MCP servers

There are two `MCPServer` instances from the official SDK, so routing between servers is real rather than simulated. Tool schemas and output schemas are generated from type hints and Pydantic models. All git access goes through `GitRepo` ([`servers/git.py`](../src/gitsleuth/servers/git.py)), which applies a timeout (20 s) and an output cap (2 MB) to each command. Anticipated failures are raised as `ToolError`, which the SDK returns as `is_error=True` so the model can read them.

### MCP client

`McpConnection` wraps `mcp.Client`. It connects, pages through `tools/list`, and exposes `call` and `recover`.

`mcp.Client` holds anyio task groups, which must be entered and exited by the same task, while retries can come from any task. So one supervisor task owns the client and other tasks only ask it to reopen the session. When several concurrent calls see the same dead session, a generation counter ensures they trigger one reconnect.

The client maps SDK errors to the three failures the dispatcher understands:

| SDK signal | Mapped to |
| --- | --- |
| `MCPError` code -32001 (request timeout) | `ToolTimeout` |
| `MCPError` code -32000 (connection closed), or any other transport exception | `ConnectionLost` |
| Any other `MCPError`, or a malformed result | `ToolProtocolError` |

The tool list is read once, when the toolbox opens. After a reconnect the connection refreshes its own list, but the registry and the definitions sent to the model stay as they were, because a changing tool list would invalidate the prompt cache. If a server drops a tool, calling it returns an error result.

### Tool dispatcher

`ToolRegistry` holds every tool as `<server>__<tool>`, rejecting invalid schemas and name collisions when a tool is registered. `ToolDispatcher.dispatch` never raises for a tool failure. See the [decision diagram](diagrams/dispatch-decisions.md).

| Error kind | Cause | Retried? |
| --- | --- | --- |
| `unknown_tool` | No such tool; the message lists the available ones | No |
| `invalid_input` | Fails JSON Schema validation; the message lists every problem | No |
| `tool_error` | The tool itself reported failure | No |
| `protocol` | MCP protocol error | No |
| `internal` | Unexpected exception; details go to the log, not the model | No |
| `timeout` | No answer within 30 s | If retry-safe |
| `connection` | Session lost; the session is always reopened | If retry-safe |

Retries use up to 3 attempts with exponential backoff (0.25 s base, 4 s cap, up to 50% jitter). `dispatch_many` runs a turn's calls concurrently, at most 4 at once, and returns outcomes in request order. Results over 60,000 characters are truncated with a marker that says how much was removed.

### Agent loop

The loop in [`agent.py`](../src/gitsleuth/agent.py) is hand-written rather than using the SDK's Tool Runner; [ADR-0002](adr/0002-manual-agent-loop.md) explains why. See the [sequence diagram](diagrams/sequence-agent-run.md).

| Stop reason | Trigger | Final model turn? | Default limit |
| --- | --- | --- | --- |
| `completed` | The model ended its turn | n/a | |
| `step_limit` | The step counter reached the limit | Yes, without tools | 12 model requests |
| `tool_call_limit` | The tool call budget ran out | Yes, without tools | 40 calls |
| `token_limit` | Total tokens over the limit, checked after each turn | No | 1,000,000 |
| `deadline` | Wall-clock limit; cancels whatever is in flight | No | 600 s |
| `truncated` | `stop_reason: max_tokens`; tool calls in that turn do not run | No | |
| `refused` | `stop_reason: refusal`; tool calls in that turn do not run | No | |
| `model_error` | The API request failed after the SDK's own retries | No | |

The token count adds input, output and cache tokens across all requests. Because it is checked after each turn, a run can pass the limit by up to one turn.

A turn with `stop_reason: pause_turn` continues with its content kept in the history. If a turn's calls exceed the remaining budget, the extra calls are answered with an error result, because the API requires a result for every `tool_use` block.

### Model client

`AnthropicModelClient` uses the official SDK's `client.beta.messages.stream`:

| Setting | Value | Why |
| --- | --- | --- |
| Model | `claude-opus-5-5` | Project default; `--model` overrides it |
| Streaming | `get_final_message()` with `max_tokens` 64,000 | Long turns cannot hit an HTTP timeout |
| Thinking | `adaptive` | |
| Effort | `high` | Set explicitly because this model defaults to `medium`; `--effort` overrides it |
| Prompt caching | Top-level `cache_control` | Each request repeats a growing history |
| Refusal fallback | `fallbacks: "default"` | A declined request can be retried on another model on the server side. `use_fallbacks=False` turns it off |
| `eager_input_streaming` | On for every tool | The dispatcher validates every input, which this setting requires of the client |

Assistant content is sent back unchanged on the next request, as thinking blocks require. If the SDK's stream parser raises on tool input it cannot parse, the request is sent once more, and a second failure becomes `model_error`.

## 5. Reliability

| Failure | Behaviour | Test |
| --- | --- | --- |
| Tool call hangs | Times out; retry-safe tools retry | `test_reliability.py` |
| Server process exits | Session reopened; retry-safe call retried | `test_reliability.py` |
| Server stays down | Attempts run out and the model gets a `connection` error; a later call reconnects | `test_reliability.py`, `test_mcp_http.py` |
| Tool with side effects loses its connection | Not retried; the error says the call may or may not have taken effect | `test_reliability.py` |
| Git hangs or produces huge output | Killed at the timeout or the output cap; a capped result is marked truncated | `test_servers.py` |
| Model call hangs | The run's deadline cancels it | `test_agent.py` |
| API failure | The run ends with `model_error`; findings so far are kept | `test_agent.py`, `test_cli.py` |

State lives in memory for one run. There is no resume after the host process itself dies.

## 6. Security

**Threat model.** Repository content is untrusted: a file or commit message can contain text aimed at the model, and repository config can name programs for git to run. The model's tool arguments are untrusted too, because that content can influence them.

| Risk | Mitigation |
| --- | --- |
| Reading secrets outside version control | Tools read committed objects only, so an uncommitted `.env` cannot be read or searched, and error messages do not reveal that it exists |
| Path traversal | Absolute paths, drive letters and `..` are refused. Reads go through `git cat-file`, not the filesystem |
| Git flag injection | Refs must start with a letter or digit and match an allowlist. Refs, paths and patterns are passed after `-e`, `--end-of-options` or `--`, or embedded in a `--flag=value` |
| Repository config running programs | `--no-ext-diff` and `--no-textconv`, plus command-line overrides for `diff.external`, `core.fsmonitor`, `core.hooksPath`, `core.pager` and `protocol.allow`. A test confirms the `diff.external` and textconv traps fire under plain `git show` and not through these tools |
| Prompt injection from repository text | The system prompt marks tool output as data. This is advice to the model, not a guarantee, and it is untested against a real model. The real limit is that no tool can change anything |
| Invented evidence | `record_finding` refuses hashes not seen in an earlier successful MCP result. Any hash-like token in a result counts as seen, so this does not prove the commit exists or that the claim follows from it |
| Sensitive data in logs | Logs record tool names, timings and counts, not arguments or results |

**Known limits.** Committed secrets can be read and will be sent to the Claude API if the model asks for them. The servers still read the user's global git config. The HTTP transport has no authentication, so it should stay on localhost.

## 7. Scaling and extension

This is a single-user CLI, and no load testing has been done.

- **Adding an MCP server:** open another `McpConnection` in [`toolbox.py`](../src/gitsleuth/toolbox.py) and call `registry.add_mcp_server`. Routing, validation and retries then apply with no other changes.
- **Adding a local tool:** implement `call` and `recover`, then register it as `Notebook.register` does.
- **Serving many users:** this would need shared HTTP servers with authentication, per-run state outside the process, and per-repository sandboxing. None of that exists today.
- **Long runs:** there is no context compaction. A run is bounded by its limits rather than by managing the context window.

## 8. Observability

`--verbose` writes one JSON object per event to stderr, including `run_started`, `model_turn` (with token counts and request ID), `tool_call` (with attempts, duration and error kind), `tool_retry`, `mcp_connected`, `mcp_session_failed`, `run_finished`. Events within a run carry its `run_id`, except those from the dispatcher and client, which sit below the run.

## 9. Design decisions

- [ADR-0001: Read committed git objects, not the filesystem](adr/0001-read-git-objects.md)
- [ADR-0002: A hand-written agent loop](adr/0002-manual-agent-loop.md)
- [ADR-0003: Retry only what the server declares safe](adr/0003-retry-safety.md)
