# ADR-0003: Retry only what the server declares safe

**Status:** Accepted

## Context
After a timeout or a lost connection, the client cannot tell whether the call ran. Retrying a read is harmless, but retrying a write can apply it twice. A tool's own reported failure is different again: the same input would most likely fail the same way, and the model can do something better with the message.

## Decision
- Retry only on `timeout` and `connection`, and only if the tool's MCP annotations set `readOnlyHint` or `idempotentHint`.
- Treat a missing annotation as unsafe, in line with the MCP defaults.
- Never retry `tool_error`, `invalid_input`, `unknown_tool`, `protocol` or `internal`. Return them to the model.
- When a call is not retried after a timeout or lost connection, say in the result that it may or may not have taken effect.
- Reopen a lost session even if the call that noticed it will not be retried, so later calls work.

## Consequences
- All bundled MCP tools declare themselves read-only and retry. `notes__record_finding` is local and is registered as not retry-safe.
- A third-party server that omits annotations loses retries but stays correct.
- Annotations are the server's claim and are not verified, so a server that wrongly declares a write as read-only could have that write repeated.
