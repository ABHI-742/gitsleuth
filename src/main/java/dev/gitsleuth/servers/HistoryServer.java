package dev.gitsleuth.servers;

import static dev.gitsleuth.servers.ToolSpec.bool;
import static dev.gitsleuth.servers.ToolSpec.clamp;
import static dev.gitsleuth.servers.ToolSpec.integer;
import static dev.gitsleuth.servers.ToolSpec.schema;
import static dev.gitsleuth.servers.ToolSpec.string;
import static dev.gitsleuth.servers.ToolSpec.tool;

import dev.gitsleuth.servers.GitRepo.GitException;
import dev.gitsleuth.servers.GitRepo.GitOutput;
import dev.gitsleuth.servers.ToolSpec.Args;
import dev.gitsleuth.servers.ToolSpec.ToolFailure;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Tools for the repository's history: commits, diffs, blame and pickaxe search. */
public final class HistoryServer {
  public static final String NAME = "gitsleuth-history";
  public static final String INSTRUCTIONS =
      "Read-only access to the commit history of one git repository.";

  private static final String FIELD = "\u001f";
  private static final String RECORD = "\u001e";
  private static final String LOG_FORMAT =
      "--format=" + RECORD + "%H" + FIELD + "%an" + FIELD + "%aI" + FIELD + "%s";
  private static final Pattern DATE = Pattern.compile("^[0-9A-Za-z:.+ -]{1,40}$");

  public record Commit(String sha, String author, String date, String subject) {}

  public record CommitList(List<Commit> commits, boolean truncated) {}

  public record CommitDetail(
      String sha,
      String author,
      String date,
      String message,
      List<String> changedFiles,
      String patch,
      boolean patchTruncated) {}

  public record BlameLine(
      String sha, String author, String date, String summary, int line, String text) {}

  public record BlameResult(String ref, String path, List<BlameLine> lines) {}

  private final GitRepo repo;

  private HistoryServer(GitRepo repo) {
    this.repo = repo;
  }

  public static List<SyncToolSpecification> tools(GitRepo repo) {
    HistoryServer server = new HistoryServer(repo);
    return List.of(
        tool(
            "git_log",
            "List commits newest first, filtered by path, message text or a since date.",
            schema(
                List.of(),
                props(
                    "ref", string("HEAD"),
                    "path", string(),
                    "message_contains", string(),
                    "since", string(),
                    "limit", integer(30))),
            server::gitLog),
        tool(
            "show_commit",
            "Show a commit's message and changed files, and optionally its patch.",
            schema(
                List.of("sha"),
                props(
                    "sha", string(),
                    "include_patch", bool(true),
                    "path", string(),
                    "max_patch_lines", integer(400))),
            server::showCommit),
        tool(
            "blame_lines",
            "Show which commit last changed each line in a range. Covers at most 200 lines.",
            schema(
                List.of("path", "start_line", "end_line"),
                props(
                    "path", string(),
                    "start_line", integer(),
                    "end_line", integer(),
                    "ref", string("HEAD"))),
            server::blameLines),
        tool(
            "find_string_changes",
            "Find commits that added or removed a literal string (git log -S), newest first.",
            schema(
                List.of("text"),
                props(
                    "text", string(),
                    "ref", string("HEAD"),
                    "path", string(),
                    "limit", integer(20))),
            server::findStringChanges));
  }

