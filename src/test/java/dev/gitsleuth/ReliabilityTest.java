package dev.gitsleuth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gitsleuth.tools.ToolDispatcher;
import dev.gitsleuth.tools.ToolDispatcher.DispatchOutcome;
import dev.gitsleuth.tools.ToolDispatcher.ErrorKind;
import dev.gitsleuth.tools.ToolDispatcher.RetryPolicy;
import dev.gitsleuth.tools.ToolDispatcher.ToolCall;
import dev.gitsleuth.tools.ToolRegistry;
import io.modelcontextprotocol.client.transport.ServerParameters;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Timeouts, crashes and retries against a real server process that misbehaves on request. */
@Timeout(120)
class ReliabilityTest {
  private static final RetryPolicy FAST_RETRY =
      new RetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(20), 0.5);

  @TempDir Path state;

  private ToolDispatcher dispatcher(McpConnection connection, Duration callTimeout) {
    ToolRegistry registry = new ToolRegistry();
    registry.addMcpServer(connection);
    return ToolDispatcher.builder(registry).retry(FAST_RETRY).callTimeout(callTimeout).build();
  }

  private McpConnection open() throws Exception {
    return McpConnection.open("chaos", ChaosServer.target(state));
  }

  private static ToolCall call(String name, Map<String, Object> arguments) {
    return new ToolCall("t", "chaos__" + name, arguments);
  }

  @Test
  void timedOutCallIsRetriedAndSucceeds() throws Exception {
    try (McpConnection connection = open()) {
      DispatchOutcome outcome =
          dispatcher(connection, Duration.ofSeconds(2))
              .dispatch(call("slow_once", Map.of("seconds", 30)));
      assertFalse(outcome.isError(), outcome.text());
      assertEquals("finished", outcome.text());
      assertEquals(2, outcome.attempts());
      assertEquals(2, ChaosServer.count(state, "slow_once"));
    }
  }

  @Test
  void timeoutsGiveUpAfterMaxAttemptsAndTheServerStaysHealthy() throws Exception {
    try (McpConnection connection = open()) {
      ToolDispatcher dispatcher = dispatcher(connection, Duration.ofMillis(500));
      DispatchOutcome outcome = dispatcher.dispatch(call("always_slow", Map.of("seconds", 30)));
      assertEquals(ErrorKind.TIMEOUT, outcome.errorKind());
      assertEquals(3, outcome.attempts());
      assertTrue(outcome.text().contains("gave up after 3 attempts"), outcome.text());
      DispatchOutcome after =
          dispatcher(connection, Duration.ofSeconds(10))
              .dispatch(call("echo", Map.of("message", "hi")));
      assertEquals("echo: hi", after.text());
    }
  }

  @Test
  void crashedServerIsRestartedAndTheCallRetried() throws Exception {
    try (McpConnection connection = open()) {
      assertEquals(1, connection.generation());
      ToolDispatcher dispatcher = dispatcher(connection, Duration.ofSeconds(20));
      DispatchOutcome outcome = dispatcher.dispatch(call("crash_once", Map.of()));
      assertFalse(outcome.isError(), outcome.text());
      assertEquals("survived", outcome.text());
      assertEquals(2, outcome.attempts());
      assertEquals(2, connection.generation());
      assertEquals("echo: hi", dispatcher.dispatch(call("echo", Map.of("message", "hi"))).text());
    }
  }

  @Test
  void toolWithSideEffectsIsNotRetriedAfterACrash() throws Exception {
    try (McpConnection connection = open()) {
      ToolDispatcher dispatcher = dispatcher(connection, Duration.ofSeconds(20));
      assertFalse(dispatcher.registry().get("chaos__write_then_crash").retrySafe());
      DispatchOutcome outcome = dispatcher.dispatch(call("write_then_crash", Map.of()));
      assertEquals(ErrorKind.CONNECTION, outcome.errorKind());
      assertEquals(1, outcome.attempts());
      assertTrue(outcome.text().contains("may or may not have taken effect"), outcome.text());
      assertEquals(1, ChaosServer.count(state, "write_then_crash"));
      // The connection was still repaired for later calls.
      assertEquals("echo: hi", dispatcher.dispatch(call("echo", Map.of("message", "hi"))).text());
    }
  }

  @Test
  void toolErrorsReachTheModelWithoutARetry() throws Exception {
    try (McpConnection connection = open()) {
      DispatchOutcome outcome =
          dispatcher(connection, Duration.ofSeconds(20)).dispatch(call("tool_failure", Map.of()));
      assertEquals(ErrorKind.TOOL_ERROR, outcome.errorKind());
      assertTrue(outcome.text().contains("found nothing"));
      assertEquals(1, outcome.attempts());
      assertEquals(1, ChaosServer.count(state, "tool_failure"));
    }
  }

  @Test
  void concurrentCallsShareOneReconnect() throws Exception {
    try (McpConnection connection = open()) {
      ToolDispatcher dispatcher = dispatcher(connection, Duration.ofSeconds(20));
      List<DispatchOutcome> outcomes =
          dispatcher.dispatchMany(
              List.of(
                  new ToolCall("a", "chaos__crash_once", Map.of()),
                  new ToolCall("b", "chaos__always_slow", Map.of("seconds", 0.5)),
                  new ToolCall("c", "chaos__echo", Map.of("message", "c"))));
      assertEquals(2, connection.generation());
      assertEquals(
          List.of("a", "b", "c"), outcomes.stream().map(DispatchOutcome::toolUseId).toList());
      assertTrue(outcomes.stream().noneMatch(DispatchOutcome::isError), outcomes.toString());
    }
  }

  @Test
  void connectionRecoversWhenTheServerComesBack() throws Exception {
    McpConnection.Target good = ChaosServer.target(state);
    try (McpConnection connection = McpConnection.open("chaos", good, Duration.ofSeconds(5))) {
      ToolDispatcher dispatcher = dispatcher(connection, Duration.ofSeconds(20));

      // While the server cannot start, attempts run out and the outcome reports it.
      String java = ProcessHandle.current().info().command().orElse("java");
      connection.retarget(
          new McpConnection.Target.Stdio(ServerParameters.builder(java).args("-version").build()));
      DispatchOutcome failed = dispatcher.dispatch(call("crash_once", Map.of()));
      assertEquals(ErrorKind.CONNECTION, failed.errorKind());
      assertEquals(3, failed.attempts());

      // Once it can start again, the next call reconnects.
      connection.retarget(good);
      DispatchOutcome recovered = dispatcher.dispatch(call("echo", Map.of("message", "hi")));
      assertEquals("echo: hi", recovered.text());
      assertEquals(2, recovered.attempts());
    }
  }
}
