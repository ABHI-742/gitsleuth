package dev.gitsleuth;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import dev.gitsleuth.Agent.Limits;
import dev.gitsleuth.Agent.Result;
import dev.gitsleuth.Agent.StopReason;
import dev.gitsleuth.McpConnection.McpConnectException;
import dev.gitsleuth.McpConnection.Target;
import dev.gitsleuth.Notebook.Finding;
import dev.gitsleuth.model.AnthropicModelClient;
import dev.gitsleuth.servers.ServerMain;
import dev.gitsleuth.tools.ToolDispatcher.DispatchOutcome;
import dev.gitsleuth.tools.ToolDispatcher.ToolCall;
import dev.gitsleuth.tools.ToolRegistry.ToolDefinition;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Command line: list tools, call one tool, serve, or run the agent. Only {@code ask} calls Claude.
 */
@Command(
    name = "gitsleuth",
    description = "Answer questions about a git repository's history through MCP tools.",
    mixinStandardHelpOptions = true,
    subcommands = {Cli.Tools.class, Cli.CallTool.class, Cli.Ask.class, Cli.Serve.class})
public final class Cli {
  static final int EXIT_USAGE = 2;

  @Option(
      names = "--verbose",
      description = "write JSON event logs to stderr",
      scope = CommandLine.ScopeType.INHERIT)
  void setVerbose(boolean verbose) {
    if (verbose) {
      Log.enable();
    }
  }

  abstract static class ToolCommand implements Callable<Integer> {
    @ParentCommand Cli parent;

    @Option(
        names = "--repo",
        defaultValue = ".",
        description = "git repository (default: current dir)")
    Path repo;

    @Option(names = "--code-url", description = "use a running code server over Streamable HTTP")
    String codeUrl;

    @Option(
        names = "--history-url",
        description = "use a running history server over Streamable HTTP")
    String historyUrl;

    abstract int runWith(Toolbox toolbox) throws Exception;

    @Override
    public Integer call() throws Exception {
      Path root = repo.toAbsolutePath().normalize();
      Target code = codeUrl != null ? new Target.Http(codeUrl) : Toolbox.stdioTarget("code", root);
      Target history =
          historyUrl != null ? new Target.Http(historyUrl) : Toolbox.stdioTarget("history", root);
      try (Toolbox toolbox = Toolbox.open(code, history)) {
        return runWith(toolbox);
      } catch (McpConnectException e) {
        System.err.println("gitsleuth: " + e.getMessage());
        return EXIT_USAGE;
      }
    }
  }

  @Command(name = "tools", description = "list the tools the MCP servers expose")
  static final class Tools extends ToolCommand {
    @Override
    int runWith(Toolbox toolbox) {
      for (ToolDefinition definition : toolbox.registry().definitions()) {
        System.out.println(definition.name() + ": " + definition.description());
      }
      return 0;
    }
  }

  @Command(name = "call", description = "run one tool through the dispatcher, without a model")
  static final class CallTool extends ToolCommand {
    @Parameters(index = "0", description = "exposed name, such as code__read_file")
    String tool;

    @Parameters(index = "1", defaultValue = "{}", description = "JSON object of arguments")
    String arguments;

    @Override
    public Integer call() throws Exception {
      try {
        parsed = JsonMapper.builder().build().readValue(arguments, new TypeReference<>() {});
      } catch (JacksonException e) {
        System.err.println("gitsleuth: arguments are not valid JSON: " + e.getOriginalMessage());
        return EXIT_USAGE;
      }
      return super.call();
    }

    private Map<String, Object> parsed;

    @Override
    int runWith(Toolbox toolbox) throws InterruptedException {
      DispatchOutcome outcome = toolbox.dispatcher().dispatch(new ToolCall("cli", tool, parsed));
      System.out.println(outcome.text());
      return outcome.isError() ? 1 : 0;
    }
  }

  @Command(
      name = "ask",
      description = "run the agent on a question (makes billed Claude API calls)")
  static final class Ask extends ToolCommand {
    private static final Limits DEFAULTS = Limits.defaults();

    @Parameters(index = "0")
    String question;

    @Option(names = "--model", defaultValue = AnthropicModelClient.DEFAULT_MODEL)
    String model;

    @Option(names = "--effort", defaultValue = AnthropicModelClient.DEFAULT_EFFORT)
    String effort;

    @Option(names = "--max-steps")
    Integer maxSteps;

    @Option(names = "--max-tool-calls")
    Integer maxToolCalls;

    @Option(names = "--max-total-tokens")
    Long maxTotalTokens;

    @Option(names = "--deadline", description = "wall-clock limit in seconds")
    Double deadline;

    @Option(names = "--json", description = "print the whole result as JSON")
    boolean json;

    @Override
    int runWith(Toolbox toolbox) {
      Limits limits =
          new Limits(
              maxSteps != null ? maxSteps : DEFAULTS.maxSteps(),
              maxToolCalls != null ? maxToolCalls : DEFAULTS.maxToolCalls(),
              maxTotalTokens != null ? maxTotalTokens : DEFAULTS.maxTotalTokens(),
              deadline != null ? Duration.ofMillis((long) (deadline * 1000)) : DEFAULTS.deadline());
      AnthropicModelClient modelClient =
          new AnthropicModelClient(
              AnthropicOkHttpClient.fromEnv(),
              model,
              AnthropicModelClient.DEFAULT_MAX_TOKENS,
              effort,
              true);
      Agent agent = new Agent(modelClient, toolbox.dispatcher(), toolbox.notebook(), limits);
      Result result = agent.run(question);
      if (json) {
        System.out.println(
            JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build()
                .writeValueAsString(result));
      } else {
        print(result);
      }
      return result.stopReason() == StopReason.MODEL_ERROR ? 1 : 0;
    }

    private static void print(Result result) {
      System.out.println(result.answer().isEmpty() ? "(no answer)" : result.answer());
      if (!result.findings().isEmpty()) {
        System.out.println("\nFindings:");
        for (Finding finding : result.findings()) {
          String commits =
              finding.commits().stream()
                  .map(commit -> commit.substring(0, Math.min(10, commit.length())))
                  .collect(Collectors.joining(", "));
          System.out.println(
              "- [" + finding.confidence() + "] " + finding.claim() + " (" + commits + ")");
        }
      }
      System.out.println(
          "\nStopped: "
              + result.stopReason().wireName()
              + (result.detail() != null ? " (" + result.detail() + ")" : "")
              + " | "
              + result.steps()
              + " steps, "
              + result.toolCalls().size()
              + " tool calls, "
              + result.usage().inputTokens()
              + " input and "
              + result.usage().outputTokens()
              + " output tokens");
    }
  }

  @Command(name = "serve", description = "run one MCP server")
  static final class Serve implements Callable<Integer> {
    @Parameters(index = "0", description = "code or history")
    String server;

    @Option(
        names = "--repo",
        defaultValue = ".",
        description = "root of the git repository to expose")
    Path repo;

    @Option(names = "--transport", defaultValue = "stdio", description = "stdio or streamable-http")
    String transport;

    @Option(names = "--host", defaultValue = "127.0.0.1")
    String host;

    @Option(names = "--port", defaultValue = "8100")
    int port;

    @Override
    public Integer call() throws Exception {
      ServerMain.serve(server, repo, transport, host, port);
      return 0;
    }
  }

  public static void main(String[] args) {
    System.exit(new CommandLine(new Cli()).execute(args));
  }
}
