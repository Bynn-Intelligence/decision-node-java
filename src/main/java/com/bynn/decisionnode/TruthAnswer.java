package com.bynn.decisionnode;

import java.util.Map;

/**
 * The answer to a {@code truth} question.
 *
 * @param truth the probability, from 0 to 1, that the statement is true
 * @param raw   the answer exactly as sent
 */
public record TruthAnswer(double truth, Map<String, Object> raw) implements Answer {
    @Override
    public String type() {
        return "truth";
    }
}
