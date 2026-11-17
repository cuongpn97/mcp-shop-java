package com.example.shop.mcp.runtime;

import com.example.shop.mcp.McpProperties;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * confirm_token for two-step writes: stored as a hash, bound to grant + tool + exact arguments,
 * single use, short-lived. Changing any argument after the preview makes the token useless.
 */
@Component
public class Confirmations {

    private final JdbcClient jdbc;
    private final McpProperties props;

    public Confirmations(JdbcClient jdbc, McpProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public String issue(UUID grantId, String tool, String argsHash) {
        String token = "cf_" + Hashing.randomToken(18);
        jdbc.sql("""
                INSERT INTO mcp_confirmations (token_hash, grant_id, tool, args_hash, expires_at)
                VALUES (:h, :g, :t, :a, now() + make_interval(secs => :ttl))""")
                .param("h", Hashing.sha256(token)).param("g", grantId).param("t", tool).param("a", argsHash)
                .param("ttl", props.confirmationTtl().toSeconds())
                .update();
        return token;
    }

    public boolean consume(UUID grantId, String tool, String argsHash, String token) {
        return jdbc.sql("""
                UPDATE mcp_confirmations SET used_at = now()
                 WHERE token_hash = :h AND grant_id = :g AND tool = :t AND args_hash = :a
                   AND used_at IS NULL AND expires_at > now()""")
                .param("h", Hashing.sha256(token)).param("g", grantId).param("t", tool).param("a", argsHash)
                .update() == 1;
    }
}
