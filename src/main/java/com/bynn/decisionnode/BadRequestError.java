package com.bynn.decisionnode;

/** HTTP 400: the server could not use the request, for example a question it cannot answer or an unknown model. Not retried. */
public class BadRequestError extends DecisionNodeError {
    private static final long serialVersionUID = 1L;

    BadRequestError(ErrorInfo info) {
        super(info);
    }
}
