package dev.gitsleuth;

import dev.gitsleuth.model.ModelClient;
import dev.gitsleuth.model.ModelClient.Message;
import dev.gitsleuth.model.ModelClient.ModelTurn;
import dev.gitsleuth.model.ModelClient.Usage;
import dev.gitsleuth.tools.ToolBackend;
import dev.gitsleuth.tools.ToolDispatcher.ToolCall;
import dev.gitsleuth.tools.ToolRegistry.ToolDefinition;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Test doubles. No test in this suite calls the Claude API. */
final class Fakes {
  private Fakes() {}

  record Request(
      String system, List<Message> messages, List<ToolDefinition> tools, boolean allowTools) {}

  /** Plays back turns in order and records the requests it received. */
  static final class ScriptedModel implements ModelClient {
    private final Deque<Object> script;
    final List<Request> requests = new ArrayList<>();

    ScriptedModel(Object... turns) {
      script = new ArrayDeque<>(Arrays.asList(turns));
    }

    @Override
    @SuppressWarnings("unchecked")
    public ModelTurn complete(
        String system, List<Message> messages, List<ToolDefinition> tools, boolean allowTools)
        throws ModelException {
      requests.add(new Request(system, messages, tools, allowTools));
      Object next = script.removeFirst();
      if (next instanceof ModelException error) {
        throw error;
      }
      if (next instanceof Function<?, ?> function) {
        return ((Function<List<Message>, ModelTurn>) function).apply(messages);
      }
      return (ModelTurn) next;
    }
  }

  static final class SlowModel implements ModelClient {
    @Override
    public ModelTurn complete(
        String system, List<Message> messages, List<ToolDefinition> tools, boolean allowTools)
        throws InterruptedException {
      Thread.sleep(60_000);
      throw new IllegalStateException("unreachable");
    }
  }

  record Named(String name, Map<String, Object> arguments) {}

  static Named named(String name, Map<String, Object> arguments) {
    return new Named(name, arguments);
  }

  static ModelTurn toolTurn(long tokens, Named... calls) {
    List<ToolCall> toolCalls = new ArrayList<>();
    for (int i = 0; i < calls.length; i++) {
      toolCalls.add(new ToolCall("toolu_" + i, calls[i].name(), calls[i].arguments()));
    }
    return new ModelTurn("tool_use", "raw", "", toolCalls, new Usage(tokens, tokens, 0, 0), null);
  }

  static ModelTurn toolTurn(Named... calls) {
    return toolTurn(10, calls);
  }

  static ModelTurn textTurn(String text) {
    return textTurn(text, "end_turn");
  }

  static ModelTurn textTurn(String text, String stopReason) {
    return new ModelTurn(stopReason, "raw", text, List.of(), new Usage(10, 10, 0, 0), null);
  }

  /** A tool backend whose calls follow a script of outputs and exceptions. */
  static class FakeBackend implements ToolBackend {
    private final Deque<Object> script;
    final List<String> calledTools = new ArrayList<>();
    final List<Map<String, Object>> calledArguments = new ArrayList<>();
    final List<Duration> timeouts = new ArrayList<>();
    int recoveries;

    FakeBackend(Object... script) {
      this.script = new ArrayDeque<>(Arrays.asList(script));
    }

    @Override
    public synchronized ToolOutput call(
        String tool, Map<String, Object> arguments, Duration timeout)
        throws ToolTimeoutException, ConnectionLostException, ToolProtocolException {
      calledTools.add(tool);
      calledArguments.add(arguments);
      timeouts.add(timeout);
      Object item = script.isEmpty() ? ToolOutput.ok("ok") : script.removeFirst();
      if (item instanceof ToolTimeoutException e) {
        throw e;
      }
      if (item instanceof ConnectionLostException e) {
        throw e;
      }
      if (item instanceof ToolProtocolException e) {
        throw e;
      }
      if (item instanceof RuntimeException e) {
        throw e;
      }
      return (ToolOutput) item;
    }

    @Override
    public void recover(ConnectionLostException lost) {
      recoveries++;
    }
  }
}
