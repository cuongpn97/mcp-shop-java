package com.example.shop.mcp.security;

import com.example.shop.mcp.McpProperties;
import com.example.shop.mcp.access.Grant;
import com.example.shop.mcp.access.GrantService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEV ONLY: mints an access token for an existing grant, so /mcp can be tried with curl or the MCP
 * Inspector without an authorization server. The "dev" profile must never run in production.
 */
@RestController
@Profile("dev")
public class DevTokenController {

    private final McpProperties props;
    private final GrantService grants;

    public DevTokenController(McpProperties props, GrantService grants) {
        this.props = props;
        this.grants = grants;
    }

    @PostMapping("/dev/mcp-token/{grantId}")
    public ResponseEntity<Map<String, Object>> mint(@PathVariable UUID grantId) {
        Grant grant = grants.findActive(grantId).orElse(null);
        if (grant == null) {
            return ResponseEntity.notFound().build();
        }
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(McpSecurityConfig.hmacKey(props)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .audience(List.of(props.resource()))
                .subject(grant.userId().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("typ", McpProperties.ACCESS_TOKEN_TYPE)
                .claim("grant_id", grant.id().toString())
                .claim("scope", grant.scope())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return ResponseEntity.ok(Map.of("access_token", token, "token_type", "Bearer", "expires_in", 3600));
    }
}
