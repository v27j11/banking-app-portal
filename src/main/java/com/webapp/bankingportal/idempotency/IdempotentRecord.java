package com.webapp.bankingportal.idempotency;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Snapshot of a controller response stored against an Idempotency-Key so
 * that a retried/duplicate request can be answered without re-executing the
 * underlying (potentially money-moving) operation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdempotentRecord {

    /** Hash of the request body, used to detect key re-use with a different payload. */
    private int requestHash;

    /** HTTP status code of the original response. */
    private int statusCode;

    /** Body of the original response, already serialized (e.g. JSON or plain text). */
    private String body;

    /** Marker stored briefly while the original request is still being processed. */
    public static final int IN_PROGRESS_STATUS = -1;

    public boolean isInProgress() {
        return statusCode == IN_PROGRESS_STATUS;
    }
}
