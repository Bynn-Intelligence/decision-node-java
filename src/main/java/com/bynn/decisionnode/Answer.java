package com.bynn.decisionnode;

import java.util.Map;

/**
 * One answer of a {@link Decision}. It is a {@link TruthAnswer}, a {@link ChoiceAnswer}, a
 * {@link ScoreAnswer}, or an {@link UnknownAnswer} for a question type this SDK version does not
 * know yet.
 */
public sealed interface Answer permits TruthAnswer, ChoiceAnswer, ScoreAnswer, UnknownAnswer {

    /** The answer's {@code type} as sent: {@code truth}, {@code choice}, {@code score} or a newer one. */
    String type();

    /** The answer exactly as the API sent it. */
    Map<String, Object> raw();
}
