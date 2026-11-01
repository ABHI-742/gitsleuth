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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tools for the repository's contents at any revision. */
public final class CodeServer {
  public static final String NAME = "gitsleuth-code";
  public static final String INSTRUCTIONS =
      "Read-only access to the files of one git repository at any revision.";

  private static final Pattern GREP_ROW = Pattern.compile("^(.*?):(\\d+):(.*)$");

  public record FileList(String ref, List<String> files, int total, boolean truncated) {}

  public record FileSlice(
      String ref,
      String path,
      int startLine,
      int endLine,
      int totalLines,
      String text,
      boolean truncated) {}

  public record SearchMatch(String path, int line, String text) {}

  public record SearchResult(String ref, List<SearchMatch> matches, boolean truncated) {}

  private final GitRepo repo;

  private CodeServer(GitRepo repo) {
    this.repo = repo;
  }

  public static List<SyncToolSpecification> tools(GitRepo repo) {
    CodeServer server = new CodeServer(repo);
    return List.of(
        tool(
            "list_files",
            "List tracked files at a revision, optionally under a directory. Returns up to 1000.",
            schema(
                List.of(),
                props(
                    "ref", string("HEAD"),
                    "path_prefix", string(""),
                    "limit", integer(200))),
            server::listFiles),
        tool(
            "read_file",
            "Read committed lines of a file at a revision. Returns up to 1000 lines per call.",
            schema(
                List.of("path"),
                props(
                    "path", string(),
                    "ref", string("HEAD"),
                    "start_line", integer(1),
                    "max_lines", integer(200))),
            server::readFile),
        tool(
            "search_code",
            "Search tracked text files at a revision. Literal by default; regex=true for a regex.",
            schema(
                List.of("pattern"),
                props(
                    "pattern", string(),
                    "ref", string("HEAD"),
                    "path_glob", string(),
                    "regex", bool(false),
                    "ignore_case", bool(false),
                    "limit", integer(100))),
            server::searchCode));
  }

  private static Map<String, Object> props(Object... pairs) {
    Map<String, Object> properties = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      properties.put((String) pairs[i], pairs[i + 1]);
    }
    return properties;
  }

  private FileList listFiles(Args args) throws GitException {
    String ref = GitRepo.validateRef(args.string("ref", "HEAD"));
    int limit = clamp(args.integer("limit", 200), 1, 1000);
    List<String> command =
        new ArrayList<>(List.of("ls-tree", "-r", "--name-only", "--end-of-options", ref));
    String prefix = args.string("path_prefix", "");
    if (!prefix.isEmpty()) {
      command.addAll(List.of("--", GitRepo.validateRepoPath(prefix)));
    }
    GitOutput output = repo.run(command);
    List<String> files = output.stdout().lines().toList();
    return new FileList(
        ref,
        files.subList(0, Math.min(limit, files.size())),
        files.size(),
        output.truncated() || files.size() > limit);
  }

  private FileSlice readFile(Args args) throws GitException, ToolFailure {
    String ref = GitRepo.validateRef(args.string("ref", "HEAD"));
    String path = GitRepo.validateRepoPath(args.required("path"));
    int startLine = Math.max(1, args.integer("start_line", 1));
    int maxLines = clamp(args.integer("max_lines", 200), 1, 1000);
    GitOutput output;
    try {
      output = repo.run(List.of("cat-file", "blob", ref + ":" + path));
    } catch (GitException e) {
      // git's wording would reveal that an uncommitted file exists on disk.
      if (e.getMessage().contains("exists on disk")) {
        throw new ToolFailure(path + " does not exist at " + ref);
      }
      throw e;
    }
    if (output.stdout().indexOf('\0') >= 0) {
      throw new ToolFailure(path + " is a binary file at " + ref);
    }
    List<String> lines = output.stdout().lines().toList();
    int from = Math.min(startLine - 1, lines.size());
    int to = Math.min(from + maxLines, lines.size());
    List<String> selected = lines.subList(from, to);
    return new FileSlice(
        ref,
        path,
        startLine,
        selected.isEmpty() ? startLine : startLine + selected.size() - 1,
        lines.size(),
        String.join("\n", selected),
        output.truncated() || startLine - 1 + maxLines < lines.size());
  }

  private SearchResult searchCode(Args args) throws GitException, ToolFailure {
    String pattern = args.required("pattern");
    if (pattern.isEmpty()) {
      throw new ToolFailure("pattern must not be empty");
    }
    String ref = GitRepo.validateRef(args.string("ref", "HEAD"));
    int limit = clamp(args.integer("limit", 100), 1, 500);
    List<String> command =
        new ArrayList<>(List.of("grep", "-n", "-I", "--no-color", "--full-name", "--no-textconv"));
    command.add(args.bool("regex", false) ? "-E" : "-F");
    if (args.bool("ignore_case", false)) {
      command.add("-i");
    }
    command.addAll(List.of("-e", pattern, "--end-of-options", ref));
    String glob = args.string("path_glob", null);
    if (glob != null && !glob.isEmpty()) {
      command.addAll(List.of("--", GitRepo.validateRepoPath(glob)));
    }
    // git grep exits 1 when nothing matches, which is an answer rather than a failure.
    GitOutput output = repo.run(command, Set.of(0, 1));

    List<SearchMatch> matches = new ArrayList<>();
    String prefix = ref + ":";
    output
        .stdout()
        .lines()
        .forEach(
            row -> {
              String body = row.startsWith(prefix) ? row.substring(prefix.length()) : row;
              Matcher parsed = GREP_ROW.matcher(body);
              if (parsed.matches()) {
                matches.add(
                    new SearchMatch(
                        parsed.group(1), Integer.parseInt(parsed.group(2)), parsed.group(3)));
              }
            });
    return new SearchResult(
        ref,
        matches.subList(0, Math.min(limit, matches.size())),
        output.truncated() || matches.size() > limit);
  }
}
