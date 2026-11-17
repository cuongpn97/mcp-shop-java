package com.example.shop.mcp.runtime;

import com.example.shop.mcp.McpProperties;
import com.example.shop.mcp.core.ToolError;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Daily caps per shop with ONE atomic statement per call: increment the day's counter row and read
 * it back. No COUNT(*) over the audit table, no race between check and increment, shared by every
 * instance. The day is the shop's local business day.
 */
@Component
public class UsageQuota {

    private record Usage(int calls, int writes) {
    }

    private final JdbcClient jdbc;
    private final McpProperties props;

    public UsageQuota(JdbcClient jdbc, McpProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Counts this call (and the write, if it is one). Returns a notice near the cap; throws past it. */
    public Optional<String> consume(UUID shopId, LocalDate day, boolean write) {
        Usage u = jdbc.sql("""
                INSERT INTO mcp_usage_daily (shop_id, day, calls, writes) VALUES (:shop, :day, 1, :w)
                ON CONFLICT (shop_id, day) DO UPDATE
                   SET calls = mcp_usage_daily.calls + 1, writes = mcp_usage_daily.writes + EXCLUDED.writes
                RETURNING calls, writes""")
                .param("shop", shopId).param("day", day).param("w", write ? 1 : 0)
                .query(Usage.class).single();
        if (u.calls() > props.dailyCallCap()) {
            throw new ToolError(ToolError.Kind.LIMITED, "The shop used all " + props.dailyCallCap()
                    + " AI calls for today. It resets at midnight shop time.", 3600);
        }
        if (write && u.writes() > props.dailyWriteCap()) {
            throw new ToolError(ToolError.Kind.LIMITED, "The shop used all " + props.dailyWriteCap()
                    + " AI changes for today.", 3600);
        }
        return u.calls() >= props.dailyCallCap() * 0.8
                ? Optional.of("Used " + u.calls() + "/" + props.dailyCallCap() + " AI calls today.")
                : Optional.empty();
    }
}
