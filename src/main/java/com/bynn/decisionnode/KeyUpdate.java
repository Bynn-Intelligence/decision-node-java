package com.bynn.decisionnode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fields to change on a device key with {@link KeysClient#update}. Only the fields set are
 * sent; a {@code clear...} method sends the field as null, which removes it. A key's device never
 * changes.
 *
 * <pre>{@code
 * dn.keys().update("key_...", KeyUpdate.builder().dailySpendCap(10.0).clearExpiresAt().build());
 * }</pre>
 */
public final class KeyUpdate {
    final Map<String, Object> fields;

    private KeyUpdate(Map<String, Object> fields) {
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builds a {@link KeyUpdate}. */
    public static final class Builder {
        private final Map<String, Object> fields = new LinkedHashMap<>();

        private Builder() {}

        /** A new name. */
        public Builder name(String name) {
            fields.put("name", name);
            return this;
        }

        /** The key's labels, replaced whole. */
        public Builder labels(Map<String, String> labels) {
            fields.put("labels", labels);
            return this;
        }

        /** Removes every label. */
        public Builder clearLabels() {
            fields.put("labels", null);
            return this;
        }

        /** The key's limits: {@code requests_per_second} and {@code tokens_per_second}. */
        public Builder limits(Map<String, ?> limits) {
            fields.put("limits", limits);
            return this;
        }

        /** Removes the key's own limits: the workspace's apply. */
        public Builder clearLimits() {
            fields.put("limits", null);
            return this;
        }

        /** The most the key may spend in a UTC day, in USD. */
        public Builder dailySpendCap(double usd) {
            fields.put("daily_spend_cap", Map.of("usd", usd));
            return this;
        }

        /** Removes the key's daily spend cap. */
        public Builder clearDailySpendCap() {
            fields.put("daily_spend_cap", null);
            return this;
        }

        /** When the key stops working: an RFC 3339 time in the future. */
        public Builder expiresAt(String expiresAt) {
            fields.put("expires_at", expiresAt);
            return this;
        }

        /** Removes the key's expiry. An expired key is never brought back. */
        public Builder clearExpiresAt() {
            fields.put("expires_at", null);
            return this;
        }

        /** Whether the key may search the workspace's collections. */
        public Builder maySearchCollections(boolean may) {
            fields.put("may_search_collections", may);
            return this;
        }

        public KeyUpdate build() {
            return new KeyUpdate(fields);
        }
    }
}
