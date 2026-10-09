package com.bynn.decisionnode;

/**
 * One booster's status and time, from the {@code x-decisionnode-boosters} response header, read by
 * {@link DecisionNodeError#boosters()}.
 *
 * @param status    {@code ok}, {@code timeout}, {@code error}, {@code skipped}, or any other value as sent
 * @param latencyMs the booster's time in milliseconds; null when the header did not carry a number
 */
public record BoosterStatus(String status, Double latencyMs) {}
