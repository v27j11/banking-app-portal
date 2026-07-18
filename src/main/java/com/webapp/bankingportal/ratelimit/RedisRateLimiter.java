package com.webapp.bankingportal.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Fixed-window rate limiter backed by Redis {@code INCR}/{@code EXPIRE}.
 *
 * <p>
 * Using Redis (rather than an in-memory counter) means the limit is enforced
 * correctly even when the API is scaled horizontally across multiple
 * instances - all instances share the same counters.
 *
 * <p>
 * The increment-and-set-expiry is done via a small Lua script so it is
 * atomic: there is no race between two requests both reading a counter as
 * "0" and both being let through.
 */
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1]) "
                    + "if current == 1 then "
                    + "  redis.call('EXPIRE', KEYS[1], ARGV[1]) "
                    + "end "
                    + "return current",
            Long.class);

    /**
     * Records one hit for {@code key} within the current window and returns
     * whether the caller is still within the allowed {@code limit}.
     *
     * @param key             unique identifier for the thing being limited,
     *                        e.g. {@code "rl:login:203.0.113.5"}
     * @param limit           max number of requests allowed per window
     * @param windowDuration  length of the fixed window
     * @return {@code true} if the request is allowed, {@code false} if the
     *         caller has exceeded {@code limit} for this window
     */
    public boolean tryAcquire(String key, int limit, Duration windowDuration) {
        Long count = redisTemplate.execute(
                SCRIPT,
                List.of(key),
                String.valueOf(windowDuration.toSeconds()));

        return count != null && count <= limit;
    }
}
