package com.bynn.decisionnode;

import java.util.List;
import java.util.Objects;

/**
 * One frame for a session: a reading (text or any JSON value), an image, or both.
 *
 * <pre>{@code
 * session.send(Frame.of(Map.of("battery", 0.31, "wind_mps", 11.4)));
 * session.send(Frame.builder().frame("camera 2").image(Image.fromBytes("frame", jpeg)).build());
 * }</pre>
 *
 * <p>A frame's image follows the client's inline threshold: under it, sent inline; at or above
 * it, uploaded first and sent by upload id; bytes uploaded before go by their cached upload id. A
 * frame takes no URL image.
 */
public final class Frame {
    private final Object frame;
    private final Object image;
    private final List<String> questions;
    private final Boolean reset;
    private final Long seq;

    private Frame(Builder b) {
        this.frame = b.frame;
        this.image = b.image;
        this.questions = b.questions == null ? null : List.copyOf(b.questions);
        this.reset = b.reset;
        this.seq = b.seq;
    }

    /** A frame holding this reading. */
    public static Frame of(Object frame) {
        return builder().frame(frame).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The reading: a string or any value that serialises to JSON; null when none. */
    public Object frame() {
        return frame;
    }

    /** The image: an {@link Image} or a wire-form map; null when none. */
    public Object image() {
        return image;
    }

    /** The ids of the session's questions to ask for this frame; null for all. */
    public List<String> questions() {
        return questions;
    }

    /** True to empty the window before this frame is answered; null to leave it. */
    public Boolean reset() {
        return reset;
    }

    /** The frame's own number; null for the session's counter. */
    public Long seq() {
        return seq;
    }

    public static final class Builder {
        private Object frame;
        private Object image;
        private List<String> questions;
        private Boolean reset;
        private Long seq;

        private Builder() {}

        public Builder frame(Object frame) {
            this.frame = frame;
            return this;
        }

        /** An {@link Image} (bytes or an upload id, never a URL) or a wire-form map. */
        public Builder image(Object image) {
            this.image = image;
            return this;
        }

        public Builder questions(List<String> questions) {
            this.questions = questions;
            return this;
        }

        public Builder reset(Boolean reset) {
            this.reset = reset;
            return this;
        }

        /**
         * The frame's number. Left out, the session counts from 1, so every frame carries one.
         */
        public Builder seq(Long seq) {
            this.seq = seq;
            return this;
        }

        public Frame build() {
            return new Frame(this);
        }
    }

    @Override
    public String toString() {
        return "Frame{seq=" + seq + ", frame=" + (frame == null ? null : "...") + ", image=" + Objects.toString(image) + "}";
    }
}
