package com.example.shop.mcp.runtime;

import com.example.shop.mcp.McpProperties;
import com.example.shop.mcp.access.GrantService;
import com.example.shop.mcp.core.ToolError;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stops an agent stuck in a loop: the same tool with the same arguments more than
 * {@code loopLimit} times within 2 minutes pauses the whole grant for {@code suspendFor}.
 * The pause is stored on the grant, so it survives restarts and applies on every instance.
 */
@Component
public class LoopGuard {

    private static final Logger log = LoggerFactory.getLogger(LoopGuard.class);

    private final Cache<String, AtomicInteger> recent =
            Caffeine.newBuilder().maximumSize(200_000).expireAfterWrite(Duration.ofMinutes(2)).build();
    private final GrantService grants;
    private final McpProperties props;

    public LoopGuard(GrantService grants, McpProperties props) {
        this.grants = grants;
        this.props = props;
    }

    public void check(UUID grantId, String argsHash) {
        int count = recent.get(grantId + ":" + argsHash, k -> new AtomicInteger()).incrementAndGet();
        if (count > props.loopLimit()) {
            String reason = "the same call was repeated " + count + " times in 2 minutes";
            grants.suspend(grantId, props.suspendFor(), reason);
            log.warn("MCP grant {} paused: {}", grantId, reason);
            throw new ToolError(ToolError.Kind.LIMITED, "Connection paused for " + props.suspendFor().toMinutes()
                    + " minutes because " + reason + ". Stop retrying; tell the user what you found so far.",
                    (int) props.suspendFor().toSeconds());
        }
    }
}
