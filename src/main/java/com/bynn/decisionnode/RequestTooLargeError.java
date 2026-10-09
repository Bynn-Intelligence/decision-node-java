package com.bynn.decisionnode;

/**
 * HTTP 413, or a body over {@link DecisionNode#MAX_BODY_BYTES} refused before anything is sent
 * (then with no status and no request id). Send large images as uploads: an image given as a
 * path or bytes above the inline threshold is uploaded, so it never counts toward the body. Not
 * retried.
 */
public class RequestTooLargeError extends DecisionNodeError {
    private static final long serialVersionUID = 1L;

    RequestTooLargeError(ErrorInfo info) {
        super(info);
    }
}
