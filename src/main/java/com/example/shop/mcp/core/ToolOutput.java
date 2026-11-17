package com.example.shop.mcp.core;

import java.util.List;

/**
 * @param data     any JSON-serialisable value; compacted before it reaches the model
 * @param rowCount rows returned (audit)
 * @param refType  write tools: kind of document written
 * @param refCode  write tools: its human code (order DH1024, SKU...)
 */
public record ToolOutput(Object data, Integer rowCount, String refType, String refCode) {

    public static ToolOutput of(Object data) {
        return new ToolOutput(data, null, null, null);
    }

    public static ToolOutput rows(List<?> rows) {
        return new ToolOutput(rows, rows.size(), null, null);
    }

    public static ToolOutput written(Object data, String refType, String refCode) {
        return new ToolOutput(data, null, refType, refCode);
    }
}
