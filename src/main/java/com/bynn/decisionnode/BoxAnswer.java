package com.bynn.decisionnode;

import java.util.Map;

/**
 * The answer to a {@code box} question: a rectangle in an image, in the units of a
 * {@link PointAnswer}.
 *
 * @param x1         the top-left corner, from the left edge of the image
 * @param y1         the top-left corner, down from the top edge
 * @param x2         the bottom-right corner, from the left edge
 * @param y2         the bottom-right corner, down from the top edge
 * @param present    the probability that what the instructions describe is in the image at all
 * @param confidence the probability that the box overlaps the thing's true box by an intersection
 *                   over union of 0.5 or more, if it is there
 * @param image      the id of the image looked in
 * @param width      the image's width in pixels
 * @param height     the image's height in pixels
 * @param raw        the answer exactly as sent
 */
public record BoxAnswer(
        double x1,
        double y1,
        double x2,
        double y2,
        double present,
        double confidence,
        String image,
        double width,
        double height,
        Map<String, Object> raw)
        implements Answer {
    @Override
    public String type() {
        return "box";
    }
}
