package com.example.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shop.mcp.core.Area;
import com.example.shop.mcp.core.McpTool;
import com.example.shop.mcp.core.ToolContext;
import com.example.shop.mcp.core.ToolOutput;
import com.example.shop.mcp.core.ToolSpec;
import com.example.shop.mcp.runtime.AuditLog;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boots the app on a real PostgreSQL with the demo shop and talks to /mcp over HTTP exactly like an
 * MCP client (JSON-RPC tools/list and tools/call with a Bearer token).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.mcp.statement-timeout=1s")
@ActiveProfiles("dev")
class McpEndToEndTest {

    static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
    }

    @AfterAll
    static void stop() throws IOException {
        PG.close();
    }

    /** A deliberately slow tool to prove that Postgres really cancels the query at the deadline. */
    @TestConfiguration
    static class SlowToolConfig {
        public record Args() {
        }

        @Bean
        McpTool<Args> slowTool(JdbcClient jdbc) {
            return new McpTool<>() {
                @Override
                public ToolSpec<Args> spec() {
                    return ToolSpec.read("debug_sleep", "Sleep", "Test only.", Args.class, Area.CORE, null);
                }

                @Override
                public ToolOutput run(Args args, ToolContext ctx) {
                    jdbc.sql("SELECT pg_sleep(5)").query().listOfRows();
                    return ToolOutput.of("never");
                }
            };
        }
    }

    static final String OWNER = "00000000-0000-0000-0000-00000000c001";
    static final String STAFF = "00000000-0000-0000-0000-00000000c002";
    static final String REPORTS_ONLY = "00000000-0000-0000-0000-00000000c003";

    @LocalServerPort
    int port;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    AuditLog audit;
    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void unauthenticatedCallGetsDiscoveryChallenge() throws Exception {
        HttpResponse<String> res = mcp(null, Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"));
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.headers().firstValue("WWW-Authenticate").orElse(""))
                .contains("resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource/mcp\"");
    }

    @Test
    void toolListDependsOnScopeAreasAndRole() throws Exception {
        assertThat(tools(OWNER)).containsExactlyInAnyOrder("whoami", "debug_sleep", "search_products", "get_product",
                "find_customers", "create_customer", "list_orders", "get_order", "create_order", "cancel_order",
                "get_low_stock", "adjust_stock", "sales_summary", "top_products");
        // Staff: only catalog + orders opened, and the role has no inventory/report keys.
        assertThat(tools(STAFF)).containsExactlyInAnyOrder("whoami", "debug_sleep", "search_products", "get_product",
                "list_orders", "get_order", "create_order", "cancel_order");
        // Owner, but this connection only opened reports, read-only.
        assertThat(tools(REPORTS_ONLY)).containsExactlyInAnyOrder("whoami", "debug_sleep", "sales_summary", "top_products");
    }

    @Test
    void readToolsReturnCompactDataWithNotices() throws Exception {
        String owner = token(OWNER);
        Map<String, Object> me = result(call(owner, "whoami", Map.of()));
        assertThat(me).containsEntry("shop", "Lan's Fashion").containsEntry("currency", "VND")
                .containsEntry("can_change_data", true);

        List<Map<String, Object>> tees = result(call(owner, "search_products", Map.of("query", "tee")));
        assertThat(tees).extracting(p -> p.get("sku")).containsExactlyInAnyOrder("TEE-BLK-M", "TEE-WHT-M");

        // Prompt-injection text stored in a customer note comes back as data, flagged as such.
        Map<String, Object> found = structured(call(owner, "find_customers", Map.of("query", "binh")));
        assertThat(found).containsKey("data_notice");

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        Map<String, Object> summary = result(call(owner, "sales_summary",
                Map.of("from", today.minusDays(6).toString(), "to", today.toString())));
        assertThat(summary).containsKeys("totals", "by_day", "previous");

        List<Map<String, Object>> low = result(call(owner, "get_low_stock", Map.of()));
        assertThat(low).extracting(p -> p.get("sku")).containsExactly("TEE-WHT-M", "CAP-KHK"); // biggest shortfall first
    }

    @Test
    void createAndCancelOrderNeedPreviewThenToken() throws Exception {
        String staff = token(STAFF);
        Map<String, Object> args = Map.of("customer_id", "00000000-0000-0000-0000-00000000d001",
                "items", List.of(Map.of("sku", "jean-slim-30", "qty", 2)), "note", "deliver after 6pm");

        Map<String, Object> preview = structured(call(staff, "create_order", args));
        assertThat(preview).containsKeys("preview", "confirm_token");
        assertThat(onHand("JEAN-SLIM-30")).isEqualTo(12);   // nothing written

        Map<String, Object> tampered = with(args, "items", List.of(Map.of("sku", "JEAN-SLIM-30", "qty", 9)));
        tampered.put("confirm_token", preview.get("confirm_token"));
        assertThat(call(staff, "create_order", tampered).get("isError")).isEqualTo(true);

        Map<String, Object> order = result(call(staff, "create_order", with(args, "confirm_token", preview.get("confirm_token"))));
        String code = (String) order.get("code");
        assertThat(order).containsEntry("status", "pending");
        assertThat(onHand("JEAN-SLIM-30")).isEqualTo(10);

        // The model retries the same order: replayed, stock taken only once.
        Map<String, Object> again = structured(call(staff, "create_order",
                with(args, "confirm_token", structured(call(staff, "create_order", args)).get("confirm_token"))));
        assertThat(again).containsKey("replayed");
        assertThat(onHand("JEAN-SLIM-30")).isEqualTo(10);

        Map<String, Object> cancelArgs = Map.of("code", code, "reason", "customer changed mind");
        Object cancelToken = structured(call(staff, "cancel_order", cancelArgs)).get("confirm_token");
        Map<String, Object> cancelled = result(call(staff, "cancel_order", with(cancelArgs, "confirm_token", cancelToken)));
        assertThat(cancelled).containsEntry("status", "cancelled");
        assertThat(onHand("JEAN-SLIM-30")).isEqualTo(12);
    }

    @Test
    void refusalsAreExplainedToTheModel() throws Exception {
        // Hidden from staff, and refused if called anyway.
        Map<String, Object> denied = call(token(STAFF), "adjust_stock", Map.of("sku", "CAP-KHK", "delta", 5, "reason", "x"));
        assertThat(denied.get("isError")).isEqualTo(true);
        assertThat((String) structured(denied).get("error")).contains("inventory area was not opened");

        // Business rule in the preview: stock cannot go below zero.
        Map<String, Object> negative = call(token(OWNER), "adjust_stock", Map.of("sku", "CAP-KHK", "delta", -5, "reason", "lost"));
        assertThat((String) structured(negative).get("error")).contains("below zero");

        // Validation from the args record.
        Map<String, Object> invalid = call(token(OWNER), "sales_summary", Map.of("from", "yesterday", "to", "2026-01-01"));
        assertThat((String) structured(invalid).get("error")).contains("from");
    }

    @Test
    void postgresCancelsSlowQueries() throws Exception {
        long t0 = System.currentTimeMillis();
        Map<String, Object> res = call(token(REPORTS_ONLY), "debug_sleep", Map.of());
        assertThat(System.currentTimeMillis() - t0).isLessThan(4000);
        assertThat((String) structured(res).get("error")).contains("took too long");
    }

    @Test
    void loopingAgentPausesItsOwnConnectionAndCallsAreAudited() throws Exception {
        jdbc.sql("""
                INSERT INTO mcp_grants (id, user_id, shop_id, client_id, scope, areas)
                VALUES ('00000000-0000-0000-0000-00000000c009', '00000000-0000-0000-0000-00000000a001',
                        '00000000-0000-0000-0000-000000005001', 'loop-client', 'shop.read', 'catalog')""").update();
        String t = token("00000000-0000-0000-0000-00000000c009");
        for (int i = 0; i < 8; i++) {
            assertThat(call(t, "get_product", Map.of("sku", "SOCK-3P")).get("isError")).isEqualTo(false);
        }
        Map<String, Object> ninth = structured(call(t, "get_product", Map.of("sku", "SOCK-3P")));
        assertThat(ninth).containsKey("retry_after_seconds");
        // The whole connection is paused, not just that query.
        assertThat((String) structured(call(t, "whoami", Map.of())).get("error")).contains("paused");

        audit.flush();
        int rows = jdbc.sql("SELECT count(*) FROM mcp_tool_calls WHERE client_id = 'loop-client'").query(Integer.class).single();
        assertThat(rows).isEqualTo(10);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────────────────

    private int onHand(String sku) {
        return jdbc.sql("SELECT s.on_hand FROM stock s JOIN products p ON p.id = s.product_id WHERE p.sku = :sku")
                .param("sku", sku).query(Integer.class).single();
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> m = new HashMap<>(base);
        m.put(key, value);
        return m;
    }

    private String token(String grantId) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/dev/mcp-token/" + grantId))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        return (String) body(res).get("access_token");
    }

    private List<String> tools(String grantId) throws Exception {
        Map<String, Object> res = body(mcp(token(grantId), Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list")));
        Map<String, Object> result = cast(res.get("result"));
        List<Map<String, Object>> tools = cast(result.get("tools"));
        return tools.stream().map(t -> (String) t.get("name")).toList();
    }

    private Map<String, Object> call(String token, String tool, Map<String, Object> args) throws Exception {
        HttpResponse<String> res = mcp(token, Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", args)));
        assertThat(res.statusCode()).isEqualTo(200);
        return cast(body(res).get("result"));
    }

    private Map<String, Object> structured(Map<String, Object> callResult) {
        return cast(callResult.get("structuredContent"));
    }

    private <T> T result(Map<String, Object> callResult) {
        assertThat(callResult.get("isError")).as(String.valueOf(callResult)).isEqualTo(false);
        return cast(structured(callResult).get("result"));
    }

    private HttpResponse<String> mcp(String token, Map<String, Object> message) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message)));
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> body(HttpResponse<String> res) {
        return json.readValue(res.body(), new TypeReference<>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) {
        return (T) o;
    }
}
