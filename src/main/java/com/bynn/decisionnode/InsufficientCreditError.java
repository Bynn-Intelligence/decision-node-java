package com.bynn.decisionnode;

/** HTTP 402: the account has no credit left. Not retried. */
public class InsufficientCreditError extends DecisionNodeError {
    private static final long serialVersionUID = 1L;

    InsufficientCreditError(ErrorInfo info) {
        super(info);
    }
}
