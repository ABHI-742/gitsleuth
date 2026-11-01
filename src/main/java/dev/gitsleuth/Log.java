package dev.gitsleuth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Structured logging: one JSON object per event, on stderr. Off unless enabled. */
public final class Log {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static volatile boolean enabled;

  private Log() {}

  public static void enable() {
    enabled = true;
  }

  /** Logs an event with alternating field names and values. */
  public static void event(String level, String logger, String event, Object... fields) {
    if (!enabled) {
      return;
    }
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("ts", Instant.now().toString());
    entry.put("level", level);
    entry.put("logger", logger);
    entry.put("event", event);
    for (int i = 0; i + 1 < fields.length; i += 2) {
      entry.put(String.valueOf(fields[i]), fields[i + 1]);
    }
    System.err.println(MAPPER.writeValueAsString(entry));
  }

  public static void info(String logger, String event, Object... fields) {
    event("info", logger, event, fields);
  }
}
