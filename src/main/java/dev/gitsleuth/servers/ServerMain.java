package dev.gitsleuth.servers;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

/** Runs one MCP server: {@code ServerMain code --repo PATH [--transport streamable-http]}. */
public final class ServerMain {
  private static final String VERSION = "0.1.0";
  private static final String ENDPOINT = "/mcp";

  private ServerMain() {}

  public static void main(String[] argv) throws Exception {
    if (argv.length < 1) {
      throw new IllegalArgumentException("usage: ServerMain code|history --repo PATH");
    }
    String kind = argv[0];
    String repo = null;
    String transport = "stdio";
    String host = "127.0.0.1";
    int port = 8100;
    for (int i = 1; i + 1 < argv.length; i += 2) {
      switch (argv[i]) {
        case "--repo" -> repo = argv[i + 1];
        case "--transport" -> transport = argv[i + 1];
        case "--host" -> host = argv[i + 1];
        case "--port" -> port = Integer.parseInt(argv[i + 1]);
        default -> throw new IllegalArgumentException("unknown option " + argv[i]);
      }
    }
    if (repo == null) {
      throw new IllegalArgumentException("--repo is required");
    }
    serve(kind, Path.of(repo), transport, host, port);
  }

  public static void serve(String kind, Path repoPath, String transport, String host, int port)
      throws Exception {
    GitRepo repo = new GitRepo(repoPath);
    List<SyncToolSpecification> tools;
    String name;
    String instructions;
    switch (kind) {
      case "code" -> {
        tools = CodeServer.tools(repo);
        name = CodeServer.NAME;
        instructions = CodeServer.INSTRUCTIONS;
      }
      case "history" -> {
        tools = HistoryServer.tools(repo);
        name = HistoryServer.NAME;
        instructions = HistoryServer.INSTRUCTIONS;
      }
      default -> throw new IllegalArgumentException("unknown server " + kind);
    }
    McpSchema.ServerCapabilities capabilities =
        McpSchema.ServerCapabilities.builder().tools(false).build();

    if ("stdio".equals(transport)) {
      serveStdio(tools, name, instructions, capabilities);
    } else if ("streamable-http".equals(transport)) {
      serveHttp(tools, name, instructions, capabilities, host, port);
    } else {
      throw new IllegalArgumentException("unknown transport " + transport);
    }
  }

  private static void serveStdio(
      List<SyncToolSpecification> tools,
      String name,
      String instructions,
      McpSchema.ServerCapabilities capabilities)
      throws InterruptedException {
    McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
        .serverInfo(name, VERSION)
        .instructions(instructions)
        .capabilities(capabilities)
        .tools(tools)
        .build();
    // A subprocess server must not outlive the client that launched it.
    ProcessHandle.current()
        .parent()
        .ifPresent(parent -> parent.onExit().thenRun(() -> Runtime.getRuntime().halt(0)));
    Thread.currentThread().join();
  }

  private static void serveHttp(
      List<SyncToolSpecification> tools,
      String name,
      String instructions,
      McpSchema.ServerCapabilities capabilities,
      String host,
      int port)
      throws Exception {
    HttpServletStreamableServerTransportProvider provider =
        HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(McpJsonDefaults.getMapper())
            .mcpEndpoint(ENDPOINT)
            .build();
    McpServer.sync(provider)
        .serverInfo(name, VERSION)
        .instructions(instructions)
        .capabilities(capabilities)
        .tools(tools)
        .build();

    Server jetty = new Server(new InetSocketAddress(host, port));
    ServletContextHandler context = new ServletContextHandler();
    context.setContextPath("/");
    ServletHolder holder = new ServletHolder(provider);
    holder.setAsyncSupported(true);
    context.addServlet(holder, "/*");
    jetty.setHandler(context);
    jetty.start();
    jetty.join();
  }
}
