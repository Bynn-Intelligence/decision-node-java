package com.bynn.decisionnode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A device key to make with {@link KeysClient#create}: its name and, when set, its device,
 * labels, limits, daily spend cap, expiry and collection search. Only the fields set are sent.
 *
 * <pre>{@code
 * Map<String, Object> made = dn.keys().create(KeyCreate.builder("Drone 0042")
 *     .deviceId("acme-drone-0042").label("customer", "acme").dailySpendCap(5.0).build());
 * }</pre>
 */
public final class KeyCreate {
    final Map<String, Object> fields;
    final String idempotencyKey;

    private KeyCreate(Builder b) {
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(b.fields));
        this.idempotencyKey = b.idempotencyKey;
    }

    /** A key with this name: up to 48 letters, digits, spaces, dots, dashes and underscores. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** Builds a {@link KeyCreate}; a field never set is left out of the request. */
    public static final class Builder {
        private final Map<String, Object> fields = new LinkedHashMap<>();
        private final Map<String, String> labels = new LinkedHashMap<>();
        private String idempotencyKey;

        private Builder(String name) {
            fields.put("name", Objects.requireNonNull(name, "name"));
        }

        /** The device the key is for: 1 to 128 printable ASCII characters, set once, never changed. */
        public Builder deviceId(String deviceId) {
            fields.put("device_id", deviceId);
            return this;
        }

        /** A label, name 1 to 32 of {@code a-z 0-9 _ . -} and a value of 1 to 64 characters; at most 16. */
        public Builder label(String name, String value) {
            labels.put(name, value);
            fields.put("labels", labels);
            return this;
        }

        /** Several labels, as {@link #label(String, String)}. */
        public Builder labels(Map<String, String> labels) {
            labels.forEach(this::label);
            return this;
        }

        /**
         * The key's own limits, at most the workspace's: {@code requests_per_second} and
         * {@code tokens_per_second}, each a whole number or null.
         */
        public Builder limits(Map<String, ?> limits) {
            fields.put("limits", limits);
            return this;
        }

        /** The most the key may spend in a UTC day, in USD; past it, its calls are refused until 00:00 UTC. */
        public Builder dailySpendCap(double usd) {
            fields.put("daily_spend_cap", Map.of("usd", usd));
            return this;
        }

        /** When the key stops working: an RFC 3339 time in the future. */
        public Builder expiresAt(String expiresAt) {
            fields.put("expires_at", expiresAt);
            return this;
        }

        /** Whether the key may search the workspace's collections. */
        public Builder maySearchCollections(boolean may) {
            fields.put("may_search_collections", may);
            return this;
        }

        /**
         * The request's {@code Idempotency-Key}, 1 to 255 printable ASCII characters: the same key
         * with the same body answers the same key again. Left out, a new one is made for the call.
         */
        public Builder idempotencyKey(String key) {
            this.idempotencyKey = key;
            return this;
        }

        public KeyCreate build() {
            return new KeyCreate(this);
        }
    }
}
