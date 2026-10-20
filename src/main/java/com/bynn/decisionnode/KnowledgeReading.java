package com.bynn.decisionnode;

import java.util.List;
import java.util.Map;

/**
 * A reading of the {@code knowledge} booster, and the answer of a knowledge base search
 * ({@link CollectionsClient#search}): the passages of the workspace's own documents that apply.
 * Every field is as sent; a field that is absent, or not of its type, is null (an empty list for
 * {@code warnings}, an empty map for {@code omitted}).
 *
 * @param collection     the id of the knowledge base read
 * @param collectionName its name; null for a publishable key's reading
 * @param corpus         the knowledge base's version that was read
 * @param query          what was searched for
 * @param budget         the passages' token budget
 * @param scope          the collection's scope key and the value named; null for a collection without
 *                       one
 * @param warnings       {@code scope_not_named}, {@code over_budget}, {@code budget_floor},
 *                       {@code pinned_filtered} or any other, as sent; empty when there are none
 * @param passages       the passages in the block's order, pinned first
 * @param omitted        the passages left out, by cause ({@code below_min_score},
 *                       {@code per_item_cap}, {@code over_budget}, {@code pinned_filtered},
 *                       {@code short_entry}, {@code context_limit} or any other), as sent
 * @param pendingItems   the documents not ready yet
 * @param search         how the search ran
 * @param truncated      true when a passage was dropped to fit the request; null on a search's
 *                       answer
 * @param raw            the reading exactly as sent
 */
public record KnowledgeReading(
        String collection,
        String collectionName,
        Corpus corpus,
        Query query,
        Budget budget,
        Scope scope,
        List<String> warnings,
        List<KnowledgePassage> passages,
        Map<String, Object> omitted,
        Long pendingItems,
        Search search,
        Boolean truncated,
        Map<String, Object> raw) implements Reading {
    @Override
    public String booster() {
        return "knowledge";
    }

    /**
     * The knowledge base's version: it moves with every document that becomes ready or is deleted.
     *
     * @param version the version read
     * @param raw     the object exactly as sent
     */
    public record Corpus(Long version, Map<String, Object> raw) {}

    /**
     * What was searched for.
     *
     * @param source {@code auto} (built from the request), {@code request} (the {@code query}
     *               option), {@code none} ({@code top_k} 0) or any other value
     * @param tokens the query's tokens
     * @param text   the query's text, with {@code include_text} and on a search; null otherwise
     * @param raw    the object exactly as sent
     */
    public record Query(String source, Long tokens, String text, Map<String, Object> raw) {}

    /**
     * The passages' token budget.
     *
     * @param mode   {@code auto} or {@code fixed}
     * @param tokens the passages' tokens
     * @param entry  {@code short}, {@code long} or null
     * @param cause  why a long entry was taken: {@code state}, {@code images},
     *               {@code other_boosters}, {@code pinned}, {@code fixed}, {@code require_floor}, any
     *               other value, or null
     * @param raw    the object exactly as sent
     */
    public record Budget(String mode, Long tokens, String entry, String cause, Map<String, Object> raw) {}

    /**
     * The collection's scope.
     *
     * @param key   the collection's scope key
     * @param value the value named; null when the request named none
     * @param raw   the object exactly as sent
     */
    public record Scope(String key, String value, Map<String, Object> raw) {}

    /**
     * How the search ran.
     *
     * @param plan       the plan, as sent
     * @param candidates the candidates considered
     * @param fallback   true when an exact rerun made up a short candidate list
     * @param raw        the object exactly as sent
     */
    public record Search(String plan, Long candidates, Boolean fallback, Map<String, Object> raw) {}
}
