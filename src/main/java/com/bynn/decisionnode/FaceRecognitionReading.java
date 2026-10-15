package com.bynn.decisionnode;

import java.util.List;
import java.util.Map;

/**
 * A reading of the {@code face_recognition} booster: the faces one image shows, searched in the
 * collection its {@code collection} option names (required: one of your own collections, or
 * {@code celebrities}); its {@code threshold} option (0 to 1, default 0.5) leaves out matches
 * below it.
 *
 * @param image   the id of the image read
 * @param faces   how many faces were searched; it may be more than {@code matches} lists
 * @param matches the faces that match an item, in the order sent, each with its box, score, the
 *                collection, the item's reference and its name or label; empty when nothing
 *                matches
 * @param collectionName the searched collection's display name, for your own collection; null when
 *                absent
 * @param raw     the reading exactly as sent
 */
public record FaceRecognitionReading(String image, long faces, List<FaceMatch> matches, String collectionName, Map<String, Object> raw)
        implements Reading {
    @Override
    public String booster() {
        return "face_recognition";
    }
}
