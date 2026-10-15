package com.bynn.decisionnode;

import java.util.List;
import java.util.Map;

/**
 * A reading of Segment Vision ({@code bynn_segment}): what one picture shows of the phrases the
 * {@code prompt} option names, each match's outline, box and centre and, with depth on, its
 * distance and size in meters. Coordinates are fractions of the picture from its top left; every
 * number is kept as sent. Distances and sizes are estimates from one picture, for decisions and
 * planning, never a safety stop.
 *
 * @param image          the id of the image read
 * @param width          the picture's width in pixels
 * @param height         the picture's height in pixels
 * @param prompts        the phrases as read
 * @param instances      the matches, by score, in the order sent; empty when none is found
 * @param depthSource    where the distances come from ({@code bynn_depth}); null with depth off or
 *                       unavailable
 * @param assumedHfovDeg the horizontal field of view, in degrees, the sizes assume; null when absent
 * @param raw            the reading exactly as sent
 */
public record SegmentReading(
        String image,
        long width,
        long height,
        List<String> prompts,
        List<SegmentInstance> instances,
        String depthSource,
        Double assumedHfovDeg,
        Map<String, Object> raw)
        implements Reading {
    @Override
    public String booster() {
        return "bynn_segment";
    }
}
