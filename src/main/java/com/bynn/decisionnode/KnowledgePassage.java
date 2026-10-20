package com.bynn.decisionnode;

import java.util.Map;

/**
 * One passage of a {@link KnowledgeReading}: a part of one document of the knowledge base, with
 * where it comes from.
 *
 * @param n         the passage's number in the block the model reads, from 1
 * @param pinned    true for a pinned document's passage, always given to the model
 * @param item      the id of the document ({@code itm_...})
 * @param chunk     the passage's chunk of that document
 * @param byteStart where the passage starts in the document's stored text: a UTF-8 byte offset,
 *                  as {@code getItem} with the text gives it. Encode that text to UTF-8 before
 *                  slicing it by these offsets, since a Java string counts UTF-16 units
 * @param byteEnd   where it ends, exclusive, a UTF-8 byte offset
 * @param reference the document's reference; null when it has none, or for a publishable key
 * @param title     the document's title; null for a publishable key
 * @param heading   the heading the passage is under; null when there is none, or for a
 *                  publishable key
 * @param tokens    the passage's tokens; null when absent
 * @param score     the similarity, 4 decimals as sent; null for a pin
 * @param lexical   true when the passage was found by its words; null when absent
 * @param rendered  false when the passage was left out to fit the request; null on a search's
 *                  passages
 * @param text      the passage's text, with {@code include_text} and on a search; null otherwise
 * @param raw       the passage exactly as sent
 */
public record KnowledgePassage(
        long n,
        boolean pinned,
        String item,
        long chunk,
        long byteStart,
        long byteEnd,
        String reference,
        String title,
        String heading,
        Long tokens,
        Double score,
        Boolean lexical,
        Boolean rendered,
        String text,
        Map<String, Object> raw) {}
