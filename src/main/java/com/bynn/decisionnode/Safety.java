package com.bynn.decisionnode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The safety check of a request, read from the {@code x-decisionnode-safety} response header.
 *
 * <p>When the check ran, {@link #checked()} is true, {@link #scores()} holds each category's
 * score ({@code harm}, {@code self_harm}) and {@link #flagged()} lists, in header order, the
 * categories above the key's thresholds. When it did not run, {@link #checked()} is false and
 * {@link #reason()} says why. A server that does not run the check sends no header, and
 * {@link Decision#safety()} is then null.
 *
 * @param checked whether the check ran
 * @param scores  each category's score, in header order
 * @param flagged the categories above the key's thresholds
 * @param reason  why the check did not run; null when it ran
 */
public record Safety(boolean checked, Map<String, Double> scores, List<String> flagged, String reason) {

    public Safety {
        scores = Collections.unmodifiableMap(new LinkedHashMap<>(scores == null ? Map.of() : scores));
        flagged = List.copyOf(flagged == null ? List.of() : flagged);
    }

    /** The harm score, or null when the header did not carry one. */
    public Double harm() {
        return scores.get("harm");
    }

    /** The self-harm score, or null when the header did not carry one. */
    public Double selfHarm() {
        return scores.get("self_harm");
    }

    /** Whether any category was flagged. */
    public boolean isFlagged() {
        return !flagged.isEmpty();
    }

    /**
     * Parses the header value, for example {@code harm=0.912;flagged, self_harm=0.004} or
     * {@code unchecked;reason="timeout"}. Returns null for a null value.
     */
    public static Safety parse(String header) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        if (value.startsWith("unchecked")) {
            String reason = null;
            String[] parts = value.split(";");
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i].trim();
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).trim().equals("reason")) {
                    reason = unquote(part.substring(eq + 1).trim());
                }
            }
            return new Safety(false, Map.of(), List.of(), reason);
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        List<String> flagged = new ArrayList<>();
        for (String member : value.split(",")) {
            member = member.trim();
            if (member.isEmpty()) {
                continue;
            }
            String[] parts = member.split(";");
            String head = parts[0];
            int eq = head.indexOf('=');
            String name = (eq >= 0 ? head.substring(0, eq) : head).trim();
            Double score = null;
            if (eq >= 0) {
                try {
                    score = Double.parseDouble(head.substring(eq + 1).trim());
                } catch (NumberFormatException e) {
                    score = null;
                }
            }
            scores.put(name, score);
            for (int i = 1; i < parts.length; i++) {
                String param = parts[i].trim();
                if (param.equals("flagged") || param.equals("blocked")) {
                    flagged.add(name);
                    break;
                }
            }
        }
        return new Safety(true, scores, flagged, null);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
