package com.bynn.decisionnode;

/**
 * A {@link SegmentInstance}'s box, as fractions of the picture from its top left.
 *
 * @param x1 the top-left corner, from the left
 * @param y1 the top-left corner, from the top
 * @param x2 the bottom-right corner, from the left
 * @param y2 the bottom-right corner, from the top
 */
public record SegmentBox(double x1, double y1, double x2, double y2) {}
