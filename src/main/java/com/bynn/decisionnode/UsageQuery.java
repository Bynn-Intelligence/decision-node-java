package com.bynn.decisionnode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Which keys' usage {@link UsageClient#list} and {@link UsageClient#all} read, and over which
 * period. Only the filters set here are sent.
 *
 * <pre>{@code
 * UsageQuery q = UsageQuery.builder("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
 *     .granularity("day").label("customer", "acme").build();
 * }</pre>
 */
public final class UsageQuery {
    final String start;
    final String end;
    final String granularity;
    final String groupBy;
    final Map<String, String> labels;
    final String deviceId;
    final List<String> keyIds;
    final String scope;
    final String status;
    final Boolean includeEmpty;
    final Integer limit;
    final String cursor;

    private UsageQuery(Builder b) {
        this.start = b.start;
        this.end = b.end;
        this.granularity = b.granularity;
        this.groupBy = b.groupBy;
        this.labels = Collections.unmodifiableMap(new TreeMap<>(b.labels));
        this.deviceId = b.deviceId;
        this.keyIds = List.copyOf(b.keyIds);
        this.scope = b.scope;
        this.status = b.status;
        this.includeEmpty = b.includeEmpty;
        this.limit = b.limit;
        this.cursor = b.cursor;
    }

    /** The period, from {@code start} to {@code end}, each an ISO 8601 time such as {@code 2026-09-01T00:00:00Z}. */
    public static Builder builder(String start, String end) {
        return new Builder(start, end);
    }

    /** The same query from the page this cursor names. */
    UsageQuery withCursor(String next) {
        Builder b = new Builder(start, end);
        b.granularity = granularity;
        b.groupBy = groupBy;
        b.labels.putAll(labels);
        b.deviceId = deviceId;
        b.keyIds.addAll(keyIds);
        b.scope = scope;
        b.status = status;
        b.includeEmpty = includeEmpty;
        b.limit = limit;
        b.cursor = next;
        return new UsageQuery(b);
    }

    /** Builds a {@link UsageQuery}; a filter never set is left out of the request. */
    public static final class Builder {
        private final String start;
        private final String end;
        private String granularity;
        private String groupBy;
        private final Map<String, String> labels = new TreeMap<>();
        private String deviceId;
        private final List<String> keyIds = new ArrayList<>();
        private String scope;
        private String status;
        private Boolean includeEmpty;
        private Integer limit;
        private String cursor;

        private Builder(String start, String end) {
            this.start = Objects.requireNonNull(start, "start");
            this.end = Objects.requireNonNull(end, "end");
        }

        /** {@code none}, {@code hour}, {@code day} or {@code month}. */
        public Builder granularity(String granularity) {
            this.granularity = granularity;
            return this;
        }

        /** {@code model} or {@code channel}. */
        public Builder groupBy(String groupBy) {
            this.groupBy = groupBy;
            return this;
        }

        /** Only keys carrying this label, sent as {@code label=name:value}; several are all required. */
        public Builder label(String name, String value) {
            labels.put(Objects.requireNonNull(name, "name"), value);
            return this;
        }

        /** Several labels, as {@link #label(String, String)}. */
        public Builder labels(Map<String, String> labels) {
            labels.forEach(this::label);
            return this;
        }

        /** Only keys made for this device: 1 to 128 printable ASCII characters, not starting with a space. */
        public Builder deviceId(String deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        /** Only these keys, each {@code key_} and 32 hex digits; at most 100. */
        public Builder keyIds(List<String> keyIds) {
            this.keyIds.addAll(keyIds);
            return this;
        }

        /** Only keys of this scope: {@code full}, {@code decide} or {@code keys}. */
        public Builder scope(String scope) {
            this.scope = scope;
            return this;
        }

        /** Only keys in this status: {@code active}, {@code expired}, {@code revoked} or {@code all}. */
        public Builder status(String status) {
            this.status = status;
            return this;
        }

        /** Whether keys with no usage in the period are listed too. */
        public Builder includeEmpty(Boolean includeEmpty) {
            this.includeEmpty = includeEmpty;
            return this;
        }

        /** The most keys a page holds, 1 to 1,000. */
        public Builder limit(Integer limit) {
            this.limit = limit;
            return this;
        }

        /** The page to read: a previous page's {@code next_cursor}. */
        public Builder cursor(String cursor) {
            this.cursor = cursor;
            return this;
        }

        public UsageQuery build() {
            return new UsageQuery(this);
        }
    }
}
