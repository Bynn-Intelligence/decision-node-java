package com.bynn.decisionnode;

import java.util.List;
import java.util.Map;

/**
 * A reading of Depth Vision ({@code bynn_depth}): distances in meters to what one picture shows.
 * Every number is kept as sent. These are estimates from one picture, for decisions and planning,
 * never a safety stop.
 *
 * @param image       the id of the image read
 * @param ahead       meters to the nearest obstacle straight ahead, the floor left out; null when
 *                    none is found
 * @param directions  seven distances in meters, from left to right, each null when none is found
 * @param ground      whether a floor was found and left out; when false, {@code ahead} and
 *                    {@code directions} include everything
 * @param gridRows    the grid's rows (the {@code grid_size} option, 8 by default)
 * @param gridCols    the grid's columns
 * @param grid        meters, row by row from the top left
 * @param depthMin    the nearest distance in the picture, in meters
 * @param depthMax    the farthest distance, in meters
 * @param depthMedian the median distance, in meters
 * @param width       the picture's width in pixels
 * @param height      the picture's height in pixels
 * @param raw         the reading exactly as sent
 */
public record DepthReading(
        String image,
        Double ahead,
        List<Double> directions,
        boolean ground,
        long gridRows,
        long gridCols,
        List<List<Double>> grid,
        double depthMin,
        double depthMax,
        double depthMedian,
        long width,
        long height,
        Map<String, Object> raw)
        implements Reading {
    @Override
    public String booster() {
        return "bynn_depth";
    }
}