  private static Map<String, Object> props(Object... pairs) {
    Map<String, Object> properties = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      properties.put((String) pairs[i], pairs[i + 1]);
    }
    return properties;
  }

  private static List<Commit> parseLog(String stdout) {
    List<Commit> commits = new ArrayList<>();
    for (String record : stdout.split(RECORD)) {
      String[] fields = record.strip().split(FIELD, -1);
      if (fields.length == 4) {
        commits.add(new Commit(fields[0], fields[1], fields[2], fields[3]));
      }
    }
    return commits;
  }

  /** Parses {@code git blame --line-porcelain}, where each line repeats its commit's metadata. */
  private static List<BlameLine> parseBlame(String stdout) {
    List<BlameLine> lines = new ArrayList<>();
    Map<String, String> current = new HashMap<>();
    for (String row : stdout.split("\n", -1)) {
      if (row.startsWith("\t")) {
        lines.add(
            new BlameLine(
                current.get("sha"),
                current.getOrDefault("author", ""),
                Instant.ofEpochSecond(Long.parseLong(current.get("author-time"))).toString(),
                current.getOrDefault("summary", ""),
                Integer.parseInt(current.get("line")),
                row.substring(1)));
        current = new HashMap<>();
      } else if (current.isEmpty()) {
        String[] parts = row.split(" ");
        if (parts.length >= 3) {
          current.put("sha", parts[0]);
          current.put("line", parts[2]);
        }
      } else {
        int space = row.indexOf(' ');
        if (space > 0) {
          current.put(row.substring(0, space), row.substring(space + 1));
        }
      }
    }
    return lines;
  }

  private CommitList toCommitList(GitOutput output, int limit) {
    List<Commit> commits = parseLog(output.stdout());
    return new CommitList(
        commits.subList(0, Math.min(limit, commits.size())),
        output.truncated() || commits.size() > limit);
  }

  private CommitList gitLog(Args args) throws GitException {
    String ref = GitRepo.validateRef(args.string("ref", "HEAD"));
    int limit = clamp(args.integer("limit", 30), 1, 200);
    List<String> command =
        new ArrayList<>(
            List.of("log", "--max-count=" + (limit + 1), LOG_FORMAT, "--no-show-signature"));
    String contains = args.string("message_contains", null);
    if (contains != null && !contains.isEmpty()) {
      command.addAll(List.of("--fixed-strings", "--grep=" + contains));
    }
    String since = args.string("since", null);
    if (since != null && !since.isEmpty()) {
      if (!DATE.matcher(since).matches()) {
        throw new GitException("invalid since value '" + since + "': expected a date");
      }
      command.add("--since=" + since);
    }
    command.addAll(List.of("--end-of-options", ref));
    String path = args.string("path", null);
    if (path != null && !path.isEmpty()) {
      command.addAll(List.of("--", GitRepo.validateRepoPath(path)));
    }
    return toCommitList(repo.run(command), limit);
  }

  private CommitDetail showCommit(Args args) throws GitException, ToolFailure {
    String sha = GitRepo.validateRef(args.required("sha"));
    String commit = sha + "^{commit}";
    int maxPatchLines = clamp(args.integer("max_patch_lines", 400), 1, 2000);
    GitOutput header =
        repo.run(
            List.of(
                "show",
                "--no-patch",
                "--no-show-signature",
                "--format=%H" + FIELD + "%an" + FIELD + "%aI" + FIELD + "%B",
                "--end-of-options",
                commit));
    GitOutput names =
        repo.run(
            List.of(
                "show",
                "--name-only",
                "--no-show-signature",
                "--format=",
                "--end-of-options",
                commit));

    String patch = null;
    boolean patchTruncated = false;
    if (args.bool("include_patch", true)) {
      List<String> command =
          new ArrayList<>(
              List.of(
                  "show",
                  "--patch",
                  "--no-ext-diff",
                  "--no-textconv",
                  "--no-show-signature",
                  "--format=",
                  "--end-of-options",
                  commit));
      String path = args.string("path", null);
      if (path != null && !path.isEmpty()) {
        command.addAll(List.of("--", GitRepo.validateRepoPath(path)));
      }
      GitOutput output = repo.run(command);
      List<String> lines = output.stdout().lines().toList();
      patch = String.join("\n", lines.subList(0, Math.min(maxPatchLines, lines.size())));
      patchTruncated = output.truncated() || lines.size() > maxPatchLines;
    }

    String[] fields = header.stdout().split(FIELD, 4);
    return new CommitDetail(
        fields[0].strip(),
        fields[1],
        fields[2],
        fields[3].strip(),
        names.stdout().lines().toList(),
        patch,
        patchTruncated);
  }

  private BlameResult blameLines(Args args) throws GitException, ToolFailure {
    int startLine = args.requiredInteger("start_line");
    int endLine = args.requiredInteger("end_line");
    if (endLine < startLine || startLine < 1) {
      throw new ToolFailure("expected 1 <= start_line <= end_line");
    }
    String ref = GitRepo.validateRef(args.string("ref", "HEAD"));
    String path = GitRepo.validateRepoPath(args.required("path"));
    int cappedEnd = Math.min(endLine, startLine + 199);
    GitOutput output =
        repo.run(
            List.of(
                "blame",
                "--line-porcelain",
                "--no-textconv",
                "-L" + startLine + "," + cappedEnd,
                // git blame rejects --end-of-options; validateRef refused a leading "-".
                ref,
                "--",
                path));
    return new BlameResult(ref, path, parseBlame(output.stdout()));
  }

  private CommitList findStringChanges(Args args) throws GitException, ToolFailure {
    String text = args.required("text");
    if (text.isEmpty()) {
      throw new ToolFailure("text must not be empty");
    }
    String ref = GitRepo.validateRef(args.string("ref", "HEAD"));
    int limit = clamp(args.integer("limit", 20), 1, 100);
    List<String> command =
        new ArrayList<>(
            List.of(
                "log",
                "--max-count=" + (limit + 1),
                LOG_FORMAT,
                "--no-show-signature",
                "--no-ext-diff",
                "--no-textconv",
                "-S" + text,
                "--end-of-options",
                ref));
    String path = args.string("path", null);
    if (path != null && !path.isEmpty()) {
      command.addAll(List.of("--", GitRepo.validateRepoPath(path)));
    }
    return toCommitList(repo.run(command), limit);
  }
}
