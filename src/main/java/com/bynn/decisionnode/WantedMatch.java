package com.bynn.decisionnode;

/**
 * One face a {@link WantedPersonReading} matched to an entry of the wanted-persons list.
 *
 * @param box       where the face is
 * @param score     how well the face matches the entry, 0 to 1
 * @param reference the list entry's reference id: an opaque id, never a name or any personal detail
 */
public record WantedMatch(FaceBox box, double score, String reference) {}
