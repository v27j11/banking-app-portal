package com.webapp.bankingportal.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as idempotent.
 *
 * <p>
 * When a client sends a request with an {@code Idempotency-Key} header, the
 * {@link IdempotencyAspect} will:
 * <ul>
 * <li>Return the previously stored response if the same key was already used
 * with an identical request payload (duplicate/retried request).</li>
 * <li>Reject the request with 409 Conflict if the same key is reused with a
 * <em>different</em> request payload (misuse of the key).</li>
 * <li>Reject the request with 409 Conflict if the same key is currently being
 * processed by another in-flight request (prevents double-spending caused by
 * concurrent retries, e.g. a client double-click or network retry).</li>
 * <li>Otherwise process the request normally and cache the response for
 * {@code ttlSeconds}.</li>
 * </ul>
 *
 * <p>
 * The {@code Idempotency-Key} header is OPTIONAL: requests sent without it
 * are processed normally with no de-duplication. This keeps the endpoint
 * backwards compatible with existing clients while letting new/retrying
 * clients opt in to safe retries.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotent {

    /**
     * How long a stored response stays valid for replay, in seconds.
     * Defaults to 24 hours.
     */
    long ttlSeconds() default 86_400;
}
