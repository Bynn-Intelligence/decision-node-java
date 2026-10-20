package com.bynn.decisionnode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the {@code knowledge} booster: passages from a knowledge base (a collection of type
 * {@code text}) given to the decision as facts and rules to apply. Sent as
 * {@code {"name": "knowledge", "require"?: true, "options": {"collection", ...}}}, each option only
 * when set, as given; the API checks them.
 *
 * <pre>{@code
 * DecideRequest.builder()
 *     .booster(KnowledgeBooster.of("col_...").scope("M-42").topK(4).require(true).build())
 *     .question("approve", truth("Should the claim be approved under our policy?"))
 *     .build();
 * }</pre>
 *
 * <p>{@link Booster#knowledge(String)} is the booster with its collection alone.
 */
public final class KnowledgeBooster {
    private final Map<String, Object> options = new LinkedHashMap<>();
    private boolean require;

    private KnowledgeBooster(String collection) {
        options.put("collection", Objects.requireNonNull(collection, "collection"));
    }

    /** The booster over this knowledge base, a {@code col_} id. */
    public static KnowledgeBooster of(String collection) {
        return new KnowledgeBooster(collection);
    }

    /** The query to search with, in place of the one built from the request's questions and state. */
    public KnowledgeBooster query(String query) {
        options.put("query", query);
        return this;
    }

    /** The value of the collection's scope key: its global documents and those of this scope. */
    public KnowledgeBooster scope(String scope) {
        options.put("scope", scope);
        return this;
    }

    /** Metadata equality, sent as given: a scalar is equal, a list is any of. */
    public KnowledgeBooster match(Map<String, ?> match) {
        options.put("match", match);
        return this;
    }

    /** A filter tree over metadata and items, sent as given. */
    public KnowledgeBooster filter(Object filter) {
        options.put("filter", filter);
        return this;
    }

    /** The most passages found, pins not counted (0 to 20; 0 is pinned only). */
    public KnowledgeBooster topK(int topK) {
        options.put("top_k", topK);
        return this;
    }

    /** The passages' token budget, 128 to 8,192. */
    public KnowledgeBooster maxTokens(int maxTokens) {
        options.put("max_tokens", maxTokens);
        return this;
    }

    /** The passages' token budget as the API's word, {@code "auto"} (the default). */
    public KnowledgeBooster maxTokens(String maxTokens) {
        options.put("max_tokens", maxTokens);
        return this;
    }

    /** False leaves the pinned documents out. */
    public KnowledgeBooster pinned(boolean pinned) {
        options.put("pinned", pinned);
        return this;
    }

    /** A similarity floor, 0 to 1, for passages found by meaning alone. */
    public KnowledgeBooster minScore(double minScore) {
        options.put("min_score", minScore);
        return this;
    }

    /** True adds each passage's text and the query's to the reading; needs a full-scope key. */
    public KnowledgeBooster includeText(boolean includeText) {
        options.put("include_text", includeText);
        return this;
    }

    /**
     * True: the reading must run over a ready knowledge base, or the request fails (the 529
     * {@code booster_unavailable}, or the 409 {@code knowledge_collection_empty}). Sent only when
     * true.
     */
    public KnowledgeBooster require(boolean require) {
        this.require = require;
        return this;
    }

    /** The booster, for {@code DecideRequest.Builder.booster}. */
    public Booster build() {
        Booster b = Booster.of("knowledge").options(options);
        return require ? b.require(true) : b;
    }
}
