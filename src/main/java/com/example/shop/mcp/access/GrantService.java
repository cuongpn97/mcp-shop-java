package com.example.shop.mcp.access;

import com.example.shop.mcp.core.Area;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Grants, cached for a short time: every MCP request needs the grant (filter of tools/list and every
 * tools/call), so this removes one query per request. Revoke / suspend on this instance evict
 * immediately; other instances see the change within {@link #TTL}.
 */
@Service
public class GrantService {

    static final Duration TTL = Duration.ofSeconds(30);

    private record Row(UUID id, UUID userId, UUID shopId, String clientId, String scope, String areas,
                       OffsetDateTime suspendedUntil, String suspendReason) {
    }

    private final JdbcClient jdbc;
    private final LoadingCache<UUID, Optional<Grant>> cache;

    public GrantService(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.cache = Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(TTL).build(this::load);
    }

    public Optional<Grant> findActive(UUID id) {
        return id == null ? Optional.empty() : cache.get(id);
    }

    public void suspend(UUID id, Duration duration, String reason) {
        jdbc.sql("UPDATE mcp_grants SET suspended_until = now() + make_interval(secs => :s), suspend_reason = :r WHERE id = :id")
                .param("s", duration.toSeconds()).param("r", reason).param("id", id).update();
        cache.invalidate(id);
    }

    public void revoke(UUID id) {
        jdbc.sql("UPDATE mcp_grants SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL").param("id", id).update();
        cache.invalidate(id);
    }

    private Optional<Grant> load(UUID id) {
        return jdbc.sql("""
                SELECT id, user_id, shop_id, client_id, scope, areas, suspended_until, suspend_reason
                  FROM mcp_grants WHERE id = :id AND revoked_at IS NULL""")
                .param("id", id)
                .query(Row.class)
                .optional()
                .map(r -> new Grant(r.id(), r.userId(), r.shopId(), r.clientId(), r.scope(), Area.parse(r.areas()),
                        r.suspendedUntil(), r.suspendReason()));
    }
}
