package com.bynn.decisionnode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Per-key usage: what each API key of the workspace used and spent over a period. Every result is
 * the API's object as sent. The reads retry as any call. The filters the API checks as it reads
 * the query are checked first, raising its {@link BadRequestError} before anything is sent; the
 * period and the cursor are the server's to judge.
 */
public final class UsageClient {
    static final List<String> GRANULARITIES = List.of("none", "hour", "day", "month");
    static final List<String> GROUPS = List.of("model", "channel");
    static final List<String> SCOPES = List.of("full", "decide", "keys");
    static final List<String> STATUSES = List.of("active", "expired", "revoked", "all");
    static final int PAGE_MAX = 1000;
    static final int MAX_KEY_IDS = 100;
    private static final Pattern KEY_ID = Pattern.compile("key_[0-9a-f]{32}");
    private static final Pattern LABEL_NAME = Pattern.compile("[a-z0-9_.-]{1,32}");

    private final DecisionNode client;

    UsageClient(DecisionNode client) {
        this.client = client;
    }

    /** {@code GET /v1/keys/{key_id}/usage}: one key's usage over the UTC month so far. */
    public Map<String, Object> key(String keyId) {
        return key(keyId, null, null, null, null);
    }

    /**
     * {@code GET /v1/keys/{key_id}/usage} from {@code start} to {@code end} (ISO 8601 times), by
     * {@code granularity} ({@code none}, {@code hour}, {@code day} or {@code month}) and grouped by
     * {@code groupBy} ({@code model} or {@code channel}). Null leaves a parameter out.
     */
    public Map<String, Object> key(String keyId, String start, String end, String granularity, String groupBy) {
        return Transport.await(keyAsync(keyId, start, end, granularity, groupBy));
    }

    public CompletableFuture<Map<String, Object>> keyAsync(String keyId) {
        return keyAsync(keyId, null, null, null, null);
    }

    public CompletableFuture<Map<String, Object>> keyAsync(String keyId, String start, String end, String granularity,
            String groupBy) {
        String path;
        try {
            List<String[]> q = period(start, end, granularity, groupBy);
            path = "/v1/keys/" + CollectionsClient.segment(keyId, "keyId") + "/usage" + query(q);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return get(path);
    }

    /** {@code GET /v1/usage}: one page of the keys' usage, with its {@code next_cursor}. */
    public Map<String, Object> list(UsageQuery query) {
        return Transport.await(listAsync(query));
    }

    public CompletableFuture<Map<String, Object>> listAsync(UsageQuery query) {
        String path;
        try {
            path = "/v1/usage" + query(listQuery(query));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return get(path);
    }

    /**
     * Every key's usage over the period: {@link #list}'s pages, {@code next_cursor} followed until
     * it is null, their items in order. A page may hold fewer keys than {@code limit}.
     */
    public List<Map<String, Object>> all(UsageQuery query) {
        return Transport.await(allAsync(query));
    }

    public CompletableFuture<List<Map<String, Object>>> allAsync(UsageQuery query) {
        List<Map<String, Object>> items = new ArrayList<>();
        UsageQuery first;
        try {
            first = query.withCursor(null);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return pages(first, items).thenApply(v -> java.util.Collections.unmodifiableList(items));
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> pages(UsageQuery query, List<Map<String, Object>> items) {
        return listAsync(query).thenCompose(page -> {
            List<Object> found = Json.asList(page.get("items"));
            if (found != null) {
                for (Object o : found) {
                    items.add((Map<String, Object>) o);
                }
            }
            Object next = page.get("next_cursor");
            if (next instanceof String cursor && !cursor.isEmpty()) {
                return pages(query.withCursor(cursor), items);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private CompletableFuture<Map<String, Object>> get(String path) {
        return client.transport().json("GET", path, null, Transport.Policy.DEFAULT).thenApply(Transport.Response::object);
    }

    private static List<String[]> period(String start, String end, String granularity, String groupBy) {
        if (granularity != null && !GRANULARITIES.contains(granularity)) {
            throw LocalChecks.usage("granularity is none, hour, day or month.", null);
        }
        if (groupBy != null && !GROUPS.contains(groupBy)) {
            throw LocalChecks.usage("group_by is model or channel.", null);
        }
        List<String[]> q = new ArrayList<>();
        add(q, "start", start);
        add(q, "end", end);
        add(q, "granularity", granularity);
        add(q, "group_by", groupBy);
        return q;
    }

    /** The list's query, checked in the API's order: the period, labels, device, keys, scope, status, limit. */
    private static List<String[]> listQuery(UsageQuery u) {
        List<String[]> q = period(u.start, u.end, u.granularity, u.groupBy);
        labelFilters(q, u.labels);
        if (u.deviceId != null) {
            add(q, "device_id", deviceIdFilter(u.deviceId));
        }
        for (int i = 0; i < u.keyIds.size(); i++) {
            String id = u.keyIds.get(i);
            if (id == null || !KEY_ID.matcher(id).matches()) {
                throw LocalChecks.usage("key_id is a key's id, key_ and 32 hex digits.", null);
            }
            if (i == MAX_KEY_IDS) {
                throw LocalChecks.usage("At most 100 key_id filters.", null);
            }
            add(q, "key_id", id);
        }
        if (u.scope != null) {
            if (!SCOPES.contains(u.scope)) {
                throw LocalChecks.usage("scope is full, decide or keys.", null);
            }
            add(q, "scope", u.scope);
        }
        if (u.status != null) {
            if (!STATUSES.contains(u.status)) {
                throw LocalChecks.usage("status is active, expired, revoked or all.", null);
            }
            add(q, "status", u.status);
        }
        if (u.includeEmpty != null) {
            add(q, "include_empty", u.includeEmpty ? "true" : "false");
        }
        if (u.limit != null) {
            if (u.limit < 1 || u.limit > PAGE_MAX) {
                throw LocalChecks.usage("limit is a whole number from 1 to 1000.", null);
            }
            add(q, "limit", String.valueOf(u.limit));
        }
        add(q, "cursor", u.cursor);
        return q;
    }

    /** Each label as {@code label=name:value}, by name, checked as the API checks a label filter. */
    static void labelFilters(List<String[]> q, Map<String, String> labels) {
        for (Map.Entry<String, String> label : labels.entrySet()) {
            String value = label.getValue();
            if (!LABEL_NAME.matcher(label.getKey()).matches() || value == null || value.isEmpty()
                    || value.codePointCount(0, value.length()) > 64
                    || value.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL)) {
                throw LocalChecks.usage("A label filter is name:value, as labels are written.", null);
            }
            add(q, "label", label.getKey() + ":" + value);
        }
    }

    /** A device id filter, checked as the API checks it. */
    static String deviceIdFilter(String d) {
        if (d.isEmpty() || d.length() > 128 || d.chars().anyMatch(c -> c < 0x20 || c > 0x7E) || d.startsWith(" ")) {
            throw LocalChecks.usage("device_id is 1 to 128 printable ASCII characters, not starting with a space.", null);
        }
        return d;
    }

    static void add(List<String[]> q, String name, String value) {
        if (value != null) {
            q.add(new String[] {name, value});
        }
    }

    static String query(List<String[]> q) {
        StringBuilder out = new StringBuilder();
        for (String[] p : q) {
            out.append(out.length() == 0 ? "?" : "&").append(escape(p[0])).append('=').append(escape(p[1]));
        }
        return out.toString();
    }

    /** Percent-encoded but for letters, digits and {@code _ . - ~}. */
    static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-'
                    || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }
}
