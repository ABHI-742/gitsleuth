# Sequence: one agent run

One run that hits a crashed server, recovers, records a finding and answers. `AnthropicModelClient` is folded into the Agent's arrow to Claude.

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Developer
    participant CLI
    participant Agent
    participant Claude as Claude API
    participant Disp as ToolDispatcher
    participant Conn as McpConnection
    participant Server as MCP server
    participant Nb as Notebook

    Dev->>CLI: gitsleuth ask "When did rounding break?"
    CLI->>Conn: open connections (one per server)
    Conn->>Server: connect, tools/list
    Server-->>Conn: tools and input schemas
    CLI->>Agent: run(question)

    Agent->>Claude: messages + tool definitions
    Claude-->>Agent: stop_reason tool_use: read_file, find_string_changes
    par up to 4 calls at once
        Agent->>Disp: dispatch(read_file)
        Disp->>Disp: route and validate against schema
        Disp->>Conn: call(read_file)
        Conn->>Server: tools/call
        Server-->>Conn: result
        Conn-->>Disp: ToolOutput
        Disp-->>Agent: DispatchOutcome
    and
        Agent->>Disp: dispatch(find_string_changes)
        Disp->>Conn: call(find_string_changes)
        Conn->>Server: tools/call
        Server--xConn: process exits
        Conn-->>Disp: ConnectionLost
        Disp->>Conn: recover()
        Conn->>Server: new session, tools/list
        Disp->>Disp: tool is retry-safe, back off
        Disp->>Conn: call(find_string_changes), attempt 2
        Conn->>Server: tools/call
        Server-->>Conn: result
        Conn-->>Disp: ToolOutput
        Disp-->>Agent: DispatchOutcome (attempts 2)
    end
    Agent->>Nb: observe(hashes in results)

    Agent->>Claude: history + all tool results in one user message
    Claude-->>Agent: stop_reason tool_use: record_finding
    Agent->>Disp: dispatch(record_finding)
    Disp->>Nb: call(record_finding)
    Nb->>Nb: cited commit was seen earlier?
    Nb-->>Disp: Recorded finding 1
    Disp-->>Agent: DispatchOutcome

    Agent->>Claude: history + tool result
    Claude-->>Agent: stop_reason end_turn: final answer
    Agent-->>CLI: AgentResult (completed, findings, usage)
    CLI-->>Dev: answer, findings, stop reason
    CLI->>Conn: close connections
```

Each pass through the loop first checks the limits. On the last allowed step, or once the tool call budget is spent, the request goes out with `tool_choice: none` so the model can only answer. The deadline covers the whole run and cancels whichever arrow is in flight.
