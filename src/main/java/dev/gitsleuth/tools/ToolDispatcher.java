package dev.gitsleuth.tools;

import dev.gitsleuth.Log;
import dev.gitsleuth.tools.ToolBackend.ConnectionLostException;
import dev.gitsleuth.tools.ToolBackend.ToolOutput;
import dev.gitsleuth.tools.ToolBackend.ToolProtocolException;
import dev.gitsleuth.tools.ToolBackend.ToolTimeoutException;
import dev.gitsleuth.tools.ToolRegistry.ToolRoute;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Routes the model's tool calls to the right backend, and keeps failures away from the agent loop.
 *
 * <p>{@link #dispatch} never throws for a tool failure. Every problem becomes a {@link
 * DispatchOutcome} that the agent returns to the model as an error result, so the model can correct
 * itself and the loop keeps a valid conversation.
 */
public final class ToolDispatcher {
  private static final String LOGGER = "gitsleuth.dispatcher";

  public enum ErrorKind {
    UNKNOWN_TOOL,
    INVALID_INPUT,
    TOOL_ERROR,
    TIMEOUT,
    CONNECTION,
    PROTOCOL,
    INTERNAL;

    public String wireName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public record ToolCall(String id, String name, Map<String, Object> arguments) {}

  public record DispatchOutcome(
      String toolUseId,
      String name,
      String text,
      boolean isError,
      ErrorKind errorKind,
      int attempts,
      long durationMs,
      boolean truncated,
      boolean local) {}

  /** Exponential backoff with jitter: base, 2x base, and so on up to the maximum delay. */
  public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, double jitter) {
    public static RetryPolicy defaults() {
      return new RetryPolicy(3, Duration.ofMillis(250), Duration.ofSeconds(4), 0.5);
    }

    public Duration delay(int failedAttempts, Random random) {
      double ceiling =
          Math.min(maxDelay.toMillis(), baseDelay.toMillis() * Math.pow(2, failedAttempts - 1));
      return Duration.ofMillis((long) (ceiling * (1 - jitter * random.nextDouble())));
    }
  }

  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private record Attempted(int attempts, String text, ErrorKind kind) {}

  private final ToolRegistry registry;
  private final Duration callTimeout;
  private final RetryPolicy retry;
  private final int maxResultChars;
  private final int maxConcurrency;
  private final Sleeper sleeper;
  private final Random random;

  private ToolDispatcher(Builder builder) {
    this.registry = builder.registry;
    this.callTimeout = builder.callTimeout;
    this.retry = builder.retry;
    this.maxResultChars = builder.maxResultChars;
    this.maxConcurrency = builder.maxConcurrency;
    this.sleeper = builder.sleeper;
    this.random = builder.random;
  }

  public static Builder builder(ToolRegistry registry) {
    return new Builder(registry);
  }

  public static final class Builder {
    private final ToolRegistry registry;
    private Duration callTimeout = Duration.ofSeconds(30);
    private RetryPolicy retry = RetryPolicy.defaults();
    private int maxResultChars = 60_000;
    private int maxConcurrency = 4;
    private Sleeper sleeper = Thread::sleep;
    private Random random = new Random();

    private Builder(ToolRegistry registry) {
      this.registry = registry;
    }

    public Builder callTimeout(Duration value) {
      callTimeout = value;
      return this;
    }

    public Builder retry(RetryPolicy value) {
      retry = value;
      return this;
    }

    public Builder maxResultChars(int value) {
      maxResultChars = value;
      return this;
    }

    public Builder maxConcurrency(int value) {
      maxConcurrency = value;
      return this;
    }

    public Builder sleeper(Sleeper value) {
      sleeper = value;
      return this;
    }

    public Builder random(Random value) {
      random = value;
      return this;
    }

    public ToolDispatcher build() {
      return new ToolDispatcher(this);
    }
  }

  public ToolRegistry registry() {
    return registry;
  }

