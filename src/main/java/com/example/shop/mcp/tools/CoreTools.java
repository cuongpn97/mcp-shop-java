package com.example.shop.mcp.tools;

import com.example.shop.mcp.core.Area;
import com.example.shop.mcp.core.McpTool;
import com.example.shop.mcp.core.ToolContext;
import com.example.shop.mcp.core.ToolOutput;
import com.example.shop.mcp.core.ToolSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Module 0: context the model needs before anything else. */
public final class CoreTools {

    private CoreTools() {
    }

    @Component
    public static class Whoami implements McpTool<Whoami.Args> {

        public record Args() {
        }

        @Override
        public ToolSpec<Args> spec() {
            return ToolSpec.read("whoami", "Who am I / shop context",
                    "Shop name, currency, timezone, today's date, the user's name and role, which areas this "
                            + "connection may use and whether it may change data. Call this first.",
                    Args.class, Area.CORE, null);
        }

        @Override
        public ToolOutput run(Args args, ToolContext ctx) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("shop", ctx.shop().name());
            out.put("currency", ctx.shop().currency());
            out.put("timezone", ctx.shop().timezone());
            out.put("today", ctx.shop().today().toString());
            out.put("user", ctx.userName());
            out.put("role", ctx.role());
            out.put("areas", ctx.areas().stream().filter(a -> a != Area.CORE).map(Area::key).sorted().toList());
            out.put("can_change_data", ctx.canWrite());
            return ToolOutput.of(out);
        }
    }
}
