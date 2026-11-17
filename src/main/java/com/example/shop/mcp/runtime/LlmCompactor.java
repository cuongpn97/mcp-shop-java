package com.example.shop.mcp.runtime;

import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes any service result safe and small for a model: drops secret-looking keys, nulls and
 * internal ids, caps lists and long strings, limits depth. Applied to every result, so a service
 * that later gains a sensitive field does not leak it to the AI.
 */
public final class LlmCompactor {

    public static final int MAX_ROWS = 50;
    static final int MAX_STRING = 400;
    static final int MAX_DEPTH = 6;
    private static final Pattern SENSITIVE =
            Pattern.compile("(?i).*(password|secret|token|otp|pin|api_?key|hash|salt|credential).*");
    private static final Pattern INTERNAL = Pattern.compile("(?i)(shop_?id|grant_?id|product_?id|user_?id)");

    public record Compacted(Object value, boolean truncated) {
    }

    private LlmCompactor() {
    }

    public static Compacted compact(Object input, JsonMapper json) {
        // Convert the whole tree (records nested in lists/maps included) to plain maps/lists first.
        Object tree = input == null || input instanceof String || input instanceof Number || input instanceof Boolean
                ? input
                : json.convertValue(input, new TypeReference<Object>() {
                });
        boolean[] truncated = {false};
        return new Compacted(walk(tree, 0, truncated), truncated[0]);
    }

    private static Object walk(Object v, int depth, boolean[] truncated) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            if (s.length() > MAX_STRING) {
                truncated[0] = true;
                return s.substring(0, MAX_STRING) + "…";
            }
            return s;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof TemporalAccessor || v instanceof UUID) {
            return v.toString();
        }
        if (depth >= MAX_DEPTH) {
            truncated[0] = true;
            return null;
        }
        if (v instanceof List<?> list) {
            if (list.size() > MAX_ROWS) {
                truncated[0] = true;
            }
            List<Object> out = new ArrayList<>();
            for (Object item : list.subList(0, Math.min(list.size(), MAX_ROWS))) {
                Object c = walk(item, depth + 1, truncated);
                if (c != null) {
                    out.add(c);
                }
            }
            return out;
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (SENSITIVE.matcher(key).matches() || INTERNAL.matcher(key).matches()) {
                    continue;
                }
                Object c = walk(e.getValue(), depth + 1, truncated);
                if (c != null) {
                    out.put(key, c);
                }
            }
            return out;
        }
        return String.valueOf(v);
    }
}
