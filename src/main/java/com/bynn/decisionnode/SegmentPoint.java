package com.bynn.decisionnode;

/**
 * A point of a {@link SegmentInstance}, as fractions of the picture from its top left, or an
 * opening's offset from the box's centre.
 *
 * @param x from the left
 * @param y from the top
 */
public record SegmentPoint(double x, double y) {}
