package com.bynn.decisionnode;

/**
 * HTTP 429 with the error type {@code session_limit_exceeded}: the key already holds as many open
 * sessions as it may. A {@link RateLimitError}, never retried: end a session, or wait for one to
 * close, before opening another.
 */
public class SessionLimitError extends RateLimitError {
    private static final long serialVersionUID = 1L;

    static final String ERROR_TYPE = "session_limit_exceeded";

    SessionLimitError(ErrorInfo info, Double retryAfter) {
        super(info, retryAfter);
    }
}
