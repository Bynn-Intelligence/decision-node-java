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
 * are held by the batch until it runs.
 */
public final class BatchRequest {
    private final String customId;
    private final Object state;
    private final Map<String, Object> questions;
    private final List<Object> images;
    private final String model;

    private BatchRequest(Builder b) {
        this.customId = b.customId;
        this.state = b.state;
        this.questions = Collections.unmodifiableMap(new LinkedHashMap<>(b.questions));
        this.images = Collections.unmodifiableList(new ArrayList<>(b.images));
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
                .model(request.model()).build();
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

    public String model() {
        return model;
    }

    public static final class Builder {
        private final String customId;
        private Object state;
        private final Map<String, Object> questions = new LinkedHashMap<>();
        private final List<Object> images = new ArrayList<>();
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
