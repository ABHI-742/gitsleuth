package dev.gitsleuth.model;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaFallbacksParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolChoiceAuto;
import com.anthropic.models.beta.messages.BetaToolChoiceNone;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.anthropic.models.beta.messages.MessageCreateParams;
import dev.gitsleuth.tools.ToolDispatcher.ToolCall;
import dev.gitsleuth.tools.ToolRegistry.ToolDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Messages API through the official SDK, streamed so long turns cannot hit an HTTP timeout. */
public final class AnthropicModelClient implements ModelClient {
  public static final String DEFAULT_MODEL = "claude-opus-5-5";
  public static final long DEFAULT_MAX_TOKENS = 64_000;
  public static final String DEFAULT_EFFORT = "high";
  private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

  private final AnthropicClient client;
  private final String model;
  private final long maxTokens;
  private final String effort;
  private final boolean useFallbacks;

  public AnthropicModelClient(AnthropicClient client) {
    this(client, DEFAULT_MODEL, DEFAULT_MAX_TOKENS, DEFAULT_EFFORT, true);
  }

  public AnthropicModelClient(
      AnthropicClient client, String model, long maxTokens, String effort, boolean useFallbacks) {
    this.client = client;
    this.model = model;
    this.maxTokens = maxTokens;
    this.effort = effort;
    this.useFallbacks = useFallbacks;
  }

  MessageCreateParams request(
      String system, List<Message> messages, List<ToolDefinition> tools, boolean allowTools) {
    MessageCreateParams.Builder builder =
        MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .system(system)
            .messages(messages.stream().map(AnthropicModelClient::toParam).toList())
            .thinking(BetaThinkingConfigAdaptive.builder().build())
            .outputConfig(
                BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(effort)).build())
            .cacheControl(BetaCacheControlEphemeral.builder().build());
    if (allowTools) {
      builder.toolChoice(BetaToolChoiceAuto.builder().build());
    } else {
      builder.toolChoice(BetaToolChoiceNone.builder().build());
    }
    for (ToolDefinition tool : tools) {
      builder.addTool(toTool(tool));
    }
    if (useFallbacks) {
      builder.addBeta(FALLBACK_BETA).fallbacks(BetaFallbacksParam.ofDefault());
    }
    return builder.build();
  }

  // eager_input_streaming stays off. With it on, this SDK turns broken tool JSON into an empty
  // object without raising, and a tool with no required fields would then run on its defaults.
  private static BetaTool toTool(ToolDefinition tool) {
    BetaTool.InputSchema.Builder schema = BetaTool.InputSchema.builder();
    for (Map.Entry<String, Object> field : tool.inputSchema().entrySet()) {
      schema.putAdditionalProperty(field.getKey(), JsonValue.from(field.getValue()));
    }
    return BetaTool.builder()
        .name(tool.name())
        .description(tool.description())
        .inputSchema(schema.build())
        .build();
  }

  private static BetaMessageParam toParam(Message message) {
    return switch (message) {
      case Message.UserText user -> BetaMessageParam.ofUser(user.text());
      case Message.Assistant assistant -> ((BetaMessage) assistant.turn().raw()).toParam();
      case Message.ToolResults results -> {
        List<BetaContentBlockParam> blocks = new ArrayList<>();
        for (ToolResult result : results.results()) {
          BetaToolResultBlockParam.Builder block =
              BetaToolResultBlockParam.builder()
                  .toolUseId(result.toolUseId())
                  .content(result.content());
          if (result.isError()) {
            block.isError(true);
          }
          blocks.add(BetaContentBlockParam.ofToolResult(block.build()));
        }
        yield BetaMessageParam.ofUserOfContentBlockParams(blocks);
      }
    };
  }

  @Override
  public ModelTurn complete(
      String system, List<Message> messages, List<ToolDefinition> tools, boolean allowTools)
      throws ModelException {
    MessageCreateParams params = request(system, messages, tools, allowTools);
    BetaMessageAccumulator accumulator = BetaMessageAccumulator.create();
    try (StreamResponse<BetaRawMessageStreamEvent> stream =
        client.beta().messages().createStreaming(params)) {
      stream.stream().forEach(accumulator::accumulate);
      return toTurn(accumulator.message());
    } catch (UnauthorizedException e) {
      throw new ModelException("authentication failed: " + e.getMessage(), e);
    } catch (RateLimitException e) {
      throw new ModelException("rate limited after SDK retries: " + e.getMessage(), e);
    } catch (AnthropicServiceException e) {
      throw new ModelException("API error " + e.statusCode() + ": " + e.getMessage(), e);
    } catch (AnthropicIoException e) {
      throw new ModelException("could not reach the API: " + e.getMessage(), e);
    } catch (AnthropicException e) {
      // Missing credentials, or a response the SDK could not read.
      throw new ModelException("model request failed: " + e.getMessage(), e);
    }
  }

  private static ModelTurn toTurn(BetaMessage message) {
    StringBuilder text = new StringBuilder();
    List<ToolCall> calls = new ArrayList<>();
    for (BetaContentBlock block : message.content()) {
      block.text().ifPresent(value -> text.append(value.text()));
      block.toolUse().ifPresent(use -> calls.add(toCall(use)));
    }
    var usage = message.usage();
    return new ModelTurn(
        message.stopReason().map(reason -> reason.asString()).orElse("end_turn"),
        message,
        text.toString(),
        calls,
        new Usage(
            usage.inputTokens(),
            usage.outputTokens(),
            usage.cacheReadInputTokens().orElse(0L),
            usage.cacheCreationInputTokens().orElse(0L)),
        message
            .stopDetails()
            .flatMap(details -> details.category())
            .map(category -> category.asString())
            .orElse(null));
  }

  @SuppressWarnings("unchecked")
  private static ToolCall toCall(BetaToolUseBlock use) {
    return new ToolCall(use.id(), use.name(), use._input().convert(Map.class));
  }
}
