package com.example.shop.mcp.runtime;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotent writes. AI clients retry on timeouts; the same call (grant + tool + arguments) within
 * {@link #REPLAY_MINUTES} returns the first result instead of writing again. The row is claimed
 * atomically BEFORE the write; a failed or stale claim can be taken over.
 */
@Component
public class WriteRequests {

    private static final Logger log = LoggerFactory.getLogger(WriteRequests.class);
    static final int REPLAY_MINUTES = 10;
    static final int STALE_PENDING_MINUTES = 2;

    public sealed interface Claim {
        record Run() implements Claim {
        }

        record Replay(Map<String, Object> result) implements Claim {
        }

        record Busy() implements Claim {
        }
    }

    private record Existing(String status, String result) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public WriteRequests(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Claim claim(UUID grantId, String tool, String key) {
        int claimed = jdbc.sql("""
                INSERT INTO mcp_write_requests (grant_id, idem_key, tool, status) VALUES (:g, :k, :t, 'pending')
                ON CONFLICT (grant_id, idem_key) DO UPDATE
                   SET status = 'pending', result = NULL, created_at = now(), updated_at = now()
                 WHERE mcp_write_requests.status = 'failed'
                    OR (mcp_write_requests.status = 'done'
                        AND mcp_write_requests.created_at < now() - make_interval(mins => :replay))
                    OR (mcp_write_requests.status = 'pending'
                        AND mcp_write_requests.updated_at < now() - make_interval(mins => :stale))""")
                .param("g", grantId).param("k", key).param("t", tool)
                .param("replay", REPLAY_MINUTES).param("stale", STALE_PENDING_MINUTES)
                .update();
        if (claimed == 1) {
            return new Claim.Run();
        }
        Optional<Existing> existing = jdbc.sql(
                        "SELECT status, result::text AS result FROM mcp_write_requests WHERE grant_id = :g AND idem_key = :k")
                .param("g", grantId).param("k", key).query(Existing.class).optional();
        if (existing.isPresent() && "done".equals(existing.get().status()) && existing.get().result() != null) {
            return new Claim.Replay(json.readValue(existing.get().result(), new TypeReference<>() {
            }));
        }
        return new Claim.Busy();
    }

    public void done(UUID grantId, String key, Map<String, Object> result) {
        jdbc.sql("""
                UPDATE mcp_write_requests SET status = 'done', result = CAST(:r AS jsonb), updated_at = now()
                 WHERE grant_id = :g AND idem_key = :k""")
                .param("r", json.writeValueAsString(result)).param("g", grantId).param("k", key).update();
    }

    /** Never throws: bookkeeping must not hide the tool's own error. */
    public void failed(UUID grantId, String key) {
        try {
            jdbc.sql("UPDATE mcp_write_requests SET status = 'failed', updated_at = now() WHERE grant_id = :g AND idem_key = :k")
                    .param("g", grantId).param("k", key).update();
        } catch (RuntimeException e) {
            log.error("Write request {} not marked failed", key, e);
        }
    }
}
