package com.bynn.decisionnode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Device keys, managed by a Manage device keys key on your server: make a decide key for each
 * device, list them, change one, revoke one or every key a label picks. Every result is the API's
 * object as sent.
 *
 * <p>{@link #create} sends an {@code Idempotency-Key} (yours, else a new one for the call, the
 * same on every attempt) and retries as any call, a 409 {@code idempotency_in_progress} included.
 * Its result holds the key's {@code secret}, shown once: hand it to the device and never log it.
 */
public final class KeysClient {
    static final int LIST_MAX = 200;
    private static final List<String> STATUSES = List.of("active", "expired", "revoked", "all");

    private final DecisionNode client;

    KeysClient(DecisionNode client) {
        this.client = client;
    }

    private Transport t() {
        return client.transport();
    }

    /** {@code POST /v1/keys}: a new decide key, answered as {@code {key, secret}}. */
    public Map<String, Object> create(KeyCreate request) {
        return Transport.await(createAsync(request));
    }

    public CompletableFuture<Map<String, Object>> createAsync(KeyCreate request) {
        byte[] body;
        String key;
        try {
            key = request.idempotencyKey;
            if (key != null && (key.isEmpty() || key.length() > 255 || key.chars().anyMatch(c -> c < 0x20 || c > 0x7E))) {
                throw LocalChecks.usage("Idempotency-Key must be sent once, as 1 to 255 printable ASCII characters.", null);
            }
            body = Json.write(request.fields);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Transport.Response> sent = key == null ? t().idempotent("POST", "/v1/keys", body)
                : t().idempotent("POST", "/v1/keys", body, key);
        return sent.thenApply(Transport.Response::object);
    }

    /** {@code GET /v1/keys}: one page of the workspace's device keys, newest first, with its {@code next_cursor}. */
    public Map<String, Object> list(KeyListQuery query) {
        return Transport.await(listAsync(query));
    }

    public CompletableFuture<Map<String, Object>> listAsync(KeyListQuery query) {
        String path;
        try {
            path = "/v1/keys" + UsageClient.query(listQuery(query));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return get(path);
    }

    /** Every key the query picks: {@link #list}'s pages, {@code next_cursor} followed until it is null. */
    public List<Map<String, Object>> all(KeyListQuery query) {
        return Transport.await(allAsync(query));
    }

    public CompletableFuture<List<Map<String, Object>>> allAsync(KeyListQuery query) {
        List<Map<String, Object>> items = new ArrayList<>();
        return pages(query.withCursor(null), items).thenApply(v -> java.util.Collections.unmodifiableList(items));
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> pages(KeyListQuery query, List<Map<String, Object>> items) {
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

    /** {@code PATCH /v1/keys/{key_id}}: changes the fields the update sets, and returns the key as sent. */
    public Map<String, Object> update(String keyId, KeyUpdate update) {
        return Transport.await(updateAsync(keyId, update));
    }

    public CompletableFuture<Map<String, Object>> updateAsync(String keyId, KeyUpdate update) {
        String path;
        byte[] body;
        try {
            path = path(keyId);
            if (update == null || update.fields.isEmpty()) {
                throw LocalChecks.usage("Nothing to change.", null);
            }
            body = Json.write(update.fields);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return t().json("PATCH", path, body, Transport.Policy.DEFAULT).thenApply(Transport.Response::object);
    }

    /** {@code DELETE /v1/keys/{key_id}}: revokes the key at once, answered as {@code {id, revoked}}. */
    public Map<String, Object> revoke(String keyId) {
        return Transport.await(revokeAsync(keyId));
    }

    public CompletableFuture<Map<String, Object>> revokeAsync(String keyId) {
        String path;
        try {
            path = path(keyId);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return t().json("DELETE", path, null, Transport.Policy.DEFAULT).thenApply(Transport.Response::object);
    }

    /**
     * {@code POST /v1/keys/revoke}: revokes the active device keys carrying every one of these
     * labels (and, when given, made for one of these devices), at most 10,000 a call; with
     * {@code dryRun} true it only counts them. Null leaves {@code deviceIds} or {@code dryRun} out.
     */
    public Map<String, Object> revokeByLabels(Map<String, String> labels, List<String> deviceIds, Boolean dryRun) {
        return Transport.await(revokeByLabelsAsync(labels, deviceIds, dryRun));
    }

    public CompletableFuture<Map<String, Object>> revokeByLabelsAsync(Map<String, String> labels, List<String> deviceIds, Boolean dryRun) {
        byte[] body;
        try {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("labels", labels);
            if (deviceIds != null) {
                b.put("device_ids", deviceIds);
            }
            if (dryRun != null) {
                b.put("dry_run", dryRun);
            }
            body = Json.write(b);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return t().json("POST", "/v1/keys/revoke", body, Transport.Policy.DEFAULT).thenApply(Transport.Response::object);
    }

    private CompletableFuture<Map<String, Object>> get(String path) {
        return t().json("GET", path, null, Transport.Policy.DEFAULT).thenApply(Transport.Response::object);
    }

    private static String path(String keyId) {
        return "/v1/keys/" + CollectionsClient.segment(keyId, "keyId");
    }

    /** The list's query, checked as the API checks it: labels, device, status, limit. */
    private static List<String[]> listQuery(KeyListQuery k) {
        List<String[]> q = new ArrayList<>();
        UsageClient.labelFilters(q, k.labels);
        if (k.deviceId != null) {
            UsageClient.add(q, "device_id", UsageClient.deviceIdFilter(k.deviceId));
        }
        if (k.status != null) {
            if (!STATUSES.contains(k.status)) {
                throw LocalChecks.usage("status is active, expired, revoked or all.", null);
            }
            UsageClient.add(q, "status", k.status);
        }
        if (k.limit != null) {
            if (k.limit < 1 || k.limit > LIST_MAX) {
                throw LocalChecks.usage("limit is a whole number from 1 to 200.", null);
            }
            UsageClient.add(q, "limit", String.valueOf(k.limit));
        }
        UsageClient.add(q, "cursor", k.cursor);
        return q;
    }
}
