package com.example.shop.mcp.security;

import com.example.shop.mcp.McpProperties;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RFC 9728 protected-resource metadata. The authorization server itself (authorize, token,
 * PKCE S256, dynamic client registration / client ID metadata documents, refresh-token rotation,
 * consent page) should be Spring Authorization Server or Keycloak rather than hand-written.
 */
@RestController
public class WellKnownController {

    private final McpProperties props;

    public WellKnownController(McpProperties props) {
        this.props = props;
    }

    @GetMapping({"/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-protected-resource"})
    public Map<String, Object> protectedResource() {
        return Map.of(
                "resource", props.resource(),
                "authorization_servers", List.of(props.issuer()),
                "scopes_supported", List.of(McpProperties.READ_SCOPE, McpProperties.WRITE_SCOPE),
                "bearer_methods_supported", List.of("header"),
                "resource_name", "Mini Shop");
    }
}
