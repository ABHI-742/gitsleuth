package dev.gitsleuth;

import dev.gitsleuth.tools.ToolBackend;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * MCP client: connects to one server, discovers its tools, calls them, reconnects after a crash.
 *
 * <p>When a stdio server dies, the SDK leaves pending requests waiting for their timeout. So a
 * crash would look like a hang. Each stdio session therefore has a watcher on the server process,
 * and a call fails as soon as the process exits.
 */
public final class McpConnection implements ToolBackend, AutoCloseable {
  private static final String LOGGER = "gitsleuth.mcp_client";
  private static final int MAX_TOOL_PAGES = 50;
  // Per-call timeouts are applied in call(). This is only the SDK's outer limit.
  private static final Duration SDK_REQUEST_TIMEOUT = Duration.ofMinutes(10);

  /** The server could not be reached, or its handshake or tool listing failed. */
  public static final class McpConnectException extends Exception {
    private static final long serialVersionUID = 1L;

    public McpConnectException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Where a server lives: a command to launch and talk to over stdio, or a Streamable HTTP URL. */
  public sealed interface Target {
    record Stdio(ServerParameters parameters) implements Target {}

    record Http(String url) implements Target {}
  }

  private final String name;
  private final Duration connectTimeout;
  private final ReentrantLock reconnectLock = new ReentrantLock();
  private volatile Target target;
  // Replaced as a whole, so a caller can never pair one session's client with another's generation.
  private volatile Session session;
  private volatile List<McpSchema.Tool> tools = List.of();
  private int lastGeneration;

  private record Session(McpAsyncClient client, int generation, CompletableFuture<Void> exited) {}

  private McpConnection(String name, Target target, Duration connectTimeout) {
    this.name = name;
    this.target = target;
    this.connectTimeout = connectTimeout;
  }

  public static McpConnection open(String name, Target target, Duration connectTimeout)
      throws McpConnectException {
    McpConnection connection = new McpConnection(name, target, connectTimeout);
    try {
      connection.connect();
    } catch (RuntimeException e) {
      throw new McpConnectException(
          "could not connect to MCP server '" + name + "': " + describe(e), e);
    }
    return connection;
  }

  public static McpConnection open(String name, Target target) throws McpConnectException {
    return open(name, target, Duration.ofSeconds(20));
  }

  public String name() {
    return name;
  }

  public List<McpSchema.Tool> tools() {
    return tools;
  }

  public int generation() {
    Session current = session;
    return current == null ? lastGeneration : current.generation();
  }

  /** Points later reconnects somewhere else. Tests use it to simulate a server that stays down. */
  void retarget(Target newTarget) {
    this.target = newTarget;
  }

  private static McpClientTransport transport(Target target) {
    return switch (target) {
      case Target.Stdio stdio ->
          new StdioClientTransport(stdio.parameters(), McpJsonDefaults.getMapper());
      case Target.Http http -> {
        URI uri = URI.create(http.url());
        String base = uri.getScheme() + "://" + uri.getAuthority();
        yield HttpClientStreamableHttpTransport.builder(base)
            .endpoint(uri.getPath())
            .jsonMapper(McpJsonDefaults.getMapper())
            .build();
      }
    };
  }

