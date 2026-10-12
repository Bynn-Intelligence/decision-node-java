package com.bynn.decisionnode;

import java.util.List;
import java.util.Map;

/**
 * The answer to a {@code box} question asked with {@code max}: up to that many rectangles in an
 * image, left to right as sent, in the units of a {@link PointsAnswer}.
 *
 * @param count    how many of the things described the image shows; it may be more than
 *                 {@code boxes} lists
 * @param present  the probability that the image shows at least one of them
 * @param complete the probability that {@code boxes} holds every one of them
 * @param boxes    the rectangles, in the order sent
 * @param image    the id of the image looked in
 * @param width    the image's width in pixels
 * @param height   the image's height in pixels
 * @param raw      the answer exactly as sent
 */
public record BoxesAnswer(
        long count,
        double present,
        double complete,
        List<PlaceBox> boxes,
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
