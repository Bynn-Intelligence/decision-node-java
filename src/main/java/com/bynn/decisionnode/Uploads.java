package com.bynn.decisionnode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Image uploads: the inline threshold, one presign for the images of a request, the parallel
 * {@code PUT}s to storage, and the client's cache of upload ids by content hash.
 */
final class Uploads {
    /** Entries the cache holds; the least recently used goes first. */
    static final int CACHE_ENTRIES = 256;
    /** A cached upload is used until this long before its {@code expires_at}. */
    static final long MARGIN_MS = 5 * 60 * 1000L;
    /** A presigned URL is valid this long; a 403 after it is presigned again, once. */
    static final long URL_TTL_MS = 5 * 60 * 1000L;

    private static final Set<String> RESTRICTED = Set.of("content-length", "host", "connection", "expect", "upgrade");
    private static final Pattern CODE = Pattern.compile("<Code>([^<]*)</Code>");

    record Key(String sha256, String mediaType) {}

    private record Entry(String uploadId, long expiresAtMillis) {}

    /** The images of one request in wire form, and the upload ids this client placed there. */
    record Wires(List<Object> wires, Set<String> placed) {}

    private final Transport transport;
    private final long inlineThreshold;
    private final LinkedHashMap<Key, Entry> cache = new LinkedHashMap<>(32, 0.75f, true);
    /** Uploads under way, so concurrent requests with the same bytes share one. */
    private final Map<Key, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();

    Uploads(Transport transport, long inlineThreshold) {
        this.transport = transport;
        this.inlineThreshold = inlineThreshold;
    }

    static Key key(Image img) {
        return new Key(Base64.getEncoder().encodeToString(img.sha256()), img.mediaType());
    }

    // ------------------------------------------------------------------ the cache

    /** The live cached upload id of these bytes, or null. */
    String cached(Image img) {
        synchronized (cache) {
            if (cache.isEmpty()) {
                return null; // nothing to look up, so the bytes need no hash
            }
        }
        Key k = key(img);
        synchronized (cache) {
            Entry e = cache.get(k);
            if (e == null) {
                return null;
            }
            if (System.currentTimeMillis() >= e.expiresAtMillis - MARGIN_MS) {
                cache.remove(k);
                return null;
            }
            return e.uploadId;
        }
    }

