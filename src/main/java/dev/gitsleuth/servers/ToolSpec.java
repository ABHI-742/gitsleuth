package dev.gitsleuth.servers;

import dev.gitsleuth.servers.GitRepo.GitException;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Small helpers for declaring read-only tools with hand-written JSON Schemas. */
final class ToolSpec {
  private static final Logger LOG = LoggerFactory.getLogger(ToolSpec.class);
  private static final JsonMapper OUTPUT_MAPPER =
      JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
  private static final ToolAnnotations READ_ONLY =
      ToolAnnotations.builder().readOnlyHint(true).idempotentHint(true).build();

  private ToolSpec() {}

  /** A failure the model should read, returned as {@code isError} rather than a protocol error. */
  static final class ToolFailure extends Exception {
    private static final long serialVersionUID = 1L;

    ToolFailure(String message) {
      super(message);
    }
  }

  @FunctionalInterface
  interface Handler {
    Object handle(Args args) throws GitException, ToolFailure;
  }

  /** Typed access to a call's arguments, with the defaults the schema advertises. */
  record Args(Map<String, Object> values) {
    String string(String name, String fallback) {
      return values.get(name) instanceof String value ? value : fallback;
    }

    String required(String name) throws ToolFailure {
      if (values.get(name) instanceof String value) {
        return value;
      }
      throw new ToolFailure(name + " is required");
    }

    int integer(String name, int fallback) {
      return values.get(name) instanceof Number value ? value.intValue() : fallback;
    }

    int requiredInteger(String name) throws ToolFailure {
      if (values.get(name) instanceof Number value) {
        return value.intValue();
      }
      throw new ToolFailure(name + " is required");
    }

    boolean bool(String name, boolean fallback) {
      return values.get(name) instanceof Boolean value ? value : fallback;
    }
  }

  static Map<String, Object> schema(List<String> required, Map<String, Object> properties) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("required", required);
    schema.put("additionalProperties", false);
    return schema;
  }

  static Map<String, Object> string() {
    return Map.of("type", "string");
  }

  static Map<String, Object> string(String defaultValue) {
    return Map.of("type", "string", "default", defaultValue);
  }

  static Map<String, Object> integer() {
    return Map.of("type", "integer");
  }

  static Map<String, Object> integer(int defaultValue) {
    return Map.of("type", "integer", "default", defaultValue);
  }

  static Map<String, Object> bool(boolean defaultValue) {
    return Map.of("type", "boolean", "default", defaultValue);
  }

  static int clamp(int value, int low, int high) {
    return Math.max(low, Math.min(value, high));
  }

  static SyncToolSpecification tool(
      String name, String description, Map<String, Object> schema, Handler handler) {
    McpSchema.Tool tool =
        McpSchema.Tool.builder(name, schema)
            .description(description)
            .annotations(READ_ONLY)
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) -> {
              Map<String, Object> arguments =
                  request.arguments() == null ? Map.of() : request.arguments();
              return invoke(name, handler, new Args(arguments));
            })
        .build();
  }

  private static CallToolResult invoke(String name, Handler handler, Args args) {
    try {
      Object output = handler.handle(args);
      Map<String, Object> structured =
          OUTPUT_MAPPER.convertValue(output, new TypeReference<Map<String, Object>>() {});
      return CallToolResult.builder()
          .content(List.of(text(OUTPUT_MAPPER.writeValueAsString(output))))
          .structuredContent(structured)
          .build();
    } catch (GitException | ToolFailure e) {
      return failure(e.getMessage());
    } catch (RuntimeException e) {
      // A crash in a tool: the details go to the log, not to the model.
      LOG.error("tool {} crashed", name, e);
      return failure("Error executing tool " + name);
    }
  }

  private static CallToolResult failure(String message) {
    return CallToolResult.builder().content(List.of(text(message))).isError(true).build();
  }

  private static TextContent text(String value) {
    return TextContent.builder(value).build();
  }
}
