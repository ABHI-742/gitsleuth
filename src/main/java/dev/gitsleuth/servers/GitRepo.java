package dev.gitsleuth.servers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs read-only git commands against one repository.
 *
 * <p>Every tool goes through this class, which is where the servers' safety rules live:
 *
 * <ul>
 *   <li>Tools read committed objects, never the filesystem, so untracked files such as {@code .env}
 *       are out of reach and a symlink cannot lead outside the repository.
 *   <li>Refs and paths from the model are validated so they cannot start with {@code -}. They and
 *       search patterns are also passed where git cannot read them as flags: after {@code -e},
 *       {@code --end-of-options} or {@code --}.
 *   <li>Repository config can name programs for git to run. Those settings are overridden on the
 *       command line, and the commands that could use them pass {@code --no-ext-diff} and {@code
 *       --no-textconv}.
 *   <li>Every command has a timeout and an output cap, and is killed when it goes over either.
 * </ul>
 */
public final class GitRepo {
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
  public static final int DEFAULT_MAX_OUTPUT_BYTES = 2_000_000;

  private static final Pattern REF = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/@{}~^+-]*$");
  private static final Pattern DRIVE_LETTER = Pattern.compile("^[A-Za-z]:.*");
  private static final List<String> HARDENING_CONFIG =
      List.of(
          "core.fsmonitor=false",
          "core.hooksPath=/dev/null",
          "core.pager=cat",
          "core.quotePath=false",
          "diff.external=",
          "protocol.allow=never");
  private static final int MAX_STDERR_BYTES = 8192;

  /** A git command failed or its arguments were refused. The model may read the message. */
  public static final class GitException extends Exception {
    private static final long serialVersionUID = 1L;

    public GitException(String message) {
      super(message);
    }
  }

  public record GitOutput(String stdout, boolean truncated) {}

  private final Path root;
  private final Duration timeout;
  private final int maxOutputBytes;

  public GitRepo(Path root) {
    this(root, DEFAULT_TIMEOUT, DEFAULT_MAX_OUTPUT_BYTES);
  }

  public GitRepo(Path root, Duration timeout, int maxOutputBytes) {
    this.root = root.toAbsolutePath().normalize();
    this.timeout = timeout;
    this.maxOutputBytes = maxOutputBytes;
    if (!Files.exists(this.root.resolve(".git"))) {
      throw new IllegalArgumentException(this.root + " is not the root of a git repository");
    }
  }

  /**
   * Accepts branch names, tags, hashes and suffixes like {@code HEAD~2}; refuses flag-like text.
   */
  public static String validateRef(String ref) throws GitException {
    if (!REF.matcher(ref).matches() || ref.contains("..")) {
      throw new GitException(
          "invalid ref '" + ref + "': expected a branch, tag or commit such as HEAD~2");
    }
    return ref;
  }

  /** Accepts a path relative to the repository root, using forward slashes. */
  public static String validateRepoPath(String path) throws GitException {
    String normalized = path.replace('\\', '/');
    boolean refused =
        normalized.isEmpty()
            || normalized.startsWith("/")
            || normalized.startsWith("-")
            || DRIVE_LETTER.matcher(normalized).matches()
            || List.of(normalized.split("/")).contains("..")
            || normalized.indexOf('\0') >= 0;
    if (refused) {
      throw new GitException(
          "invalid path '" + path + "': expected a path relative to the repository root");
    }
    if (normalized.startsWith("./")) {
      normalized = normalized.substring(2);
    }
    return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
  }

  public GitOutput run(List<String> args) throws GitException {
    return run(args, Set.of(0));
  }

  /** Runs git, keeping at most the configured number of bytes of stdout. */
  public GitOutput run(List<String> args, Set<Integer> okCodes) throws GitException {
    List<String> command = new ArrayList<>(List.of("git", "--no-pager", "--no-optional-locks"));
    for (String setting : HARDENING_CONFIG) {
      command.add("-c");
      command.add(setting);
    }
    command.addAll(args);

    ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile());
    builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.environment().put("GIT_NO_LAZY_FETCH", "1");
    builder.environment().put("LC_ALL", "C");

    Process process;
    try {
      process = builder.start();
      process.getOutputStream().close();
    } catch (IOException e) {
      throw new GitException("could not start git: " + e.getMessage());
    }

    CappedReader stdout = new CappedReader(process.getInputStream(), maxOutputBytes, process);
    CappedReader stderr = new CappedReader(process.getErrorStream(), MAX_STDERR_BYTES, null);
    Thread stdoutThread = Thread.ofVirtual().start(stdout);
    Thread stderrThread = Thread.ofVirtual().start(stderr);
    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new GitException("git " + args.get(0) + " timed out after " + describe(timeout));
      }
      stdoutThread.join(Duration.ofSeconds(5));
      stderrThread.join(Duration.ofSeconds(5));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitException("git " + args.get(0) + " was interrupted");
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }

    if (!stdout.truncated() && !okCodes.contains(process.exitValue())) {
      String detail = stderr.text().strip();
      String last =
          detail.isEmpty()
              ? "exit " + process.exitValue()
              : detail.substring(detail.lastIndexOf('\n') + 1);
      throw new GitException("git " + args.get(0) + " failed: " + last);
    }
    return new GitOutput(stdout.text(), stdout.truncated());
  }

  private static String describe(Duration duration) {
    return duration.toMillis() / 1000.0 + "s";
  }

  /** Reads a stream up to a cap. Going over the cap kills the process, if one is given. */
  private static final class CappedReader implements Runnable {
    private final InputStream stream;
    private final int cap;
    private final Process toKill;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private volatile boolean truncated;

    CappedReader(InputStream stream, int cap, Process toKill) {
      this.stream = stream;
      this.cap = cap;
      this.toKill = toKill;
    }

    @Override
    public void run() {
      byte[] chunk = new byte[8192];
      try {
        int read;
        while ((read = stream.read(chunk)) != -1) {
          int remaining = cap - buffer.size();
          buffer.write(chunk, 0, Math.min(read, remaining));
          if (read > remaining) {
            truncated = true;
            if (toKill != null) {
              toKill.destroyForcibly();
              return;
            }
          }
        }
      } catch (IOException e) {
        // The stream closes when the process is killed, so what was read is what there is.
      }
    }

    boolean truncated() {
      return truncated;
    }

    String text() {
      return buffer.toString(StandardCharsets.UTF_8);
    }
  }
}
