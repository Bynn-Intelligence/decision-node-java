package com.bynn.decisionnode;

/** HTTP 403 that is not a refusal: the key may not use this resource. Not retried. */
public class PermissionDeniedError extends DecisionNodeError {
    private static final long serialVersionUID = 1L;

    PermissionDeniedError(ErrorInfo info) {
        super(info);
    }
}
