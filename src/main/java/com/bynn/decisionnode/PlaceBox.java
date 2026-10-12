package com.bynn.decisionnode;

/**
 * One rectangle of a {@link BoxesAnswer}, in the answer's units, exactly as sent.
 *
 * @param x1         the top-left corner, from the left edge of the image
 * @param y1         the top-left corner, down from the top edge
 * @param x2         the bottom-right corner, from the left edge
 * @param y2         the bottom-right corner, down from the top edge
 * @param confidence the probability that the box holds one of the things described
 */
public record PlaceBox(double x1, double y1, double x2, double y2, double confidence) {}
