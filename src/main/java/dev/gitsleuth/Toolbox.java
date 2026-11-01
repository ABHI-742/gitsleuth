package dev.gitsleuth;

import dev.gitsleuth.McpConnection.McpConnectException;
import dev.gitsleuth.McpConnection.Target;
import dev.gitsleuth.servers.ServerMain;
import dev.gitsleuth.tools.ToolDispatcher;
import dev.gitsleuth.tools.ToolRegistry;
import io.modelcontextprotocol.client.transport.ServerParameters;
import java.nio.file.Path;

/** Wires the MCP connections, local tools and dispatcher together for one repository. */
public final class Toolbox implements AutoCloseable {
  private final McpConnection code;
  private final McpConnection history;
  private final ToolRegistry registry;
  private final ToolDispatcher dispatcher;
  private final Notebook notebook;

  private Toolbox(
      McpConnection code,
      McpConnection history,
      ToolRegistry registry,
      ToolDispatcher dispatcher,
      Notebook notebook) {
    this.code = code;
    this.history = history;
    this.registry = registry;
    this.dispatcher = dispatcher;
    this.notebook = notebook;
  }

  /** Launches a bundled server as a subprocess, with this JVM and classpath. */
  public static Target stdioTarget(String server, Path repo) {
    String java = ProcessHandle.current().info().command().orElse("java");
    return new Target.Stdio(
        ServerParameters.builder(java)
            .args(
                "-cp",
                System.getProperty("java.class.path"),
                ServerMain.class.getName(),
                server,
                "--repo",
                repo.toAbsolutePath().toString())
            .build());
  }

  public static Toolbox open(Target codeTarget, Target historyTarget) throws McpConnectException {
    McpConnection code = McpConnection.open("code", codeTarget);
    try {
      McpConnection history = McpConnection.open("history", historyTarget);
      ToolRegistry registry = new ToolRegistry();
      registry.addMcpServer(code);
      registry.addMcpServer(history);
      Notebook notebook = new Notebook();
      notebook.register(registry);
      return new Toolbox(
          code, history, registry, ToolDispatcher.builder(registry).build(), notebook);
    } catch (McpConnectException | RuntimeException e) {
      code.close();
      throw e;
    }
  }

  public static Toolbox openLocal(Path repo) throws McpConnectException {
    return open(stdioTarget("code", repo), stdioTarget("history", repo));
  }

  public ToolRegistry registry() {
    return registry;
  }

  public ToolDispatcher dispatcher() {
    return dispatcher;
  }

  public Notebook notebook() {
    return notebook;
  }

  @Override
  public void close() {
    history.close();
    code.close();
  }
}
