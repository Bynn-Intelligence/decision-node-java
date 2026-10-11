package com.bynn.decisionnode;

/**
 * One entry of a {@link WantedPersonReading}'s matches.
 *
 * @param box       its box
 * @param score     its {@code score}, as sent
 * @param reference its {@code reference}, as sent
 */
public record WantedMatch(FaceBox box, double score, String reference) {}
