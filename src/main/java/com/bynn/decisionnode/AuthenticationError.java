package com.bynn.decisionnode;

/** HTTP 401, or no API key at all: the key is missing, wrong or revoked. Not retried. */
public class AuthenticationError extends DecisionNodeError {
    private static final long serialVersionUID = 1L;

    AuthenticationError(ErrorInfo info) {
        super(info);
    }

    AuthenticationError(String message) {
        super(message);
    }
}
