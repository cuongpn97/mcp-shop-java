package com.example.shop.mcp.runtime;

import com.example.shop.mcp.McpProperties;
import com.example.shop.mcp.core.ToolError;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Per-grant token bucket in memory: O(1), no database round-trip on the hot path, exact under
 * parallel bursts (unlike counting audit rows that are written asynchronously).
 * With several instances each one enforces the rate on its own; switch to bucket4j-redis
 * (or another distributed backend) when a strict global rate is needed.
 */
@Component
public class RateLimiter {

    private final Cache<UUID, Bucket> buckets;
    private final McpProperties props;

    public RateLimiter(McpProperties props) {
        this.props = props;
        this.buckets = Caffeine.newBuilder().maximumSize(100_000).expireAfterAccess(Duration.ofMinutes(10)).build();
    }

    public void acquire(UUID grantId) {
        Bucket bucket = buckets.get(grantId, id -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(props.burst())
                        .refillGreedy(props.ratePerMinute(), Duration.ofMinutes(1))
                        .build())
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long wait = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
            throw new ToolError(ToolError.Kind.LIMITED, "Too many calls on this connection (max "
                    + props.ratePerMinute() + "/min). Wait a moment.", (int) wait);
        }
    }
}
