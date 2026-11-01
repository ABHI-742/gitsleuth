package dev.gitsleuth;

import static dev.gitsleuth.Fakes.named;
import static dev.gitsleuth.Fakes.textTurn;
import static dev.gitsleuth.Fakes.toolTurn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gitsleuth.Agent.Limits;
import dev.gitsleuth.Agent.Result;
import dev.gitsleuth.Agent.StopReason;
import dev.gitsleuth.Agent.ToolCallRecord;
import dev.gitsleuth.Fakes.FakeBackend;
import dev.gitsleuth.Fakes.Named;
import dev.gitsleuth.Fakes.ScriptedModel;
import dev.gitsleuth.Fakes.SlowModel;
import dev.gitsleuth.Notebook.Finding;
import dev.gitsleuth.model.ModelClient;
import dev.gitsleuth.model.ModelClient.Message;
import dev.gitsleuth.model.ModelClient.ModelException;
import dev.gitsleuth.model.ModelClient.ModelTurn;
import dev.gitsleuth.model.ModelClient.ToolResult;
import dev.gitsleuth.model.ModelClient.Usage;
import dev.gitsleuth.tools.ToolBackend.ToolOutput;
import dev.gitsleuth.tools.ToolDispatcher;
import dev.gitsleuth.tools.ToolRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The agent loop's stopping rules, with a scripted model and a local tool backend. */
@Timeout(30)
class AgentTest {
  private static final Map<String, Object> SCHEMA =
      Map.of(
          "type",
          "object",
          "properties",
          Map.of("q", Map.of("type", "string")),
          "required",
          List.of("q"));
  private static final String SHA = "a".repeat(40);

  private static Named find(String q) {
    return named("srv__find", Map.of("q", q));
  }

  private static Agent agent(ModelClient model, FakeBackend backend, Limits limits) {
    ToolRegistry registry = new ToolRegistry();
    registry.add("srv", "find", "Find", SCHEMA, true, backend, false);
    Notebook notebook = new Notebook();
    notebook.register(registry);
    return new Agent(model, ToolDispatcher.builder(registry).build(), notebook, limits);
  }

  private static List<ToolResult> toolResults(Fakes.Request request, int index) {
    return ((Message.ToolResults) request.messages().get(index)).results();
  }

  @Test
  void loopFeedsToolResultsBackUntilTheModelAnswers() {
    ScriptedModel model = new ScriptedModel(toolTurn(find("bug")), textTurn("The answer."));
    FakeBackend backend = new FakeBackend(ToolOutput.ok("evidence"));
    Result result = agent(model, backend, Limits.defaults()).run("Where is the bug?");

    assertEquals(StopReason.COMPLETED, result.stopReason());
    assertEquals("The answer.", result.answer());
    assertEquals(2, result.steps());
    assertEquals(
        List.of("srv__find"), result.toolCalls().stream().map(ToolCallRecord::name).toList());
    assertEquals(20, result.usage().inputTokens());
    assertEquals(Map.of("q", "bug"), backend.calledArguments.get(0));

    List<Message> history = model.requests.get(1).messages();
    assertEquals(new Message.UserText("Where is the bug?"), history.get(0));
    assertTrue(history.get(1) instanceof Message.Assistant);
    assertEquals(
        List.of(new ToolResult("toolu_0", "evidence", false)),
        toolResults(model.requests.get(1), 2));
  }

  @Test
  void assistantContentIsEchoedBackUnchanged() {
    ModelTurn first = toolTurn(find("x"));
    ScriptedModel model = new ScriptedModel(first, textTurn("done"));
    agent(model, new FakeBackend(), Limits.defaults()).run("q");
    assertSame(first, ((Message.Assistant) model.requests.get(1).messages().get(1)).turn());
  }

  @Test
  void parallelCallsReturnInOneUserMessageInOrder() {
    ScriptedModel model =
        new ScriptedModel(toolTurn(find("1"), find("2"), find("3")), textTurn("done"));
    agent(model, new FakeBackend(), Limits.defaults()).run("q");
    assertEquals(3, model.requests.get(1).messages().size());
    assertEquals(
        List.of("toolu_0", "toolu_1", "toolu_2"),
        toolResults(model.requests.get(1), 2).stream().map(ToolResult::toolUseId).toList());
  }

