package com.bynn.decisionnode;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The base of every error this SDK throws.
 *
 * <p>{@link #getMessage()} is the server's message: {@code detail.message} from the body, or
 * {@code detail} itself when it is a string; {@code ""}, never null, when the response carried
 * none. An error
 * raised before any response arrived (no API key, a connection error, a timeout) has the
 * client's own description instead.
 *
 * <p>An error that comes from an API response also carries the HTTP {@link #status()}, the
 * {@link #errorType()} from the body, the {@link #requestId()} and the {@link #reason()}: the
 * server's explanation in words, for example
 * {@code question 'q': a score takes up to 10 levels, got 11}.
 *
 * <p>A status with no class of its own (a 3xx, 405, 409, or a 408 once its retries are spent)
 * is this class itself, with that {@link #status()}.
 *
 * <p>All errors are unchecked.
 */
public class DecisionNodeError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Integer status;
    private final String errorType;
    private final String requestId;
    private final String reason;
    private final transient Object body;
    private final transient Map<String, List<String>> headers;

    public DecisionNodeError(String message) {
        this(message, (Throwable) null);
    }

    public DecisionNodeError(String message, Throwable cause) {
        super(message, cause);
        this.status = null;
        this.errorType = null;
        this.requestId = null;
        this.reason = null;
        this.body = null;
        this.headers = Collections.emptyMap();
    }

    DecisionNodeError(ErrorInfo info) {
        super(info.message());
        this.status = info.status();
        this.errorType = info.errorType();
        this.requestId = info.requestId();
        this.reason = info.reason();
        this.body = info.body();
        this.headers = info.headers() == null ? Collections.emptyMap() : info.headers();
    }

    /** The HTTP status, or null when no response was received. */
    public Integer status() {
        return status;
    }

    /** {@code detail.error_type} from the body, for example {@code max_tokens_exceeded}; null when absent. */
    public String errorType() {
        return errorType;
    }

    /** The {@code x-request-id} response header; quote it when you contact support. */
    public String requestId() {
        return requestId;
    }

    /** The {@code x-decisionnode-error} response header, as received. */
    public String reason() {
        return reason;
    }

    /** The parsed response body, or null when it was empty or not JSON. */
    public Object body() {
        return body;
    }

    /** The response headers, keyed by lower-case name. Empty when no response was received. */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * For a request that named boosters: each booster's status and time, by name in the request's
     * order, from the {@code x-decisionnode-boosters} response header, so a caller can see which
     * required booster failed. Empty when the header is absent or no response was received.
     */
    public Map<String, BoosterStatus> boosters() {
        List<String> values = headers.get("x-decisionnode-boosters");
        return Responses.boosters(values == null || values.isEmpty() ? null : String.join(",", values));
    }

    @Override
    public String toString() {
        String name = getClass().getName();
        String text = getMessage();
        String head = status == null ? name : name + " (HTTP " + status + ")";
        return text == null || text.isEmpty() ? head : head + ": " + text;
    }
}