  private static CompletableFuture<Void> watchForExit(McpClientTransport transport) {
    CompletableFuture<Void> exited = new CompletableFuture<>();
    if (transport instanceof StdioClientTransport stdio) {
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  stdio.awaitForExit();
                } finally {
                  exited.complete(null);
                }
              });
    }
    return exited;
  }

  private void connect() {
    McpClientTransport transport = transport(target);
    McpAsyncClient fresh =
        McpClient.async(transport)
            .requestTimeout(SDK_REQUEST_TIMEOUT)
            .initializationTimeout(connectTimeout)
            .build();
    try {
      McpSchema.InitializeResult initialized = fresh.initialize().block(connectTimeout);
      List<McpSchema.Tool> listed = listAllTools(fresh);
      this.tools = listed;
      this.lastGeneration++;
      // The stdio process only exists once initialize() has run.
      this.session = new Session(fresh, lastGeneration, watchForExit(transport));
      Log.info(
          LOGGER,
          "mcp_connected",
          "server",
          name,
          "generation",
          lastGeneration,
          "protocol",
          initialized == null ? null : initialized.protocolVersion(),
          "tools",
          listed.stream().map(McpSchema.Tool::name).toList());
    } catch (RuntimeException e) {
      closeQuietly(fresh);
      Log.event("warning", LOGGER, "mcp_session_failed", "server", name, "error", describe(e));
      throw e;
    }
  }

  private List<McpSchema.Tool> listAllTools(McpAsyncClient listing) {
    List<McpSchema.Tool> all = new ArrayList<>();
    String cursor = null;
    for (int page = 0; page < MAX_TOOL_PAGES; page++) {
      McpSchema.ListToolsResult result = listing.listTools(cursor).block(connectTimeout);
      all.addAll(result.tools());
      cursor = result.nextCursor();
      if (cursor == null) {
        return all;
      }
    }
    throw new IllegalStateException(
        "tool listing did not finish within " + MAX_TOOL_PAGES + " pages");
  }

  @Override
  public ToolOutput call(String tool, Map<String, Object> arguments, Duration timeout)
      throws ToolTimeoutException, ConnectionLostException, ToolProtocolException {
    Session current = session;
    if (current == null) {
      throw new ConnectionLostException("not connected to '" + name + "'", -1);
    }
    int seen = current.generation();
    Mono<McpSchema.CallToolResult> exited =
        Mono.fromFuture(current.exited(), true)
            .then(Mono.error(new IllegalStateException("server process exited")));
    McpSchema.CallToolResult result;
    try {
      result =
          Mono.firstWithSignal(
                  current.client().callTool(new McpSchema.CallToolRequest(tool, arguments)), exited)
              .timeout(timeout)
              .block();
    } catch (RuntimeException e) {
      Throwable cause = Exceptions.unwrap(e);
      if (cause instanceof TimeoutException) {
        throw new ToolTimeoutException(
            tool + " timed out after " + timeout.toMillis() / 1000.0 + "s");
      }
      // A session that ended fails its pending calls with an McpError, which is not the server's
      // answer: the call may never have run.
      if (current.exited().isDone() || session != current) {
        throw new ConnectionLostException("connection to '" + name + "' closed", seen);
      }
      if (cause instanceof McpError error && error.getJsonRpcError() != null) {
        int code = error.getJsonRpcError().code();
        throw new ToolProtocolException(
            "MCP error " + code + ": " + error.getJsonRpcError().message());
      }
      if (Thread.currentThread().isInterrupted()) {
        throw e;
      }
      throw new ConnectionLostException(
          "transport failure to '" + name + "': " + describe(cause), seen);
    }
    return new ToolOutput(contentToText(result.content()), Boolean.TRUE.equals(result.isError()));
  }

  /** Reopens the session unless another caller already replaced the one that failed. */
  @Override
  public void recover(ConnectionLostException lost) throws ConnectionLostException {
    reconnectLock.lock();
    try {
      Session old = session;
      if (old != null && old.generation() != lost.generation()) {
        return;
      }
      session = null;
      if (old != null) {
        closeQuietly(old.client());
      }
      try {
        connect();
      } catch (RuntimeException e) {
        throw new ConnectionLostException("reconnect to '" + name + "' failed: " + describe(e), -1);
      }
    } finally {
      reconnectLock.unlock();
    }
  }

  @Override
  public void close() {
    Session current = session;
    session = null;
    if (current != null) {
      closeQuietly(current.client());
    }
  }

  private static void closeQuietly(McpAsyncClient target) {
    try {
      target.closeGracefully().block(Duration.ofSeconds(5));
    } catch (RuntimeException e) {
      target.close();
    }
  }

  private static String contentToText(List<McpSchema.Content> content) {
    List<String> parts = new ArrayList<>();
    for (McpSchema.Content block : content) {
      if (block instanceof McpSchema.TextContent text) {
        parts.add(text.text());
      } else if (block instanceof McpSchema.ImageContent image) {
        parts.add("[" + image.mimeType() + " image omitted]");
      } else {
        parts.add("[" + block.type() + " content omitted]");
      }
    }
    return String.join("\n", parts);
  }

  private static String describe(Throwable error) {
    Throwable cause = Exceptions.unwrap(error);
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }
}
