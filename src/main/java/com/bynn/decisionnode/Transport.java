package com.bynn.decisionnode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Sends API requests: one shared {@link HttpClient} (HTTP/2 over TLS, so every call of a client
 * shares its connections), a deadline over each whole attempt, and the retry rules.
 */
final class Transport {
    /** Which failures a call may retry. */
    enum Policy {
        /** 408, 429, 5xx, timeouts and connection errors: a call that changes nothing on the server. */
        DEFAULT,
        /**
         * Only 429 and 529, which the API answers before applying anything: a call that a repeat
         * could apply twice (creating a batch, adding to it, finalizing it).
         */
        MUTATION,
        /** As {@link #DEFAULT}, except that a 429 {@code session_limit_exceeded} is never retried. */
        SESSION_OPEN,
        /**
         * A decision whose boosters name {@code ocr}: as {@link #DEFAULT}, except that a timeout is
         * never retried, since the server finishes the timed-out attempt and may bill it.
         */
        OCR,
        /**
         * A call that carries its own {@code Idempotency-Key}, the same on every attempt (a
         * collection's create, an enrollment): as {@link #DEFAULT}, and also a 409
         * {@code idempotency_in_progress}, after its wait.
         */
        IDEMPOTENT
    }

    record Response(int status, HttpHeaders headers, Map<String, Object> object) {}

    static final String USER_AGENT = "decisionnode-java/" + DecisionNode.VERSION;
    private static final System.Logger LOG = System.getLogger("com.bynn.decisionnode");