  @Test
  void toolErrorsGoBackToTheModelMarkedAsErrors() {
    ScriptedModel model =
        new ScriptedModel(
            toolTurn(named("srv__find", Map.of("wrong", 1)), named("srv__missing", Map.of())),
            textTurn("recovered"));
    FakeBackend backend = new FakeBackend();
    Result result = agent(model, backend, Limits.defaults()).run("q");
    assertEquals(StopReason.COMPLETED, result.stopReason());
    assertTrue(backend.calledTools.isEmpty());
    assertTrue(toolResults(model.requests.get(1), 2).stream().allMatch(ToolResult::isError));
    assertEquals(
        List.of("invalid_input", "unknown_tool"),
        result.toolCalls().stream().map(ToolCallRecord::errorKind).toList());
  }

  @Test
  void stepLimitGivesAFinalTurnWithoutTools() {
    ScriptedModel model =
        new ScriptedModel(toolTurn(find("1")), toolTurn(find("2")), textTurn("best answer so far"));
    Result result = agent(model, new FakeBackend(), Limits.defaults().withMaxSteps(3)).run("q");
    assertEquals(
        List.of(true, true, false),
        model.requests.stream().map(Fakes.Request::allowTools).toList());
    assertEquals(StopReason.STEP_LIMIT, result.stopReason());
    assertEquals("best answer so far", result.answer());
    assertEquals(3, result.steps());
  }

  @Test
  void toolsRequestedOnTheFinalTurnDoNotRun() {
    ScriptedModel model = new ScriptedModel(toolTurn(find("1")));
    FakeBackend backend = new FakeBackend();
    Result result = agent(model, backend, Limits.defaults().withMaxSteps(1)).run("q");
    assertEquals(StopReason.STEP_LIMIT, result.stopReason());
    assertTrue(backend.calledTools.isEmpty());
  }

  @Test
  void toolCallBudgetAnswersEveryCallAndWindsDown() {
    ScriptedModel model =
        new ScriptedModel(toolTurn(find("1"), find("2"), find("3")), textTurn("partial answer"));
    FakeBackend backend = new FakeBackend();
    Result result = agent(model, backend, Limits.defaults().withMaxToolCalls(2)).run("q");
    assertEquals(StopReason.TOOL_CALL_LIMIT, result.stopReason());
    assertEquals("partial answer", result.answer());
    assertEquals(2, backend.calledTools.size());
    assertFalse(model.requests.get(1).allowTools());
    List<ToolResult> results = toolResults(model.requests.get(1), 2);
    assertEquals(
        List.of("toolu_0", "toolu_1", "toolu_2"),
        results.stream().map(ToolResult::toolUseId).toList());
    assertTrue(results.get(2).isError());
  }

  @Test
  void tokenLimitStopsWithoutAnotherModelCall() {
    ScriptedModel model = new ScriptedModel(toolTurn(600, find("1")));
    Result result =
        agent(model, new FakeBackend(), Limits.defaults().withMaxTotalTokens(1000)).run("q");
    assertEquals(StopReason.TOKEN_LIMIT, result.stopReason());
    assertEquals(1, model.requests.size());
  }

  @Test
  void deadlineCancelsAStuckModelCall() {
    Result result =
        agent(
                new SlowModel(),
                new FakeBackend(),
                Limits.defaults().withDeadline(Duration.ofMillis(100)))
            .run("q");
    assertEquals(StopReason.DEADLINE, result.stopReason());
    assertTrue(result.durationMs() < 5000);
  }

