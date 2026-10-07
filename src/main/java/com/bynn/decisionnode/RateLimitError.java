package com.bynn.decisionnode;

/** HTTP 429: too many requests. Retried; thrown once the retries are used up. */
public class RateLimitError extends DecisionNodeError {
    private static final long serialVersionUID = 1L;

    private final Double retryAfter;

    RateLimitError(ErrorInfo info, Double retryAfter) {
        super(info);
        this.retryAfter = retryAfter;
    }

    /**
     * Seconds the server asked to wait, from {@code retry-after-ms} or else {@code Retry-After};
     * null when it sent neither.
     */
    public Double retryAfter() {
        return retryAfter;
    }
}
