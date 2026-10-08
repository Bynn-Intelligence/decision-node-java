package com.bynn.decisionnode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
 * connections (HTTP/2 over TLS); create one and keep it.
 *
 * <p>The API key, base URL and model fall back to {@code DECISIONNODE_API_KEY},
 * {@code DECISIONNODE_BASE_URL} and {@code DECISIONNODE_MODEL}; an empty string counts as not
 * given. Failed attempts are retried on 408, 429, 5xx, timeouts and connection errors, twice by
 * default. Only a 2xx response is a success, and redirects are never followed. The base URL must
 * be {@code https}; plain {@code http} is accepted only for {@code localhost}, {@code 127.0.0.1}
 * and {@code ::1}. Each attempt's timeout covers it whole, up to the last byte of the body.
 *
 * <p>Every request is checked against the API's fixed rules before it is sent (images, option and
 * level counts, number grids), so a request the API would refuse fails at once with the API's own
 * error class and costs no round trip. Problems with the caller's own arguments throw
 * {@link IllegalArgumentException} before anything is sent.
 */
public final class DecisionNode {
    /** This SDK's version, sent in the {@code User-Agent} header. */
    public static final String VERSION = "0.1.0";
    /** The version of the DecisionNode SDK specification this SDK implements. */
    public static final String SPEC_VERSION = "1.1";
    /**
     * The inline threshold in bytes: a request's local images that total under it are sent inline
     * as base64; at or above it they are uploaded once and sent by upload id. Change it per client
     * with {@link Builder#inlineThreshold(long)}.
     */
    public static final int DEFAULT_INLINE_THRESHOLD = 524_288;
    public static final String DEFAULT_BASE_URL = "https://api.decisionnode.com";
    public static final String DEFAULT_MODEL = "decisionnode-latest";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final int DEFAULT_CONCURRENCY = 8;

    /** The {@code error_type}s that say an upload this client placed is no longer usable. */
    private static final Set<String> STALE_UPLOAD = Set.of("upload_expired", "upload_consumed", "upload_not_found", "upload_incomplete");

