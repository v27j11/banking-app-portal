package com.webapp.bankingportal.logging;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Adds request-scoped tracing context to every log line emitted while
 * handling an HTTP request, and emits a single structured "access log" line
 * per request once it completes.
 *
 * <p>
 * The request id:
 * <ul>
 * <li>is read from the incoming {@code X-Request-Id} header if the caller
 * (e.g. an API gateway or another internal service) already assigned
 * one, so a request can be traced across service boundaries;</li>
 * <li>otherwise a new one is generated;</li>
 * <li>is echoed back on the response so the client can quote it when
 * reporting an issue;</li>
 * <li>is placed in the SLF4J {@link MDC} as {@code requestId}, so it is
 * automatically included in every log line for the duration of the
 * request by the logback pattern / JSON encoder, without every log
 * statement needing to pass it explicitly.</li>
 * </ul>
 *
 * <p>
 * Runs as early as possible in the filter chain (before authentication) so
 * that even rejected/unauthenticated requests get a request id and are
 * captured in the access log.
 */
@Component
@Order(Integer.MIN_VALUE)
@Slf4j
public class RequestTracingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_METHOD = "httpMethod";
    public static final String MDC_PATH = "httpPath";
    public static final String MDC_CLIENT_IP = "clientIp";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        long startNanos = System.nanoTime();
        String requestId = resolveRequestId(request);

        try {
            MDC.put(MDC_REQUEST_ID, requestId);
            MDC.put(MDC_METHOD, request.getMethod());
            MDC.put(MDC_PATH, request.getRequestURI());
            MDC.put(MDC_CLIENT_IP, request.getRemoteAddr());
            response.setHeader(REQUEST_ID_HEADER, requestId);

            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("request_completed status={} durationMs={}", response.getStatus(), durationMs);

            // Always clear MDC at the end of the request: thread pools reuse threads,
            // and a leaked requestId would otherwise leak into an unrelated request
            // handled later on the same thread.
            MDC.clear();
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        if (incoming != null && !incoming.isBlank()) {
            return incoming;
        }
        return java.util.UUID.randomUUID().toString();
    }
}
