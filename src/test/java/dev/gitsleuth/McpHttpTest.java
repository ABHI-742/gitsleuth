package dev.gitsleuth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gitsleuth.McpConnection.McpConnectException;
import dev.gitsleuth.McpConnection.Target;
import dev.gitsleuth.servers.ServerMain;
import dev.gitsleuth.tools.ToolDispatcher;
import dev.gitsleuth.tools.ToolDispatcher.DispatchOutcome;
import dev.gitsleuth.tools.ToolDispatcher.ErrorKind;
import dev.gitsleuth.tools.ToolDispatcher.ToolCall;
import dev.gitsleuth.tools.ToolRegistry;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** The real client talking to real server processes over the Streamable HTTP transport. */
@Timeout(120)
class McpHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static Process startServer(String kind, Path repo, int port) throws Exception {
    String java = ProcessHandle.current().info().command().orElse("java");
    Process process =
        new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                ServerMain.class.getName(),
                kind,
                "--repo",
                repo.toString(),
                "--transport",
                "streamable-http",
                "--port",
                String.valueOf(port))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (true) {
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
        return process;
      } catch (java.io.IOException e) {
        if (System.nanoTime() > deadline) {
          process.destroyForcibly();
          throw new IllegalStateException(kind + " server did not start", e);
        }
        Thread.sleep(100);
      }
    }
  }

  private static void stop(Process process) throws Exception {
    process.destroyForcibly();
    process.waitFor();
  }

  private static Target http(int port) {
    return new Target.Http("http://127.0.0.1:" + port + "/mcp");
  }

  @Test
  void toolsWorkOverStreamableHttp(@TempDir Path dir) throws Exception {
    SampleRepo repo = SampleRepo.create(dir);
    int codePort = freePort();
    int historyPort = freePort();
    Process code = startServer("code", repo.path, codePort);
    Process history = startServer("history", repo.path, historyPort);
    try (Toolbox box = Toolbox.open(http(codePort), http(historyPort))) {
      assertTrue(box.registry().names().contains("history__git_log"));
      DispatchOutcome log =
          box.dispatcher().dispatch(new ToolCall("t", "history__git_log", Map.of("limit", 1)));
      assertEquals(
          repo.shas.get("binary"),
          JSON.readTree(log.text()).get("commits").get(0).get("sha").asString());
      DispatchOutcome error =
          box.dispatcher().dispatch(new ToolCall("t", "code__read_file", Map.of("path", "../x")));
      assertEquals(ErrorKind.TOOL_ERROR, error.errorKind());
    } finally {
      stop(code);
      stop(history);
    }
  }

  @Test
  void httpServerThatDiedIsReportedThenPickedUpAfterRestart(@TempDir Path dir) throws Exception {
    SampleRepo repo = SampleRepo.create(dir);
    int port = freePort();
    Process process = startServer("code", repo.path, port);
    try (McpConnection connection = McpConnection.open("code", http(port), Duration.ofSeconds(5))) {
      ToolRegistry registry = new ToolRegistry();
      registry.addMcpServer(connection);
      ToolDispatcher dispatcher =
          ToolDispatcher.builder(registry).callTimeout(Duration.ofSeconds(5)).build();
      ToolCall call = new ToolCall("t", "code__list_files", Map.of());
      assertFalse(dispatcher.dispatch(call).isError());

      stop(process);
      DispatchOutcome failed = dispatcher.dispatch(call);
      assertTrue(failed.isError(), failed.text());
      assertTrue(
          List.of(ErrorKind.CONNECTION, ErrorKind.TIMEOUT).contains(failed.errorKind()),
          failed.toString());
      assertEquals(3, failed.attempts());

      process = startServer("code", repo.path, port);
      DispatchOutcome recovered = dispatcher.dispatch(call);
      assertFalse(recovered.isError(), recovered.text());
    } finally {
      stop(process);
    }
  }

  @Test
  void connectingToNothingRaisesAClearError() throws Exception {
    McpConnectException error =
        assertThrows(
            McpConnectException.class,
            () -> McpConnection.open("code", http(freePort()), Duration.ofSeconds(5)));
    assertTrue(error.getMessage().contains("could not connect"), error.getMessage());
  }
}
