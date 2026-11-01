package dev.gitsleuth;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A real MCP server that misbehaves on request, for testing the client and dispatcher. */
public final class ChaosServer {
  private static final ToolAnnotations READ_ONLY =
      ToolAnnotations.builder().readOnlyHint(true).idempotentHint(true).build();
  private static final ToolAnnotations SIDE_EFFECTS =
      ToolAnnotations.builder().readOnlyHint(false).idempotentHint(false).build();
  private static Path stateDir;

  private ChaosServer() {}

  public static McpConnection.Target target(Path state) {
    String java = ProcessHandle.current().info().command().orElse("java");
    return new McpConnection.Target.Stdio(
        ServerParameters.builder(java)
            .args(
                "-cp",
                System.getProperty("java.class.path"),
                ChaosServer.class.getName(),
                state.toString())
            .build());
  }

  public static int count(Path state, String name) throws IOException {
    Path file = state.resolve(name + ".count");
    return Files.exists(file) ? Integer.parseInt(Files.readString(file).strip()) : 0;
  }

  /** Counts calls in a file, so the count survives this process being killed. */
  private static synchronized int bump(String name) {
    try {
      int next = count(stateDir, name) + 1;
      // Moved into place, so a process killed mid-write cannot leave an empty counter.
      Path scratch = Files.createTempFile(stateDir, name, ".tmp");
      Files.writeString(scratch, String.valueOf(next));
      Files.move(scratch, stateDir.resolve(name + ".count"), StandardCopyOption.REPLACE_EXISTING);
      return next;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void sleep(Map<String, Object> args) {
    try {
      Thread.sleep((long) (((Number) args.get("seconds")).doubleValue() * 1000));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static CallToolResult text(String value) {
    return CallToolResult.builder().content(List.of(TextContent.builder(value).build())).build();
  }

  private static SyncToolSpecification tool(
      String name,
      ToolAnnotations annotations,
      Map<String, Object> properties,
      Function<Map<String, Object>, CallToolResult> handler) {
    Map<String, Object> schema = Map.of("type", "object", "properties", properties);
    return SyncToolSpecification.builder()
        .tool(
            McpSchema.Tool.builder(name, schema).description(name).annotations(annotations).build())
        .callHandler((exchange, request) -> handler.apply(request.arguments()))
        .build();
  }

  public static void main(String[] args) throws Exception {
    stateDir = Path.of(args[0]);
    Map<String, Object> seconds = Map.of("seconds", Map.of("type", "number"));
    McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
        .serverInfo("chaos", "0.0.1")
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .tools(
            tool(
                "echo",
                READ_ONLY,
                Map.of("message", Map.of("type", "string")),
                in -> {
                  bump("echo");
                  return text("echo: " + in.get("message"));
                }),
            tool(
                "slow_once",
                READ_ONLY,
                seconds,
                in -> {
                  if (bump("slow_once") == 1) {
                    sleep(in);
                  }
                  return text("finished");
                }),
            tool(
                "always_slow",
                READ_ONLY,
                seconds,
                in -> {
                  bump("always_slow");
                  sleep(in);
                  return text("finished");
                }),
            tool(
                "crash_once",
                READ_ONLY,
                Map.of(),
                in -> {
                  if (bump("crash_once") == 1) {
                    Runtime.getRuntime().halt(1);
                  }
                  return text("survived");
                }),
            tool(
                "write_then_crash",
                SIDE_EFFECTS,
                Map.of(),
                in -> {
                  bump("write_then_crash");
                  Runtime.getRuntime().halt(1);
                  return text("unreachable");
                }),
            tool(
                "tool_failure",
                READ_ONLY,
                Map.of(),
                in -> {
                  bump("tool_failure");
                  return CallToolResult.builder()
                      .content(
                          List.of(TextContent.builder("the tool looked and found nothing").build()))
                      .isError(true)
                      .build();
                }))
        .build();
    ProcessHandle.current()
        .parent()
        .ifPresent(parent -> parent.onExit().thenRun(() -> Runtime.getRuntime().halt(0)));
    Thread.currentThread().join();
  }
}
