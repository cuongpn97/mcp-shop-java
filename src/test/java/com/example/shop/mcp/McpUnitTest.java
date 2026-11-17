package com.example.shop.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shop.mcp.access.AccessPolicy;
import com.example.shop.mcp.access.AccessPolicy.Verdict;
import com.example.shop.mcp.access.Grant;
import com.example.shop.mcp.access.MemberService.Member;
import com.example.shop.mcp.core.Area;
import com.example.shop.mcp.core.ToolError;
import com.example.shop.mcp.core.ToolSchemas;
import com.example.shop.mcp.runtime.LlmCompactor;
import com.example.shop.mcp.runtime.RateLimiter;
import com.example.shop.mcp.tools.InventoryTools;
import com.example.shop.mcp.tools.OrderTools;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class McpUnitTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID shop = UUID.randomUUID();

    private Grant grant(String scope, Area... areas) {
        return new Grant(UUID.randomUUID(), UUID.randomUUID(), shop, "c", scope,
                areas.length == 0 ? EnumSet.noneOf(Area.class) : EnumSet.of(areas[0], areas), null, null);
    }

    private Member staff(String... perms) {
        return new Member(UUID.randomUUID(), shop, "Minh", "STAFF", Set.of(perms));
    }

    @Test
    void policyChecksScopeThenAreaThenRole() {
        var adjust = new InventoryTools.AdjustStock(null).spec();
        var listOrders = new OrderTools.ListOrders(null).spec();
        Member owner = new Member(UUID.randomUUID(), shop, "Lan", "OWNER", Set.of());

        assertThat(reason(adjust, grant("shop.read", Area.INVENTORY), owner)).isEqualTo("scope");
        assertThat(reason(adjust, grant("shop.read shop.write", Area.ORDERS), owner)).isEqualTo("area");
        assertThat(reason(adjust, grant("shop.read shop.write", Area.INVENTORY), staff("inventory_read"))).isEqualTo("role");
        assertThat(AccessPolicy.evaluate(adjust, grant("shop.read shop.write", Area.INVENTORY), owner))
                .isInstanceOf(Verdict.Allowed.class);
        assertThat(AccessPolicy.evaluate(listOrders, grant("shop.read", Area.ORDERS), staff("orders_read")))
                .isInstanceOf(Verdict.Allowed.class);
    }

    private static String reason(com.example.shop.mcp.core.ToolSpec<?> spec, Grant g, Member m) {
        return ((Verdict.Denied) AccessPolicy.evaluate(spec, g, m)).reason();
    }

    @Test
    void compactorRemovesSecretsAndInternalIdsAndCapsRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            rows.add(Map.of("sku", "S" + i, "product_id", "x", "api_key", "k", "note", "n".repeat(1000)));
        }
        var c = LlmCompactor.compact(Map.of("items", rows, "password", "p"), json);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) ((Map<String, Object>) c.value()).get("items");
        assertThat(c.truncated()).isTrue();
        assertThat(items).hasSize(LlmCompactor.MAX_ROWS);
        assertThat(items.get(0)).containsOnlyKeys("sku", "note");
        assertThat((String) items.get(0).get("note")).hasSizeLessThan(450);
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaIsGeneratedFromTheArgsRecord() {
        var schema = ToolSchemas.inputSchema(new OrderTools.CreateOrder(null).spec(), json);
        var props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKeys("customer_id", "items", "note", ToolSchemas.CONFIRM_TOKEN);
        assertThat((List<String>) schema.get("required")).contains("items");
        var items = (Map<String, Object>) props.get("items");
        assertThat(items).containsEntry("type", "array").containsEntry("minItems", 1);
        assertThat(schema).containsEntry("additionalProperties", false);
    }

    @Test
    void tokenBucketAllowsBurstThenRefuses() {
        var props = new McpProperties("http://x", null, null, null, 60, 3, 8, Duration.ofMinutes(10), 100, 10,
                Duration.ofSeconds(8), Duration.ofMinutes(5));
        RateLimiter limiter = new RateLimiter(props);
        UUID g = UUID.randomUUID();
        limiter.acquire(g);
        limiter.acquire(g);
        limiter.acquire(g);
        assertThatThrownBy(() -> limiter.acquire(g)).isInstanceOfSatisfying(ToolError.class,
                e -> assertThat(e.retryAfterSeconds()).isPositive());
        limiter.acquire(UUID.randomUUID()); // other connections are not affected
    }
}
