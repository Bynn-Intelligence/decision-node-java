package com.bynn.decisionnode;

import java.util.Map;

/**
 * One answer of a {@link Decision}. It is a {@link TruthAnswer}, a {@link ChoiceAnswer}, a
 * {@link ScoreAnswer}, a {@link NumberAnswer}, a {@link PointAnswer}, a {@link BoxAnswer}, or an
 * {@link UnknownAnswer} for a question type this SDK version does not know yet.
 */
public sealed interface Answer
        permits TruthAnswer, ChoiceAnswer, ScoreAnswer, NumberAnswer, PointAnswer, BoxAnswer, UnknownAnswer {

    /**
     * The answer's type: {@code truth}, {@code choice}, {@code score}, {@code number},
     * {@code point}, {@code box}, or the type as sent for an {@link UnknownAnswer}.
     */
    String type();

    /** The answer exactly as the API sent it. */
    Map<String, Object> raw();
}
