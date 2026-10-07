package com.bynn.decisionnode;

/** HTTP 404. Not retried. */
public class NotFoundError extends DecisionNodeError {
    private static final long serialVersionUID = 1L;

    NotFoundError(ErrorInfo info) {
        super(info);
    }
}
