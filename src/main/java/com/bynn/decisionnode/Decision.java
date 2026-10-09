package com.bynn.decisionnode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The result of one {@code POST /v1/decide}: one answer per question, read by question id.
 *
 * <pre>{@code
 * Decision d = dn.decide(state, questions);
 * d.choice("route").choice();     // "billing"
 * d.truth("escalate").truth();    // 0.94
 * }</pre>
 */
public final class Decision {
    private final Map<String, Answer> answers;
    private final String model;
    private final Usage usage;
    private final String requestId;
    private final Double latencyMs;
    private final Safety safety;
    private final List<String> warnings;
    private final Map<String, Evidence> evidence;
    private final Map<String, Object> raw;

    Decision(Map<String, Answer> answers, String model, Usage usage, String requestId, Double latencyMs, Safety safety,
            List<String> warnings, Map<String, Evidence> evidence, Map<String, Object> raw) {
        this.answers = Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        this.model = model;
        this.usage = usage;
        this.requestId = requestId;
        this.latencyMs = latencyMs;
        this.safety = safety;
        this.warnings = warnings;
        this.evidence = evidence == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
        this.raw = raw;
    }

    /** Every answer, by question id, in the order the API sent them. */
    public Map<String, Answer> answers() {
        return answers;
    }

    /**
     * The answer to the question with this id.
     *
     * @throws NoSuchElementException when the response has no answer with this id
     */
    public Answer get(String id) {
        Answer a = answers.get(id);
        if (a == null) {
            throw new NoSuchElementException("no answer with the id '" + id + "'; the ids are " + answers.keySet());
        }
        return a;
    }

    /** The answer to a {@code truth} question. */
    public TruthAnswer truth(String id) {
        return as(id, TruthAnswer.class);
    }

    /** The answer to a {@code choice} question. */
    public ChoiceAnswer choice(String id) {
        return as(id, ChoiceAnswer.class);
    }

    /** The answer to a {@code score} question. */
    public ScoreAnswer score(String id) {
        return as(id, ScoreAnswer.class);
    }

    /** The answer to a {@code number} question. */
    public NumberAnswer number(String id) {
        return as(id, NumberAnswer.class);
    }

    /** The answer to a {@code point} question. */
    public PointAnswer point(String id) {
        return as(id, PointAnswer.class);
    }

    /** The answer to a {@code box} question. */
    public BoxAnswer box(String id) {
        return as(id, BoxAnswer.class);
    }

    private <T extends Answer> T as(String id, Class<T> type) {
        Answer a = get(id);
        if (!type.isInstance(a)) {
            throw new IllegalStateException("the answer '" + id + "' is of type '" + a.type() + "', not " + type.getSimpleName());
        }
        return type.cast(a);
    }

    /**
     * What each booster the request named read, by booster name in the request's order; null when
     * the request named none (and on a refused request).
     */
    public Map<String, Evidence> evidence() {
        return evidence;
    }

    /** What the booster of this name read; null when the response carries no evidence from it. */
    public Evidence evidence(String booster) {
        return evidence == null ? null : evidence.get(booster);
    }

    /**
     * The model version that answered, for example {@code decisionnode-1.0}; for a fine-tuned model
     * its pinned name, for example {@code decisionnode-1.0-flash@acme/fraud:3}.
     */
    public String model() {
        return model;
    }

    /** The tokens this decision used and, for a request with videos, the frames and seconds read. */
    public Usage usage() {
        return usage;
    }

    /** The {@code x-request-id} response header. */
    public String requestId() {
        return requestId;
    }

    /** The server's processing time in milliseconds, from {@code x-decisionnode-latency-ms}; null when absent. */
    public Double latencyMs() {
        return latencyMs;
    }

    /** The safety check, from {@code x-decisionnode-safety}; null when the server sent none. */
    public Safety safety() {
        return safety;
    }

    /**
     * The server's notes on a request with videos, exactly as sent: one when it read a video at a
     * lower rate or resolution than asked. Empty when there are none.
     */
    public List<String> warnings() {
        return warnings;
    }

    /** The response body as parsed JSON, unknown fields included. */
    public Map<String, Object> raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "Decision{model=" + model + ", answers=" + answers + ", requestId=" + requestId + "}";
    }
}
