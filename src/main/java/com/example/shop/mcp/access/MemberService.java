package com.example.shop.mcp.access;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The user's role in the shop: the SAME source of truth as the app's own screens, so the AI can
 * never do more than the person could do by hand. Cached briefly (tools/list evaluates every tool).
 */
@Service
public class MemberService {

    public record Member(UUID userId, UUID shopId, String displayName, String role, Set<String> permissions) {
        public boolean owner() {
            return "OWNER".equals(role);
        }

        public boolean has(String permission) {
            return permission == null || owner() || permissions.contains(permission);
        }
    }

    private record Key(UUID userId, UUID shopId) {
    }

    private record Row(String displayName, String role, String permissions) {
    }

    private final JdbcClient jdbc;
    private final LoadingCache<Key, Optional<Member>> cache;

    public MemberService(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.cache = Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(Duration.ofSeconds(30)).build(this::load);
    }

    /** Empty when the user is not (or no longer) an active member of the shop. */
    public Optional<Member> find(UUID userId, UUID shopId) {
        return cache.get(new Key(userId, shopId));
    }

    private Optional<Member> load(Key k) {
        return jdbc.sql("""
                SELECT display_name, role, permissions FROM shop_members
                 WHERE user_id = :u AND shop_id = :s AND status = 'active'""")
                .param("u", k.userId()).param("s", k.shopId())
                .query(Row.class)
                .optional()
                .map(r -> new Member(k.userId(), k.shopId(), r.displayName(), r.role(),
                        r.permissions() == null ? Set.of() : Arrays.stream(r.permissions().split(","))
                                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet())));
    }
}
