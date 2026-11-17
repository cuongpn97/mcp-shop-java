package com.example.shop.mcp.core;

import com.example.shop.domain.ShopService.Shop;
import java.util.Set;
import java.util.UUID;

/** The caller, as resolved by the runner from the access token. Tools only read it. */
public record ToolContext(UUID userId, String userName, String role, Shop shop, UUID grantId, Set<Area> areas,
                          boolean canWrite) {

    public UUID shopId() {
        return shop.id();
    }
}