    private final String model;
    private final long inlineThreshold;
    private final Transport transport;
    private final Uploads uploads;

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
        String apiKey = firstNonEmpty(b.apiKey, env.apply("DECISIONNODE_API_KEY"));
        String url = firstNonEmpty(b.baseUrl, env.apply("DECISIONNODE_BASE_URL"));
        url = url == null ? DEFAULT_BASE_URL : url;
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String baseUrl = checkBaseUrl(url);
        String m = firstNonEmpty(b.model, env.apply("DECISIONNODE_MODEL"));
        this.model = m == null ? DEFAULT_MODEL : m;
        Duration timeout = b.timeout == null ? DEFAULT_TIMEOUT : b.timeout;
        int maxRetries = b.maxRetries == null ? DEFAULT_MAX_RETRIES : b.maxRetries;
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be above 0, got " + timeout);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be 0 or more, got " + maxRetries);
        }
        this.inlineThreshold = b.inlineThreshold == null ? DEFAULT_INLINE_THRESHOLD : b.inlineThreshold;
        Map<String, String> headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        boolean https = baseUrl.regionMatches(true, 0, "https:", 0, 6);
        HttpClient http;
        if (b.httpClient != null) {
            TransportCheck.checkRedirects(b.httpClient);
            http = b.httpClient;
        } else {
            HttpClient.Builder hb = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NEVER);
            // HTTP/2 is negotiated over TLS, so every call of the client shares one connection; a
            // plain-HTTP base URL (a local or self-hosted server) is spoken to in HTTP/1.1 rather
            // than through an upgrade attempt.
            hb.version(https ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1);
            http = hb.build();
        }
        if (https) {
            // TLS must check certificates and hostnames, or https protects nothing.
            TransportCheck.checkTls(b.httpClient);
        }
        this.transport = new Transport(apiKey, baseUrl, timeout, maxRetries, headers, http,
                b.waiter != null ? b.waiter : Transport::delay);
        this.uploads = new Uploads(transport, inlineThreshold);
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
            if (isLoopback(host)) {
                return url;
            }
            throw new IllegalArgumentException(
                    "base URL must use https; plain http is accepted only for localhost, 127.0.0.1 and ::1: " + url);
        }
        throw new IllegalArgumentException("base URL must use https: " + url);
    }

    /** {@code localhost}, {@code 127.0.0.1} or {@code ::1}, compared whole. */
    static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1");
    }

    /** The base URL requests go to, without a trailing slash. */
    public String baseUrl() {
        return transport.baseUrl;
    }

    /** The model sent when a call names none. */
    public String model() {
        return model;
    }

    public Duration timeout() {
        return transport.timeout;
    }

    public int maxRetries() {
        return transport.maxRetries;
    }

    /** The inline threshold in bytes; see {@link #DEFAULT_INLINE_THRESHOLD}. */
    public long inlineThreshold() {
        return inlineThreshold;
    }

    Transport transport() {
        return transport;
    }

    Uploads uploads() {
        return uploads;
    }

    // ------------------------------------------------------------------ decide

    /** Sends one decision. See {@link #decide(Object, Map, List, String)}. */
    public Decision decide(DecideRequest request) {
        return Transport.await(decideAsync(request));
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
     * @throws DecisionNodeError or one of its subclasses; {@link BadRequestError} or
     *     {@link ValidationError} before anything is sent for a request the API would refuse
     */
    public Decision decide(Object state, Map<String, ?> questions, List<?> images, String model) {
        return decide(DecideRequest.builder().state(state).questions(questions).images(images).model(model).build());
    }

    public CompletableFuture<Decision> decideAsync(DecideRequest request) {
        Plan plan;
        try {
            plan = plan("/v1/decide", request, false);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return send(plan).thenApply(r -> Responses.decision(r.object(), r.headers()));
    }

    public CompletableFuture<Decision> decideAsync(Object state, Map<String, ?> questions) {
        return decideAsync(DecideRequest.of(state, questions));
    }

    public CompletableFuture<Decision> decideAsync(Object state, Map<String, ?> questions, List<?> images, String model) {
        return decideAsync(DecideRequest.builder().state(state).questions(questions).images(images).model(model).build());
    }

    // ------------------------------------------------------------------ systemone

    /** Sends one {@code POST /v1/systemone}. See {@link #systemone(Object, Map, List, String)}. */
    public Decision systemone(Object state, Map<String, ?> questions) {
        return systemone(state, questions, null, null);
    }

    /**
     * Sends one {@code POST /v1/systemone}, the strict path that takes {@code truth},
     * {@code choice} and {@code score} questions. The model is always sent: the given one, else
     * the client's. A {@code truth} question is written in the wire type that path takes; its
     * answer comes back as a {@link TruthAnswer}. Images are sent as on {@link #decide}.
     */
    public Decision systemone(Object state, Map<String, ?> questions, List<?> images, String model) {
        return systemone(DecideRequest.builder().state(state).questions(questions).images(images).model(model).build());
    }

    /** {@link #systemone(Object, Map, List, String)} from a {@link DecideRequest}. */
    public Decision systemone(DecideRequest request) {
        return Transport.await(systemoneAsync(request));
    }

    public CompletableFuture<Decision> systemoneAsync(Object state, Map<String, ?> questions) {
        return systemoneAsync(state, questions, null, null);
    }

    public CompletableFuture<Decision> systemoneAsync(Object state, Map<String, ?> questions, List<?> images, String model) {
        return systemoneAsync(DecideRequest.builder().state(state).questions(questions).images(images).model(model).build());
    }

    public CompletableFuture<Decision> systemoneAsync(DecideRequest request) {
        Plan plan;
        try {
            plan = plan("/v1/systemone", request, true);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return send(plan).thenApply(r -> Responses.decision(r.object(), r.headers()));
    }

    // ------------------------------------------------------------------ one request, checked and sent

    /**
     * A request checked and ready to send. {@code payload} is the finished body when its images
     * go inline (or there are none); null when some must be uploaded first.
     */
    private record Plan(String path, Map<String, Object> head, List<Object> images, byte[] payload, Set<String> placed) {}

    /**
     * Checks a request and prepares its body. Every check happens here, before anything is sent:
     * the caller's arguments ({@link IllegalArgumentException}), then the API's fixed rules (the
     * API's own error class).
     */
    private Plan plan(String path, DecideRequest request, boolean systemone) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.questions().isEmpty()) {
            throw new IllegalArgumentException("questions must hold at least one question");
        }
        List<Object> images = request.images();
        LocalChecks.images(images);
        LocalChecks.questions(request.questions());
        Map<String, Object> head = new LinkedHashMap<>();
        String m = request.model();
        head.put("model", m != null && !m.isEmpty() ? m : model);
        head.put("state", request.state() == null ? "" : request.state());
        head.put("questions", systemone ? strict(request.questions()) : request.questions());
        if (images.isEmpty()) {
            return new Plan(path, head, images, Json.write(head), Set.of());
        }
        CompletableFuture<Uploads.Wires> wires = needsUpload(images) ? null : uploads.wires(images, true, false);
        if (wires != null && wires.isDone()) {
            Uploads.Wires w = wires.join();
            Map<String, Object> body = new LinkedHashMap<>(head);
            body.put("images", w.wires());
            return new Plan(path, head, images, Json.write(body), w.placed());
        }
        // Some images go up first: a body that cannot be serialised fails before any upload.
        Json.write(head);
        return new Plan(path, head, images, null, Set.of());
    }

    /** True when some local image of the request is to be uploaded rather than sent inline. */
    private boolean needsUpload(List<Object> images) {
        long total = 0;
        boolean uncached = false;
        for (Object o : images) {
            if (o instanceof Image && ((Image) o).isLocal()) {
                Image img = (Image) o;
                total += img.size();
                uncached |= uploads.cached(img) == null;
            }
        }
        return uncached && total >= inlineThreshold;
    }

    /** {@code truth} written in the wire type {@code /v1/systemone} takes for it. */
    private static Map<String, Object> strict(Map<String, Object> questions) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : questions.entrySet()) {
            Map<String, Object> q = LocalChecks.wire(e.getValue());
            if (q != null && "truth".equals(q.get("type"))) {
                Map<String, Object> copy = new LinkedHashMap<>(q);
                copy.put("type", "noul");
                out.put(e.getKey(), copy);
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * Sends a planned request. When the API calls an upload this client placed stale (expired,
     * consumed, not found or incomplete), those entries leave the cache, the images go again, and
     * the request is sent once more; that is not counted as a retry.
     */
    private CompletableFuture<Transport.Response> send(Plan plan) {
        CompletableFuture<Transport.Response> first = plan.payload() != null
                ? transport.json("POST", plan.path(), plan.payload(), Transport.Policy.DEFAULT)
                        .exceptionallyCompose(err -> CompletableFuture.failedFuture(stale(err, plan.placed())))
                : sendWithImages(plan);
        return first.exceptionallyCompose(err -> {
            Throwable cause = Transport.unwrap(err);
            if (cause instanceof StaleUpload) {
                StaleUpload stale = (StaleUpload) cause;
                uploads.drop(stale.placed);
                return sendWithImages(plan).exceptionally(e2 -> {
                    Throwable c2 = Transport.unwrap(e2);
                    throw c2 instanceof StaleUpload ? ((StaleUpload) c2).error : asUnchecked(c2);
                });
            }
            return CompletableFuture.failedFuture(cause);
        });
    }

    private CompletableFuture<Transport.Response> sendWithImages(Plan plan) {
        return uploads.wires(plan.images(), true, false).thenCompose(w -> {
            Map<String, Object> body = new LinkedHashMap<>(plan.head());
            body.put("images", w.wires());
            return transport.json("POST", plan.path(), Json.write(body), Transport.Policy.DEFAULT)
                    .exceptionallyCompose(err -> CompletableFuture.failedFuture(stale(err, w.placed())));
        });
    }

    /** A stale-upload answer for uploads this client placed, carried to the resend. */
    private static final class StaleUpload extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Set<String> placed;
        final BadRequestError error;

        StaleUpload(BadRequestError error, Set<String> placed) {
            super(error.getMessage(), null, false, false);
            this.error = error;
            this.placed = placed;
        }
    }

    private static Throwable stale(Throwable err, Set<String> placed) {
        Throwable cause = Transport.unwrap(err);
        if (!placed.isEmpty() && cause instanceof BadRequestError && STALE_UPLOAD.contains(((BadRequestError) cause).errorType())) {
            return new StaleUpload((BadRequestError) cause, placed);
        }
        return cause;
    }

    private static RuntimeException asUnchecked(Throwable t) {
        return t instanceof RuntimeException ? (RuntimeException) t : new DecisionNodeError(String.valueOf(t), t);
    }

    // ------------------------------------------------------------------ decideMany

    /** Sends every request, at most {@value #DEFAULT_CONCURRENCY} at a time. See {@link #decideMany(List, int)}. */
    public List<DecisionResult> decideMany(List<DecideRequest> requests) {
        return decideMany(requests, DEFAULT_CONCURRENCY);
    }

    /**
     * Sends every request, at most {@code concurrency} at a time, each with its own retries.
     * Every request is checked before any is sent.
     *
     * @return one result per request, in input order: a decision or the error that request ended
     *     with. One failure does not stop the others.
     * @throws IllegalArgumentException when {@code concurrency} is below 1 or a request is not one
     *     that can be sent; nothing is sent
     * @throws BadRequestError or {@link ValidationError} when a request breaks the API's fixed
     *     rules; nothing is sent
     */
    public List<DecisionResult> decideMany(List<DecideRequest> requests, int concurrency) {
        return Transport.await(decideManyAsync(requests, concurrency));
    }

    public CompletableFuture<List<DecisionResult>> decideManyAsync(List<DecideRequest> requests) {
        return decideManyAsync(requests, DEFAULT_CONCURRENCY);
    }

    /**
     * {@link #decideMany(List, int)}. A {@code concurrency} below 1, an invalid request or one
     * that breaks the API's fixed rules throws from this method itself, before anything is sent.
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
        List<Plan> items = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            try {
                items.add(plan("/v1/decide", requests.get(i), false));
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

    private CompletableFuture<Void> worker(List<Plan> items, DecisionResult[] out, AtomicInteger next) {
        int i = next.getAndIncrement();
        if (i >= items.size()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Decision> call;
        try {
            call = send(items.get(i)).thenApply(r -> Responses.decision(r.object(), r.headers()));
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
        return Transport.await(checkAsync(state, instructions, ifTrue, ifFalse, images, model));
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
        return Transport.await(classifyAsync(state, instructions, options, images, model));
    }

    /** {@link #classify(Object, Object, List)} with images and a model. */
    public ChoiceAnswer classify(Object state, Object instructions, List<String> options, List<?> images, String model) {
        return Transport.await(classifyAsync(state, instructions, options, images, model));
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
        return Transport.await(scoreAsync(state, instructions, levels, images, model));
    }

    public CompletableFuture<ScoreAnswer> scoreAsync(Object state, Object instructions, List<?> levels) {
        return scoreAsync(state, instructions, levels, null, null);
    }

    public CompletableFuture<ScoreAnswer> scoreAsync(
            Object state, Object instructions, List<?> levels, List<?> images, String model) {
        return single(state, "score", Questions.score(instructions, levels), images, model, ScoreAnswer.class);
    }

    /**
     * One {@code number} question, sent under the id {@code number}: a value on the grid from
     * {@code min} to {@code max} in steps of {@code step}. Each bound left null is left out, so
     * the API's default applies (0, 255 and 1).
     */
    public NumberAnswer number(Object state, Object instructions, Number min, Number max, Number step) {
        return number(state, instructions, min, max, step, null, null);
    }

    /** {@link #number(Object, Object, Number, Number, Number)} with images and a model. */
    public NumberAnswer number(Object state, Object instructions, Number min, Number max, Number step, List<?> images, String model) {
        return Transport.await(numberAsync(state, instructions, min, max, step, images, model));
    }

    public CompletableFuture<NumberAnswer> numberAsync(Object state, Object instructions, Number min, Number max, Number step) {
        return numberAsync(state, instructions, min, max, step, null, null);
    }

    public CompletableFuture<NumberAnswer> numberAsync(
            Object state, Object instructions, Number min, Number max, Number step, List<?> images, String model) {
        return single(state, "number", Questions.number(instructions, min, max, step), images, model, NumberAnswer.class);
    }

    private <T extends Answer> CompletableFuture<T> single(
            Object state, String id, Question q, List<?> images, String model, Class<T> type) {
        Plan plan;
        try {
            plan = plan("/v1/decide", DecideRequest.builder().state(state).question(id, q).images(images).model(model).build(), false);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        // A helper promises a typed answer. When its own answer is missing, malformed or of
        // another type, the call fails with a ServerError carrying the server's reason, where
        // decide would return an UnknownAnswer.
        return send(plan).thenApply(r -> {
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
        return Transport.await(gateAsync(action, context, images, model));
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

    // ------------------------------------------------------------------ uploads

    /**
     * Uploads images now, whatever their size, and returns one upload id per image, in order:
     * {@code POST /v1/uploads} once for the images not already uploaded by this client, then one
     * {@code PUT} each, in parallel. An image already uploaded returns its cached id, and an
     * {@link Image#fromUpload upload id} is returned as given. Pass the ids on with
     * {@link Image#fromUpload(String, String)}; this client also reuses them by content on its
     * own, so calling this first is never required.
     *
     * @throws IllegalArgumentException when an image is given by URL, which is never uploaded
     * @throws BadRequestError before anything is sent, for an image the API would refuse
     */
    public List<String> upload(List<Image> images) {
        return Transport.await(uploadAsync(images));
    }

    public CompletableFuture<List<String>> uploadAsync(List<Image> images) {
        List<String> out = new ArrayList<>();
        List<Integer> todo = new ArrayList<>();
        try {
            Objects.requireNonNull(images, "images");
            LocalChecks.images(images);
            for (int i = 0; i < images.size(); i++) {
                Image img = images.get(i);
                if (img.url() != null) {
                    throw new IllegalArgumentException("image '" + img.id() + "' is given by URL; a URL is never uploaded");
                }
                String id = img.isLocal() ? uploads.cached(img) : img.uploadId();
                out.add(id);
                if (id == null) {
                    todo.add(i);
                }
            }
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (todo.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.unmodifiableList(out));
        }
        List<Image> local = new ArrayList<>();
        for (int i : todo) {
            local.add(images.get(i));
        }
        return uploads.upload(local).thenApply(ids -> {
            for (int n = 0; n < todo.size(); n++) {
                out.set(todo.get(n), ids.get(n));
            }
            return Collections.unmodifiableList(out);
        });
    }

    // ------------------------------------------------------------------ models, raw

    /** {@code GET /v1/models}: the models with their published limits. */
    public List<ModelInfo> models() {
        return Transport.await(modelsAsync());
    }

    public CompletableFuture<List<ModelInfo>> modelsAsync() {
        return transport.json("GET", "/v1/models", null, Transport.Policy.DEFAULT).thenApply(r -> Responses.models(r.object()));
    }

    /**
     * Sends {@code body} to {@code POST /v1/decide} exactly as given, without filling in a model or
     * checking anything, and returns the response body as parsed JSON. Use it for an API field this
     * SDK version does not know yet. Errors and retries work as for {@link #decide}.
     */
    public Map<String, Object> decideRaw(Object body) {
        return Transport.await(decideRawAsync(body));
    }

    public CompletableFuture<Map<String, Object>> decideRawAsync(Object body) {
        byte[] payload;
        try {
            payload = Json.write(Objects.requireNonNull(body, "body"));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return transport.json("POST", "/v1/decide", payload, Transport.Policy.DEFAULT).thenApply(Transport.Response::object);
    }

    // ------------------------------------------------------------------ support

    static DecisionNodeError asError(Throwable t) {
        t = Transport.unwrap(t);
        return t instanceof DecisionNodeError ? (DecisionNodeError) t : new DecisionNodeError(String.valueOf(t), t);
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
        private Long inlineThreshold;
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
         * The inline threshold in bytes: a request's local images (or a session frame's, or a
         * batch line's) that total under it are sent inline as base64; at or above it they are
         * uploaded and sent by upload id. Default: {@value DecisionNode#DEFAULT_INLINE_THRESHOLD}.
         * 0 uploads every local image.
         *
         * @throws IllegalArgumentException when it is below 0
         */
        public Builder inlineThreshold(long bytes) {
            if (bytes < 0) {
                throw new IllegalArgumentException("inlineThreshold must be 0 or more, got " + bytes);
            }
            this.inlineThreshold = bytes;
            return this;
        }

        /**
         * A header sent on every API request and on a session's handshake, for example
         * {@code x-decisionnode-safety-body: true}, which asks the API to put the safety check in
         * the response body as well. It is never sent to storage with an upload.
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
         * How a wait is spent (between attempts, between storage retries, between batch polls,
         * before a session reopens): given milliseconds, a future that completes when the wait is
         * over. Tests replace it to record waits without sleeping.
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
