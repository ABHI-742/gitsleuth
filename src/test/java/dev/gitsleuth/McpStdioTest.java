package dev.gitsleuth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gitsleuth.McpConnection.McpConnectException;
import dev.gitsleuth.tools.ToolDispatcher.DispatchOutcome;
import dev.gitsleuth.tools.ToolDispatcher.ErrorKind;
import dev.gitsleuth.tools.ToolDispatcher.ToolCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The real client talking to the real servers, as subprocesses, over the stdio transport. */
@Timeout(120)
class McpStdioTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir static Path sharedDir;
  private static SampleRepo repo;
  private static Toolbox box;

  @BeforeAll
  static void open() throws Exception {
    repo = SampleRepo.create(sharedDir);
    box = Toolbox.openLocal(repo.path);
  }

  @AfterAll
  static void close() {
    box.close();
  }

  private static DispatchOutcome call(String tool, Map<String, Object> arguments) throws Exception {
    return box.dispatcher().dispatch(new ToolCall("t", tool, arguments));
  }

  private static JsonNode json(String tool, Map<String, Object> arguments) throws Exception {
    DispatchOutcome outcome = call(tool, arguments);
    assertFalse(outcome.isError(), outcome.text());
    return JSON.readTree(outcome.text());
  }

  private static List<String> shas(JsonNode commits) {
    List<String> shas = new ArrayList<>();
    commits.forEach(commit -> shas.add(commit.get("sha").asString()));
    return shas;
  }

  @Test
  void discoversToolsFromBothServersWithTheirSchemas() {
    assertEquals(
        List.of(
            "code__list_files",
            "code__read_file",
            "code__search_code",
            "history__blame_lines",
            "history__find_string_changes",
            "history__git_log",
            "history__show_commit",
            "notes__record_finding"),
        box.registry().names());
    var readFile = box.registry().get("code__read_file");
    assertEquals(List.of("path"), readFile.inputSchema().get("required"));
    assertTrue(readFile.retrySafe());
    assertFalse(box.registry().get("notes__record_finding").retrySafe());
  }

  @Test
  void everyToolReturnsRealDataFromTheRepository() throws Exception {
    Map<String, String> s = repo.shas;

    List<String> files = new ArrayList<>();
    json("code__list_files", Map.of()).get("files").forEach(f -> files.add(f.asString()));
    assertEquals(List.of("README.md", "billing/invoice.py", "logo.bin"), files);

    JsonNode read =
        json("code__read_file", Map.of("path", "billing/invoice.py", "ref", s.get("regression")));
    assertTrue(read.get("text").asString().contains("ROUNDING - 1"));
    assertEquals(5, read.get("total_lines").asInt());

    JsonNode found = json("code__search_code", Map.of("pattern", "ROUNDING"));
    assertEquals(2, found.get("matches").size());
    assertEquals("billing/invoice.py", found.get("matches").get(0).get("path").asString());
    assertEquals(1, found.get("matches").get(0).get("line").asInt());
    assertEquals(5, found.get("matches").get(1).get("line").asInt());

    assertEquals(
        List.of(s.get("fix"), s.get("regression"), s.get("initial")),
        shas(json("history__git_log", Map.of("path", "billing/invoice.py")).get("commits")));
    assertEquals(
        List.of(s.get("fix")),
        shas(json("history__git_log", Map.of("message_contains", "off-by-one")).get("commits")));

    JsonNode commit = json("history__show_commit", Map.of("sha", s.get("regression")));
    assertEquals("Round invoice totals for display", commit.get("message").asString());
    assertEquals("billing/invoice.py", commit.get("changed_files").get(0).asString());
    assertTrue(
        commit.get("patch").asString().contains("+    return round(sum(items), ROUNDING - 1)"));

    JsonNode blame =
        json(
            "history__blame_lines",
            Map.of("path", "billing/invoice.py", "start_line", 1, "end_line", 5));
    assertEquals(s.get("regression"), blame.get("lines").get(0).get("sha").asString());
    assertEquals(s.get("fix"), blame.get("lines").get(4).get("sha").asString());

    assertEquals(
        List.of(s.get("fix"), s.get("regression")),
        shas(json("history__find_string_changes", Map.of("text", "ROUNDING - 1")).get("commits")));
  }

  @Test
  void searchWithNoMatchesIsAnEmptyAnswerNotAnError() throws Exception {
    assertEquals(
        0, json("code__search_code", Map.of("pattern", "zzz-not-here")).get("matches").size());
  }

  private static void assertToolError(String tool, Map<String, Object> arguments, String expected)
      throws Exception {
    DispatchOutcome outcome = call(tool, arguments);
    assertTrue(outcome.isError(), tool + " " + arguments + ": " + outcome.text());
    assertEquals(ErrorKind.TOOL_ERROR, outcome.errorKind(), outcome.text());
    assertTrue(outcome.text().contains(expected), outcome.text());
    assertEquals(1, outcome.attempts());
    assertFalse(outcome.text().contains("do-not-read"));
  }

  @Test
  void unsafeOrImpossibleRequestsComeBackAsToolErrors() throws Exception {
    assertToolError("code__read_file", Map.of("path", ".env"), "does not exist");
    assertToolError(
        "history__blame_lines",
        Map.of("path", ".env", "start_line", 1, "end_line", 1),
        "no such path");
    assertToolError("code__read_file", Map.of("path", "../../etc/passwd"), "invalid path");
    assertToolError("code__read_file", Map.of("path", "/etc/passwd"), "invalid path");
    assertToolError("code__read_file", Map.of("path", "C:/Windows/win.ini"), "invalid path");
    assertToolError("code__read_file", Map.of("path", "logo.bin"), "binary file");
    assertToolError(
        "code__read_file", Map.of("path", "README.md", "ref", "--output=/tmp/x"), "invalid ref");
    assertToolError("history__git_log", Map.of("ref", "--all"), "invalid ref");
    assertToolError("history__show_commit", Map.of("sha", "-p"), "invalid ref");
    assertToolError("history__show_commit", Map.of("sha", "deadbeef"), "failed");
    assertToolError(
        "code__search_code", Map.of("pattern", "x", "path_glob", "../.."), "invalid path");
  }

  @Test
  void flagLikeSearchPatternIsTreatedAsText() throws Exception {
    assertEquals(
        0,
        json("code__search_code", Map.of("pattern", "--open-files-in-pager=calc"))
            .get("matches")
            .size());
  }

  @Test
  void uncommittedFilesAreInvisibleToSearch() throws Exception {
    assertEquals(
        0, json("code__search_code", Map.of("pattern", "do-not-read")).get("matches").size());
  }

  @Test
  void repositoryConfigCannotRunADiffProgram(@TempDir Path dir) throws Exception {
    SampleRepo trapped = SampleRepo.create(dir);
    Path marker = dir.resolve("pwned");
    Path script = dir.resolve("evil.sh");
    // Git for Windows runs this through its own sh, so the same script works on both platforms.
    Files.writeString(
        script, "#!/bin/sh\necho ran > '" + marker.toString().replace('\\', '/') + "'\n");
    String command = "sh '" + script.toString().replace('\\', '/') + "'";
    Path config = trapped.path.resolve(".git/config");
    Files.writeString(
        config,
        Files.readString(config)
            + "[diff]\n\texternal = \""
            + command
            + "\"\n[diff \"evil\"]\n\ttextconv = \""
            + command
            + "\"\n");
    Files.createDirectories(trapped.path.resolve(".git/info"));
    Files.writeString(trapped.path.resolve(".git/info/attributes"), "* diff=evil\n");

    // Control: plain git really does run the configured program in this repository.
    trapped.git("2026-07-01T00:00:00+00:00", "show", trapped.shas.get("fix"));
    assertTrue(Files.exists(marker), "the trap should fire under plain git");
    Files.delete(marker);

    try (Toolbox trappedBox = Toolbox.openLocal(trapped.path)) {
      var dispatcher = trappedBox.dispatcher();
      DispatchOutcome shown =
          dispatcher.dispatch(
              new ToolCall("t", "history__show_commit", Map.of("sha", trapped.shas.get("fix"))));
      assertFalse(shown.isError(), shown.text());
      dispatcher.dispatch(
          new ToolCall("t", "history__find_string_changes", Map.of("text", "ROUNDING - 1")));
      dispatcher.dispatch(
          new ToolCall(
              "t",
              "history__blame_lines",
              Map.of("path", "billing/invoice.py", "start_line", 1, "end_line", 2)));
      dispatcher.dispatch(new ToolCall("t", "code__search_code", Map.of("pattern", "ROUNDING")));
    }
    assertFalse(Files.exists(marker), "the trap must not fire through the tools");
  }

  @Test
  void serverRefusesToStartOutsideAGitRepository(@TempDir Path notARepo) {
    assertThrows(
        McpConnectException.class,
        () ->
            McpConnection.open(
                "code", Toolbox.stdioTarget("code", notARepo), Duration.ofSeconds(10)));
  }
}
