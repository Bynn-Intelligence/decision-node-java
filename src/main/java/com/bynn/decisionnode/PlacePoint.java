package com.bynn.decisionnode;

/**
 * One spot of a {@link PointsAnswer}, in the answer's units, exactly as sent.
 *
 * @param x          the spot, from the left edge of the image
 * @param y          the spot, down from the top edge
 * @param confidence the probability that the spot lies on one of the things described
 */
public record PlacePoint(double x, double y, double confidence) {}