    private void remember(Key k, String uploadId, long expiresAt) {
        synchronized (cache) {
            cache.put(k, new Entry(uploadId, expiresAt));
            Iterator<Key> it = cache.keySet().iterator();
            while (cache.size() > CACHE_ENTRIES && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    /** Forgets these upload ids: the API called them stale. */
    void drop(Collection<String> uploadIds) {
        synchronized (cache) {
            cache.values().removeIf(e -> uploadIds.contains(e.uploadId));
        }
    }

    // ------------------------------------------------------------------ wire forms

    /**
     * The wire forms of a request's images: URLs, upload ids and wire-form maps as given; local
     * bytes by their cached upload id when they have one; the rest inline when the request's
     * local images total under the threshold, else uploaded. {@code withIds} false gives a
     * session frame's form, which has no id.
     */
    CompletableFuture<Wires> wires(List<?> images, boolean withIds, boolean alwaysUpload) {
        Object[] wires = new Object[images.size()];
        Set<String> placed = new HashSet<>();
        List<Integer> local = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < images.size(); i++) {
            Object o = images.get(i);
            if (!(o instanceof Image)) {
                wires[i] = o;
                continue;
            }
            Image img = (Image) o;
            if (!img.isLocal()) {
                Map<String, Object> m = img.toMap();
                if (!withIds) {
                    m.remove("id");
                }
                wires[i] = m;
                continue;
            }
            total += img.size();
            String hit = cached(img);
            if (hit != null) {
                wires[i] = byId(img, hit, withIds);
                placed.add(hit);
            } else {
                local.add(i);
            }
        }
        if (local.isEmpty() || (!alwaysUpload && total < inlineThreshold)) {
            for (int i : local) {
                wires[i] = ((Image) images.get(i)).inline(withIds);
            }
            return CompletableFuture.completedFuture(new Wires(java.util.Arrays.asList(wires), placed));
        }
        List<Image> todo = new ArrayList<>();
        for (int i : local) {
            todo.add((Image) images.get(i));
        }
        return upload(todo).thenApply(ids -> {
            for (int n = 0; n < local.size(); n++) {
                int i = local.get(n);
                wires[i] = byId((Image) images.get(i), ids.get(n), withIds);
                placed.add(ids.get(n));
            }
            return new Wires(java.util.Arrays.asList(wires), placed);
        });
    }

    private static Map<String, Object> byId(Image img, String uploadId, boolean withId) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (withId) {
            m.put("id", img.id());
        }
        m.put("upload_id", uploadId);
        return m;
    }

    // ------------------------------------------------------------------ upload

    /**
     * Uploads these images, whatever their size, and returns one upload id per image: one presign
     * for the distinct bytes not already being uploaded, then one {@code PUT} each, in parallel.
     */
    CompletableFuture<List<String>> upload(List<Image> images) {
        List<Key> keys = new ArrayList<>(images.size());
        Map<Key, Image> mine = new LinkedHashMap<>();
        Map<Key, CompletableFuture<String>> futures = new LinkedHashMap<>();
        for (Image img : images) {
            Key k = key(img);
            keys.add(k);
            if (futures.containsKey(k)) {
                continue;
            }
            CompletableFuture<String> created = new CompletableFuture<>();
            CompletableFuture<String> running = inflight.putIfAbsent(k, created);
            if (running != null) {
                futures.put(k, running);
            } else {
                futures.put(k, created);
                mine.put(k, img);
            }
        }
        if (!mine.isEmpty()) {
            presignAndPut(new ArrayList<>(mine.entrySet())).whenComplete((ids, err) -> {
                int n = 0;
                for (Map.Entry<Key, Image> e : mine.entrySet()) {
                    CompletableFuture<String> f = futures.get(e.getKey());
                    inflight.remove(e.getKey(), f);
                    if (err != null) {
                        f.completeExceptionally(Transport.unwrap(err));
                    } else {
                        f.complete(ids.get(n));
                    }
                    n++;
                }
            });
        }
        CompletableFuture<?>[] all = futures.values().toArray(new CompletableFuture<?>[0]);
        return CompletableFuture.allOf(all).thenApply(v -> {
            List<String> out = new ArrayList<>(keys.size());
            for (Key k : keys) {
                out.add(futures.get(k).join());
            }
            return out;
        });
    }

    private CompletableFuture<List<String>> presignAndPut(List<Map.Entry<Key, Image>> files) {
        List<Map<String, Object>> body = new ArrayList<>();
        for (Map.Entry<Key, Image> e : files) {
            Image img = e.getValue();
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("content_type", img.mediaType());
            f.put("byte_size", img.bytes().length);
            f.put("checksum", Base64.getEncoder().encodeToString(Image.digest("MD5", img.bytes())));
            f.put("sha256", e.getKey().sha256());
            body.add(f);
        }
        byte[] payload = Json.write(Map.of("files", body));
        long presignedAt = System.currentTimeMillis();
        return transport.json("POST", "/v1/uploads", payload, Transport.Policy.DEFAULT).thenCompose(r -> {
            List<Object> uploads = Json.asList(r.object().get("uploads"));
            if (uploads == null || uploads.size() != files.size()) {
                throw Responses.malformedAnswer(r.status(), r.headers(), r.object(),
                        "the upload response does not hold one upload per file");
            }
            List<CompletableFuture<String>> puts = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                Map<String, Object> up = Json.asMap(uploads.get(i));
                Map.Entry<Key, Image> file = files.get(i);
                Map<String, Object> fileBody = body.get(i);
                puts.add(putOrPresignAgain(up, file, fileBody, presignedAt));
            }
            return CompletableFuture.allOf(puts.toArray(new CompletableFuture<?>[0])).thenApply(v -> {
                List<String> ids = new ArrayList<>();
                for (CompletableFuture<String> p : puts) {
                    ids.add(p.join());
                }
                return ids;
            });
        });
    }

