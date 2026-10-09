package com.bynn.decisionnode;

import java.util.Map;

/**
 * A reading of the {@code age} booster: how old the person in one image is. The largest face is
 * read. With {@code faces} 0 the image shows no face and the age fields are null.
 *
 * @param image      the id of the image read
 * @param faces      how many faces were found
 * @param ageYears   the age in years; null when no face was found
 * @param low        the low end of the range, in years; null when no face was found
 * @param high       the high end of the range, in years; null when no face was found
 * @param confidence how sure the reading is, 0 to 1; null when no face was found
 * @param raw        the reading exactly as sent
 */
public record AgeReading(
        String image, long faces, Long ageYears, Long low, Long high, Double confidence, Map<String, Object> raw)
        implements Reading {
    @Override
    public String booster() {
        return "age";
    }
}
