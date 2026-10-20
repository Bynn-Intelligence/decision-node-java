package com.bynn.decisionnode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The options of a knowledge base search, for {@link CollectionsClient#search}. Each is sent only
 * when set, as given; the API checks them.
 *
 * <pre>{@code
 * KnowledgeReading r = dn.collections().search("col_...", "Do claims over 500 SEK need a receipt?",
 *     SearchOptions.builder().scope("M-42").topK(5).build());
 * }</pre>
 */
public final class SearchOptions {
    private final Map<String, Object> options;

    private SearchOptions(Map<String, Object> options) {
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The options to send, in the order set. */
    public Map<String, Object> options() {
        return options;
    }

    /** Builds {@link SearchOptions}; an option never set is left out of the request. */
    public static final class Builder {
        private final Map<String, Object> options = new LinkedHashMap<>();

        private Builder() {}

        /** The value of the collection's scope key: its global documents and those of this scope. */
        public Builder scope(String scope) {
            options.put("scope", scope);
            return this;
        }

        /** Metadata equality, sent as given: a scalar is equal, a list is any of. */
        public Builder match(Map<String, ?> match) {
            options.put("match", match);
            return this;
        }

        /** A filter tree over metadata and items, sent as given. */
        public Builder filter(Object filter) {
            options.put("filter", filter);
            return this;
        }

        /** The most passages found, pins not counted (0 to 20; 0 is pinned only). */
        public Builder topK(int topK) {
            options.put("top_k", topK);
            return this;
        }

        /** False leaves the pinned documents out. */
        public Builder pinned(boolean pinned) {
            options.put("pinned", pinned);
            return this;
        }

        /** The passages' token budget, 128 to 8,192. */
        public Builder maxTokens(int maxTokens) {
            options.put("max_tokens", maxTokens);
            return this;
        }

        /** The passages' token budget as the API's word, {@code "auto"}. */
        public Builder maxTokens(String maxTokens) {
            options.put("max_tokens", maxTokens);
            return this;
        }

        public SearchOptions build() {
            return new SearchOptions(options);
        }
    }
}