    /**
     * One {@code PUT}; a 403 once the presigned URL's 5 minutes have passed is presigned again,
     * once. Then the upload id goes in the cache until its {@code expires_at}.
     */
    private CompletableFuture<String> putOrPresignAgain(Map<String, Object> up, Map.Entry<Key, Image> file,
            Map<String, Object> fileBody, long presignedAt) {
        if (up == null || !(up.get("upload_id") instanceof String) || !(up.get("url") instanceof String)) {
            return CompletableFuture.failedFuture(new ServerError(new ErrorInfo(201, null,
                    "the upload response has an entry without upload_id or url", null, null, up, Map.of())));
        }
        Image img = file.getValue();
        return put(up, img.bytes()).handle((v, err) -> {
            if (err == null) {
                return CompletableFuture.completedFuture(done(up, file.getKey()));
            }
            Throwable cause = Transport.unwrap(err);
            if (cause instanceof DecisionNodeError && Integer.valueOf(403).equals(((DecisionNodeError) cause).status())
                    && System.currentTimeMillis() - presignedAt >= URL_TTL_MS) {
                byte[] payload = Json.write(Map.of("files", List.of(fileBody)));
                return transport.json("POST", "/v1/uploads", payload, Transport.Policy.DEFAULT).thenCompose(r -> {
                    List<Object> again = Json.asList(r.object().get("uploads"));
                    Map<String, Object> next = again == null || again.isEmpty() ? null : Json.asMap(again.get(0));
                    if (next == null || !(next.get("url") instanceof String)) {
                        throw Responses.malformedAnswer(r.status(), r.headers(), r.object(),
                                "the upload response does not hold one upload per file");
                    }
                    return put(next, img.bytes()).thenApply(x -> done(next, file.getKey()));
                });
            }
            return CompletableFuture.<String>failedFuture(cause);
        }).thenCompose(java.util.function.Function.identity());
    }

    private String done(Map<String, Object> up, Key key) {
        String id = (String) up.get("upload_id");
        Long expires = parseTime(Responses.str(up.get("expires_at")));
        if (expires != null) {
            remember(key, id, expires);
        }
        return id;
    }

    static Long parseTime(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    /**
     * The {@code PUT} to storage: exactly the returned headers and the bytes; no key, none of the
     * client's headers, no redirect followed. Retried on 5xx, timeouts and connection errors; a
     * 412 on a retry means an earlier attempt stored the object.
     */
    CompletableFuture<Void> put(Map<String, Object> up, byte[] data) {
        URI uri;
        try {
            uri = URI.create((String) up.get("url"));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new DecisionNodeError("the storage URL is not a URL: " + up.get("url"), e));
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = scheme.equals("https");
        if (!https && !(scheme.equals("http") && DecisionNode.isLoopback(uri.getHost()))) {
            return CompletableFuture.failedFuture(new DecisionNodeError(
                    "the storage URL must be https; nothing was sent to " + uri.getScheme() + "://" + uri.getHost()));
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(transport.timeout);
        if (!https) {
            rb.version(HttpClient.Version.HTTP_1_1);
        }
        Map<String, Object> headers = Json.asMap(up.get("headers"));
        if (headers != null) {
            for (Map.Entry<String, Object> h : headers.entrySet()) {
                if (h.getValue() != null && !RESTRICTED.contains(h.getKey().toLowerCase(Locale.ROOT))) {
                    rb.header(h.getKey(), String.valueOf(h.getValue()));
                }
            }
        }
        String method = up.get("method") instanceof String ? (String) up.get("method") : "PUT";
        rb.method(method, HttpRequest.BodyPublishers.ofByteArray(data));
        return putAttempt(rb.build(), 0);
    }

    private CompletableFuture<Void> putAttempt(HttpRequest request, int attempt) {
        long started = System.nanoTime();
        return transport.http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).handle((resp, exc) -> {
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (exc != null) {
                DecisionNodeError err = transport.transportError(Transport.unwrap(exc));
                transport.debug(request, null, null, ms, attempt, err.getMessage());
                if ((err instanceof TimeoutError || err instanceof ConnectionError) && attempt < transport.maxRetries) {
                    return retry(request, attempt);
                }
                return CompletableFuture.<Void>failedFuture(err);
            }
            int status = resp.statusCode();
            transport.debug(request, status, null, ms, attempt, null);
            if (status >= 200 && status <= 299) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            if (status == 412 && attempt > 0) {
                return CompletableFuture.<Void>completedFuture(null); // stored by an earlier attempt: write-once
            }
            if (status >= 500 && status <= 599 && attempt < transport.maxRetries) {
                return retry(request, attempt);
            }
            String text = new String(resp.body(), StandardCharsets.UTF_8);
            Matcher m = CODE.matcher(text);
            String code = m.find() ? m.group(1) : null;
            return CompletableFuture.<Void>failedFuture(new DecisionNodeError(new ErrorInfo(status, "upload_failed",
                    "the upload to storage failed with " + status, null, code, null, Responses.lowerCased(resp.headers()))));
        }).thenCompose(java.util.function.Function.identity());
    }

    private CompletableFuture<Void> retry(HttpRequest request, int attempt) {
        return transport.sleep(Transport.millis(Transport.backoff(attempt))).thenCompose(v -> putAttempt(request, attempt + 1));
    }
}
