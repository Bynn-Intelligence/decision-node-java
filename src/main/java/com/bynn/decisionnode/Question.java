package com.bynn.decisionnode;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One question in its wire form, made by {@link Questions#truth}, {@link Questions#choice},
 * {@link Questions#score}, {@link Questions#number}, {@link Questions#point} or {@link Questions#box}. A plain map in the same wire form is accepted wherever a
 * {@code Question} is.
 */
public final class Question {
    private final Map<String, Object> wire;

    Question(Map<String, Object> wire) {
        this.wire = Collections.unmodifiableMap(new LinkedHashMap<>(wire));
    }

    /** The question type: {@code truth}, {@code choice}, {@code score}, {@code number}, {@code point} or {@code box}. */
    public String type() {
        return (String) wire.get("type");
    }

    /** The wire form, exactly as it is sent. */
    @JsonValue
    public Map<String, Object> toMap() {
        return wire;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Question && ((Question) o).wire.equals(wire);
    }

    @Override
    public int hashCode() {
        return Objects.hash(wire);
    }

    @Override
    public String toString() {
        return "Question" + wire;
    }
}
