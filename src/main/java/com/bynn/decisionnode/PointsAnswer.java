package com.bynn.decisionnode;

import java.util.List;
import java.util.Map;

/**
 * The answer to a {@code point} question asked with {@code max}: up to that many spots in an
 * image, left to right as sent. The numbers are in the units the question asked for, exactly as
 * sent.
 *
 * @param count    how many of the things described the image shows; it may be more than
 *                 {@code points} lists
 * @param present  the probability that the image shows at least one of them
 * @param complete the probability that {@code points} holds every one of them
 * @param points   the spots, in the order sent
 * @param image    the id of the image looked in
 * @param width    the image's width in pixels
 * @param height   the image's height in pixels
 * @param raw      the answer exactly as sent
 */
public record PointsAnswer(
        long count,
        double present,
        double complete,
        List<PlacePoint> points,
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
