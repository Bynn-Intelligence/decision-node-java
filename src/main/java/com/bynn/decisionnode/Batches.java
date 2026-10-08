package com.bynn.decisionnode;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The Batch API: decisions answered within 24 hours at half the price.
 *
 * <pre>{@code
 * BatchRun run = dn.runBatch(List.of(
 *     BatchRequest.builder("t1").state("I was charged twice.").question("refund", truth("Refund?")).build()));
 * for (BatchResult r : run.results()) { ... }
 * }</pre>
 *
 * <p>Reading calls ({@link #get}, {@link #list}, {@link #results}, {@link #cancel}) retry as any
 * call. {@link #create}, {@link #add} and {@link #finalize(String)} retry only on 429 and 529,
 * which the API answers before applying anything, never after a timeout or a lost answer, since a
 * repeat could make a second draft. A finalize answered {@code 409 invalid_batch_state} is read
 * with {@link #get}: when the batch is no longer a draft, an earlier finalize went through.
 */
public final class Batches {
    /** The most bytes one add call carries; a longer list is split into several calls, in order. */
    static final int MAX_ADD_BYTES = 10 * 1024 * 1024;
    static final Duration MIN_POLL_INTERVAL = Duration.ofSeconds(10);
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(60);
    static final Duration DEFAULT_RUN_TIMEOUT = Duration.ofHours(26);
    private static final String NDJSON = "application/x-ndjson";

    private final DecisionNode client;

    Batches(DecisionNode client) {
        this.client = client;
    }

    private Transport t() {
        return client.transport();
    }

    // ------------------------------------------------------------------ create, add, finalize

    /** {@code POST /v1/batches}: a draft holding these requests; none makes an empty draft. */
    public Batch create(List<BatchRequest> requests) {
        return Transport.await(createAsync(requests));
    }

    public CompletableFuture<Batch> createAsync(List<BatchRequest> requests) {
        List<LinePlan> plans;
        try {
            plans = plan(requests == null ? List.of() : requests);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return lines(plans).thenCompose(lines -> t().call("POST", "/v1/batches", join(lines), NDJSON, Transport.Policy.MUTATION))
                .thenApply(r -> new Batch(r.object()));
    }

    /**
     * {@code POST /v1/batches/{id}/requests}: adds requests to a draft, in calls of at most
     * 10 MiB, in order.
     *
     * @return the batch after the last call
     */
    public Batch add(String batchId, List<BatchRequest> requests) {
        return Transport.await(addAsync(batchId, requests));
    }

    public CompletableFuture<Batch> addAsync(String batchId, List<BatchRequest> requests) {
        List<LinePlan> plans;
        try {
            if (requests == null || requests.isEmpty()) {
                throw new IllegalArgumentException("add needs at least one request");
            }
            plans = plan(requests);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return lines(plans).thenCompose(lines -> addPlanned(batchId, lines));
    }

    private CompletableFuture<Batch> addChunks(String path, List<List<byte[]>> chunks, int i, Batch last) {
        if (i >= chunks.size()) {
            return CompletableFuture.completedFuture(last);
        }
        return t().call("POST", path, join(chunks.get(i)), NDJSON, Transport.Policy.MUTATION)
                .thenCompose(r -> addChunks(path, chunks, i + 1, new Batch(r.object())));
    }

    /** {@code POST /v1/batches/{id}/finalize}: queues the draft to run. */
    public Batch finalize(String batchId) {
        return Transport.await(finalizeAsync(batchId));
    }

    public CompletableFuture<Batch> finalizeAsync(String batchId) {
        String path;
        try {
            path = path(batchId) + "/finalize";
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return t().json("POST", path, null, Transport.Policy.MUTATION)
                .thenApply(r -> new Batch(r.object()))
                .exceptionallyCompose(err -> {
                    Throwable cause = Transport.unwrap(err);
                    if (cause instanceof DecisionNodeError && Integer.valueOf(409).equals(((DecisionNodeError) cause).status())
                            && "invalid_batch_state".equals(((DecisionNodeError) cause).errorType())) {
                        // An earlier finalize whose answer was lost may have gone through.
                        return getAsync(batchId).thenApply(b -> {
                            if (!"draft".equals(b.state())) {
                                return b;
                            }
                            throw (DecisionNodeError) cause;
                        });
                    }
                    return CompletableFuture.failedFuture(cause);
                });
    }

    // ------------------------------------------------------------------ read, cancel

    /** {@code GET /v1/batches/{id}}. */
    public Batch get(String batchId) {
        return Transport.await(getAsync(batchId));
    }

    public CompletableFuture<Batch> getAsync(String batchId) {
        return getWithHeaders(batchId).thenApply(r -> new Batch(r.object()));
    }

    private CompletableFuture<Transport.Response> getWithHeaders(String batchId) {
        try {
            return t().json("GET", path(batchId), null, Transport.Policy.DEFAULT);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** {@code GET /v1/batches}: the first page with the server's default size. */
    public BatchPage list() {
        return list(null, null);
    }

    /** {@code GET /v1/batches?offset=&limit=}: the key's batches, newest first. Null leaves a parameter out. */
    public BatchPage list(Integer offset, Integer limit) {
        return Transport.await(listAsync(offset, limit));
    }

    public CompletableFuture<BatchPage> listAsync(Integer offset, Integer limit) {
        StringBuilder q = new StringBuilder();
        if (offset != null) {
            q.append("offset=").append(offset);
        }
        if (limit != null) {
            q.append(q.length() > 0 ? "&" : "").append("limit=").append(limit);
        }
        String path = "/v1/batches" + (q.length() > 0 ? "?" + q : "");
        return t().json("GET", path, null, Transport.Policy.DEFAULT).thenApply(r -> new BatchPage(r.object()));
    }

    /**
     * {@code GET /v1/batches/{id}/results}: one {@link BatchResult} per request in upload order,
     * read line by line as they are iterated. Close the results when done with them.
     */
    public BatchResults results(String batchId) {
        return Transport.await(resultsAsync(batchId));
    }

    public CompletableFuture<BatchResults> resultsAsync(String batchId) {
        String path;
        try {
            path = path(batchId) + "/results";
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return t().lines(path).thenApply(r -> new BatchResults(r.body()));
    }

    /** {@code POST /v1/batches/{id}/cancel}: safe to repeat. */
    public Batch cancel(String batchId) {
        return Transport.await(cancelAsync(batchId));
    }

    public CompletableFuture<Batch> cancelAsync(String batchId) {
        String path;
        try {
            path = path(batchId) + "/cancel";
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return t().json("POST", path, null, Transport.Policy.DEFAULT).thenApply(r -> new Batch(r.object()));
    }

    // ------------------------------------------------------------------ run

    CompletableFuture<BatchRun> run(List<BatchRequest> requests, Duration pollInterval, Duration timeout) {
        if (pollInterval == null || pollInterval.compareTo(MIN_POLL_INTERVAL) < 0) {
            throw new IllegalArgumentException("pollInterval must be at least 10 seconds, got " + pollInterval);
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be above 0, got " + timeout);
        }
        List<LinePlan> plans = plan(requests == null ? List.of() : requests);
        return createAsync(List.of()).thenCompose(draft -> {
            CompletableFuture<Batch> added = plans.isEmpty()
                    ? CompletableFuture.completedFuture(draft)
                    : lines(plans).thenCompose(lines -> addPlanned(draft.id(), lines));
            return added.exceptionallyCompose(err -> {
                Throwable cause = Transport.unwrap(err);
                // The draft would never run; cancel it, then raise the add's error.
                return cancelAsync(draft.id()).handle((b, e) -> {
                    throw cause instanceof RuntimeException ? (RuntimeException) cause : DecisionNode.asError(cause);
                });
            }).thenCompose(b -> finalizeAsync(draft.id()));
        }).thenCompose(b -> poll(b, null, 0L, pollInterval.toMillis(), timeout.toMillis()))
                .thenCompose(b -> resultsAsync(b.id()).thenApply(r -> new BatchRun(b, r)));
    }

    private CompletableFuture<Batch> addPlanned(String batchId, List<byte[]> lines) {
        List<List<byte[]>> chunks = new ArrayList<>();
        List<byte[]> cur = new ArrayList<>();
        long size = 0;
        for (byte[] line : lines) {
            if (!cur.isEmpty() && size + line.length > MAX_ADD_BYTES) {
                chunks.add(cur);
                cur = new ArrayList<>();
                size = 0;
            }
            cur.add(line);
            size += line.length;
        }
        chunks.add(cur);
        return addChunks(path(batchId) + "/requests", chunks, 0, null);
    }

    /** Polls at the fixed interval, or the server's {@code Retry-After} when it is longer. */
    private CompletableFuture<Batch> poll(Batch b, HttpHeaders last, long waited, long interval, long timeout) {
        if (b.isEnded()) {
            return CompletableFuture.completedFuture(b);
        }
        if (waited >= timeout) {
            return CompletableFuture.failedFuture(new TimeoutError(
                    "the batch " + b.id() + " did not end within " + Duration.ofMillis(timeout), null));
        }
        long wait = interval;
        Double server = last == null ? null : Responses.retryAfter(last);
        if (server != null) {
            wait = Math.max(wait, Math.round(server * 1000));
        }
        long w = wait;
        return t().sleep(w)
                .thenCompose(v -> getWithHeaders(b.id()))
                .thenCompose(r -> poll(new Batch(r.object()), r.headers(), waited + w, interval, timeout));
    }

    // ------------------------------------------------------------------ lines

    /** A request checked and ready: its line without images, and its images. */
    private record LinePlan(Map<String, Object> head, List<Object> images, byte[] line) {}

    /** Checks every request before anything is sent and prepares its line. */
    private List<LinePlan> plan(List<BatchRequest> requests) {
        List<LinePlan> out = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            BatchRequest r = requests.get(i);
            if (r == null) {
                throw new IllegalArgumentException("requests[" + i + "] is null");
            }
            if (r.questions().isEmpty()) {
                throw new IllegalArgumentException("requests[" + i + "]: questions must hold at least one question");
            }
            LocalChecks.images(r.images());
            LocalChecks.questions(r.questions());
            Map<String, Object> head = new LinkedHashMap<>();
            head.put("custom_id", r.customId());
            String m = r.model();
            head.put("model", m != null && !m.isEmpty() ? m : client.model());
            head.put("state", r.state() == null ? "" : r.state());
            head.put("questions", r.questions());
            byte[] line = null;
            if (r.images().isEmpty()) {
                line = line(head);
            } else {
                Json.write(head); // a value that cannot be serialised fails before anything is sent
            }
            out.add(new LinePlan(head, r.images(), line));
        }
        return out;
    }

    /** Each request's line, its images inline or uploaded by the threshold, counted per line. */
    private CompletableFuture<List<byte[]>> lines(List<LinePlan> plans) {
        List<CompletableFuture<byte[]>> out = new ArrayList<>(plans.size());
        for (LinePlan p : plans) {
            if (p.line() != null) {
                out.add(CompletableFuture.completedFuture(p.line()));
            } else {
                out.add(client.uploads().wires(p.images(), true, false).thenApply(w -> {
                    Map<String, Object> body = new LinkedHashMap<>(p.head());
                    body.put("images", w.wires());
                    return line(body);
                }));
            }
        }
        return CompletableFuture.allOf(out.toArray(new CompletableFuture<?>[0])).thenApply(v -> {
            List<byte[]> lines = new ArrayList<>(out.size());
            for (CompletableFuture<byte[]> f : out) {
                lines.add(f.join());
            }
            return lines;
        });
    }

    private static byte[] line(Map<String, Object> body) {
        byte[] json = Json.write(body);
        byte[] line = java.util.Arrays.copyOf(json, json.length + 1);
        line[json.length] = '\n';
        return line;
    }

    private static byte[] join(List<byte[]> lines) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] l : lines) {
            out.writeBytes(l);
        }
        return out.toByteArray();
    }

    private static String path(String batchId) {
        if (batchId == null || batchId.isEmpty()) {
            throw new IllegalArgumentException("batchId must not be empty");
        }
        return "/v1/batches/" + URLEncoder.encode(batchId, StandardCharsets.UTF_8);
    }
}