    /** One daemon thread that cancels attempts whose time is up. */
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();

    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "decisionnode-deadline");
            t.setDaemon(true);
            return t;
        });
        ex.setRemoveOnCancelPolicy(true);
        return ex;
    }

    /** The default per-attempt timeout of a decision naming {@code ocr}, when the caller set none. */
    static final Duration OCR_TIMEOUT = Duration.ofSeconds(30);

    final String apiKey;
    final String baseUrl;
    final Duration timeout;
    /** True when the caller set {@link #timeout}; false when it is the default. */
    final boolean timeoutSet;
    final int maxRetries;
    final Map<String, String> headers;
    final HttpClient http;
    final boolean plainHttp;
    private final Function<Long, CompletableFuture<Void>> waiter;

    Transport(String apiKey, String baseUrl, Duration timeout, boolean timeoutSet, int maxRetries,
            Map<String, String> headers, HttpClient http, Function<Long, CompletableFuture<Void>> waiter) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.timeoutSet = timeoutSet;
        this.maxRetries = maxRetries;
        this.headers = headers;
        this.http = http;
        this.waiter = waiter;
        this.plainHttp = !baseUrl.regionMatches(true, 0, "https:", 0, 6);
    }

    /** Completes after the wait, through the client's waiter (tests record waits with it). */
    CompletableFuture<Void> sleep(long millis) {
        return waiter.apply(Math.max(0L, millis));
    }

    /** Exponential backoff from 0.5 s, capped at 8 s, with jitter, in seconds. */
    static double backoff(int attempt) {
        double base = Math.min(8.0, 0.5 * Math.pow(2, attempt));
        return base * (0.75 + ThreadLocalRandom.current().nextDouble() * 0.25);
    }

    // ------------------------------------------------------------------ JSON calls

    CompletableFuture<Response> json(String method, String path, byte[] body, Policy policy) {
        return call(method, path, body, "application/json", policy, timeout);
    }

    /**
     * A decision: a request whose boosters name {@code ocr} waits {@link #OCR_TIMEOUT} per attempt
     * unless the caller set a timeout (used as given, even below it), and is never retried after a
     * timeout.
     */
    CompletableFuture<Response> decision(String path, byte[] body, boolean namesOcr) {
        if (!namesOcr) {
            return json("POST", path, body, Policy.DEFAULT);
        }
        return call("POST", path, body, "application/json", Policy.OCR, decisionTimeout(true));
    }

    /** The per-attempt timeout of a decision: 30 s for one naming ocr unless the caller set one. */
    Duration decisionTimeout(boolean namesOcr) {
        return namesOcr && !timeoutSet ? OCR_TIMEOUT : timeout;
    }

    CompletableFuture<Response> call(String method, String path, byte[] body, String contentType, Policy policy) {
        return call(method, path, body, contentType, policy, timeout);
    }

    /**
     * A call the server applies once per {@code Idempotency-Key}: a new random key for this call,
     * sent on every attempt of it, so retrying it never applies it twice.
     */
    CompletableFuture<Response> idempotent(String method, String path, byte[] body) {
        return call(method, path, body, "application/json", Policy.IDEMPOTENT, timeout, java.util.UUID.randomUUID().toString());
    }

    private CompletableFuture<Response> call(String method, String path, byte[] body, String contentType, Policy policy,
            Duration limit) {
        return call(method, path, body, contentType, policy, limit, null);
    }

    /** One call whose success is a JSON object, each attempt within {@code limit}. */
    private CompletableFuture<Response> call(String method, String path, byte[] body, String contentType, Policy policy,
            Duration limit, String idempotencyKey) {
        HttpRequest request;
        try {
            request = request(method, path, body, contentType, "application/json", limit, idempotencyKey);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return this.<byte[]>attempt(request, 0, policy, limit, HttpResponse.BodySubscribers::ofByteArray)
                .thenApply(r -> {
                    Map<String, Object> obj;
                    try {
                        obj = Json.asMap(Json.read(r.body()));
                    } catch (IOException e) {
                        obj = null;
                    }
                    if (obj == null) {
                        throw Responses.notJson(r.statusCode(), r.headers(), r.body());
                    }
                    return new Response(r.statusCode(), r.headers(), obj);
                });
    }

    /**
     * A GET whose success is read line by line as the caller consumes it: the deadline covers the
     * attempt up to the response headers; the body then streams, and fails when no data arrives
     * for the timeout while the reader waits for more.
     */
    CompletableFuture<HttpResponse<Stream<String>>> lines(String path) {
        HttpRequest request;
        try {
            request = request("GET", path, null, null, "application/x-ndjson", timeout, null);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return this.attempt(request, 0, Policy.DEFAULT, timeout,
                () -> new IdleTimeout<>(HttpResponse.BodySubscribers.ofLines(java.nio.charset.StandardCharsets.UTF_8), timeout, DEADLINES));
    }

    private HttpRequest request(String method, String path, byte[] body, String contentType, String accept,
            Duration limit, String idempotencyKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new AuthenticationError("no API key: pass one to the client or set DECISIONNODE_API_KEY");
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(limit)
                .header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept);
        if (body != null) {
            rb.header("Content-Type", contentType);
            rb.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        }
        for (Map.Entry<String, String> h : headers.entrySet()) {
            rb.setHeader(h.getKey(), h.getValue());
        }
        if (idempotencyKey != null) {
            rb.setHeader("Idempotency-Key", idempotencyKey);
        }
        return rb.build();
    }

    /**
     * One attempt, then the retries the policy allows. A 2xx body is read with {@code ok}; any
     * other status is read whole and becomes the error its status gives.
     */
    private <T> CompletableFuture<HttpResponse<T>> attempt(HttpRequest request, int attempt, Policy policy, Duration limit,
            java.util.function.Supplier<HttpResponse.BodySubscriber<T>> okBody) {
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<Object>> sent;
        HttpResponse.BodyHandler<Object> handler = info -> {
            if (info.statusCode() >= 200 && info.statusCode() <= 299) {
                return HttpResponse.BodySubscribers.mapping(okBody.get(), b -> (Object) b);
            }
            return HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArray(), b -> (Object) b);
        };
        try {
            sent = http.sendAsync(request, handler);
        } catch (RuntimeException e) {
            sent = CompletableFuture.failedFuture(e);
        }
        // The timeout covers the whole attempt, from sending to the last byte of the body. The
        // request's own timeout covers only the wait for the response headers, so an overall
        // deadline cancels the exchange when it runs out.
        AtomicBoolean expired = new AtomicBoolean();
        CompletableFuture<HttpResponse<Object>> exchange = sent;
        ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
            if (!exchange.isDone()) {
                expired.set(true);
                exchange.cancel(true);
            }
        }, limit.toNanos(), TimeUnit.NANOSECONDS);
        return sent.handle((resp, exc) -> {
            deadline.cancel(false);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (exc != null) {
                Throwable cause = unwrap(exc);
                DecisionNodeError err = expired.get() && !(cause instanceof HttpTimeoutException)
                        ? new TimeoutError("no complete response within " + limit.toMillis() + " ms", cause)
                        : transportError(cause, limit);
                debug(request, null, null, ms, attempt, err.getMessage());
                if (policy != Policy.MUTATION && retryable(policy, err) && attempt < maxRetries) {
                    return sleep(millis(backoff(attempt))).thenCompose(v -> attempt(request, attempt + 1, policy, limit, okBody));
                }
                return CompletableFuture.<HttpResponse<T>>failedFuture(err);
            }
            int status = resp.statusCode();
            debug(request, status, Responses.header(resp.headers(), "x-request-id"), ms, attempt, null);
            if (status >= 200 && status <= 299) {
                @SuppressWarnings("unchecked")
                HttpResponse<T> ok = (HttpResponse<T>) (HttpResponse<?>) resp;
                return CompletableFuture.completedFuture(ok);
            }
            DecisionNodeError err = Responses.error(status, resp.headers(), (byte[]) resp.body());
            if (retryable(policy, status, err) && attempt < maxRetries) {
                Double wait = Responses.retryAfter(resp.headers());
                return sleep(millis(wait != null ? wait : backoff(attempt)))
                        .thenCompose(v -> attempt(request, attempt + 1, policy, limit, okBody));
            }
            return CompletableFuture.<HttpResponse<T>>failedFuture(err);
        }).thenCompose(Function.identity());
    }

    static long millis(double seconds) {
        return Math.max(0L, Math.round(Math.min(seconds, Responses.MAX_RETRY_AFTER) * 1000.0));
    }

    static boolean retryableStatus(int status) {
        return status == 408 || status == 429 || (status >= 500 && status <= 599);
    }

    private static boolean retryable(Policy policy, int status, DecisionNodeError err) {
        switch (policy) {
            case MUTATION:
                return status == 429 || status == 529;
            case SESSION_OPEN:
                return !(err instanceof SessionLimitError) && retryableStatus(status);
            case IDEMPOTENT:
                return retryableStatus(status) || (status == 409 && "idempotency_in_progress".equals(err.errorType()));
            default:
                return retryableStatus(status);
        }
    }

    /** A failure without a response: a timeout (never for a decision naming ocr) or a connection error. */
    private static boolean retryable(Policy policy, DecisionNodeError e) {
        return (e instanceof TimeoutError && policy != Policy.OCR) || e instanceof ConnectionError;
    }

    DecisionNodeError transportError(Throwable t) {
        return transportError(t, timeout);
    }

    private DecisionNodeError transportError(Throwable t, Duration limit) {
        if (t instanceof DecisionNodeError) {
            return (DecisionNodeError) t;
        }
        if (t instanceof HttpTimeoutException) {
            return new TimeoutError("no response within " + limit.toMillis() + " ms", t);
        }
        if (t instanceof IOException) {
            return new ConnectionError("connection error: " + t, t);
        }
        if (t instanceof CancellationException) {
            return new DecisionNodeError("the request was cancelled", t);
        }
        return new DecisionNodeError("request failed: " + t, t);
    }

    void debug(HttpRequest request, Integer status, String requestId, long ms, int attempt, String failure) {
        if (!LOG.isLoggable(System.Logger.Level.DEBUG)) {
            return;
        }
        LOG.log(System.Logger.Level.DEBUG, "{0} {1} -> {2} request_id={3} {4} ms attempt={5}",
                request.method(), request.uri().getPath(), status != null ? status.toString() : failure,
                requestId, ms, attempt + 1);
    }

    static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /** Waits for a future; its error is thrown as it is, unchecked. */
    static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new DecisionNodeError(String.valueOf(cause), cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DecisionNodeError("interrupted while waiting for the response", e);
        }
    }

    /** Completes after {@code millis} milliseconds without holding a thread. */
    static CompletableFuture<Void> delay(Long millis) {
        return CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS));
    }
}
