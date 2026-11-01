package dev.gitsleuth;

import dev.gitsleuth.tools.ToolBackend;
import dev.gitsleuth.tools.ToolRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local (in-process) tool: the agent's notebook of findings.
 *
 * <p>A finding must cite the commits behind it, and a cited commit must have appeared in an earlier
 * MCP tool result in this run. The check stops an invented hash from reaching the report. It does
 * not prove that the claim follows from the commit.
 */
public final class Notebook implements ToolBackend {
  public static final String SERVER = "notes";
  private static final Pattern HEX = Pattern.compile("\\b[0-9a-f]{7,40}\\b");

  private static final Map<String, Object> RECORD_FINDING_SCHEMA =
      Map.of(
          "type",
          "object",
          "properties",
          Map.of(
              "claim",
              Map.of(
                  "type", "string", "minLength", 1, "description", "One specific factual claim."),
              "commits",
              Map.of(
                  "type",
                  "array",
                  "items",
                  Map.of("type", "string", "pattern", "^[0-9a-f]{7,40}$"),
                  "minItems",
                  1,
                  "description",
                  "Commit hashes, seen in earlier tool results, that support the claim."),
              "confidence",
              Map.of("type", "string", "enum", List.of("high", "medium", "low"))),
          "required",
          List.of("claim", "commits", "confidence"),
          "additionalProperties",
          false);

  public record Finding(String claim, List<String> commits, String confidence) {}

  private final List<Finding> findings = new ArrayList<>();
  private final Set<String> seenHashes = new HashSet<>();

  /** Remembers hash-like tokens from an MCP tool result. */
  public synchronized void observe(String toolOutput) {
    Matcher matcher = HEX.matcher(toolOutput);
    while (matcher.find()) {
      seenHashes.add(matcher.group());
    }
  }

  private boolean wasSeen(String commit) {
    return seenHashes.stream().anyMatch(seen -> seen.startsWith(commit) || commit.startsWith(seen));
  }

  public void register(ToolRegistry registry) {
    registry.add(
        SERVER,
        "record_finding",
        "Record one evidence-backed finding for the final report. Every commit you cite must have"
            + " appeared in an earlier tool result.",
        RECORD_FINDING_SCHEMA,
        // Retrying would record the finding twice.
        false,
        this,
        true);
  }

  @Override
  public synchronized ToolOutput call(
      String tool, Map<String, Object> arguments, Duration timeout) {
    if (!"record_finding".equals(tool)) {
      return ToolOutput.error("unknown local tool '" + tool + "'");
    }
    List<String> commits =
        ((List<?>) arguments.get("commits")).stream().map(String::valueOf).toList();
    List<String> unseen = commits.stream().filter(commit -> !wasSeen(commit)).toList();
    if (!unseen.isEmpty()) {
      return ToolOutput.error(
          "Not recorded: "
              + String.join(", ", unseen)
              + " did not appear in any earlier tool result. Look the commit up first, or cite one"
              + " you have seen.");
    }
    findings.add(
        new Finding(
            (String) arguments.get("claim"), commits, (String) arguments.get("confidence")));
    return ToolOutput.ok("Recorded finding #" + findings.size() + ".");
  }

  @Override
  public void recover(ConnectionLostException lost) throws ConnectionLostException {
    throw lost;
  }

  public synchronized List<Finding> findings() {
    return List.copyOf(findings);
  }
}
