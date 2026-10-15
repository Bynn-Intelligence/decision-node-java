package com.bynn.decisionnode;

/**
 * A {@link SegmentInstance}'s size in meters, an estimate from one picture, for decisions and
 * planning, never a safety stop.
 *
 * @param width   its width
 * @param height  its height
 * @param opening its opening's size; null when it has none
 */
public record SegmentSize(double width, double height, Opening opening) {
    /**
     * An opening's size in meters.
     *
     * @param width  its width
     * @param height its height
     */
    public record Opening(double width, double height) {}
}
