package com.bynn.decisionnode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a session is opened with: the questions it answers about every frame, and optionally a
 * state, instructions, a model, the window and the time to live.
 *
 * <pre>{@code
 * SessionRequest req = SessionRequest.builder()
 *     .state(Map.of("mission", "Survey the north field"))
 *     .question("abort", truth("Should the mission be aborted now?"))
 *     .window(8)
 *     .build();
 * }</pre>
 */
public final class SessionRequest {
    private final Object state;
    private final Map<String, Object> questions;
    private final Object instructions;
    private final String model;
    private final Integer window;
    private final Integer ttlSeconds;

    private SessionRequest(Builder b) {
        this.state = b.state;
        this.questions = Collections.unmodifiableMap(new LinkedHashMap<>(b.questions));
        this.instructions = b.instructions;
        this.model = b.model;
        this.window = b.window;
        this.ttlSeconds = b.ttlSeconds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Object state() {
        return state;
    }

    public Map<String, Object> questions() {
        return questions;
    }

    public Object instructions() {
        return instructions;
    }

    public String model() {
        return model;
    }

    public Integer window() {
        return window;
    }

    public Integer ttlSeconds() {
        return ttlSeconds;
    }

    public static final class Builder {
        private Object state;
        private final Map<String, Object> questions = new LinkedHashMap<>();
        private Object instructions;
        private String model;
        private Integer window;
        private Integer ttlSeconds;

        private Builder() {}

        /** What every frame is judged against: a string or any value that serialises to JSON. */
        public Builder state(Object state) {
            this.state = state;
            return this;
        }

        /** Adds a question: a {@link Question} or a wire-form map. */
        public Builder question(String id, Object question) {
            questions.put(id, question);
            return this;
        }

        public Builder questions(Map<String, ?> questions) {
            if (questions != null) {
                this.questions.putAll(questions);
            }
            return this;
        }

        public Builder instructions(Object instructions) {
            this.instructions = instructions;
            return this;
        }

        /** The model; null or empty for the client's. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** How many recent frames each answer sees. */
        public Builder window(Integer window) {
            this.window = window;
            return this;
        }

        /** How long the session lives, in seconds. */
        public Builder ttlSeconds(Integer ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
            return this;
        }

        public SessionRequest build() {
            return new SessionRequest(this);
        }
    }
}
