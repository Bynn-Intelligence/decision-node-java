package com.bynn.decisionnode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One request of a batch: a decision request and the {@code custom_id} its result is found by.
 *
 * <pre>{@code
 * BatchRequest r = BatchRequest.builder("ticket-1")
 *     .state("I was charged twice.")
 *     .question("refund", truth("Is a refund asked for?"))
 *     .build();
 * }</pre>
 *
 * <p>Its local images follow the client's inline threshold, counted per request; uploaded ones
 * are held by the batch until it runs. Its videos go inline, as on a decision, and its boosters
 * are sent as a live request's are.
 */
public final class BatchRequest {
    private final String customId;
    private final Object state;
    private final Map<String, Object> questions;
    private final List<Object> images;
    private final List<Object> videos;
    private final List<Object> boosters;
    private final String model;

    private BatchRequest(Builder b) {
        this.customId = b.customId;
        this.state = b.state;
        this.questions = Collections.unmodifiableMap(new LinkedHashMap<>(b.questions));
        this.images = Collections.unmodifiableList(new ArrayList<>(b.images));
        this.videos = Collections.unmodifiableList(new ArrayList<>(b.videos));
        this.boosters = Collections.unmodifiableList(new ArrayList<>(b.boosters));
        this.model = b.model;
    }

    /**
     * A request with this {@code custom_id}: 1 to 512 characters, unique within the batch. A
     * missing, empty or repeated id is refused with the API's {@link BadRequestError} before
     * anything is sent.
     */
    public static Builder builder(String customId) {
        return new Builder(customId);
    }

    /** A decision request under this {@code custom_id}. */
    public static BatchRequest of(String customId, DecideRequest request) {
        return builder(customId).state(request.state()).questions(request.questions()).images(request.images())
                .videos(request.videos()).boosters(request.boosters()).model(request.model()).build();
    }

    public String customId() {
        return customId;
    }

    public Object state() {
        return state;
    }

    public Map<String, Object> questions() {
        return questions;
    }

    public List<Object> images() {
        return images;
    }

    /** {@link Video}s or wire-form maps. */
    public List<Object> videos() {
        return videos;
    }

    /** {@link Booster}s, booster names or wire-form maps, in the order given. */
    public List<Object> boosters() {
        return boosters;
    }

    public String model() {
        return model;
    }

    public static final class Builder {
        private final String customId;
        private Object state;
        private final Map<String, Object> questions = new LinkedHashMap<>();
        private final List<Object> images = new ArrayList<>();
        private final List<Object> videos = new ArrayList<>();
        private final List<Object> boosters = new ArrayList<>();
        private String model;

        private Builder(String customId) {
            this.customId = customId;
        }

        public Builder state(Object state) {
            this.state = state;
            return this;
        }

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

        public Builder image(Image image) {
            images.add(image);
            return this;
        }

        public Builder images(List<?> images) {
            if (images != null) {
                this.images.addAll(images);
            }
            return this;
        }

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

        /** Adds a booster by name: it reads every image of the request. */
        public Builder booster(String name) {
            boosters.add(name);
            return this;
        }

        /** Adds a booster with its settings. */
        public Builder booster(Booster booster) {
            boosters.add(booster);
            return this;
        }

        /** Adds boosters: {@link Booster}s, names or wire-form maps, sent in this order. */
        public Builder boosters(List<?> boosters) {
            if (boosters != null) {
                this.boosters.addAll(boosters);
            }
            return this;
        }

        /** The model for this request; null or empty for the client's. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public BatchRequest build() {
            return new BatchRequest(this);
        }
    }
}
