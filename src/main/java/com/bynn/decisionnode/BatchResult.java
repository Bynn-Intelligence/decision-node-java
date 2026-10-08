package com.bynn.decisionnode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One line of a batch's results: the request's {@code custom_id}, its status, and the decision
 * or the error it ended with. The error is a value, never thrown.
 */
public final class BatchResult {
    private final Map<String, Object> raw;
    private final Map<String, String> headers;
    private final Decision decision;
    private final DecisionNodeError error;

    BatchResult(Map<String, Object> raw) {
        this.raw = Collections.unmodifiableMap(raw);
        Map<String, String> h = new LinkedHashMap<>();
        Map<String, Object> given = Json.asMap(raw.get("headers"));
        if (given != null) {
            given.forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v == null ? null : String.valueOf(v)));
        }
        this.headers = Collections.unmodifiableMap(h);
        Object status = raw.get("status");
        Map<String, Object> body = Json.asMap(raw.get("body"));
        Responses.Headers lookup = Responses.of(headers);
        if (Integer.valueOf(200).equals(statusCode(status)) && body != null) {
            this.decision = Responses.decision(body, lookup);
            this.error = null;
        } else if (statusCode(status) != null) {
            Map<String, List<String>> all = new TreeMap<>();
            headers.forEach((k, v) -> all.put(k, List.of(String.valueOf(v))));
            this.decision = null;
            this.error = Responses.error(statusCode(status), lookup, Collections.unmodifiableMap(all), raw.get("body"));
        } else {
            this.decision = null;
            this.error = null;
        }
    }

    private static Integer statusCode(Object status) {
        return status instanceof Number ? ((Number) status).intValue() : null;
    }

    public String customId() {
        return Responses.str(raw.get("custom_id"));
    }

    /** The status as sent: an {@link Integer} (the HTTP status), or {@code "expired"} or {@code "cancelled"}. */
    public Object status() {
        Object s = raw.get("status");
        return s instanceof Number ? (Object) ((Number) s).intValue() : s;
    }

    /** The HTTP status, or null for a line that never ran ({@code expired} or {@code cancelled}). */
    public Integer statusCode() {
        return statusCode(raw.get("status"));
    }

    /** True for a {@code 200} line with its decision. */
    public boolean isSuccess() {
        return decision != null;
    }

    /** The decision of a {@code 200} line, read as {@link DecisionNode#decide} reads it; else null. */
    public Decision decision() {
        return decision;
    }

    /**
     * The error class and fields the line's status and body give, as a live response would; null
     * for a {@code 200} line and for a line that never ran.
     */
    public DecisionNodeError error() {
        return error;
    }

    /** The line's headers, by lower-case name. Empty for a line that never ran. */
    public Map<String, String> headers() {
        return headers;
    }

    /** The line exactly as sent. */
    public Map<String, Object> raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "BatchResult{" + customId() + ", " + status() + "}";
    }
}
