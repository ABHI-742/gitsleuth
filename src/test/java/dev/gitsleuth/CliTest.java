package dev.gitsleuth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The command line, run as a user would run it. No command here calls Claude. */
@Timeout(120)
class CliTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir static Path sharedDir;
  private static SampleRepo repo;

  private record Run(int exit, String stdout, String stderr) {}

  @BeforeAll
  static void createRepo() throws Exception {
    repo = SampleRepo.create(sharedDir);
  }

  private static Run run(Map<String, String> env, String... args) throws Exception {
    String java = ProcessHandle.current().info().command().orElse("java");
    List<String> command =
        new ArrayList<>(
            List.of(java, "-cp", System.getProperty("java.class.path"), Cli.class.getName()));
    command.addAll(List.of(args));
    Path out = Files.createTempFile(sharedDir, "out", ".txt");
    Path err = Files.createTempFile(sharedDir, "err", ".txt");
    ProcessBuilder builder =
        new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
    builder.environment().keySet().removeIf(name -> name.startsWith("ANTHROPIC_"));
    builder.environment().putAll(env);
    Process process = builder.start();
    assertTrue(process.waitFor(100, TimeUnit.SECONDS), "CLI did not exit");
    return new Run(
        process.exitValue(),
        Files.readString(out, StandardCharsets.UTF_8),
        Files.readString(err, StandardCharsets.UTF_8));
  }

  private static Run run(String... args) throws Exception {
    return run(Map.of(), args);
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").startsWith("Windows");
  }

  private static String repoPath() {
    return repo.path.toString();
  }

  @Test
  void toolsListsEveryDiscoveredTool() throws Exception {
    Run result = run("tools", "--repo", repoPath());
    assertEquals(0, result.exit(), result.stderr());
    assertTrue(result.stdout().contains("code__read_file:"));
    assertTrue(result.stdout().contains("history__blame_lines:"));
    assertTrue(result.stdout().contains("notes__record_finding:"));
  }

  @Test
  void callRunsOneToolThroughTheDispatcher() throws Exception {
    // Java on Windows does not escape quotes in process arguments, so the test does it.
    String arguments = isWindows() ? "{\\\"limit\\\": 1}" : "{\"limit\": 1}";
    Run result = run("call", "--repo", repoPath(), "history__git_log", arguments);
    assertEquals(0, result.exit(), result.stderr());
    assertEquals(
        "Add logo", JSON.readTree(result.stdout()).get("commits").get(0).get("subject").asString());
  }

  @Test
  void callReportsInvalidInputWithAFailingExitCode() throws Exception {
    Run result = run("call", "--repo", repoPath(), "code__read_file", "{}");
    assertEquals(1, result.exit());
    assertTrue(result.stdout().contains("Invalid input"), result.stdout());
    assertTrue(result.stdout().contains("path"), result.stdout());
  }

  @Test
  void verboseWritesJsonLogsToStderrOnly() throws Exception {
    Run result = run("--verbose", "call", "--repo", repoPath(), "code__list_files");
    assertEquals(0, result.exit(), result.stderr());
    JSON.readTree(result.stdout());
    JsonNode toolCall = null;
    for (String line : result.stderr().split("\\R")) {
      if (line.startsWith("{") && line.contains("\"event\":\"tool_call\"")) {
        toolCall = JSON.readTree(line);
      }
    }
    assertTrue(toolCall != null, result.stderr());
    assertEquals("code__list_files", toolCall.get("tool").asString());
    assertEquals(1, toolCall.get("attempts").asInt());
  }

  @Test
  void badJsonArgumentsGetAClearMessage() throws Exception {
    Run result = run("call", "--repo", repoPath(), "code__read_file", "{nope");
    assertEquals(2, result.exit());
    assertTrue(result.stderr().contains("not valid JSON"), result.stderr());
  }

  @Test
  void askWithAnUnreachableApiExitsCleanlyWithoutARealCall() throws Exception {
    // The base URL points at a closed local port, so this cannot spend credits.
    Run result =
        run(
            Map.of(
                "ANTHROPIC_API_KEY",
                "test-key-not-real",
                "ANTHROPIC_BASE_URL",
                "http://127.0.0.1:9"),
            "ask",
            "--repo",
            repoPath(),
            "why?");
    assertEquals(1, result.exit(), result.stdout() + result.stderr());
    assertTrue(result.stdout().contains("Stopped: model_error"), result.stdout());
    assertTrue(result.stdout().contains("could not reach the API"), result.stdout());
  }

  @Test
  void aDirectoryThatIsNotARepositoryGetsAClearMessage(@TempDir Path notARepo) throws Exception {
    Run result = run("tools", "--repo", notARepo.toString());
    assertEquals(2, result.exit());
    assertTrue(result.stderr().contains("could not connect"), result.stderr());
  }
}
