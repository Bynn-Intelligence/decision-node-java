package com.bynn.decisionnode;

import java.util.List;
import java.util.Map;

/**
 * A reading of the {@code wanted_person} booster, its fields as sent.
 *
 * @param image   the id of the image read
 * @param faces   the {@code faces} count as sent; it may be more than {@code matches} lists
 * @param matches the {@code matches} as sent, in order
 * @param raw     the reading exactly as sent
 */
public record WantedPersonReading(String image, long faces, List<WantedMatch> matches, Map<String, Object> raw)
        implements Reading {
    @Override
    public String booster() {
        return "wanted_person";
    }
}