  public DispatchOutcome dispatch(ToolCall call) throws InterruptedException {
    long started = System.nanoTime();
    ToolRoute route = registry.get(call.name());
    int attempts = 0;
    boolean truncated = false;
    String text;
    ErrorKind kind = null;

    if (route == null) {
      kind = ErrorKind.UNKNOWN_TOOL;
      text =
          "Unknown tool '"
              + call.name()
              + "'. Available tools: "
              + String.join(", ", registry.names());
    } else {
      List<String> problems = ToolRegistry.validationProblems(route, call.arguments());
      if (!problems.isEmpty()) {
        kind = ErrorKind.INVALID_INPUT;
        text = "Invalid input for " + call.name() + ": " + String.join("; ", problems);
      } else {
        Attempted result = callWithRetries(route, call);
        attempts = result.attempts();
        kind = result.kind();
        text = result.text();
        if (text.length() > maxResultChars) {
          int omitted = text.length() - maxResultChars;
          text =
              text.substring(0, maxResultChars)
                  + "\n[output truncated by dispatcher: "
                  + omitted
                  + " characters omitted]";
          truncated = true;
        }
      }
    }

    DispatchOutcome outcome =
        new DispatchOutcome(
            call.id(),
            call.name(),
            text,
            kind != null,
            kind,
            attempts,
            (System.nanoTime() - started) / 1_000_000,
            truncated,
            route != null && route.local());
    Log.info(
        LOGGER,
        "tool_call",
        "tool",
        call.name(),
        "server",
        route == null ? null : route.server(),
        "is_error",
        outcome.isError(),
        "error_kind",
        kind == null ? null : kind.wireName(),
        "attempts",
        attempts,
        "duration_ms",
        outcome.durationMs(),
        "result_chars",
        text.length(),
        "truncated",
        truncated);
    return outcome;
  }

  /** Runs calls concurrently, up to the limit, and returns outcomes in request order. */
  public List<DispatchOutcome> dispatchMany(List<ToolCall> calls) throws InterruptedException {
    Semaphore limiter = new Semaphore(maxConcurrency);
    List<Callable<DispatchOutcome>> tasks = new ArrayList<>();
    for (ToolCall call : calls) {
      tasks.add(
          () -> {
            limiter.acquire();
            try {
              return dispatch(call);
            } finally {
              limiter.release();
            }
          });
    }
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<DispatchOutcome>> futures = new ArrayList<>();
      try {
        futures = executor.invokeAll(tasks);
      } catch (InterruptedException e) {
        executor.shutdownNow();
        throw e;
      }
      List<DispatchOutcome> outcomes = new ArrayList<>();
      for (Future<DispatchOutcome> future : futures) {
        try {
          outcomes.add(future.get());
        } catch (ExecutionException e) {
          throw new IllegalStateException("dispatch failed", e.getCause());
        }
      }
      return outcomes;
    }
  }

  private Attempted callWithRetries(ToolRoute route, ToolCall call) throws InterruptedException {
    int attempt = 0;
    while (true) {
      attempt++;
      ErrorKind kind;
      String message;
      try {
        ToolOutput output = route.backend().call(route.remoteName(), call.arguments(), callTimeout);
        return new Attempted(
            attempt, output.text(), output.isError() ? ErrorKind.TOOL_ERROR : null);
      } catch (ToolTimeoutException e) {
        kind = ErrorKind.TIMEOUT;
        message = e.getMessage();
      } catch (ConnectionLostException e) {
        kind = ErrorKind.CONNECTION;
        message = e.getMessage();
        recover(route, e);
      } catch (ToolProtocolException e) {
        return new Attempted(attempt, e.getMessage(), ErrorKind.PROTOCOL);
      } catch (RuntimeException e) {
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedException("interrupted during " + route.name());
        }
        Log.event(
            "error", LOGGER, "tool_internal_error", "tool", route.name(), "error", e.toString());
        return new Attempted(attempt, route.name() + " failed unexpectedly", ErrorKind.INTERNAL);
      }

      if (!route.retrySafe()) {
        return new Attempted(
            attempt,
            message
                + ". Not retried, because "
                + route.name()
                + " may have side effects; the call may or may not have taken effect.",
            kind);
      }
      if (attempt >= retry.maxAttempts()) {
        return new Attempted(attempt, message + " (gave up after " + attempt + " attempts)", kind);
      }

      Duration delay = retry.delay(attempt, random);
      Log.info(
          LOGGER,
          "tool_retry",
          "tool",
          route.name(),
          "attempt",
          attempt,
          "reason",
          kind.wireName(),
          "error",
          message,
          "delay_ms",
          delay.toMillis());
      sleeper.sleep(delay);
    }
  }

  private void recover(ToolRoute route, ConnectionLostException lost) {
    try {
      route.backend().recover(lost);
    } catch (ConnectionLostException e) {
      // The next attempt fails fast and asks to recover again, until attempts run out.
      Log.event("warning", LOGGER, "tool_recover_failed", "error", e.getMessage());
    }
  }
}
