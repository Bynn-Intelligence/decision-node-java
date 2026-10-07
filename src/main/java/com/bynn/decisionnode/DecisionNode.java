package com.bynn.decisionnode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.net.ssl.SSLContext;

/**
 * The DecisionNode client.
 *
 * <pre>{@code
 * DecisionNode dn = new DecisionNode();   // reads DECISIONNODE_API_KEY
 *
 * Decision d = dn.decide(
 *     Map.of("ticket", "I was charged twice and nobody has replied for 3 days."),
 *     Map.of("escalate", truth("Escalate to a human now?")));
 *
 * d.truth("escalate").truth();   // 0.94
 * }</pre>
 *
 * <p>Every method has a blocking form and an {@code ...Async} form that returns a
 * {@link CompletableFuture}. A client is safe to share between threads and reuses its
 * connections; create one and keep it.
 *
 * <p>The API key, base URL and model fall back to {@code DECISIONNODE_API_KEY},
 * {@code DECISIONNODE_BASE_URL} and {@code DECISIONNODE_MODEL}; an empty string counts as not
 * given. Failed attempts are retried on 408, 429, 5xx, timeouts and connection errors, twice by
 * default. Only a 2xx response is a success, and redirects are never followed. The base URL must
 * be {@code https}; plain {@code http} is accepted only for {@code localhost}, {@code 127.0.0.1}
 * and {@code ::1}. Each attempt's timeout covers it whole, up to the last byte of the body.
 *
 * <p>Problems with the caller's own arguments throw {@link IllegalArgumentException} before
 * anything is sent.
 */
public final class DecisionNode {
    /** This SDK's version, sent in the {@code User-Agent} header. */
    public static final String VERSION = "0.1.0";
    /** The version of the DecisionNode SDK specification this SDK implements. */
    public static final String SPEC_VERSION = "1.0";
    public static final String DEFAULT_BASE_URL = "https://api.decisionnode.com";
    public static final String DEFAULT_MODEL = "decisionnode-latest";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final int DEFAULT_CONCURRENCY = 8;

    private static final String USER_AGENT = "decisionnode-java/" + VERSION;
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

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final int maxRetries;
    private final Map<String, String> headers;
    private final HttpClient http;
    private final Function<Long, CompletableFuture<Void>> waiter;

    /** A client configured from the environment. */
    public DecisionNode() {
        this(builder());
    }

    /** A client with this API key; everything else from the environment or the defaults. */
    public DecisionNode(String apiKey) {
        this(builder().apiKey(apiKey));
    }

    private DecisionNode(Builder b) {
        Function<String, String> env = b.environment;
        this.apiKey = firstNonEmpty(b.apiKey, env.apply("DECISIONNODE_API_KEY"));
        String url = firstNonEmpty(b.baseUrl, env.apply("DECISIONNODE_BASE_URL"));
        url = url == null ? DEFAULT_BASE_URL : url;
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.baseUrl = checkBaseUrl(url);
        String m = firstNonEmpty(b.model, env.apply("DECISIONNODE_MODEL"));
        this.model = m == null ? DEFAULT_MODEL : m;
        this.timeout = b.timeout == null ? DEFAULT_TIMEOUT : b.timeout;
        this.maxRetries = b.maxRetries == null ? DEFAULT_MAX_RETRIES : b.maxRetries;
        if (this.timeout.isNegative() || this.timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be above 0, got " + this.timeout);
        }
        if (this.maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be 0 or more, got " + this.maxRetries);
        }
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        this.waiter = b.waiter != null ? b.waiter : DecisionNode::delay;
        boolean https = this.baseUrl.regionMatches(true, 0, "https:", 0, 6);
        if (b.httpClient != null) {
            TransportCheck.checkRedirects(b.httpClient);
            this.http = b.httpClient;
        } else {
            HttpClient.Builder hb = HttpClient.newBuilder()
                    .connectTimeout(this.timeout)
                    .followRedirects(HttpClient.Redirect.NEVER);
            // HTTP/2 is negotiated over TLS; a plain-HTTP base URL (a local or self-hosted
            // server) is spoken to in HTTP/1.1 rather than through an upgrade attempt.
            hb.version(https ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1);
            this.http = hb.build();
        }
        if (https) {
            // TLS must check certificates and hostnames, or https protects nothing.
            TransportCheck.checkTls(b.httpClient);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The base URL must be {@code https}, so the API key never travels unencrypted. Plain
     * {@code http} is accepted only for a server on this machine: {@code localhost},
     * {@code 127.0.0.1} or {@code ::1}.
     */
    private static String checkBaseUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("base URL is not a valid URL: " + url, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("base URL has no host: " + url);
        }
        if (scheme.equals("https")) {
            return url;
        }
        if (scheme.equals("http")) {
            String h = host.toLowerCase(java.util.Locale.ROOT);
            if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1")) {
                return url;
            }
            throw new IllegalArgumentException(
                    "base URL must use https; plain http is accepted only for localhost, 127.0.0.1 and ::1: " + url);
        }
        throw new IllegalArgumentException("base URL must use https: " + url);
    }

