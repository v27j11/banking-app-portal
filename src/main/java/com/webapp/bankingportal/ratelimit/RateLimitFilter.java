package com.webapp.bankingportal.ratelimit;

import java.io.IOException;
import java.time.Duration;

import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies per-client request limits to protect the API from abuse and
 * brute-force attacks.
 *
 * <p>
 * Endpoints are bucketed into three classes, each with its own limit:
 * <ul>
 * <li><b>auth</b> - login, registration, OTP and password-reset endpoints.
 * These are pre-authentication, so the client is identified by IP address.
 * Kept strict to slow down credential-stuffing / brute-force attempts.</li>
 * <li><b>transaction</b> - deposit, withdrawal, fund-transfer. Identified by
 * account number when authenticated (falls back to IP otherwise).</li>
 * <li><b>default</b> - everything else under {@code /api/**}.</li>
 * </ul>
 *
 * <p>
 * Backed by Redis so the limit is enforced consistently even when the
 * application is scaled to multiple instances. If Redis is temporarily
 * unavailable, the filter fails OPEN (allows the request through) rather
 * than taking the whole API down - availability of banking operations is
 * prioritised over strict rate limiting in that edge case, and the failure
 * is logged so it can be alerted on.
 */
@Component
@Order(Integer.MIN_VALUE + 10)
@Slf4j
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RedisRateLimiter rateLimiter;
    private final RateLimitProperties properties;

    private static final String[] AUTH_PATHS = {
            "/api/users/login",
            "/api/users/register",
            "/api/users/generate-otp",
            "/api/users/verify-otp",
            "/api/auth/"
    };

    private static final String[] TRANSACTION_PATHS = {
            "/api/account/deposit",
            "/api/account/withdraw",
            "/api/account/fund-transfer"
    };

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (!properties.isEnabled() || !request.getRequestURI().startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        Bucket bucket = classify(request.getRequestURI());
        String clientKey = "rl:" + bucket.name + ":" + clientIdentifier(request);

        boolean allowed;
        try {
            allowed = rateLimiter.tryAcquire(
                    clientKey, bucket.limit, Duration.ofSeconds(bucket.windowSeconds));
        } catch (Exception e) {
            log.error("Rate limiter unavailable, failing open for {}: {}", request.getRequestURI(), e.getMessage());
            allowed = true;
        }

        if (!allowed) {
            log.warn("Rate limit exceeded for key={} on path={}", clientKey, request.getRequestURI());
            response.setStatus(429); // HttpStatus.TOO_MANY_REQUESTS
            response.setHeader("Retry-After", String.valueOf(bucket.windowSeconds));
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"message\": \"Too many requests. Please try again in " + bucket.windowSeconds
                            + " seconds.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private Bucket classify(String uri) {
        for (String path : AUTH_PATHS) {
            if (uri.startsWith(path)) {
                return new Bucket("auth", properties.getAuth().getLimit(), properties.getAuth().getWindowSeconds());
            }
        }

        for (String path : TRANSACTION_PATHS) {
            if (uri.startsWith(path)) {
                return new Bucket("txn", properties.getTransaction().getLimit(),
                        properties.getTransaction().getWindowSeconds());
            }
        }

        return new Bucket("default", properties.getDefaultBucket().getLimit(),
                properties.getDefaultBucket().getWindowSeconds());
    }

    /**
     * Prefer the authenticated account number (so legitimate multi-user
     * traffic from behind the same NAT/proxy isn't penalised together), and
     * fall back to client IP for unauthenticated requests.
     */
    private String clientIdentifier(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            // Use the raw token as the identity for pre-auth-filter requests; good
            // enough to distinguish clients without needing to parse/validate the
            // JWT again here.
            return "token:" + Integer.toHexString(authHeader.hashCode());
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        return request.getRemoteAddr();
    }

    private record Bucket(String name, int limit, int windowSeconds) {
    }
}
