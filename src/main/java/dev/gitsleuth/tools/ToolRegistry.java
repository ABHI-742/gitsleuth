package dev.gitsleuth.tools;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.gitsleuth.McpConnection;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Tools exposed to the model as {@code <server>__<tool>}, so two servers can reuse a name. */
public final class ToolRegistry {
  private static final int MAX_TOOL_NAME_LENGTH = 64;
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final SchemaRegistry SCHEMAS =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final Schema META_SCHEMA =
      SCHEMAS.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));

  /** A tool could not be registered: a bad schema, or a name that collides. */
  public static final class ToolRegistryException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ToolRegistryException(String message) {
      super(message);
    }
  }

  public record ToolRoute(
      String name,
      String server,
      String remoteName,
      String description,
      Map<String, Object> inputSchema,
      boolean retrySafe,
      boolean local,
      ToolBackend backend,
      Schema validator) {}

  public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {}

  // Sorted, because any change to the order of the tool list breaks prompt caching.
  private final Map<String, ToolRoute> routes = new TreeMap<>();

  public ToolRoute add(
      String server,
      String tool,
      String description,
      Map<String, Object> inputSchema,
      boolean retrySafe,
      ToolBackend backend,
      boolean local) {
    String name = (server + "__" + tool).replaceAll("[^a-zA-Z0-9_-]", "_");
    if (name.length() > MAX_TOOL_NAME_LENGTH) {
      throw new ToolRegistryException(
          "tool name '" + name + "' exceeds " + MAX_TOOL_NAME_LENGTH + " characters");
    }
    if (routes.containsKey(name)) {
      throw new ToolRegistryException("duplicate tool name '" + name + "'");
    }
    JsonNode schemaNode = MAPPER.valueToTree(inputSchema);
    var problems = META_SCHEMA.validate(schemaNode);
    if (!problems.isEmpty()) {
      throw new ToolRegistryException(
          name + " has an invalid schema: " + problems.get(0).getMessage());
    }
    ToolRoute route =
        new ToolRoute(
            name,
            server,
            tool,
            description,
            inputSchema,
            retrySafe,
            local,
            backend,
            SCHEMAS.getSchema(schemaNode));
    routes.put(name, route);
    return route;
  }

  /**
   * Registers everything a connected server exposed.
   *
   * <p>MCP tool annotations default to not read-only and not idempotent, so a tool is only retried
   * when its server declares one of those hints.
   */
  public void addMcpServer(McpConnection connection) {
    for (McpSchema.Tool tool : connection.tools()) {
      McpSchema.ToolAnnotations hints = tool.annotations();
      boolean retrySafe =
          hints != null
              && (Boolean.TRUE.equals(hints.readOnlyHint())
                  || Boolean.TRUE.equals(hints.idempotentHint()));
      add(
          connection.name(),
          tool.name(),
          tool.description() == null ? "" : tool.description(),
          tool.inputSchema(),
          retrySafe,
          connection,
          false);
    }
  }

  public ToolRoute get(String name) {
    return routes.get(name);
  }

  public List<String> names() {
    return new ArrayList<>(routes.keySet());
  }

  public List<ToolDefinition> definitions() {
    return routes.values().stream()
        .map(route -> new ToolDefinition(route.name(), route.description(), route.inputSchema()))
        .toList();
  }

  /** Every schema problem with these arguments, or an empty list. */
  public static List<String> validationProblems(ToolRoute route, Map<String, Object> arguments) {
    JsonNode instance = MAPPER.valueToTree(arguments);
    return route.validator().validate(instance).stream()
        .map(
            error -> {
              String where = error.getInstanceLocation().toString();
              return (where.isEmpty() || "$".equals(where) ? "(root)" : where)
                  + ": "
                  + error.getMessage();
            })
        .toList();
  }
}
