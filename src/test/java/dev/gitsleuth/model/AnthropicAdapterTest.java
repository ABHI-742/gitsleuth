package dev.gitsleuth.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import dev.gitsleuth.Agent;
import dev.gitsleuth.Agent.Limits;
import dev.gitsleuth.Agent.Result;
import dev.gitsleuth.Agent.StopReason;
import dev.gitsleuth.Notebook;
import dev.gitsleuth.model.ModelClient.Message;
import dev.gitsleuth.model.ModelClient.ModelException;
import dev.gitsleuth.model.ModelClient.ModelTurn;
import dev.gitsleuth.tools.ToolBackend;
import dev.gitsleuth.tools.ToolDispatcher;
import dev.gitsleuth.tools.ToolRegistry;
import dev.gitsleuth.tools.ToolRegistry.ToolDefinition;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real Anthropic SDK against a scripted local HTTP server. Nothing leaves this machine.
 *
 * <p>This proves the request the SDK builds and how its stream is parsed. It cannot prove that the
 * API accepts the request: that needs a real call, which this suite never makes.
 */
@Timeout(60)
class AnthropicAdapterTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<ToolDefinition> TOOLS =
      List.of(
          new ToolDefinition(
              "srv__find",
              "Find",
              Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string")))));
  private static final List<Message> HI = List.of(new Message.UserText("hi"));

  private record Scripted(int status, String contentType, String body) {}

  private HttpServer server;
  private final Deque<Scripted> responses = new ArrayDeque<>();
  private final List<JsonNode> requests = new ArrayList<>();
  private final List<String> betaHeaders = new ArrayList<>();
  private AnthropicClient client;

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
          betaHeaders.add(exchange.getRequestHeaders().getFirst("anthropic-beta"));
          Scripted next = responses.removeFirst();
          byte[] body = next.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("content-type", next.contentType());
          exchange.sendResponseHeaders(next.status(), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    client =
        AnthropicOkHttpClient.builder()
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .apiKey("test-key-not-real")
            .maxRetries(0)
            .build();
  }

  @AfterEach
  void stopServer() {
    client.close();
    server.stop(0);
  }

  private static Map<String, Object> map(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      result.put((String) pairs[i], pairs[i + 1]);
    }
    return result;
  }

  private static Map<String, Object> toolUse(String id, String partialJson) {
    return map("type", "tool_use", "id", id, "name", "srv__find", "partial", partialJson);
  }

  private static Map<String, Object> text(String value) {
    return map("type", "text", "text", value);
  }

  private static Map<String, Object> thinking(String signature) {
    return map("type", "thinking", "signature", signature);
  }

  private static Scripted stream(
      List<Map<String, Object>> blocks, String stopReason, Map<String, Object> stopDetails) {
    List<Map<String, Object>> events = new ArrayList<>();
    events.add(
        map(
            "type",
            "message_start",
            "message",
            map(
                "id",
                "msg_1",
                "type",
                "message",
                "role",
                "assistant",
                "model",
                "claude-opus-5-5",
                "content",
                List.of(),
                "stop_reason",
                null,
                "stop_sequence",
                null,
                "usage",
                map(
                    "input_tokens", 11,
                    "output_tokens", 1,
                    "cache_read_input_tokens", 5,
                    "cache_creation_input_tokens", 3))));
    for (int index = 0; index < blocks.size(); index++) {
      Map<String, Object> block = blocks.get(index);
      Map<String, Object> start;
      Map<String, Object> delta;
      switch ((String) block.get("type")) {
        case "tool_use" -> {
          start =
              map(
                  "type",
                  "tool_use",
                  "id",
                  block.get("id"),
                  "name",
                  block.get("name"),
                  "input",
                  Map.of());
          delta = map("type", "input_json_delta", "partial_json", block.get("partial"));
        }
        case "thinking" -> {
          start = map("type", "thinking", "thinking", "", "signature", "");
          delta = map("type", "signature_delta", "signature", block.get("signature"));
        }
        default -> {
          start = map("type", "text", "text", "");
          delta = map("type", "text_delta", "text", block.get("text"));
        }
      }
      events.add(map("type", "content_block_start", "index", index, "content_block", start));
      events.add(map("type", "content_block_delta", "index", index, "delta", delta));
      events.add(map("type", "content_block_stop", "index", index));
    }
    events.add(
        map(
            "type",
            "message_delta",
            "delta",
            map("stop_reason", stopReason, "stop_sequence", null, "stop_details", stopDetails),
            "usage",
            map("output_tokens", 7)));
    events.add(map("type", "message_stop"));

    StringBuilder body = new StringBuilder();
    for (Map<String, Object> event : events) {
      body.append("event: ").append(event.get("type")).append('\n');
      body.append("data: ").append(JSON.writeValueAsString(event)).append("\n\n");
    }
    return new Scripted(200, "text/event-stream", body.toString());
  }

  private static Scripted error(int status) {
    return new Scripted(
        status,
        "application/json",
        "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}");
  }

  @Test
  void requestUsesTheDocumentedDefaultsAndTheResponseIsParsed() throws Exception {
    responses.add(
        stream(List.of(text("Looking."), toolUse("toolu_1", "{\"q\": \"x\"}")), "tool_use", null));
    ModelTurn turn = new AnthropicModelClient(client).complete("sys", HI, TOOLS, true);

    JsonNode body = requests.get(0);
    assertEquals("claude-opus-5-5", body.get("model").asString());
    assertEquals(64000, body.get("max_tokens").asInt());
    assertTrue(body.get("stream").asBoolean());
    assertEquals("adaptive", body.get("thinking").get("type").asString());
    assertEquals("high", body.get("output_config").get("effort").asString());
    assertEquals("auto", body.get("tool_choice").get("type").asString());
    assertEquals("default", body.get("fallbacks").asString());
    assertEquals("ephemeral", body.get("cache_control").get("type").asString());
    assertEquals("srv__find", body.get("tools").get(0).get("name").asString());
    assertEquals(
        "string",
        body.get("tools")
            .get(0)
            .get("input_schema")
            .get("properties")
            .get("q")
            .get("type")
            .asString());
    assertFalse(body.has("temperature"));
    assertTrue(betaHeaders.get(0).contains("server-side-fallback-2026-07-01"));

    assertEquals("tool_use", turn.stopReason());
    assertEquals("Looking.", turn.text());
    assertEquals("toolu_1", turn.toolCalls().get(0).id());
    assertEquals("srv__find", turn.toolCalls().get(0).name());
    assertEquals(Map.of("q", "x"), turn.toolCalls().get(0).arguments());
    assertEquals(11, turn.usage().inputTokens());
    assertEquals(7, turn.usage().outputTokens());
    assertEquals(5, turn.usage().cacheReadTokens());
    assertEquals(3, turn.usage().cacheCreationTokens());
  }

  @Test
  void finalTurnDisablesToolsWithoutForcingACall() throws Exception {
    responses.add(stream(List.of(text("done")), "end_turn", null));
    new AnthropicModelClient(client).complete("s", HI, TOOLS, false);
    assertEquals("none", requests.get(0).get("tool_choice").get("type").asString());
  }

  @Test
  void fallbacksCanBeTurnedOff() throws Exception {
    responses.add(stream(List.of(text("done")), "end_turn", null));
    new AnthropicModelClient(client, "claude-opus-5-5", 64000, "high", false)
        .complete("s", HI, TOOLS, true);
    assertFalse(requests.get(0).has("fallbacks"));
  }

  @Test
  void realSdkBlocksRoundTripThroughTheAgentLoop() {
    responses.add(
        stream(
            List.of(thinking("sig-abc"), toolUse("toolu_1", "{\"q\": \"x\"}")), "tool_use", null));
    responses.add(stream(List.of(text("All done.")), "end_turn", null));

    ToolRegistry registry = new ToolRegistry();
    registry.add(
        "srv",
        "find",
        "Find",
        TOOLS.get(0).inputSchema(),
        true,
        new ToolBackend() {
          @Override
          public ToolOutput call(String tool, Map<String, Object> arguments, Duration timeout) {
            return ToolOutput.ok("result text");
          }

          @Override
          public void recover(ConnectionLostException lost) throws ConnectionLostException {
            throw lost;
          }
        },
        false);
    Agent agent =
        new Agent(
            new AnthropicModelClient(client),
            ToolDispatcher.builder(registry).build(),
            new Notebook(),
            Limits.defaults());
    Result result = agent.run("question");

    assertEquals(StopReason.COMPLETED, result.stopReason());
    assertEquals("All done.", result.answer());
    JsonNode history = requests.get(1).get("messages");
    assertEquals("user", history.get(0).get("role").asString());
    assertEquals("assistant", history.get(1).get("role").asString());
    assertEquals("user", history.get(2).get("role").asString());
    JsonNode echoed = history.get(1).get("content");
    assertEquals("thinking", echoed.get(0).get("type").asString());
    assertEquals("sig-abc", echoed.get(0).get("signature").asString());
    assertEquals("tool_use", echoed.get(1).get("type").asString());
    assertEquals("toolu_1", echoed.get(1).get("id").asString());
    assertEquals("x", echoed.get(1).get("input").get("q").asString());
    JsonNode toolResult = history.get(2).get("content").get(0);
    assertEquals("tool_result", toolResult.get("type").asString());
    assertEquals("toolu_1", toolResult.get("tool_use_id").asString());
    assertEquals("result text", toolResult.get("content").asString());
    assertFalse(toolResult.has("is_error"));
  }

  @Test
  void brokenToolJsonBecomesEmptyArgumentsWhichIsWhyEagerStreamingStaysOff() throws Exception {
    // This pins the SDK behaviour behind the decision. Without eager streaming the API validates
    // tool input before sending it, so this stream should not occur.
    responses.add(stream(List.of(toolUse("toolu_1", "}{ not json")), "tool_use", null));
    ModelTurn turn = new AnthropicModelClient(client).complete("s", HI, TOOLS, true);
    assertEquals(Map.of(), turn.toolCalls().get(0).arguments());
    assertFalse(requests.get(0).get("tools").get(0).has("eager_input_streaming"));
  }

  @Test
  void anUnreadableResponseBecomesAModelError() {
    responses.add(new Scripted(200, "text/event-stream", "event: message_start\ndata: {nope\n\n"));
    ModelException thrown =
        assertThrows(
            ModelException.class,
            () -> new AnthropicModelClient(client).complete("s", HI, TOOLS, true));
    assertTrue(thrown.getMessage().contains("model request failed"), thrown.getMessage());
  }

  @Test
  void refusalDetailsAreRead() throws Exception {
    responses.add(
        stream(
            List.of(),
            "refusal",
            map("type", "refusal", "category", "cyber", "explanation", "declined")));
    ModelTurn turn = new AnthropicModelClient(client).complete("s", HI, TOOLS, true);
    assertEquals("refusal", turn.stopReason());
    assertEquals("cyber", turn.refusalCategory());
  }

  @Test
  void apiErrorsBecomeModelErrors() {
    Object[][] cases = {
      {401, "authentication failed"}, {429, "rate limited"}, {500, "API error 500"}
    };
    for (Object[] c : cases) {
      responses.add(error((Integer) c[0]));
      ModelException thrown =
          assertThrows(
              ModelException.class,
              () -> new AnthropicModelClient(client).complete("s", HI, TOOLS, true));
      assertTrue(thrown.getMessage().contains((String) c[1]), thrown.getMessage());
    }
  }

  @Test
  void connectionFailureBecomesAModelError() {
    AnthropicClient unreachable =
        AnthropicOkHttpClient.builder()
            .baseUrl("http://127.0.0.1:9")
            .apiKey("test-key-not-real")
            .maxRetries(0)
            .build();
    ModelException thrown =
        assertThrows(
            ModelException.class,
            () -> new AnthropicModelClient(unreachable).complete("s", HI, TOOLS, true));
    assertTrue(thrown.getMessage().contains("could not reach"), thrown.getMessage());
    unreachable.close();
  }
}
