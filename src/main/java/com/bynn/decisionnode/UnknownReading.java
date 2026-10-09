package com.bynn.decisionnode;

/**
 * A reading of a booster this SDK version does not know, or a reading whose fields are missing or
 * of the wrong type. Its fields are in {@link #raw()}, so a new booster on the server does not
 * break an older client and one odd reading never fails the decision.
 *
 * @param booster the name of the booster that read
 * @param raw     the reading exactly as sent: usually a map, or any other JSON value
 */
public record UnknownReading(String booster, Object raw) implements Reading {}
