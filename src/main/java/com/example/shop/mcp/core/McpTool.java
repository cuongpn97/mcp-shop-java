package com.example.shop.mcp.core;

/**
 * One MCP tool: a thin adapter from validated arguments to an existing domain service.
 * Tools never check permissions, scope the shop, count calls or catch errors themselves:
 * the {@code ToolRunner} does all of that for every tool in the same way.
 */
public interface McpTool<A extends Record> {

    ToolSpec<A> spec();

    ToolOutput run(A args, ToolContext ctx);

    /** For {@link ToolSpec#confirm()} tools: what would be written. Must not write. */
    default Object preview(A args, ToolContext ctx) {
        return args;
    }
}
