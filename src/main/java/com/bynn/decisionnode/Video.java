package com.bynn.decisionnode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A video sent beside the state as the frames the caller sampled, each with its time in seconds.
 *
 * <pre>{@code
 * List<VideoFrame> frames = List.of(
 *     VideoFrame.fromBytes(0.0, Files.readAllBytes(Path.of("dock-0000.jpg"))),
 *     VideoFrame.fromBytes(0.5, Files.readAllBytes(Path.of("dock-0001.jpg"))));
 * Video dock = Video.of("dock", frames).fps(2);
 * }</pre>
 *
 * <p>Every question sees every video, the images and the state. The server reads the frames as
 * one video, in order and with their times, not as separate images. Only frames are sent: no
 * video file, no video URL and no sound. A video in its JSON wire form, a map, is accepted
 * wherever a {@code Video} is, and is checked by the same rules.
 */
public final class Video {
    private final String id;
    private final List<Object> frames;
    private final Double fps;
    private final Double start;
    private final Double end;

    private Video(String id, List<Object> frames, Double fps, Double start, Double end) {
        this.id = id;
        this.frames = frames;
        this.fps = fps;
        this.start = start;
        this.end = end;
    }

    /**
     * A video with this id and these frames, in time order: {@link VideoFrame}s or wire-form maps.
     * The id must be unique across the request's images and videos; text in the state or the
     * instructions can refer to the video by it.
     */
    public static Video of(String id, List<?> frames) {
        Objects.requireNonNull(frames, "frames");
        return new Video(id, Collections.unmodifiableList(new ArrayList<>(frames)), null, null, null);
    }

    /** The most frames a second the server reads: above 0 and at most 4 (the API's default is 2). */
    public Video fps(double fps) {
        return new Video(id, frames, fps, start, end);
    }

    /** The start of the window read, on the frames' own times: a frame is read when {@code start <= t < end}. */
    public Video start(double seconds) {
        return new Video(id, frames, fps, seconds, end);
    }

    /** The end of the window read (not included); it must be after the start (0 when not given). */
    public Video end(double seconds) {
        return new Video(id, frames, fps, start, seconds);
    }

    public String id() {
        return id;
    }

    /** The frames: {@link VideoFrame}s or wire-form maps. */
    public List<Object> frames() {
        return frames;
    }

    /** The rate cap, or null when not given. */
    public Double fps() {
        return fps;
    }

    /** The window's start, or null when not given. */
    public Double start() {
        return start;
    }

    /** The window's end, or null when not given. */
    public Double end() {
        return end;
    }

    /** The wire form: {@code {"id", "fps", "start", "end", "frames"}}, the optional numbers only when given. */
    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        if (fps != null) {
            out.put("fps", fps);
        }
        if (start != null) {
            out.put("start", start);
        }
        if (end != null) {
            out.put("end", end);
        }
        List<Object> wire = new ArrayList<>(frames.size());
        for (Object f : frames) {
            wire.add(f instanceof VideoFrame ? ((VideoFrame) f).toMap() : f);
        }
        out.put("frames", wire);
        return out;
    }

    @Override
    public String toString() {
        return "Video{id=" + id + ", frames=" + frames.size() + ", fps=" + fps + ", start=" + start + ", end=" + end + "}";
    }
}
