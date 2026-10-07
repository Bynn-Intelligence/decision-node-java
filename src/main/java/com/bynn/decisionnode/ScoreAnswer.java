package com.bynn.decisionnode;

import java.util.Map;

/**
 * The answer to a {@code score} question.
 *
 * @param score         the expected level, from 0 to the number of levels minus one
 * @param confidence    how concentrated the probabilities are, from 0 to 1
 * @param probabilities the probability of each level, by its index
 * @param legend        each level as it was sent, by its index
 * @param raw           the answer exactly as sent
 */
public record ScoreAnswer(
        double score,
        double confidence,
        Map<Integer, Double> probabilities,
        Map<Integer, Object> legend,
        Map<String, Object> raw)
        implements Answer {
    @Override
    public String type() {
        return "score";
    }
}
