package com.bynn.decisionnode;

import java.util.Map;

/**
 * The answer to a {@code point} question: one spot in an image.
 *
 * <p>Every number is the JSON number the API sent: whole pixels, or fractions of the width and the
 * height when the question asked for {@code normalized} units.
 *
 * @param x          the spot, from the left edge of the image
 * @param y          the spot, down from the top edge
 * @param present    the probability that what the instructions describe is in the image at all;
 *                   under a low value the spot is only the best guess
 * @param confidence the probability that the spot lies on the thing, if it is there
 * @param image      the id of the image looked in
 * @param width      the image's width in pixels
 * @param height     the image's height in pixels
 * @param raw        the answer exactly as sent
 */
public record PointAnswer(
        double x,
        double y,
        double present,
        double confidence,
        String image,
        double width,
        double height,
        Map<String, Object> raw)
        implements Answer {
    @Override
    public String type() {
        return "point";
    }
}