  @Test
  void deadlineCancelsAStuckToolCallAndKeepsEarlierFindings() {
    FakeBackend stuck =
        new FakeBackend() {
          @Override
          public ToolOutput call(String tool, Map<String, Object> arguments, Duration timeout) {
            if ("evidence".equals(arguments.get("q"))) {
              return ToolOutput.ok("commit " + SHA);
            }
            try {
              Thread.sleep(60_000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return ToolOutput.ok("late");
          }
        };
    ScriptedModel model =
        new ScriptedModel(
            toolTurn(find("evidence")),
            toolTurn(
                named(
                    "notes__record_finding",
                    Map.of(
                        "claim", "It broke here", "commits", List.of(SHA), "confidence", "high"))),
            toolTurn(find("stuck")));
    Result result =
        agent(model, stuck, Limits.defaults().withDeadline(Duration.ofSeconds(1))).run("q");
    assertEquals(StopReason.DEADLINE, result.stopReason());
    assertEquals(List.of("It broke here"), result.findings().stream().map(Finding::claim).toList());
  }

  @Test
  void toolsInCutOffTurnsNeverRun() {
    Object[][] cases = {{"max_tokens", StopReason.TRUNCATED}, {"refusal", StopReason.REFUSED}};
    for (Object[] c : cases) {
      ModelTurn base = toolTurn(find("partial"));
      ModelTurn turn =
          new ModelTurn((String) c[0], base.raw(), "", base.toolCalls(), base.usage(), null);
      FakeBackend backend = new FakeBackend();
      Result result = agent(new ScriptedModel(turn), backend, Limits.defaults()).run("q");
      assertEquals(c[1], result.stopReason());
      assertTrue(backend.calledTools.isEmpty());
    }
  }

  @Test
  void refusalReportsItsCategoryAndNoAnswer() {
    ModelTurn turn = new ModelTurn("refusal", "raw", "partial", List.of(), Usage.ZERO, "cyber");
    Result result = agent(new ScriptedModel(turn), new FakeBackend(), Limits.defaults()).run("q");
    assertEquals(StopReason.REFUSED, result.stopReason());
    assertEquals("", result.answer());
    assertTrue(result.detail().contains("cyber"));
  }

  @Test
  void modelErrorEndsTheRunWithTheReason() {
    ScriptedModel model =
        new ScriptedModel(toolTurn(find("1")), new ModelException("API error 500"));
    Result result = agent(model, new FakeBackend(), Limits.defaults()).run("q");
    assertEquals(StopReason.MODEL_ERROR, result.stopReason());
    assertEquals("API error 500", result.detail());
    assertEquals(1, result.toolCalls().size());
  }

  @Test
  void pauseTurnContinuesWithThePausedContentInHistory() {
    ScriptedModel model =
        new ScriptedModel(textTurn("thinking", "pause_turn"), textTurn("finished"));
    Result result = agent(model, new FakeBackend(), Limits.defaults()).run("q");
    assertEquals(StopReason.COMPLETED, result.stopReason());
    assertEquals("finished", result.answer());
    assertTrue(model.requests.get(1).messages().get(1) instanceof Message.Assistant);
  }

  @Test
  void findingsMustCiteCommitsSeenInEarlierToolResults() {
    Named finding =
        named(
            "notes__record_finding",
            Map.of("claim", "c", "commits", List.of("1234567"), "confidence", "low"));
    ScriptedModel model =
        new ScriptedModel(
            toolTurn(finding), toolTurn(find("x")), toolTurn(finding), textTurn("done"));
    Result result =
        agent(model, new FakeBackend(ToolOutput.ok("commit 1234567abcdef")), Limits.defaults())
            .run("q");
    ToolResult refused = toolResults(model.requests.get(1), 2).get(0);
    assertTrue(refused.isError());
    assertTrue(refused.content().contains("1234567"));
    assertEquals(
        List.of(List.of("1234567")), result.findings().stream().map(Finding::commits).toList());
  }

  @Test
  void hashesInFailedToolResultsDoNotCountAsSeen() {
    Named finding =
        named(
            "notes__record_finding",
            Map.of("claim", "c", "commits", List.of("1234567"), "confidence", "low"));
    ScriptedModel model =
        new ScriptedModel(toolTurn(find("x")), toolTurn(finding), textTurn("done"));
    Result result =
        agent(model, new FakeBackend(ToolOutput.error("bad revision 1234567")), Limits.defaults())
            .run("q");
    assertTrue(result.findings().isEmpty());
  }
}
