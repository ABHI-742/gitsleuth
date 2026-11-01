# ADR-0002: A hand-written agent loop

**Status:** Accepted

## Context
The Anthropic SDK's Tool Runner drives a tool loop for you and is usually the better default. The API's MCP connector can also call MCP servers directly, but it needs servers Anthropic can reach by URL, and these run locally against a local repository.

## Decision
Write the loop by hand against a small `ModelClient` protocol, with an MCP client and dispatcher in this process.

The main reason is scope: the loop and dispatcher are what this project sets out to demonstrate. There are technical reasons too. The loop needs several kinds of stop with partial results, a final turn without tools when a limit is reached, and a budget that spans a parallel batch. Tests can also script turns at the protocol level, without HTTP.

## Consequences
- The loop has to follow API rules itself: echo assistant content unchanged, return all results in one user message, answer every `tool_use` block, and handle `pause_turn`, `max_tokens` and `refusal`. Tests cover each of these.
- There is no dependency on the Tool Runner's beta interface, although the fallback feature still uses the beta Messages endpoint.
- A team building a product rather than a demonstration should compare this with the Tool Runner's per-turn hooks first.
