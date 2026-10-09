package com.bynn.decisionnode;

import java.util.List;
import java.util.Map;

/**
 * What one booster read for a decision, from {@link Decision#evidence()}.
 *
 * @param version     the version of the booster that read, for example {@code age-v3}; null when
 *                    absent
 * @param status      {@code ok}; {@code timeout} (no answer within its time budget); {@code error};
 *                    {@code skipped} (none of the request's images is one it reads); or any other
 *                    value as sent. Null when absent
 * @param latencyMs   how long the booster took, in milliseconds; null when absent
 * @param billedCalls the calls of this booster billed on this request (one per image read, none
 *                    for a reading served from the last hour's results or one that timed out or
 *                    failed); null when absent
 * @param results     one reading per image read, in the order sent; empty unless {@code status} is
 *                    {@code ok}
 * @param raw         the entry exactly as sent
 */
public record Evidence(
        String version, String status, Double latencyMs, Long billedCalls, List<Reading> results, Map<String, Object> raw) {
    /** Whether the booster read the request's images: its status is {@code ok}. */
    public boolean ok() {
        return "ok".equals(status);
    }
}
