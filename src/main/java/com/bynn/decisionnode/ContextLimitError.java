package com.bynn.decisionnode;

/** HTTP 400 with the error type {@code max_tokens_exceeded}: the request is longer than the model accepts. Not retried. */
public class ContextLimitError extends BadRequestError {
    private static final long serialVersionUID = 1L;

    ContextLimitError(ErrorInfo info) {
        super(info);
    }
}
