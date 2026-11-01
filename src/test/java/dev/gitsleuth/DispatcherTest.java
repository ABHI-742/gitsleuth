package dev.gitsleuth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gitsleuth.Fakes.FakeBackend;
import dev.gitsleuth.tools.ToolBackend.ConnectionLostException;
import dev.gitsleuth.tools.ToolBackend.ToolOutput;
import dev.gitsleuth.tools.ToolBackend.ToolProtocolException;
import dev.gitsleuth.tools.ToolBackend.ToolTimeoutException;
import dev.gitsleuth.tools.ToolDispatcher;
import dev.gitsleuth.tools.ToolDispatcher.DispatchOutcome;
import dev.gitsleuth.tools.ToolDispatcher.ErrorKind;
import dev.gitsleuth.tools.ToolDispatcher.RetryPolicy;
import dev.gitsleuth.tools.ToolDispatcher.ToolCall;
import dev.gitsleuth.tools.ToolRegistry;
import dev.gitsleuth.tools.ToolRegistry.ToolDefinition;
import dev.gitsleuth.tools.ToolRegistry.ToolRegistryException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Dispatcher rules, using fake backends so each failure can be scripted exactly. */
class DispatcherTest {
  private static final Map<String, Object> SCHEMA =
      Map.of(
          "type",
          "object",
          "properties",
          Map.of(
              "path", Map.of("type", "string"), "limit", Map.of("type", "integer", "minimum", 1)),
          "required",
          List.of("path"),
          "additionalProperties",
          false);
  private static final ToolCall GOOD_CALL = new ToolCall("t", "srv__read", Map.of("path", "a.py"));

  private final List<Duration> sleeps = new ArrayList<>();

  private ToolDispatcher.Builder builder(FakeBackend backend, boolean retrySafe) {
    ToolRegistry registry = new ToolRegistry();
    registry.add("srv", "read", "Read", SCHEMA, retrySafe, backend, false);
    return ToolDispatcher.builder(registry).sleeper(sleeps::add).random(new Random(1));
  }

  private ToolDispatcher make(FakeBackend backend) {
    return builder(backend, true).build();
  }

  @Test
  void routesToTheBackendUnderTheServersOwnToolName() throws Exception {
    FakeBackend first = new FakeBackend(ToolOutput.ok("from one"));
    FakeBackend second = new FakeBackend(ToolOutput.ok("from two"));
    ToolRegistry registry = new ToolRegistry();
    registry.add("one", "read", "", SCHEMA, true, first, false);
    registry.add("two", "read", "", SCHEMA, true, second, false);
    ToolDispatcher dispatcher =
        ToolDispatcher.builder(registry).callTimeout(Duration.ofSeconds(7)).build();

    DispatchOutcome outcome =
        dispatcher.dispatch(new ToolCall("t", "two__read", Map.of("path", "a")));

    assertEquals("from two", outcome.text());
    assertEquals(List.of("read"), second.calledTools);
    assertEquals(List.of(Duration.ofSeconds(7)), second.timeouts);
    assertTrue(first.calledTools.isEmpty());
  }

  @Test
  void unknownToolListsWhatExistsAndCallsNothing() throws Exception {
    FakeBackend backend = new FakeBackend();
    DispatchOutcome outcome = make(backend).dispatch(new ToolCall("t", "srv__nope", Map.of()));
    assertEquals(ErrorKind.UNKNOWN_TOOL, outcome.errorKind());
    assertTrue(outcome.text().contains("srv__read"));
    assertEquals(0, outcome.attempts());
    assertTrue(backend.calledTools.isEmpty());
  }

  @Test
  void inputIsValidatedBeforeAnyBackendCall() throws Exception {
    List<Map<String, Object>> invalid =
        List.of(
            Map.of(),
            Map.of("path", 3),
            Map.of("path", "a", "limit", 0),
            Map.of("path", "a", "extra", 1));
    for (Map<String, Object> arguments : invalid) {
      FakeBackend backend = new FakeBackend();
      DispatchOutcome outcome = make(backend).dispatch(new ToolCall("t", "srv__read", arguments));
      assertEquals(ErrorKind.INVALID_INPUT, outcome.errorKind(), arguments.toString());
      assertTrue(backend.calledTools.isEmpty());
    }
  }

  @Test
  void allValidationProblemsAreReportedTogether() throws Exception {
    DispatchOutcome outcome =
        make(new FakeBackend())
            .dispatch(new ToolCall("t", "srv__read", Map.of("limit", "x", "other", 1)));
    assertTrue(outcome.text().contains("path"), outcome.text());
    assertTrue(outcome.text().contains("limit"), outcome.text());
    assertTrue(outcome.text().contains("other"), outcome.text());
  }

