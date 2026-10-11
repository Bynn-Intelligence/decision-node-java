package com.bynn.decisionnode;

import java.util.List;
import java.util.Map;

/**
 * A reading of the {@code wanted_person} booster: whether a face in one image matches an entry of
 * the wanted-persons list. The booster's {@code threshold} option, sent as given, leaves out
 * matches below it.
 *
 * @param image   the id of the image read
 * @param faces   how many faces were checked against the list; it may be more than
 *                {@code matches} lists
 * @param matches the faces that match, in the order sent, each with its box, score and the entry's
 *                reference id; empty when nothing matches
 * @param raw     the reading exactly as sent
 */
public record WantedPersonReading(String image, long faces, List<WantedMatch> matches, Map<String, Object> raw)
        implements Reading {
    @Override
    public String booster() {
        return "wanted_person";
    }
}
