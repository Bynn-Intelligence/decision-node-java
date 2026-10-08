package com.bynn.decisionnode;

import java.util.Map;

/**
 * The answer to a {@code number} question.
 *
 * <p>Every value is the JSON number the API sent, unrounded and unchanged. {@code number} is
 * always a value of the question's grid, and the keys of {@code probabilities} are the grid
 * values exactly as the API wrote them ({@code "7"}, {@code "3.5"}), so look them up as strings.
 *
 * @param number        the most likely value of the grid
 * @param expected      the probability-weighted mean over the whole grid
 * @param confidence    the probability of {@code number}, from 0 to 1
 * @param probabilities the probability of each listed value, keyed by the value as the API wrote
 *                      it, in ascending order of value
 * @param raw           the answer exactly as sent
 */
public record NumberAnswer(
        double number,
        double expected,
        double confidence,
        Map<String, Double> probabilities,
        Map<String, Object> raw)
        implements Answer {
    @Override
    public String type() {
        return "number";
    }
}
