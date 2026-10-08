package com.bynn.decisionnode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The arguments of one decision: the state, the questions by id, optional images, optional videos
 * and an optional model. Used by {@link DecisionNode#decide(DecideRequest)} and {@link DecisionNode#decideMany}.
 *
 * <pre>{@code
 * DecideRequest req = DecideRequest.builder()
 *     .state(Map.of("ticket", "I was charged twice."))
 *     .question("route", choice("Where should this go?", "billing", "bug", "account"))
 *     .question("escalate", truth("Escalate to a human now?"))
 *     .build();
 * }</pre>
 */
public final class DecideRequest {
    private final Object state;
    private final Map<String, Object> questions;
    private final List<Object> images;
    private final List<Object> videos;
    private final String model;

    private DecideRequest(Builder b) {
        this.state = b.state;
        this.questions = Collections.unmodifiableMap(new LinkedHashMap<>(b.questions));
        this.images = Collections.unmodifiableList(new ArrayList<>(b.images));
        this.videos = Collections.unmodifiableList(new ArrayList<>(b.videos));
        this.model = b.model;
    }

    /** A request with a state and questions (each a {@link Question} or a wire-form map). */
    public static DecideRequest of(Object state, Map<String, ?> questions) {
        return builder().state(state).questions(questions).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The state; null means none, sent as the empty string. */
    public Object state() {
        return state;
    }

    public Map<String, Object> questions() {
        return questions;
    }

    /** {@link Image}s or wire-form maps. */
    public List<Object> images() {
        return images;
    }

    /** {@link Video}s or wire-form maps. */
    public List<Object> videos() {
        return videos;
    }

    /** The model for this request, or null for the client's. An empty string counts as null. */
    public String model() {
        return model;
    }

    public static final class Builder {
        private Object state;
        private final Map<String, Object> questions = new LinkedHashMap<>();
        private final List<Object> images = new ArrayList<>();
        private final List<Object> videos = new ArrayList<>();
        private String model;

        private Builder() {}

        /** What is judged: a string or any value that serialises to JSON. */
        public Builder state(Object state) {
            this.state = state;
            return this;
        }

        /** Adds a question: a {@link Question} or a wire-form map. */
        public Builder question(String id, Object question) {
            questions.put(id, question);
            return this;
        }

        /** Adds every question of the map, in its iteration order. */
        public Builder questions(Map<String, ?> questions) {
            if (questions != null) {
                this.questions.putAll(questions);
            }
            return this;
        }

        public Builder image(Image image) {
            images.add(image);
            return this;
        }

        /** Adds images: {@link Image}s or wire-form maps. */
        public Builder images(List<?> images) {
            if (images != null) {
                this.images.addAll(images);
            }
            return this;
        }

        /** Adds a video, sent as its frames. Up to 4 a request. */
        public Builder video(Video video) {
            videos.add(video);
            return this;
        }

        /** Adds videos: {@link Video}s or wire-form maps. */
        public Builder videos(List<?> videos) {
            if (videos != null) {
                this.videos.addAll(videos);
            }
            return this;
        }

        /** The model for this request, for example {@code decisionnode-flash-latest}. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public DecideRequest build() {
            return new DecideRequest(this);
        }
    }
}
