package dev.gitsleuth.tools;

import java.time.Duration;
import java.util.Map;

/** Something that can run tools: an MCP connection, or a local tool such as the notebook. */
public interface ToolBackend {
  ToolOutput call(String tool, Map<String, Object> arguments, Duration timeout)
      throws ToolTimeoutException, ConnectionLostException, ToolProtocolException;

  void recover(ConnectionLostException lost) throws ConnectionLostException;

  record ToolOutput(String text, boolean isError) {
    public static ToolOutput ok(String text) {
      return new ToolOutput(text, false);
    }

    public static ToolOutput error(String text) {
      return new ToolOutput(text, true);
    }
  }

  /** The call got no answer in time. It may still have run, so only retry-safe tools retry. */
  final class ToolTimeoutException extends Exception {
    private static final long serialVersionUID = 1L;

    public ToolTimeoutException(String message) {
      super(message);
    }
  }

  /** The session with the backend ended. */
  final class ConnectionLostException extends Exception {
    private static final long serialVersionUID = 1L;

    private final int generation;

    public ConnectionLostException(String message, int generation) {
      super(message);
      this.generation = generation;
    }

    public int generation() {
      return generation;
    }
  }

  /** The backend answered with a protocol error, which a retry would not fix. */
  final class ToolProtocolException extends Exception {
    private static final long serialVersionUID = 1L;

    public ToolProtocolException(String message) {
      super(message);
    }
  }
}