  @Test
  void timeoutsBackOffExponentiallyThenSucceed() throws Exception {
    FakeBackend backend =
        new FakeBackend(
            new ToolTimeoutException("slow"),
            new ToolTimeoutException("slow"),
            ToolOutput.ok("done"));
    ToolDispatcher dispatcher =
        builder(backend, true)
            .retry(new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(10), 0.0))
            .build();
    DispatchOutcome outcome = dispatcher.dispatch(GOOD_CALL);
    assertEquals("done", outcome.text());
    assertFalse(outcome.isError());
    assertEquals(3, outcome.attempts());
    assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), sleeps);
  }

  @Test
  void backoffIsCappedAndJitterOnlyShortensIt() {
    RetryPolicy policy = new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(4), 0.5);
    Random random = new Random(7);
    int[][] cases = {{1, 1000}, {2, 2000}, {3, 4000}, {9, 4000}};
    for (int[] c : cases) {
      long delay = policy.delay(c[0], random).toMillis();
      assertTrue(delay >= c[1] / 2 && delay <= c[1], "delay " + delay);
    }
  }

  @Test
  void connectionLossRecoversBeforeRetrying() throws Exception {
    FakeBackend backend =
        new FakeBackend(new ConnectionLostException("gone", 1), ToolOutput.ok("back"));
    assertEquals("back", make(backend).dispatch(GOOD_CALL).text());
    assertEquals(1, backend.recoveries);
  }

  @Test
  void toolsNotDeclaredSafeAreNeverRetried() throws Exception {
    Object[][] failures = {
      {new ToolTimeoutException("slow"), ErrorKind.TIMEOUT},
      {new ConnectionLostException("gone", 1), ErrorKind.CONNECTION}
    };
    for (Object[] failure : failures) {
      FakeBackend backend = new FakeBackend(failure[0]);
      DispatchOutcome outcome = builder(backend, false).build().dispatch(GOOD_CALL);
      assertEquals(failure[1], outcome.errorKind());
      assertEquals(1, outcome.attempts());
      assertEquals(1, backend.calledTools.size());
      assertTrue(sleeps.isEmpty());
    }
  }

  @Test
  void protocolErrorsAreNotRetried() throws Exception {
    FakeBackend backend =
        new FakeBackend(new ToolProtocolException("MCP error -32602: bad params"));
    assertEquals(ErrorKind.PROTOCOL, make(backend).dispatch(GOOD_CALL).errorKind());
    assertEquals(1, backend.calledTools.size());
  }

  @Test
  void toolReportedErrorsAreNotRetried() throws Exception {
    FakeBackend backend = new FakeBackend(ToolOutput.error("no such file"));
    DispatchOutcome outcome = make(backend).dispatch(GOOD_CALL);
    assertEquals(ErrorKind.TOOL_ERROR, outcome.errorKind());
    assertEquals("no such file", outcome.text());
    assertEquals(1, backend.calledTools.size());
  }

  @Test
  void unexpectedExceptionsBecomeAnInternalErrorWithoutLeakingDetails() throws Exception {
    DispatchOutcome outcome =
        make(new FakeBackend(new IllegalStateException("secret internals"))).dispatch(GOOD_CALL);
    assertEquals(ErrorKind.INTERNAL, outcome.errorKind());
    assertFalse(outcome.text().contains("secret internals"));
  }

  @Test
  void longResultsAreTruncatedWithAMarker() throws Exception {
    FakeBackend backend = new FakeBackend(ToolOutput.ok("x".repeat(500)));
    DispatchOutcome outcome =
        builder(backend, true).maxResultChars(100).build().dispatch(GOOD_CALL);
    assertTrue(outcome.truncated());
    assertTrue(outcome.text().startsWith("x".repeat(100)));
    assertTrue(outcome.text().contains("400 characters omitted"));
  }

  @Test
  void dispatchManyKeepsOrderAndRespectsTheConcurrencyLimit() throws Exception {
    AtomicInteger running = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    FakeBackend tracking =
        new FakeBackend() {
          @Override
          public ToolOutput call(String tool, Map<String, Object> arguments, Duration timeout) {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            String path = (String) arguments.get("path");
            try {
              // Later calls finish first, so an order bug would show.
              Thread.sleep(100 / (1 + Integer.parseInt(path)));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            return ToolOutput.ok(path);
          }
        };
    List<ToolCall> calls = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      calls.add(new ToolCall(String.valueOf(i), "srv__read", Map.of("path", String.valueOf(i))));
    }
    List<DispatchOutcome> outcomes =
        builder(tracking, true).maxConcurrency(2).build().dispatchMany(calls);
    assertEquals(
        List.of("0", "1", "2", "3", "4", "5"),
        outcomes.stream().map(DispatchOutcome::toolUseId).toList());
    assertEquals(
        List.of("0", "1", "2", "3", "4", "5"),
        outcomes.stream().map(DispatchOutcome::text).toList());
    assertEquals(2, peak.get());
  }

  @Test
  void registryRejectsBadSchemasAndCollisions() {
    ToolRegistry registry = new ToolRegistry();
    FakeBackend backend = new FakeBackend();
    registry.add("a", "t", "", SCHEMA, true, backend, false);
    assertTrue(
        assertThrows(
                ToolRegistryException.class,
                () -> registry.add("a", "t", "", SCHEMA, true, backend, false))
            .getMessage()
            .contains("duplicate"));
    assertTrue(
        assertThrows(
                ToolRegistryException.class,
                () -> registry.add("a", "u", "", Map.of("type", "nonsense"), true, backend, false))
            .getMessage()
            .contains("invalid schema"));
    assertTrue(
        assertThrows(
                ToolRegistryException.class,
                () -> registry.add("a", "x".repeat(80), "", SCHEMA, true, backend, false))
            .getMessage()
            .contains("exceeds"));
  }

  @Test
  void toolNamesAreMadeValidAndDefinitionsSorted() {
    ToolRegistry registry = new ToolRegistry();
    FakeBackend backend = new FakeBackend();
    registry.add("zeta", "read.file", "", SCHEMA, true, backend, false);
    registry.add("alpha", "read", "", SCHEMA, true, backend, false);
    assertEquals(
        List.of("alpha__read", "zeta__read_file"),
        registry.definitions().stream().map(ToolDefinition::name).toList());
  }
}
