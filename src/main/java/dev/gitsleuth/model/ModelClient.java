package dev.gitsleuth.model;

import dev.gitsleuth.tools.ToolDispatcher.ToolCall;
import dev.gitsleuth.tools.ToolRegistry.ToolDefinition;
import java.util.List;

/** The agent's view of a model. Tests script it, so they never call the API. */
public interface ModelClient {
  ModelTurn complete(
      String system, List<Message> messages, List<ToolDefinition> tools, boolean allowTools)
      throws ModelException, InterruptedException;

  /** A model request failed after the SDK's own retries. */
  final class ModelException extends Exception {
    private static final long serialVersionUID = 1L;

    public ModelException(String message, Throwable cause) {
      super(message, cause);
    }

    public ModelException(String message) {
      super(message);
    }
  }

  /** One entry in the conversation. */
  sealed interface Message {
    record UserText(String text) implements Message {}

    record Assistant(ModelTurn turn) implements Message {}

    record ToolResults(List<ToolResult> results) implements Message {}
  }

  record ToolResult(String toolUseId, String content, boolean isError) {}

  /**
   * One model response.
   *
   * @param raw the provider's own response, echoed back unchanged on the next request, because
   *     thinking blocks must not be edited
   */
  record ModelTurn(
      String stopReason,
      Object raw,
      String text,
      List<ToolCall> toolCalls,
      Usage usage,
      String refusalCategory) {}

  record Usage(
      long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens) {
    public static final Usage ZERO = new Usage(0, 0, 0, 0);

    public long total() {
      return inputTokens + outputTokens + cacheReadTokens + cacheCreationTokens;
    }

    public Usage plus(Usage other) {
      return new Usage(
          inputTokens + other.inputTokens,
          outputTokens + other.outputTokens,
          cacheReadTokens + other.cacheReadTokens,
          cacheCreationTokens + other.cacheCreationTokens);
    }
  }
}
