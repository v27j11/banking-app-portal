package com.webapp.bankingportal.idempotency;

import java.util.Arrays;
import java.util.Optional;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.webapp.bankingportal.service.CacheService;
import com.webapp.bankingportal.type.CacheKeyType;
import com.webapp.bankingportal.util.LoggedinUser;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implements safe-retry semantics for money-moving endpoints (deposit,
 * withdrawal, fund transfer, ...) annotated with {@link Idempotent}.
 *
 * <p>
 * Clients that want a request to be safely retried (e.g. after a timeout or
 * network failure) send a unique {@code Idempotency-Key} header. The first
 * request with a given key is processed normally and its response is cached.
 * Any subsequent request reusing the same key:
 * <ul>
 * <li>with the SAME request body -> returns the cached response, the
 * underlying operation is NOT executed again.</li>
 * <li>with a DIFFERENT request body -> rejected with 409 Conflict, since
 * reusing a key for a different payload is a client error.</li>
 * <li>while the original request is still in flight -> rejected with 409
 * Conflict, preventing a double-spend race between two concurrent
 * retries.</li>
 * </ul>
 *
 * <p>
 * Requests without the header are processed exactly as before (no
 * behavioural change for existing clients).
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class IdempotencyAspect {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final CacheService cacheService;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        Optional<HttpServletRequest> requestOpt = currentRequest();
        if (requestOpt.isEmpty()) {
            return joinPoint.proceed();
        }

        String idempotencyKey = requestOpt.get().getHeader(IDEMPOTENCY_KEY_HEADER);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // No key supplied: caller did not opt in to de-duplication.
            return joinPoint.proceed();
        }

        String scope = resolveScope();
        String path = requestOpt.get().getRequestURI();
        int requestHash = Arrays.hashCode(joinPoint.getArgs());

        String[] keyArgs = { scope, path, idempotencyKey };

        Optional<IdempotentRecord> existing = cacheService.get(
                CacheKeyType.IDEMPOTENCY, IdempotentRecord.class, keyArgs);

        if (existing.isPresent()) {
            IdempotentRecord record = existing.get();

            if (record.isInProgress()) {
                log.warn("Duplicate in-flight request detected for idempotency key={} path={}", idempotencyKey,
                        path);
                return conflict("A request with this Idempotency-Key is already being processed.");
            }

            if (record.getRequestHash() != requestHash) {
                log.warn("Idempotency key={} reused with a different request payload on path={}", idempotencyKey,
                        path);
                return conflict("This Idempotency-Key was already used with a different request payload.");
            }

            log.info("Replaying cached response for idempotency key={} path={}", idempotencyKey, path);
            return ResponseEntity.status(record.getStatusCode()).body(record.getBody());
        }

        // Acquire a short-lived "in progress" lock so a concurrent duplicate
        // request fails fast instead of racing this one.
        boolean lockAcquired = cacheService.putIfAbsent(
                CacheKeyType.IDEMPOTENCY,
                new IdempotentRecord(requestHash, IdempotentRecord.IN_PROGRESS_STATUS, null),
                30, // lock TTL: long enough to cover a slow request, short enough to self-heal
                keyArgs);

        if (!lockAcquired) {
            log.warn("Could not acquire idempotency lock for key={} path={} (concurrent request in flight)",
                    idempotencyKey, path);
            return conflict("A request with this Idempotency-Key is already being processed.");
        }

        try {
            Object result = joinPoint.proceed();

            if (result instanceof ResponseEntity<?> response) {
                Object body = response.getBody();
                String bodyAsString = body == null ? null : body.toString();
                cacheService.put(
                        CacheKeyType.IDEMPOTENCY,
                        new IdempotentRecord(requestHash, response.getStatusCode().value(), bodyAsString),
                        idempotent.ttlSeconds(),
                        keyArgs);
            } else {
                // Not a ResponseEntity: nothing meaningful to replay, release the lock.
                cacheService.delete(CacheKeyType.IDEMPOTENCY, keyArgs);
            }

            return result;
        } catch (Throwable t) {
            // The operation failed (e.g. insufficient balance, invalid PIN): release
            // the lock so the client can legitimately retry with the same key once
            // they fix the request, instead of being stuck behind a stale lock.
            cacheService.delete(CacheKeyType.IDEMPOTENCY, keyArgs);
            throw t;
        }
    }

    private String resolveScope() {
        try {
            return LoggedinUser.getAccountNumber();
        } catch (Exception e) {
            return "anonymous";
        }
    }

    private Optional<HttpServletRequest> currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return Optional.of(servletRequestAttributes.getRequest());
        }
        return Optional.empty();
    }

    private ResponseEntity<String> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(message);
    }
}
