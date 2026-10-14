package com.bynn.decisionnode;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Which device keys {@link KeysClient#list} and {@link KeysClient#all} read. Only the filters set are sent. */
public final class KeyListQuery {
    final Map<String, String> labels;
    final String deviceId;
    final String status;
    final Integer limit;
    final String cursor;

    private KeyListQuery(Map<String, String> labels, String deviceId, String status, Integer limit, String cursor) {
        this.labels = Collections.unmodifiableMap(new TreeMap<>(labels));
        this.deviceId = deviceId;
        this.status = status;
        this.limit = limit;
        this.cursor = cursor;
    }

    public static Builder builder() {
        return new Builder();
    }

    KeyListQuery withCursor(String next) {
        return new KeyListQuery(labels, deviceId, status, limit, next);
    }

    /** Builds a {@link KeyListQuery}. */
    public static final class Builder {
        private final Map<String, String> labels = new TreeMap<>();
        private String deviceId;
        private String status;
        private Integer limit;
        private String cursor;

        private Builder() {}

        /** Only keys carrying this label; several are all required. */
        public Builder label(String name, String value) {
            labels.put(name, value);
            return this;
        }

        /** Several labels, as {@link #label(String, String)}. */
        public Builder labels(Map<String, String> labels) {
            labels.forEach(this::label);
            return this;
        }

        /** Only the key made for this device. */
        public Builder deviceId(String deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        /** {@code active} (the API's default), {@code expired}, {@code revoked} or {@code all}. */
        public Builder status(String status) {
            this.status = status;
            return this;
        }

        /** The most keys a page holds, 1 to 200. */
        public Builder limit(Integer limit) {
            this.limit = limit;
            return this;
        }

        /** The page to read: a previous page's {@code next_cursor}. */
        public Builder cursor(String cursor) {
            this.cursor = cursor;
            return this;
        }

        public KeyListQuery build() {
            return new KeyListQuery(labels, deviceId, status, limit, cursor);
        }
    }
}
