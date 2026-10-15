package com.bynn.decisionnode;

/**
 * A {@link SegmentInstance}'s distances in meters, estimates from one picture, for decisions and
 * planning, never a safety stop.
 *
 * @param median            the median distance to the instance; null when none
 * @param openingMedian     the median distance through its opening; null when none
 * @param openingRimMedian  the median distance to its opening's rim; null when none
 */
public record SegmentDepth(Double median, Double openingMedian, Double openingRimMedian) {}
