package com.example.shop.mcp;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Remote MCP connector settings.
 *
 * @param publicBaseUrl       public origin of this server; the token audience is {@code <publicBaseUrl>/mcp}
 * @param authorizationServer issuer of access tokens (Keycloak, Spring Authorization Server...)
 * @param jwkSetUri           JWKS of the authorization server (preferred: asymmetric keys, rotation by kid)
 * @param hmacSecret          HS256 secret, only when no JWKS (dev / single-app setups)
 * @param ratePerMinute       sustained calls per grant per minute (token bucket, in memory)
 * @param burst               bucket capacity: short bursts allowed above the sustained rate
 * @param loopLimit           identical calls (same tool + args) within 2 minutes before the grant is paused
 * @param suspendFor          pause length after a loop
 * @param dailyCallCap        calls per shop per business day
 * @param dailyWriteCap       executed writes per shop per business day
 * @param statementTimeout    Postgres statement_timeout for each tool (the DB really cancels the query)
 * @param confirmationTtl     lifetime of a confirm_token
 */
@ConfigurationProperties("app.mcp")
public record McpProperties(
        String publicBaseUrl,
        String authorizationServer,
        String jwkSetUri,
        String hmacSecret,
        @DefaultValue("60") int ratePerMinute,
        @DefaultValue("20") int burst,
        @DefaultValue("8") int loopLimit,
        @DefaultValue("10m") Duration suspendFor,
        @DefaultValue("2000") int dailyCallCap,
        @DefaultValue("200") int dailyWriteCap,
        @DefaultValue("8s") Duration statementTimeout,
        @DefaultValue("5m") Duration confirmationTtl) {

    public static final String READ_SCOPE = "shop.read";
    public static final String WRITE_SCOPE = "shop.write";
    public static final String ACCESS_TOKEN_TYPE = "mcp_at";

    public String resource() {
        return trim(publicBaseUrl) + "/mcp";
    }

    public String resourceMetadataUrl() {
        return trim(publicBaseUrl) + "/.well-known/oauth-protected-resource/mcp";
    }

    public String issuer() {
        return authorizationServer == null || authorizationServer.isBlank() ? trim(publicBaseUrl) : trim(authorizationServer);
    }

    private static String trim(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }
}
