package com.bynn.decisionnode;

/** No response within the client's timeout. Retried. */
public class TimeoutError extends DecisionNodeError {
    private static final long serialVersionUID = 1L;

    TimeoutError(String message, Throwable cause) {
        super(message, cause);
    }
}
