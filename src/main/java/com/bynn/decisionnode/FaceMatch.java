package com.bynn.decisionnode;

/**
 * One face a {@link FaceRecognitionReading} matched in a collection.
 *
 * @param box        where the face is
 * @param score      how well the face matches the item, 0 to 1
 * @param collection the id of the collection searched
 * @param reference  the matched item's reference, as it was enrolled
 * @param name       the person's name, for an item of a public collection that has one; null when
 *                   absent
 * @param label      the item's label, for an item of your own collection that has one; null when
 *                   absent
 */
public record FaceMatch(FaceBox box, double score, String collection, String reference, String name, String label) {}
