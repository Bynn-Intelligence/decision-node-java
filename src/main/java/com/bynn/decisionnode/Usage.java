package com.bynn.decisionnode;

/**
 * The tokens a decision used.
 *
 * @param inputTokens  tokens read; null when the response did not say
 * @param outputTokens tokens written; null when the response did not say
 */
public record Usage(Long inputTokens, Long outputTokens) {}
