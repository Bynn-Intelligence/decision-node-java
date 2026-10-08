package com.bynn.decisionnode;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One frame of a {@link Video}: an image (JPEG, PNG or WebP) and its time in seconds.
 *
 * <pre>{@code
 * VideoFrame.fromBytes(0.5, jpegBytes);                      // media type from the first bytes
 * VideoFrame.fromBytes(0.5, bytes, "image/png");
 * VideoFrame.fromBase64(0.5, base64Text, "image/jpeg");      // sent exactly as given
 * }</pre>
 *
 * <p>Frames always travel inline as base64: they are never uploaded, never cached and never
 * resized or re-encoded. A frame in its JSON wire form, a map with {@code t}, {@code media_type}
 * and {@code data}, is accepted wherever a {@code VideoFrame} is.
 */
public final class VideoFrame {
    private final double t;
    private final byte[] data;
    private final String text;
    private final String mediaType;

    private VideoFrame(double t, byte[] data, String text, String mediaType) {
        this.t = t;
        this.data = data;
        this.text = text;
        this.mediaType = mediaType == null || mediaType.isEmpty() ? null : mediaType;
    }

    /** A frame from image bytes taken {@code t} seconds into the video, its media type read from the first bytes. The bytes are copied. */
    public static VideoFrame fromBytes(double t, byte[] data) {
        return fromBytes(t, data, null);
    }

    /**
     * A frame from image bytes taken {@code t} seconds into the video, with its media type:
     * {@code image/jpeg}, {@code image/png} or {@code image/webp}. A null or empty media type is
     * read from the first bytes. The bytes are copied.
     */
    public static VideoFrame fromBytes(double t, byte[] data, String mediaType) {
        Objects.requireNonNull(data, "data");
        return new VideoFrame(t, data.clone(), null, mediaType);
    }

    /**
     * A frame from image bytes given as base64 text, sent exactly as given, taken {@code t}
     * seconds into the video, with its media type: {@code image/jpeg}, {@code image/png} or
     * {@code image/webp}.
     */
    public static VideoFrame fromBase64(double t, String base64, String mediaType) {
        Objects.requireNonNull(base64, "base64");
        return new VideoFrame(t, null, base64, mediaType);
    }

    /** The frame's time in seconds. */
    public double t() {
        return t;
    }

    /** The declared media type, else the one read from the first bytes; null when neither is known. */
    public String mediaType() {
        if (mediaType != null || data == null) {
            return mediaType;
        }
        return ImageHeader.sniff(data);
    }

    /** The frame in its wire form: {@code {"t", "media_type", "data"}}. */
    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("t", t);
        out.put("media_type", mediaType());
        out.put("data", text != null ? text : Base64.getEncoder().encodeToString(data));
        return out;
    }

    @Override
    public String toString() {
        return "VideoFrame{t=" + t + ", mediaType=" + mediaType() + ", "
                + (data != null ? data.length + " bytes" : text.length() + " base64 characters") + "}";
    }
}
