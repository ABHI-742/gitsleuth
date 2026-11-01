# ADR-0004: Working with the Java SDKs' behaviour

**Status:** Accepted

## Context
This project was first written in Python and then rewritten in Java. The design stayed the same, but tests against the Java SDKs showed three behaviours the design had to account for.

1. **A dead stdio server looks like a hang.** With MCP Java SDK 2.1.0, when the server process exits, pending requests wait for their timeout rather than failing. With a 30 s timeout and 3 attempts, one crash would stall a call for 90 s and then report the wrong cause.
2. **Broken tool JSON becomes an empty object.** With Anthropic Java SDK 3.3.0 and `eager_input_streaming` on, the stream accumulator turns unparseable tool input into `{}` without raising. A tool with no required fields, such as `git_log`, would then pass schema validation and run on its defaults.
3. **Jackson 2 and 3 share an annotations jar.** The Anthropic SDK uses Jackson 2 and the MCP SDK uses Jackson 3. Maven's default resolution picked an annotations version too old for Jackson 3, and the failure appeared only as a server that never answered the handshake.

## Decision
1. Watch each stdio server process with `StdioClientTransport.awaitForExit()` on a virtual thread, and race every call against that exit. An error from a session that has ended is treated as a lost connection, not as the server's answer.
2. Leave `eager_input_streaming` off. Tool inputs here are short, so streaming them early gains nothing, and without it the API validates input before sending it. A test pins the SDK behaviour, so a change in a later version will be noticed.
3. Pin `jackson-annotations` in `dependencyManagement`.

## Consequences
- Crash recovery takes about as long as a JVM start, rather than the length of a timeout.
- The watcher covers stdio only. Over HTTP a dead server surfaces through the transport's own errors.
- Treating tool input as valid JSON relies on the API's validation, which has not been exercised against the real API. The dispatcher's schema check still runs on every call.
- Upgrading either SDK should include re-running `ReliabilityTest` and `AnthropicAdapterTest`, because both pin SDK behaviour.
