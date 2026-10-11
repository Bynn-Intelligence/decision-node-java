package com.bynn.decisionnode;

import java.util.Map;

/**
 * What one answer cost, in USD, from the answer's {@code usage.cost}. Every amount is read exactly
 * as the API sent it: {@code usd} is the amount booked for the answer, never computed here from the
 * two parts (adding them as doubles can differ from the booked amount in the last digits).
 *
 * @param usd         the amount booked for this answer: the model's part plus the boosters' part,
 *                    at your workspace's rates; null when absent or not a number
 * @param modelUsd    the model's part; 0 when the free tier covered the request; null when absent or
 *                    not a number
 * @param boostersUsd the boosters' part; null when absent or not a number
 * @param freeTier    true when the free tier covered the request; null when absent or not a boolean
 * @param raw         the cost object exactly as sent
 */
public record Cost(Double usd, Double modelUsd, Double boostersUsd, Boolean freeTier, Map<String, Object> raw) {}
