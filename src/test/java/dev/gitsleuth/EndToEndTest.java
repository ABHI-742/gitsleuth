package dev.gitsleuth;

import static dev.gitsleuth.Fakes.named;
import static dev.gitsleuth.Fakes.textTurn;
import static dev.gitsleuth.Fakes.toolTurn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gitsleuth.Agent.Limits;
import dev.gitsleuth.Agent.Result;
import dev.gitsleuth.Agent.StopReason;
import dev.gitsleuth.Agent.ToolCallRecord;
import dev.gitsleuth.Fakes.ScriptedModel;
import dev.gitsleuth.Notebook.Finding;
import dev.gitsleuth.model.ModelClient.Message;
import dev.gitsleuth.model.ModelClient.ModelTurn;
import dev.gitsleuth.model.ModelClient.ToolResult;
import dev.gitsleuth.tools.ToolRegistry.ToolDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One whole agent run: scripted model, real dispatcher, real client, real servers, real git. */
@Timeout(120)
class EndToEndTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static List<ToolResult> lastResults(List<Message> messages) {
    return ((Message.ToolResults) messages.get(messages.size() - 1)).results();
  }

  @Test
  void agentTracesARegressionThroughRealMcpServers(@TempDir Path dir) throws Exception {
    SampleRepo repo = SampleRepo.create(dir);

    // Decide from what the servers actually returned, as a real model would have to.
    Function<List<Message>, ModelTurn> recordFinding =
        messages -> {
          JsonNode commits = JSON.readTree(lastResults(messages).get(1).content()).get("commits");
          String introducing = commits.get(commits.size() - 1).get("sha").asString();
          return toolTurn(
              named(
                  "notes__record_finding",
                  Map.of(
                      "claim",
                      "The off-by-one rounding arrived in this commit",
                      "commits",
                      List.of(introducing),
                      "confidence",
                      "high")),
              named("history__show_commit", Map.of("sha", introducing, "include_patch", false)));
        };
    ScriptedModel model =
        new ScriptedModel(
            toolTurn(
                named("code__read_file", Map.of("path", "billing/invoice.py")),
                named("history__find_string_changes", Map.of("text", "ROUNDING - 1"))),
            recordFinding,
            textTurn("The bug came from the display rounding commit."));

    Result result;
    try (Toolbox box = Toolbox.openLocal(repo.path)) {
      Agent agent = new Agent(model, box.dispatcher(), box.notebook(), Limits.defaults());
      result = agent.run("When did invoice rounding break?");
    }

    assertEquals(StopReason.COMPLETED, result.stopReason());
    assertEquals(3, result.steps());
    assertEquals(
        List.of(
            "code__read_file",
            "history__find_string_changes",
            "notes__record_finding",
            "history__show_commit"),
        result.toolCalls().stream().map(ToolCallRecord::name).toList());
    assertTrue(result.toolCalls().stream().noneMatch(ToolCallRecord::isError), result.toString());
    assertEquals(
        List.of(List.of(repo.shas.get("regression"))),
        result.findings().stream().map(Finding::commits).toList());

    // The model saw the discovered tools and the commit details from the real server.
    List<String> offered =
        model.requests.get(0).tools().stream().map(ToolDefinition::name).toList();
    assertTrue(
        offered.containsAll(
            List.of("code__read_file", "history__blame_lines", "notes__record_finding")));
    JsonNode detail = JSON.readTree(lastResults(model.requests.get(2).messages()).get(1).content());
    assertEquals("Round invoice totals for display", detail.get("message").asString());
  }
}
