package dev.gitsleuth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gitsleuth.servers.GitRepo;
import dev.gitsleuth.servers.GitRepo.GitException;
import dev.gitsleuth.servers.GitRepo.GitOutput;
import dev.gitsleuth.tools.ToolDispatcher.DispatchOutcome;
import dev.gitsleuth.tools.ToolDispatcher.ToolCall;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Server behaviour in detail, through real server processes. */
@Timeout(120)
class ServerToolsTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String DATE = "2026-06-01T00:00:00+00:00";

  @TempDir Path dir;

  private static JsonNode ok(Toolbox box, String tool, Map<String, Object> arguments)
      throws Exception {
    DispatchOutcome outcome = box.dispatcher().dispatch(new ToolCall("t", tool, arguments));
    assertFalse(outcome.isError(), outcome.text());
    return JSON.readTree(outcome.text());
  }

  private static DispatchOutcome raw(Toolbox box, String tool, Map<String, Object> arguments)
      throws Exception {
    return box.dispatcher().dispatch(new ToolCall("t", tool, arguments));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"HEAD", "main", "v1.2.3", "HEAD~2", "HEAD^", "feature/x", "abc1234", "main@{1}"})
  void validRefsPass(String ref) throws Exception {
    assertEquals(ref, GitRepo.validateRef(ref));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "-p", "--all", "a..b", "a b", "a;b", "$(x)", "a:b", "\n"})
  void dangerousRefsAreRefused(String ref) {
    assertThrows(GitException.class, () -> GitRepo.validateRef(ref));
  }

  @ParameterizedTest
  @CsvSource({"a/b.py,a/b.py", "./a.py,a.py", "a\\b.py,a/b.py", "dir/,dir"})
  void validPathsAreNormalized(String path, String expected) throws Exception {
    assertEquals(expected, GitRepo.validateRepoPath(path));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/etc/passwd", "../x", "a/../../x", "C:/x", "-x", "a\0b"})
  void pathsOutsideTheRepositoryAreRefused(String path) {
    assertThrows(GitException.class, () -> GitRepo.validateRepoPath(path));
  }

  @Test
  void repoMustBeARepositoryRoot() {
    assertThrows(IllegalArgumentException.class, () -> new GitRepo(dir));
  }

  @Test
  void outputCapStopsGitAndMarksTheResultTruncated() throws Exception {
    SampleRepo sample = SampleRepo.create(dir);
    GitRepo repo = new GitRepo(sample.path, GitRepo.DEFAULT_TIMEOUT, 50);
    GitOutput output = repo.run(List.of("log", "--format=%H%n%H%n%H"));
    assertTrue(output.truncated());
    assertEquals(50, output.stdout().length());
  }

  @Test
  void slowGitCommandsTimeOut() throws Exception {
    SampleRepo sample = SampleRepo.create(dir);
    GitRepo repo = new GitRepo(sample.path, Duration.ofNanos(1), GitRepo.DEFAULT_MAX_OUTPUT_BYTES);
    GitException error = assertThrows(GitException.class, () -> repo.run(List.of("log")));
    assertTrue(error.getMessage().contains("timed out"), error.getMessage());
  }

  @Test
  void failedGitCommandsReportTheLastErrorLine() throws Exception {
    SampleRepo sample = SampleRepo.create(dir);
    GitRepo repo = new GitRepo(sample.path);
    GitException error =
        assertThrows(GitException.class, () -> repo.run(List.of("show", "deadbeefdeadbeef")));
    assertTrue(error.getMessage().contains("git show failed"), error.getMessage());
  }

  @Test
  void toolsBehaveAsDocumentedAcrossTheirOptions() throws Exception {
    SampleRepo sample = SampleRepo.create(dir);
    StringBuilder longFile = new StringBuilder();
    for (int n = 1; n <= 10; n++) {
      longFile.append("line ").append(n).append('\n');
    }
    sample.commit("long.txt", longFile.toString(), "Add long file", DATE);
    // Windows cannot create this name on disk, so it goes straight into the index.
    sample.commitBlob(
        "100644", "odd name:v2.txt", "needle one\nNEEDLE two\n", "Add odd file", DATE);
    // A symlink is stored as a blob holding its target.
    sample.commitBlob(
        "120000", "link-to-env", "../.env", "Add symlink", "2026-06-02T00:00:00+00:00");
    String oddFileCommit = sample.git(DATE, "rev-parse", "HEAD~1");

    try (Toolbox box = Toolbox.openLocal(sample.path)) {
      // read_file pages through lines.
      JsonNode first = ok(box, "code__read_file", Map.of("path", "long.txt", "max_lines", 4));
      assertTrue(first.get("text").asString().endsWith("line 4"));
      assertTrue(first.get("truncated").asBoolean());
      assertEquals(10, first.get("total_lines").asInt());
      JsonNode last =
          ok(box, "code__read_file", Map.of("path", "long.txt", "start_line", 9, "max_lines", 4));
      assertEquals("line 9\nline 10", last.get("text").asString());
      assertEquals(10, last.get("end_line").asInt());
      assertFalse(last.get("truncated").asBoolean());

      // Limits mark results truncated.
      JsonNode listed = ok(box, "code__list_files", Map.of("limit", 1));
      assertEquals(1, listed.get("files").size());
      assertTrue(listed.get("total").asInt() > 1);
      assertTrue(listed.get("truncated").asBoolean());
      JsonNode log = ok(box, "history__git_log", Map.of("limit", 2));
      assertEquals(2, log.get("commits").size());
      assertTrue(log.get("truncated").asBoolean());

      // Search handles regex, case, and paths with colons and spaces.
      JsonNode literal = ok(box, "code__search_code", Map.of("pattern", "needle"));
      assertEquals("odd name:v2.txt", literal.get("matches").get(0).get("path").asString());
      assertEquals(1, literal.get("matches").size());
      assertEquals(
          2,
          ok(box, "code__search_code", Map.of("pattern", "needle", "ignore_case", true))
              .get("matches")
              .size());
      assertEquals(
          2,
          ok(box, "code__search_code", Map.of("pattern", "^NEE.*two$", "regex", true))
              .get("matches")
              .get(0)
              .get("line")
              .asInt());
      assertEquals(
          0,
          ok(box, "code__search_code", Map.of("pattern", "ROUNDING", "path_glob", "README.md"))
              .get("matches")
              .size());
      assertEquals(
          "odd name:v2.txt",
          ok(box, "history__show_commit", Map.of("sha", oddFileCommit, "include_patch", false))
              .get("changed_files")
              .get(0)
              .asString());

      // Symlinks are returned as their target text and not followed.
      assertEquals(
          "../.env",
          ok(box, "code__read_file", Map.of("path", "link-to-env")).get("text").asString());

      // git_log filters by date and refuses bad dates.
      assertEquals(
          sample.shas.get("binary"),
          ok(box, "history__git_log", Map.of("since", "2026-04-15", "path", "logo.bin"))
              .get("commits")
              .get(0)
              .get("sha")
              .asString());
      assertTrue(raw(box, "history__git_log", Map.of("since", "$(whoami)")).isError());

      // show_commit only accepts commits, and can scope and limit the patch.
      DispatchOutcome tree = raw(box, "history__show_commit", Map.of("sha", "HEAD^{tree}"));
      assertTrue(tree.isError());
      assertTrue(tree.text().contains("git show failed"), tree.text());
      JsonNode limited =
          ok(
              box,
              "history__show_commit",
              Map.of("sha", sample.shas.get("fix"), "max_patch_lines", 2));
      assertTrue(limited.get("patch_truncated").asBoolean());
      assertEquals(2, limited.get("patch").asString().lines().count());
      assertEquals(
          "",
          ok(
                  box,
                  "history__show_commit",
                  Map.of("sha", sample.shas.get("fix"), "path", "README.md"))
              .get("patch")
              .asString());

      // Blame rejects backwards ranges and reports ISO dates.
      assertTrue(
          raw(
                  box,
                  "history__blame_lines",
                  Map.of("path", "README.md", "start_line", 3, "end_line", 1))
              .isError());
      JsonNode blame =
          ok(
              box,
              "history__blame_lines",
              Map.of("path", "README.md", "start_line", 1, "end_line", 1));
      assertTrue(
          blame.get("lines").get(0).get("date").asString().startsWith("2026-03-01T00:00:00"));
      assertEquals("Add readme", blame.get("lines").get(0).get("summary").asString());

      // Input the schema allows but the tool refuses is a tool error.
      assertTrue(raw(box, "code__search_code", Map.of("pattern", "")).isError());
      assertTrue(raw(box, "history__find_string_changes", Map.of("text", "")).isError());

      // Every server tool declares itself read-only.
      for (String name : box.registry().names()) {
        if (!name.startsWith("notes__")) {
          assertTrue(box.registry().get(name).retrySafe(), name);
          assertFalse(box.registry().get(name).description().isEmpty(), name);
        }
      }
    }
  }
}
