package com.bynn.decisionnode;

import java.util.List;

/**
 * The largest hole a {@link SegmentInstance} encloses, such as a door's opening, as fractions of
 * the picture.
 *
 * @param polygon  its outline
 * @param centroid its centre
 * @param width    its width
 * @param height   its height
 * @param offset   its centre's offset from the instance box's centre, {@code [dx, dy]}
 * @param area     its share of the picture
 */
public record SegmentOpening(List<SegmentPoint> polygon, SegmentPoint centroid, double width, double height, SegmentPoint offset,
        double area) {}
