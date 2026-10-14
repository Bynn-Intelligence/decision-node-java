package com.bynn.decisionnode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The fixed rules of a device key's fields, checked before anything is sent; the first fault is
 * raised as the API's 422. A limit's ceiling (the workspace's), the expiry's window (from now)
 * and a name's characters are the server's to judge.
 */
final class KeyChecks {
    static final int MAX_LABELS = 16;
    static final int MAX_REVOKE_DEVICES = 1000;
    private static final Pattern LABEL_NAME = Pattern.compile("[a-z0-9_.-]{1,32}");
    private static final String DEVICE_RULE = "device_id is 1 to 128 printable ASCII characters, not starting with a space.";

    private KeyChecks() {}

    /** A key's name: trimmed, 1 to 48 characters. */
    static List<Map<String, Object>> name(Object name) {
        String trimmed = name instanceof String s ? s.strip() : "";
        if (trimmed.isEmpty()) {
            return List.of(LocalChecks.bodyEntry("value_error", List.of("name"), "Give the key a name.", name));
        }
        if (trimmed.codePointCount(0, trimmed.length()) > 48) {
            return List.of(LocalChecks.bodyEntry("value_error", List.of("name"), "Key names are at most 48 characters.", name));
        }
        return List.of();
    }

    static boolean deviceOk(Object value) {
        return value instanceof String d && !d.isEmpty() && d.length() <= 128 && d.chars().allMatch(c -> c >= 0x20 && c <= 0x7E)
                && !d.startsWith(" ");
    }

    /** A key's labels: an object of at most 16 names (1 to 32 of a-z, 0-9, _ . -) to values of 1 to 64 characters. */
    static List<Map<String, Object>> labels(Object labels) {
        if (!(labels instanceof Map<?, ?> m)) {
            return List.of(LocalChecks.bodyEntry("value_error", List.of("labels"), "labels is an object of names to values.", labels));
        }
        if (m.size() > MAX_LABELS) {
            return List.of(LocalChecks.bodyEntry("value_error", List.of("labels"), "A key has at most 16 labels.", labels));
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String name) || !LABEL_NAME.matcher(name).matches()) {
                return List.of(LocalChecks.bodyEntry("value_error", List.of("labels"), "A label's name is 1 to 32 of a-z, 0-9, _ . and -.",
                        labels));
            }
            Object value = e.getValue();
            if (!(value instanceof String v) || v.isEmpty() || v.codePointCount(0, v.length()) > 64
                    || v.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL)) {
                return List.of(LocalChecks.bodyEntry("value_error", List.of("labels", name), "A label's value is 1 to 64 characters of text.",
                        labels));
            }
        }
        return List.of();
    }

    /** A daily spend cap: {@code {"usd"}} from 0.01 to 1000, in whole cents. */
    static List<Map<String, Object>> cap(Object cap) {
        Object usd = cap instanceof Map<?, ?> m ? m.get("usd") : null;
        boolean ok = usd instanceof Number n && Double.isFinite(n.doubleValue());
        double value = ok ? ((Number) usd).doubleValue() : 0;
        double cents = ok ? Math.rint(value * 100) : 0;
        if (!ok || Math.abs(value * 100 - cents) >= 1e-6 || cents < 1 || value > 1000) {
            return List.of(LocalChecks.bodyEntry("value_error", List.of("daily_spend_cap"),
                    "daily_spend_cap.usd is from 0.01 to 1000, in cents, or null.", cap));
        }
        return List.of();
    }

    /**
     * The fields beyond the name, in the order the body sends them; on an update ({@code clears}) a
     * field given as null clears it and is checked no further.
     */
    static List<Map<String, Object>> fields(Map<String, Object> fields, boolean clears) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (fields.containsKey("device_id") && !deviceOk(fields.get("device_id"))) {
            out.add(LocalChecks.bodyEntry("value_error", List.of("device_id"), DEVICE_RULE, fields.get("device_id")));
        }
        if (fields.containsKey("labels") && !(clears && fields.get("labels") == null)) {
            out.addAll(labels(fields.get("labels")));
        }
        if (fields.containsKey("limits") && !(clears && fields.get("limits") == null) && !(fields.get("limits") instanceof Map)) {
            out.add(LocalChecks.bodyEntry("value_error", List.of("limits"), "limits is an object, or null.", fields.get("limits")));
        }
        if (fields.containsKey("daily_spend_cap") && !(clears && fields.get("daily_spend_cap") == null)) {
            out.addAll(cap(fields.get("daily_spend_cap")));
        }
        return out;
    }

    /** A bulk revoke: at least one label, and at most 1,000 well-formed device ids when given. */
    static List<Map<String, Object>> revoke(Map<String, String> labels, List<String> deviceIds) {
        List<Map<String, Object>> out = new ArrayList<>(labels(labels));
        if (out.isEmpty() && labels.isEmpty()) {
            out.add(LocalChecks.bodyEntry("value_error", List.of("labels"),
                    "A bulk revoke names at least one label: every key it revokes has them all.", labels));
        }
        if (out.isEmpty() && deviceIds != null) {
            if (deviceIds.size() > MAX_REVOKE_DEVICES) {
                out.add(LocalChecks.bodyEntry("value_error", List.of("device_ids"), "device_ids is a list of at most 1,000 device ids.",
                        deviceIds));
            } else {
                for (String d : deviceIds) {
                    if (!deviceOk(d)) {
                        out.add(LocalChecks.bodyEntry("value_error", List.of("device_id"), DEVICE_RULE, d));
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** Raises the first entry as the API's 422. */
    static void raiseFirst(List<Map<String, Object>> entries) {
        if (!entries.isEmpty()) {
            throw LocalChecks.invalidEntries(List.of(entries.get(0)));
        }
    }
}
