package com.bynn.decisionnode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A batch as the API returns it. Every field is read by name; {@link #raw()} holds them all.
 * The states are {@code draft}, {@code queued}, {@code running}, and, once ended,
 * {@code completed}, {@code failed}, {@code cancelled} or {@code expired}.
 */
public final class Batch {
    private final Map<String, Object> raw;

    Batch(Map<String, Object> raw) {
        this.raw = Collections.unmodifiableMap(raw);
    }

    /** {@code batch_} and 10 characters. */
    public String id() {
        return Responses.str(raw.get("id"));
    }

    public String state() {
        return Responses.str(raw.get("state"));
    }

    /** True once the batch has ended: its results can be read. */
    public boolean isEnded() {
        return raw.get("ended_at") != null;
    }

    /**
     * The line counters: {@code requests}, {@code queued}, {@code in_progress},
     * {@code completed}, {@code failed}, {@code expired}, {@code cancelled}.
     */
    public Map<String, Long> counters() {
        Map<String, Long> out = new LinkedHashMap<>();
        Map<String, Object> c = Json.asMap(raw.get("counters"));
        if (c != null) {
            c.forEach((k, v) -> out.put(k, Json.asLong(v)));
        }
        return Collections.unmodifiableMap(out);
    }

    public String createdAt() {
        return Responses.str(raw.get("created_at"));
    }

    public String finalizedAt() {
        return Responses.str(raw.get("finalized_at"));
    }

    public String expiresAt() {
        return Responses.str(raw.get("expires_at"));
    }

    public String endedAt() {
        return Responses.str(raw.get("ended_at"));
    }

    public String resultsUrl() {
        return Responses.str(raw.get("results_url"));
    }

    public String resultsExpireAt() {
        return Responses.str(raw.get("results_expire_at"));
    }

    public Usage usage() {
        return Responses.usage(Json.asMap(raw.get("usage")));
    }

    /** When the batch failed: {@code error_type} and {@code message}; otherwise null. */
    public Map<String, Object> error() {
        return Json.asMap(raw.get("error"));
    }

    /** The batch object exactly as sent. */
    public Map<String, Object> raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "Batch{" + id() + ", " + state() + "}";
    }
}
