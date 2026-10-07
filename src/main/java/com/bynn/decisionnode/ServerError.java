package com.bynn.decisionnode;

/** HTTP 500 to 599 other than 529, after the retries; also a success response whose body is not JSON. */
public class ServerError extends DecisionNodeError {
    private static final long serialVersionUID = 1L;

    ServerError(ErrorInfo info) {
        super(info);
    }
}
