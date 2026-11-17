package com.example.shop.mcp.runtime;

import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * One audit row per tool call, written in batches off the request path: the call only enqueues
 * (lock-free, bounded), a flusher inserts up to 500 rows per round-trip every 500 ms and on
 * shutdown. If the queue is full the row is dropped and counted rather than slowing the AI down.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final int BATCH = 500;

    public record Entry(UUID grantId, UUID userId, UUID shopId, String clientId, String tool, Map<String, Object> args,
                        String status, int durationMs, Integer rowCount, String error, String refType,
                        String refCode, Instant at) {
    }

    private final BlockingQueue<Entry> queue = new ArrayBlockingQueue<>(20_000);
    private final AtomicLong dropped = new AtomicLong();
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public AuditLog(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(Entry e) {
        if (!queue.offer(e)) {
            long n = dropped.incrementAndGet();
            if (n % 1000 == 1) {
                log.warn("MCP audit queue full, {} rows dropped so far", n);
            }
        }
    }

    @Scheduled(fixedDelay = 500)
    public void flush() {
        List<Entry> batch = new ArrayList<>(BATCH);
        while (queue.drainTo(batch, BATCH) > 0) {
            write(batch);
            batch.clear();
        }
    }

    @PreDestroy
    void drainOnShutdown() {
        flush();
    }

    private void write(List<Entry> batch) {
        MapSqlParameterSource[] params = batch.stream().map(e -> new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("grant", e.grantId())
                .addValue("user", e.userId())
                .addValue("shop", e.shopId())
                .addValue("client", e.clientId())
                .addValue("tool", e.tool())
                .addValue("args", json.writeValueAsString(e.args()))
                .addValue("status", e.status())
                .addValue("ms", e.durationMs())
                .addValue("rows", e.rowCount())
                .addValue("error", e.error())
                .addValue("refType", e.refType())
                .addValue("refCode", e.refCode())
                .addValue("at", Timestamp.from(e.at()))).toArray(MapSqlParameterSource[]::new);
        try {
            jdbc.batchUpdate("""
                    INSERT INTO mcp_tool_calls (id, grant_id, user_id, shop_id, client_id, tool, args, status,
                                                duration_ms, row_count, error, ref_type, ref_code, created_at)
                    VALUES (:id, :grant, :user, :shop, :client, :tool, CAST(:args AS jsonb), :status,
                            :ms, :rows, :error, :refType, :refCode, :at)""", params);
            jdbc.update("UPDATE mcp_grants SET last_used_at = now() WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", batch.stream().map(Entry::grantId).distinct().toList()));
        } catch (RuntimeException ex) {
            log.error("MCP audit batch of {} rows failed", batch.size(), ex);
        }
    }
}