    /** The base URL requests go to, without a trailing slash. */
    public String baseUrl() {
        return baseUrl;
    }

    /** The model sent when a call names none. */
    public String model() {
        return model;
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxRetries() {
        return maxRetries;
    }

    // ------------------------------------------------------------------ decide

    /** Sends one decision. See {@link #decide(Object, Map, List, String)}. */
    public Decision decide(DecideRequest request) {
        return await(decideAsync(request));
    }

    /** Sends one decision without a state. */
    public Decision decide(Map<String, ?> questions) {
        return decide(DecideRequest.of(null, questions));
    }

    /** Sends one decision. */
    public Decision decide(Object state, Map<String, ?> questions) {
        return decide(DecideRequest.of(state, questions));
    }

    /** Sends one decision with images. */
    public Decision decide(Object state, Map<String, ?> questions, List<?> images) {
        return decide(state, questions, images, null);
    }

    /**
     * Sends one {@code POST /v1/decide}.
     *
     * @param state     what is judged: a string or any value that serialises to JSON; null sends {@code ""}
     * @param questions the questions by id: {@link Question}s or wire-form maps; at least one
     * @param images    {@link Image}s or wire-form maps; may be null
     * @param model     the model for this call; null or empty for the client's
     * @throws DecisionNodeError or one of its subclasses
     */
    public Decision decide(Object state, Map<String, ?> questions, List<?> images, String model) {
        return decide(DecideRequest.builder().state(state).questions(questions).images(images).model(model).build());
    }

    public CompletableFuture<Decision> decideAsync(DecideRequest request) {
        byte[] payload;
        try {
            payload = payload(request);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        return decidePayload(payload);
    }

    private CompletableFuture<Decision> decidePayload(byte[] payload) {
        return sendBytes("POST", "/v1/decide", payload).thenApply(r -> Responses.decision(r.object(), r.headers()));
    }

    /**
     * The JSON body of one decision. Every check of the caller's arguments happens here, so a
     * request that passes it is sent as it is.
     *
     * @throws IllegalArgumentException when the request is not one that can be sent
     */
    private byte[] payload(DecideRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        return Json.write(body(request));
    }

    public CompletableFuture<Decision> decideAsync(Object state, Map<String, ?> questions) {
        return decideAsync(DecideRequest.of(state, questions));
    }

    public CompletableFuture<Decision> decideAsync(Object state, Map<String, ?> questions, List<?> images, String model) {
        return decideAsync(DecideRequest.builder().state(state).questions(questions).images(images).model(model).build());
    }

    private Map<String, Object> body(DecideRequest request) {
        if (request.questions().isEmpty()) {
            throw new IllegalArgumentException("questions must hold at least one question");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        String m = request.model();
        body.put("model", m != null && !m.isEmpty() ? m : model);
        body.put("state", request.state() == null ? "" : request.state());
        body.put("questions", request.questions());
        if (!request.images().isEmpty()) {
            List<Object> images = new ArrayList<>();
            for (Object img : request.images()) {
                images.add(img instanceof Image ? ((Image) img).toMap() : img);
            }
            body.put("images", images);
        }
        return body;
    }

    // ------------------------------------------------------------------ decideMany

    /** Sends every request, at most {@value #DEFAULT_CONCURRENCY} at a time. See {@link #decideMany(List, int)}. */
    public List<DecisionResult> decideMany(List<DecideRequest> requests) {
        return decideMany(requests, DEFAULT_CONCURRENCY);
    }

    /**
     * Sends every request, at most {@code concurrency} at a time, each with its own retries.
     *
     * @return one result per request, in input order: a decision or the error that request ended
     *     with. One failure does not stop the others.
     * @throws IllegalArgumentException when {@code concurrency} is below 1; nothing is sent
     */
    public List<DecisionResult> decideMany(List<DecideRequest> requests, int concurrency) {
        return await(decideManyAsync(requests, concurrency));
    }

    public CompletableFuture<List<DecisionResult>> decideManyAsync(List<DecideRequest> requests) {
        return decideManyAsync(requests, DEFAULT_CONCURRENCY);
    }

    /**
     * {@link #decideMany(List, int)}. A {@code concurrency} below 1 or an invalid request throws
     * {@link IllegalArgumentException} from this method itself, before anything is sent.
     */
    public CompletableFuture<List<DecisionResult>> decideManyAsync(List<DecideRequest> requests, int concurrency) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be at least 1, got " + concurrency);
        }
        if (requests == null) {
            throw new IllegalArgumentException("requests must not be null");
        }
        // Every request is checked before any is sent: one invalid request is the caller's
        // mistake, so the whole call fails and nothing goes out.
        List<byte[]> items = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            try {
                items.add(payload(requests.get(i)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("requests[" + i + "]: " + e.getMessage(), e);
            }
        }
        DecisionResult[] out = new DecisionResult[items.size()];
        AtomicInteger next = new AtomicInteger();
        int workers = Math.min(concurrency, Math.max(1, items.size()));
        CompletableFuture<?>[] running = new CompletableFuture<?>[workers];
        for (int w = 0; w < workers; w++) {
            running[w] = worker(items, out, next);
        }
        return CompletableFuture.allOf(running).thenApply(v -> Collections.unmodifiableList(Arrays.asList(out)));
    }

    private CompletableFuture<Void> worker(List<byte[]> items, DecisionResult[] out, AtomicInteger next) {
        int i = next.getAndIncrement();
        if (i >= items.size()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Decision> call;
        try {
            call = decidePayload(items.get(i));
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call
                .handle((d, e) -> {
                    out[i] = e == null ? DecisionResult.of(d) : DecisionResult.of(asError(e));
                    return null;
                })
                .thenComposeAsync(v -> worker(items, out, next));
    }

    // ------------------------------------------------------------------ helpers
    //
    // Each helper takes images and a model like decide: images are Images or wire-form maps and
    // may be null; a null or empty model means the client's.

    /** One {@code truth} question, sent under the id {@code check}. */
    public TruthAnswer check(Object state, Object instructions) {
        return check(state, instructions, null, null, null, null);
    }

    /** One {@code truth} question with criteria, sent under the id {@code check}. */
    public TruthAnswer check(Object state, Object instructions, Object ifTrue, Object ifFalse) {
        return check(state, instructions, ifTrue, ifFalse, null, null);
    }

    /** One {@code truth} question with criteria, images and a model, sent under the id {@code check}. */
    public TruthAnswer check(Object state, Object instructions, Object ifTrue, Object ifFalse, List<?> images, String model) {
        return await(checkAsync(state, instructions, ifTrue, ifFalse, images, model));
    }

    public CompletableFuture<TruthAnswer> checkAsync(Object state, Object instructions) {
        return checkAsync(state, instructions, null, null, null, null);
    }

    public CompletableFuture<TruthAnswer> checkAsync(Object state, Object instructions, Object ifTrue, Object ifFalse) {
        return checkAsync(state, instructions, ifTrue, ifFalse, null, null);
    }

    public CompletableFuture<TruthAnswer> checkAsync(
            Object state, Object instructions, Object ifTrue, Object ifFalse, List<?> images, String model) {
        return single(state, "check", Questions.truth(instructions, ifTrue, ifFalse), images, model, TruthAnswer.class);
    }

    /** One {@code choice} question, sent under the id {@code classify}. Options are a map of name to description. */
    public ChoiceAnswer classify(Object state, Object instructions, Map<String, ?> options) {
        return classify(state, instructions, options, null, null);
    }

    /** One {@code choice} question over option names, sent under the id {@code classify}. */
    public ChoiceAnswer classify(Object state, Object instructions, List<String> options) {
        return classify(state, instructions, options, null, null);
    }

    /** {@link #classify(Object, Object, Map)} with images and a model. */
    public ChoiceAnswer classify(Object state, Object instructions, Map<String, ?> options, List<?> images, String model) {
        return await(classifyAsync(state, instructions, options, images, model));
    }

    /** {@link #classify(Object, Object, List)} with images and a model. */
    public ChoiceAnswer classify(Object state, Object instructions, List<String> options, List<?> images, String model) {
        return await(classifyAsync(state, instructions, options, images, model));
    }

    public CompletableFuture<ChoiceAnswer> classifyAsync(Object state, Object instructions, Map<String, ?> options) {
        return classifyAsync(state, instructions, options, null, null);
    }

    public CompletableFuture<ChoiceAnswer> classifyAsync(Object state, Object instructions, List<String> options) {
        return classifyAsync(state, instructions, options, null, null);
    }

    public CompletableFuture<ChoiceAnswer> classifyAsync(
            Object state, Object instructions, Map<String, ?> options, List<?> images, String model) {
        return single(state, "classify", Questions.choice(instructions, options), images, model, ChoiceAnswer.class);
    }

    public CompletableFuture<ChoiceAnswer> classifyAsync(
            Object state, Object instructions, List<String> options, List<?> images, String model) {
        return single(state, "classify", Questions.choice(instructions, options), images, model, ChoiceAnswer.class);
    }

    /** One {@code score} question, sent under the id {@code score}. Levels run from low to high. */
    public ScoreAnswer score(Object state, Object instructions, List<?> levels) {
        return score(state, instructions, levels, null, null);
    }

    /** {@link #score(Object, Object, List)} with images and a model. */
    public ScoreAnswer score(Object state, Object instructions, List<?> levels, List<?> images, String model) {
        return await(scoreAsync(state, instructions, levels, images, model));
    }

    public CompletableFuture<ScoreAnswer> scoreAsync(Object state, Object instructions, List<?> levels) {
        return scoreAsync(state, instructions, levels, null, null);
    }

    public CompletableFuture<ScoreAnswer> scoreAsync(
            Object state, Object instructions, List<?> levels, List<?> images, String model) {
        return single(state, "score", Questions.score(instructions, levels), images, model, ScoreAnswer.class);
    }

    private <T extends Answer> CompletableFuture<T> single(
            Object state, String id, Question q, List<?> images, String model, Class<T> type) {
        DecideRequest request = DecideRequest.builder().state(state).question(id, q).images(images).model(model).build();
        byte[] payload;
        try {
            payload = payload(request);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        // A helper promises a typed answer. When its own answer is missing, malformed or of
        // another type, the call fails with a ServerError carrying the server's reason, where
        // decide would return an UnknownAnswer.
        return sendBytes("POST", "/v1/decide", payload).thenApply(r -> {
            Decision d = Responses.decision(r.object(), r.headers());
            Answer a = d.answers().get(id);
            if (!type.isInstance(a)) {
                throw Responses.malformedAnswer(r.status(), r.headers(), r.object(),
                        (a == null ? "the response has no answer" : "the response has no well-formed " + q.type() + " answer")
                                + " under the id '" + id + "'");
            }
            return type.cast(a);
        });
    }

    /**
     * Asks whether an action should run: a {@code risk} score ({@code none} to {@code severe}), a
     * {@code touches_production} truth and a {@code verdict} choice of {@code allow},
     * {@code confirm} or {@code block}. The wording of the three questions is fixed and the same
     * in every DecisionNode SDK. The state sent is {@code {"action": action, "context": context}}.
     */
    public Decision gate(Object action, Object context) {
        return gate(action, context, null, null);
    }

    /** {@link #gate(Object, Object)} without context. */
    public Decision gate(Object action) {
        return gate(action, null, null, null);
    }

    /** {@link #gate(Object, Object)} with images and a model. */
    public Decision gate(Object action, Object context, List<?> images, String model) {
        return await(gateAsync(action, context, images, model));
    }

    public CompletableFuture<Decision> gateAsync(Object action, Object context) {
        return gateAsync(action, context, null, null);
    }

    public CompletableFuture<Decision> gateAsync(Object action, Object context, List<?> images, String model) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("action", action);
        if (context != null) {
            state.put("context", context);
        }
        return decideAsync(DecideRequest.builder()
                .state(state)
                .question("risk", Questions.score(
                        "How much harm could this action cause if it is wrong?",
                        "none", "low", "moderate", "high", "severe"))
                .question("touches_production", Questions.truth(
                        "Does this action change a production system, production data or anything customers use?"))
                .question("verdict", Questions.choice(
                        "Should this action run as it is, wait for a person to confirm it, or be blocked?",
                        Questions.options(
                                "allow", "safe to run without asking",
                                "confirm", "a person should confirm it first",
                                "block", "should not run")))
                .images(images)
                .model(model)
                .build());
    }

    // ------------------------------------------------------------------ models, raw

    /** {@code GET /v1/models}: the models with their published limits. */
    public List<ModelInfo> models() {
        return await(modelsAsync());
    }

    public CompletableFuture<List<ModelInfo>> modelsAsync() {
        return send("GET", "/v1/models", null).thenApply(r -> Responses.models(r.object()));
    }

    /**
     * Sends {@code body} to {@code POST /v1/decide} exactly as given, without filling in a model,
     * and returns the response body as parsed JSON. Use it for an API field this SDK version does
     * not know yet. Errors and retries work as for {@link #decide}.
     */
    public Map<String, Object> decideRaw(Object body) {
        return await(decideRawAsync(body));
    }

    public CompletableFuture<Map<String, Object>> decideRawAsync(Object body) {
        return send("POST", "/v1/decide", Objects.requireNonNull(body, "body")).thenApply(Response::object);
    }

    // ------------------------------------------------------------------ transport

    private record Response(int status, java.net.http.HttpHeaders headers, Map<String, Object> object) {}

    private CompletableFuture<Response> send(String method, String path, Object body) {
        byte[] payload;
        try {
            payload = body == null ? null : Json.write(body);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        return sendBytes(method, path, payload);
    }

    private CompletableFuture<Response> sendBytes(String method, String path, byte[] body) {
        if (apiKey == null || apiKey.isEmpty()) {
            return CompletableFuture.failedFuture(new AuthenticationError(
                    "no API key: pass one to the client or set DECISIONNODE_API_KEY"));
        }
        HttpRequest request;
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json");
            if (body != null) {
                rb.header("Content-Type", "application/json");
                rb.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            } else {
                rb.method(method, HttpRequest.BodyPublishers.noBody());
            }
            for (Map.Entry<String, String> h : headers.entrySet()) {
                rb.setHeader(h.getKey(), h.getValue());
            }
            request = rb.build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        return attempt(request, 0);
    }

    private CompletableFuture<Response> attempt(HttpRequest request, int attempt) {
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> sent;
        try {
            sent = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (RuntimeException e) {
            sent = CompletableFuture.failedFuture(e);
        }
        // The timeout covers the whole attempt, from sending to the last byte of the body. The
        // request's own timeout only covers the wait for the response headers, so an overall
        // deadline cancels the exchange when it runs out.
        AtomicBoolean expired = new AtomicBoolean();
        CompletableFuture<HttpResponse<byte[]>> exchange = sent;
        ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
            if (!exchange.isDone()) {
                expired.set(true);
                exchange.cancel(true);
            }
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);
        return sent.handle((resp, exc) -> {
            deadline.cancel(false);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (exc != null) {
                Throwable cause = unwrap(exc);
                DecisionNodeError err = expired.get() && !(cause instanceof HttpTimeoutException)
                        ? new TimeoutError("no complete response within " + timeout.toMillis() + " ms", cause)
                        : transportError(cause);
                debug(request, null, null, ms, attempt, err.getMessage());
                if (retryable(err) && attempt < maxRetries) {
                    return retryAfter(request, attempt, backoff(attempt));
                }
                return CompletableFuture.<Response>failedFuture(err);
            }
            int status = resp.statusCode();
            String requestId = Responses.header(resp.headers(), "x-request-id");
            debug(request, status, requestId, ms, attempt, null);
            if (status >= 200 && status <= 299) {
                Map<String, Object> obj;
                try {
                    obj = Json.asMap(Json.read(resp.body()));
                } catch (IOException e) {
                    obj = null;
                }
                if (obj == null) {
                    return CompletableFuture.<Response>failedFuture(Responses.notJson(status, resp.headers(), resp.body()));
                }
                return CompletableFuture.completedFuture(new Response(status, resp.headers(), obj));
            }
            DecisionNodeError err = Responses.error(status, resp.headers(), resp.body());
            if (retryableStatus(status) && attempt < maxRetries) {
                Double wait = Responses.retryAfter(resp.headers());
                return retryAfter(request, attempt, wait != null ? wait : backoff(attempt));
            }
            return CompletableFuture.<Response>failedFuture(err);
        }).thenCompose(Function.identity());
    }

    private CompletableFuture<Response> retryAfter(HttpRequest request, int attempt, double seconds) {
        long millis = Math.max(0L, Math.round(Math.min(seconds, Responses.MAX_RETRY_AFTER) * 1000.0));
        return waiter.apply(millis).thenCompose(v -> attempt(request, attempt + 1));
    }

    /** Completes after {@code millis} milliseconds without holding a thread. */
    private static CompletableFuture<Void> delay(Long millis) {
        return CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS));
    }

    static boolean retryableStatus(int status) {
        return status == 408 || status == 429 || (status >= 500 && status <= 599);
    }

    private static boolean retryable(DecisionNodeError e) {
        return e instanceof TimeoutError || e instanceof ConnectionError;
    }

    /** Exponential backoff from 0.5 s, capped at 8 s, with jitter, in seconds. */
    static double backoff(int attempt) {
        double base = Math.min(8.0, 0.5 * Math.pow(2, attempt));
        return base * (0.75 + ThreadLocalRandom.current().nextDouble() * 0.25);
    }

    private DecisionNodeError transportError(Throwable t) {
        if (t instanceof DecisionNodeError) {
            return (DecisionNodeError) t;
        }
        if (t instanceof HttpTimeoutException) {
            return new TimeoutError("no response within " + timeout.toMillis() + " ms", t);
        }
        if (t instanceof IOException) {
            return new ConnectionError("connection error: " + t, t);
        }
        if (t instanceof CancellationException) {
            return new DecisionNodeError("the request was cancelled", t);
        }
        return new DecisionNodeError("request failed: " + t, t);
    }

    private void debug(HttpRequest request, Integer status, String requestId, long ms, int attempt, String failure) {
        if (!LOG.isLoggable(System.Logger.Level.DEBUG)) {
            return;
        }
        LOG.log(System.Logger.Level.DEBUG, "{0} {1} -> {2} request_id={3} {4} ms attempt={5}",
                request.method(), request.uri().getPath(), status != null ? status.toString() : failure,
                requestId, ms, attempt + 1);
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static DecisionNodeError asError(Throwable t) {
        t = unwrap(t);
        return t instanceof DecisionNodeError ? (DecisionNodeError) t : new DecisionNodeError(String.valueOf(t), t);
    }

    private static <T> T await(CompletableFuture<T> future) {
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

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        return b != null && !b.isEmpty() ? b : null;
    }

    /** Builds a {@link DecisionNode}. Every setting is optional. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private String model;
        private Duration timeout;
        private Integer maxRetries;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private HttpClient httpClient;
        private Function<String, String> environment = System::getenv;
        private Function<Long, CompletableFuture<Void>> waiter;

        private Builder() {}

        /** The API key. Default: {@code DECISIONNODE_API_KEY}. Null or empty counts as not given. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * The API's base URL. Default: {@code DECISIONNODE_BASE_URL}, else
         * {@value DecisionNode#DEFAULT_BASE_URL}. Null or empty counts as not given. It must be
         * {@code https}; plain {@code http} is accepted only for {@code localhost},
         * {@code 127.0.0.1} and {@code ::1}, and anything else makes {@link #build()} throw
         * {@link IllegalArgumentException}.
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * The model sent when a call names none. Default: {@code DECISIONNODE_MODEL}, else
         * {@value DecisionNode#DEFAULT_MODEL}. Null or empty counts as not given.
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * The time allowed for each attempt, from sending the request to the last byte of the
         * response body. Default: 10 seconds.
         *
         * @throws IllegalArgumentException when it is not above 0
         */
        public Builder timeout(Duration timeout) {
            if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
                throw new IllegalArgumentException("timeout must be above 0, got " + timeout);
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * Retries after the first attempt. Default: 2. Zero turns retries off.
         *
         * @throws IllegalArgumentException when it is below 0
         */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be 0 or more, got " + maxRetries);
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * A header sent on every request, for example {@code x-decisionnode-safety-body: true},
         * which asks the API to put the safety check in the response body as well.
         */
        public Builder header(String name, String value) {
            headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Headers sent on every request. */
        public Builder headers(Map<String, String> headers) {
            if (headers != null) {
                headers.forEach(this::header);
            }
            return this;
        }

        /**
         * The {@link HttpClient} to send with, for a proxy, a custom executor or TLS settings.
         * Default: one made for this client. The client's timeout still applies to each attempt.
         * It must be the JDK's own client (from {@link HttpClient#newBuilder()} or
         * {@link HttpClient#newHttpClient()}) and must not follow redirects
         * ({@link HttpClient.Redirect#NEVER}, the JDK default), so the API key is never sent on
         * to another URL; a subclass cannot be shown to refuse redirects. For an {@code https}
         * base URL its {@link SSLContext} must verify certificates: one that is not the JDK
         * default is tested once with an in-memory handshake against a throwaway self-signed
         * certificate, and one that accepts it is refused. {@link #build()} throws
         * {@link IllegalArgumentException} for each of these.
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /** Where the environment variables are read from; tests use it. */
        Builder environment(Function<String, String> environment) {
            this.environment = Objects.requireNonNull(environment);
            return this;
        }

        /**
         * How the wait between attempts is spent: given milliseconds, a future that completes when
         * the next attempt may start. Tests replace it to record waits without sleeping.
         */
        Builder waiter(Function<Long, CompletableFuture<Void>> waiter) {
            this.waiter = Objects.requireNonNull(waiter);
            return this;
        }

        public DecisionNode build() {
            return new DecisionNode(this);
        }
    }
}
