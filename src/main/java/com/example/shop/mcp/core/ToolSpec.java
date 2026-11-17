package com.example.shop.mcp.core;

/**
 * Static description of a tool.
 *
 * @param name        snake_case tool name
 * @param title       short human title
 * @param description what the model reads to pick the tool: when to use it, units, limits
 * @param argsType    arguments record; its JSON Schema is generated from it
 * @param area        business area (must be opened by the user at consent)
 * @param permission  role permission key needed, same as the equivalent app screen; null = none
 * @param write       needs the shop.write scope
 * @param destructive cannot be undone by a simple edit (MCP destructiveHint)
 * @param confirm     server-enforced two-step call: preview + confirm_token, then execute
 */
public record ToolSpec<A extends Record>(
        String name,
        String title,
        String description,
        Class<A> argsType,
        Area area,
        String permission,
        boolean write,
        boolean destructive,
        boolean confirm) {

    public static <A extends Record> ToolSpec<A> read(String name, String title, String description, Class<A> args,
                                                      Area area, String permission) {
        return new ToolSpec<>(name, title, description, args, area, permission, false, false, false);
    }

    /** A write that is easy to undo (e.g. create a customer): no confirmation round-trip. */
    public static <A extends Record> ToolSpec<A> write(String name, String title, String description, Class<A> args,
                                                       Area area, String permission) {
        return new ToolSpec<>(name, title, description, args, area, permission, true, false, false);
    }

    /** A write with consequences (stock, money, cancellation): preview + confirm_token required. */
    public static <A extends Record> ToolSpec<A> confirmedWrite(String name, String title, String description,
                                                                Class<A> args, Area area, String permission,
                                                                boolean destructive) {
        return new ToolSpec<>(name, title, description, args, area, permission, true, destructive, true);
    }
}
