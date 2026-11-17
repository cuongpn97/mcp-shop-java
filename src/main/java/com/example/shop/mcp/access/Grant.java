package com.example.shop.mcp.access;

import com.example.shop.mcp.McpProperties;
import com.example.shop.mcp.core.Area;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/**
 * One consent: this user let this AI client use these areas of this shop, read-only or with writes.
 * Revoking the grant invalidates every token issued for it.
 */
public record Grant(
        UUID id,
        UUID userId,
        UUID shopId,
        String clientId,
        String scope,
        Set<Area> areas,
        OffsetDateTime suspendedUntil,
        String suspendReason) {

    public boolean canWrite() {
        return scope != null && Arrays.asList(scope.split("\\s+")).contains(McpProperties.WRITE_SCOPE);
    }

    public boolean suspended() {
        return suspendedUntil != null && suspendedUntil.isAfter(OffsetDateTime.now());
    }
}
