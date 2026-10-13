package com.bynn.decisionnode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fields to change on a collection item, for {@link CollectionsClient#updateItem}. Only the
 * fields set here are sent:
 *
 * <pre>{@code
 * dn.collections().updateItem("col_...", "itm_...", ItemUpdate.builder().label("Front desk").build());
 * dn.collections().updateItem("col_...", "itm_...", ItemUpdate.builder().clearLabel().build());
 * }</pre>
 */
public final class ItemUpdate {
    private final Map<String, Object> fields;

    private ItemUpdate(Map<String, Object> fields) {
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The fields to send, in the order set; a label set to null clears it. */
    public Map<String, Object> fields() {
        return fields;
    }

    /** Builds an {@link ItemUpdate}; a field never set is left out of the request. */
    public static final class Builder {
        private final Map<String, Object> fields = new LinkedHashMap<>();

        private Builder() {}

        /** A new reference: 1 to 64 of {@code A-Z a-z 0-9 _ . : -}. */
        public Builder reference(String reference) {
            fields.put("reference", reference);
            return this;
        }

        /** A new label of 1 to 128 characters; null clears the item's label, as {@link #clearLabel()} does. */
        public Builder label(String label) {
            fields.put("label", label);
            return this;
        }

        /** Removes the item's label: sent as {@code "label": null}. */
        public Builder clearLabel() {
            fields.put("label", null);
            return this;
        }

        /**
         * The item's metadata, which replaces its whole object ({@code {}} removes every key): at most
         * 20 keys of 1 to 40 of {@code a-z 0-9 _}, each a text of at most 500 characters, a number or
         * a boolean.
         */
        public Builder metadata(Map<String, ?> metadata) {
            fields.put("metadata", metadata);
            return this;
        }

        public ItemUpdate build() {
            return new ItemUpdate(fields);
        }
    }
}
