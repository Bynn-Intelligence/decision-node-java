package com.bynn.decisionnode;

import java.util.List;

/**
 * One match of a {@link SegmentReading}. Coordinates are fractions of the picture from its top
 * left; distances and sizes are estimates from one picture, for decisions and planning, never a
 * safety stop.
 *
 * @param label    the phrase it matched
 * @param score    how well it matches, 0 to 1
 * @param box      its box
 * @param polygon  its outline, at most {@code polygon_points} points
 * @param area     its share of the picture
 * @param centroid its centre
 * @param opening  the largest hole it encloses; null when it has none
 * @param depth    its distances in meters; null with depth off or unavailable
 * @param sizeM    its size in meters; null with depth off or unavailable
 */
public record SegmentInstance(
        String label,
        double score,
        SegmentBox box,
        List<SegmentPoint> polygon,
        double area,
        SegmentPoint centroid,
        SegmentOpening opening,
        SegmentDepth depth,
        SegmentSize sizeM) {}
