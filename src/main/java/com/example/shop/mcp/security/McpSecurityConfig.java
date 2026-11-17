package com.example.shop.mcp.security;

import com.example.shop.mcp.McpProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * /mcp is an OAuth 2.1 protected resource (MCP authorization spec):
 * Bearer JWT with signature, exp, iss, aud == this server's /mcp URL and typ == mcp_at.
 * A 401 carries {@code WWW-Authenticate: Bearer resource_metadata="..."} so clients can
 * discover the authorization server (RFC 9728).
 */
@Configuration
public class McpSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, McpProperties props) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, ex) -> {
            StringBuilder header = new StringBuilder("Bearer resource_metadata=\"")
                    .append(props.resourceMetadataUrl()).append("\", scope=\"").append(McpProperties.READ_SCOPE).append('"');
            if (ex instanceof OAuth2AuthenticationException oauth) {
                header.append(", error=\"").append(oauth.getError().getErrorCode()).append('"');
            }
            response.setHeader("WWW-Authenticate", header.toString());
            response.setHeader("Access-Control-Expose-Headers", "WWW-Authenticate");
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        };

        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/mcp", "/mcp/**").authenticated()
                        .requestMatchers("/public/**", "/.well-known/**", "/dev/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> { }).authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint));
        return http.build();
    }

    @Bean
    JwtDecoder mcpJwtDecoder(McpProperties props) {
        NimbusJwtDecoder decoder;
        if (props.jwkSetUri() != null && !props.jwkSetUri().isBlank()) {
            // Recommended: asymmetric keys published by the authorization server (key rotation via kid).
            decoder = NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri()).build();
        } else {
            decoder = NimbusJwtDecoder.withSecretKey(hmacKey(props)).macAlgorithm(MacAlgorithm.HS256).build();
        }
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.issuer()),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(props.resource())),
                new JwtClaimValidator<String>("typ", McpProperties.ACCESS_TOKEN_TYPE::equals),
                new JwtClaimValidator<String>("grant_id", id -> id != null && !id.isBlank()));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    public static SecretKey hmacKey(McpProperties props) {
        String secret = props.hmacSecret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("app.mcp.hmac-secret must be >= 32 chars (or set app.mcp.jwk-set-uri)");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
