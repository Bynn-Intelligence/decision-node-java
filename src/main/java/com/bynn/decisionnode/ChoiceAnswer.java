package com.bynn.decisionnode;

import java.util.Map;

/**
 * The answer to a {@code choice} question.
 *
 * @param choice        the most likely option, by its name exactly as sent
 * @param confidence    how clearly that option leads, from 0 to 1
 * @param probabilities the probability of every option, by name, in the order sent
 * @param raw           the answer exactly as sent
 */
public record ChoiceAnswer(String choice, double confidence, Map<String, Double> probabilities, Map<String, Object> raw)
        implements Answer {
    @Override
    public String type() {
        return "choice";
    }
}
