package com.bynn.decisionnode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A document's fields, for {@link CollectionsClient#addDocument} and
 * {@link CollectionsClient#replaceDocument}. Only the fields set here are sent:
 *
 * <pre>{@code
 * dn.collections().addDocument("col_...", Document.fromText(policy),
 *     DocumentOptions.builder().title("Claims policy").pinned(true).waitUntilReady(true).build());
 * }</pre>
 */
public final class DocumentOptions {
    private final String title;
    private final Map<String, Object> fields;
    private final boolean wait;

    private DocumentOptions(String title, Map<String, Object> fields, boolean wait) {
        this.title = title;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        this.wait = wait;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** No field set and no wait. */
    static final DocumentOptions NONE = builder().build();

    /** The title given, or null. */
    public String title() {
        return title;
    }

    /** The fields to send besides the title, in the order {@code reference}, {@code pinned}, {@code pin_rank}, {@code label}, {@code metadata}. */
    public Map<String, Object> fields() {
        return fields;
    }

    /** Whether the call asks the server to answer once the document is ready ({@code ?wait=true}). */
    public boolean waitUntilReady() {
        return wait;
    }

    /** Builds {@link DocumentOptions}; a field never set is left out of the request. */
    public static final class Builder {
        private String title;
        private String reference;
        private Boolean pinned;
        private Integer pinRank;
        private String label;
        private Map<String, ?> metadata;
        private boolean wait;

        private Builder() {}

        /**
         * The document's title, 1 to 200 characters once trimmed. Required for text held in memory
         * and for a text file without a file name; a file otherwise takes its file name without its
         * extension.
         */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /** The document's reference, unique in the collection: 1 to 64 of {@code A-Z a-z 0-9 _ . : -}. */
        public Builder reference(String reference) {
            this.reference = reference;
            return this;
        }

        /** True: a rule that must always apply, given to every decision that names the collection. */
        public Builder pinned(boolean pinned) {
            this.pinned = pinned;
            return this;
        }

        /** The order of a pinned document among the pins, 0 to 1,000, lowest first. */
        public Builder pinRank(int pinRank) {
            this.pinRank = pinRank;
            return this;
        }

        /** A label of 1 to 128 characters. */
        public Builder label(String label) {
            this.label = label;
            return this;
        }

        /**
         * The document's metadata: at most 20 keys of 1 to 40 of {@code a-z 0-9 _}, each a text of
         * at most 500 characters, a number or a boolean. The collection's scope key here scopes the
         * document; without it the document is global.
         */
        public Builder metadata(Map<String, ?> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * True: the server holds the call up to 10 seconds and answers once the document is ready,
         * else as it stands. Such a call waits 20 seconds per attempt unless the client's timeout is
         * set.
         */
        public Builder waitUntilReady(boolean wait) {
            this.wait = wait;
            return this;
        }

        public DocumentOptions build() {
            Map<String, Object> f = new LinkedHashMap<>();
            if (reference != null) {
                f.put("reference", reference);
            }
            if (pinned != null) {
                f.put("pinned", pinned);
            }
            if (pinRank != null) {
                f.put("pin_rank", pinRank);
            }
            if (label != null) {
                f.put("label", label);
            }
            if (metadata != null) {
                f.put("metadata", metadata);
            }
            return new DocumentOptions(title, f, wait);
        }
    }
}
