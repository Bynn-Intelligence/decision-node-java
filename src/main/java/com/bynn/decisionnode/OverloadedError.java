package com.bynn.decisionnode;

/** HTTP 529: the service is overloaded. Retried; thrown once the retries are used up. */
public class OverloadedError extends DecisionNodeError {
    private static final long serialVersionUID = 1L;

    private final Double retryAfter;

    OverloadedError(ErrorInfo info, Double retryAfter) {
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
