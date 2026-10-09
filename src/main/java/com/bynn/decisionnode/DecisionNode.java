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
import java.util.function.Consumer;
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
    public static final String VERSION = "0.3.0";
    /** The version of the DecisionNode SDK specification this SDK implements. */
    public static final String SPEC_VERSION = "1.3";

    /** The most images one upload call takes: one presign holds 1 to 16 files. */
    static final int MAX_UPLOAD_FILES = 16;
    /**
     * The inline threshold in bytes: a request's local images that total under it are sent inline
     * as base64; at or above it they are uploaded once and sent by upload id. Change it per client
     * with {@link Builder#inlineThreshold(long)}.
     */
    public static final int DEFAULT_INLINE_THRESHOLD = 524_288;
    public static final String DEFAULT_BASE_URL = "https://api.decisionnode.com";
    public static final String DEFAULT_MODEL = "decisionnode-latest";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    /**
     * The time allowed for each storage {@code PUT} of an image upload, apart from
     * {@link #DEFAULT_TIMEOUT}: an image of 10 MB on a slow uplink needs more than an API call.
     */
    public static final Duration DEFAULT_UPLOAD_TIMEOUT = Duration.ofSeconds(60);
    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final int DEFAULT_CONCURRENCY = 8;

    /** The {@code error_type}s that say an upload this client placed is no longer usable. */
    private static final Set<String> STALE_UPLOAD = Set.of("upload_expired", "upload_consumed", "upload_not_found", "upload_incomplete");

    private final String model;
    private final long inlineThreshold;
    private final Transport transport;
    private final Uploads uploads;
    private final Batches batches;
    private volatile Map<String, Map<String, Object>> held = Map.of();

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
        this.uploads = new Uploads(transport, inlineThreshold, b.uploadTimeout == null ? DEFAULT_UPLOAD_TIMEOUT : b.uploadTimeout,
                b.clock != null ? b.clock : System::currentTimeMillis);
        this.batches = new Batches(this);
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
        return decide(state, questions, images, null, model);
    }

    /**
     * {@link #decide(Object, Map, List, String)} with videos: {@link Video}s or wire-form maps, up
     * to 4; may be null.
     */
    public Decision decide(Object state, Map<String, ?> questions, List<?> images, List<?> videos, String model) {
        return decide(state, questions, images, videos, model, null);
    }

    /**
     * {@link #decide(Object, Map, List, List, String)} with boosters: {@link Booster}s, booster
     * names or wire-form maps, read before the decision and returned in
     * {@link Decision#evidence()}; may be null.
     */
    public Decision decide(
            Object state, Map<String, ?> questions, List<?> images, List<?> videos, String model, List<?> boosters) {
        return decide(DecideRequest.builder().state(state).questions(questions).images(images).videos(videos)
                .boosters(boosters).model(model).build());
    }

    public CompletableFuture<Decision> decideAsync(DecideRequest request) {
        Plan plan;
        try {
            plan = plan("/v1/decide", request);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return send(plan).thenApply(r -> Responses.decision(r.object(), r.headers()));
    }

    public CompletableFuture<Decision> decideAsync(Object state, Map<String, ?> questions) {
        return decideAsync(DecideRequest.of(state, questions));
    }

    public CompletableFuture<Decision> decideAsync(Object state, Map<String, ?> questions, List<?> images, String model) {
        return decideAsync(state, questions, images, null, model);
    }

    public CompletableFuture<Decision> decideAsync(
            Object state, Map<String, ?> questions, List<?> images, List<?> videos, String model) {
        return decideAsync(state, questions, images, videos, model, null);
    }

    public CompletableFuture<Decision> decideAsync(
            Object state, Map<String, ?> questions, List<?> images, List<?> videos, String model, List<?> boosters) {
        return decideAsync(DecideRequest.builder().state(state).questions(questions).images(images).videos(videos)
                .boosters(boosters).model(model).build());
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
    private Plan plan(String path, DecideRequest request) {
        Map<String, Object> head = checked(request);
        List<Object> images = request.images();
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

    /**
     * Every local check of a decision, in the server's order, and the body without its images.
     * Nothing is sent or uploaded.
     */
    private Map<String, Object> checked(DecideRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.questions().isEmpty()) {
            throw new IllegalArgumentException("questions must hold at least one question");
        }
        List<Object> images = request.images();
        Map<String, Object> head = new LinkedHashMap<>();
        String m = request.model();
        head.put("model", m != null && !m.isEmpty() ? m : model);
        head.put("state", request.state() == null ? "" : request.state());
        head.put("questions", request.questions());
        if (!request.boosters().isEmpty()) {
            // Left out when none are given, so the body is the one a request without boosters
            // always had. The list is the API's to judge; only its nesting counts here.
            head.put("boosters", Booster.wires(request.boosters()));
        }
        // The server's order: the body (nesting, the 422 entries, the first 400), then the
        // images, then the videos.
        Map<String, Object> limits = limits((String) head.get("model"));
        List<Object> videos = request.videos();
        LocalChecks.request(LocalChecks.probe(head, images.size(), videos), limits, images, videos);
        LocalChecks.images(images, limits);
        List<Object> wires = LocalChecks.videos(videos, images, limits);
        if (!wires.isEmpty()) {
            // Frames always go inline: they are never uploaded or cached.
            head.put("videos", wires);
        }
        return head;
    }

    // ------------------------------------------------------------------ checks without sending

    /**
     * Runs every local check {@link #decide(DecideRequest)} runs before sending, exactly as
     * sending would (limits held from an earlier {@link #models()} call included), and throws
     * what it would throw. Nothing is sent and nothing is uploaded. Returns normally when the
     * request would be sent. The helpers ({@code check}, {@code classify}, {@code score},
     * {@code number}, {@code gate}) send a decision, so their requests check the same way.
     *
     * @throws BadRequestError or {@link ValidationError} for a request the API would refuse
     * @throws IllegalArgumentException for an argument that cannot be sent
     */
    public void checkRequest(DecideRequest request) {
        Json.write(checked(request));
    }

    /**
     * Runs every local check {@link #openSession(SessionRequest)} runs before sending and throws
     * what it would throw. Nothing is sent. Frames check with {@link Session#checkFrame(Frame)};
     * batches with {@link Batches#checkCreateRequest(List)} and {@link Batches#checkAddRequest(List)}.
     */
    public void checkRequest(SessionRequest request) {
        Json.write(sessionBody(request));
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
                items.add(plan("/v1/decide", requests.get(i)));
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
    // Each helper takes images, videos and a model like decide: images are Images or wire-form
    // maps, videos are Videos or wire-form maps, either may be null; a null or empty model means
    // the client's.

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
        return check(state, instructions, ifTrue, ifFalse, images, null, model);
    }

    /** {@link #check(Object, Object, Object, Object, List, String)} with videos. */
    public TruthAnswer check(Object state, Object instructions, Object ifTrue, Object ifFalse, List<?> images, List<?> videos, String model) {
        return check(state, instructions, ifTrue, ifFalse, images, videos, model, null);
    }

    /** {@link #check(Object, Object, Object, Object, List, List, String)} with boosters. */
    public TruthAnswer check(Object state, Object instructions, Object ifTrue, Object ifFalse, List<?> images, List<?> videos,
            String model, List<?> boosters) {
        return Transport.await(checkAsync(state, instructions, ifTrue, ifFalse, images, videos, model, boosters));
    }

    public CompletableFuture<TruthAnswer> checkAsync(Object state, Object instructions) {
        return checkAsync(state, instructions, null, null, null, null);
    }

    public CompletableFuture<TruthAnswer> checkAsync(Object state, Object instructions, Object ifTrue, Object ifFalse) {
        return checkAsync(state, instructions, ifTrue, ifFalse, null, null);
    }

    public CompletableFuture<TruthAnswer> checkAsync(
            Object state, Object instructions, Object ifTrue, Object ifFalse, List<?> images, String model) {
        return checkAsync(state, instructions, ifTrue, ifFalse, images, null, model);
    }

    public CompletableFuture<TruthAnswer> checkAsync(
            Object state, Object instructions, Object ifTrue, Object ifFalse, List<?> images, List<?> videos, String model) {
        return checkAsync(state, instructions, ifTrue, ifFalse, images, videos, model, null);
    }

    public CompletableFuture<TruthAnswer> checkAsync(Object state, Object instructions, Object ifTrue, Object ifFalse,
            List<?> images, List<?> videos, String model, List<?> boosters) {
        return single(state, "check", Questions.truth(instructions, ifTrue, ifFalse), images, videos, model, boosters,
                TruthAnswer.class);
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
        return classify(state, instructions, options, images, null, model);
    }

    /** The classify call above with videos. */
    public ChoiceAnswer classify(Object state, Object instructions, Map<String, ?> options, List<?> images, List<?> videos, String model) {
        return classify(state, instructions, options, images, videos, model, null);
    }

    /** The classify call above with boosters. */
    public ChoiceAnswer classify(Object state, Object instructions, Map<String, ?> options, List<?> images, List<?> videos,
            String model, List<?> boosters) {
        return Transport.await(classifyAsync(state, instructions, options, images, videos, model, boosters));
    }

    /** {@link #classify(Object, Object, List)} with images and a model. */
    public ChoiceAnswer classify(Object state, Object instructions, List<String> options, List<?> images, String model) {
        return classify(state, instructions, options, images, null, model);
    }

    /** The classify call above with videos. */
    public ChoiceAnswer classify(Object state, Object instructions, List<String> options, List<?> images, List<?> videos, String model) {
        return classify(state, instructions, options, images, videos, model, null);
    }

    /** The classify call above with boosters. */
    public ChoiceAnswer classify(Object state, Object instructions, List<String> options, List<?> images, List<?> videos,
            String model, List<?> boosters) {
        return Transport.await(classifyAsync(state, instructions, options, images, videos, model, boosters));
    }

    public CompletableFuture<ChoiceAnswer> classifyAsync(Object state, Object instructions, Map<String, ?> options) {
        return classifyAsync(state, instructions, options, null, null);
    }

    public CompletableFuture<ChoiceAnswer> classifyAsync(Object state, Object instructions, List<String> options) {
        return classifyAsync(state, instructions, options, null, null);
    }

    public CompletableFuture<ChoiceAnswer> classifyAsync(
            Object state, Object instructions, Map<String, ?> options, List<?> images, String model) {
        return classifyAsync(state, instructions, options, images, null, model);
    }

    public CompletableFuture<ChoiceAnswer> classifyAsync(
            Object state, Object instructions, Map<String, ?> options, List<?> images, List<?> videos, String model) {
        return classifyAsync(state, instructions, options, images, videos, model, null);
    }

    public CompletableFuture<ChoiceAnswer> classifyAsync(Object state, Object instructions, Map<String, ?> options,
            List<?> images, List<?> videos, String model, List<?> boosters) {
        return single(state, "classify", Questions.choice(instructions, options), images, videos, model, boosters,
                ChoiceAnswer.class);
    }

    public CompletableFuture<ChoiceAnswer> classifyAsync(
            Object state, Object instructions, List<String> options, List<?> images, String model) {
        return classifyAsync(state, instructions, options, images, null, model);
    }

    public CompletableFuture<ChoiceAnswer> classifyAsync(
            Object state, Object instructions, List<String> options, List<?> images, List<?> videos, String model) {
        return classifyAsync(state, instructions, options, images, videos, model, null);
    }

    public CompletableFuture<ChoiceAnswer> classifyAsync(Object state, Object instructions, List<String> options,
            List<?> images, List<?> videos, String model, List<?> boosters) {
        return single(state, "classify", Questions.choice(instructions, options), images, videos, model, boosters,
                ChoiceAnswer.class);
    }

    /** One {@code score} question, sent under the id {@code score}. Levels run from low to high. */
    public ScoreAnswer score(Object state, Object instructions, List<?> levels) {
        return score(state, instructions, levels, null, null);
    }

    /** {@link #score(Object, Object, List)} with images and a model. */
    public ScoreAnswer score(Object state, Object instructions, List<?> levels, List<?> images, String model) {
        return score(state, instructions, levels, images, null, model);
    }

    /** {@link #score(Object, Object, List, List, String)} with videos. */
    public ScoreAnswer score(Object state, Object instructions, List<?> levels, List<?> images, List<?> videos, String model) {
        return score(state, instructions, levels, images, videos, model, null);
    }

    /** {@link #score(Object, Object, List, List, List, String)} with boosters. */
    public ScoreAnswer score(Object state, Object instructions, List<?> levels, List<?> images, List<?> videos, String model,
            List<?> boosters) {
        return Transport.await(scoreAsync(state, instructions, levels, images, videos, model, boosters));
    }

    public CompletableFuture<ScoreAnswer> scoreAsync(Object state, Object instructions, List<?> levels) {
        return scoreAsync(state, instructions, levels, null, null);
    }

    public CompletableFuture<ScoreAnswer> scoreAsync(
            Object state, Object instructions, List<?> levels, List<?> images, String model) {
        return scoreAsync(state, instructions, levels, images, null, model);
    }

    public CompletableFuture<ScoreAnswer> scoreAsync(
            Object state, Object instructions, List<?> levels, List<?> images, List<?> videos, String model) {
        return scoreAsync(state, instructions, levels, images, videos, model, null);
    }

    public CompletableFuture<ScoreAnswer> scoreAsync(Object state, Object instructions, List<?> levels, List<?> images,
            List<?> videos, String model, List<?> boosters) {
        return single(state, "score", Questions.score(instructions, levels), images, videos, model, boosters, ScoreAnswer.class);
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
        return number(state, instructions, min, max, step, images, null, model);
    }

    /** {@link #number(Object, Object, Number, Number, Number, List, String)} with videos. */
    public NumberAnswer number(
            Object state, Object instructions, Number min, Number max, Number step, List<?> images, List<?> videos, String model) {
        return number(state, instructions, min, max, step, images, videos, model, null);
    }

    /** {@link #number(Object, Object, Number, Number, Number, List, List, String)} with boosters. */
    public NumberAnswer number(Object state, Object instructions, Number min, Number max, Number step, List<?> images,
            List<?> videos, String model, List<?> boosters) {
        return Transport.await(numberAsync(state, instructions, min, max, step, images, videos, model, boosters));
    }

    public CompletableFuture<NumberAnswer> numberAsync(Object state, Object instructions, Number min, Number max, Number step) {
        return numberAsync(state, instructions, min, max, step, null, null);
    }

    public CompletableFuture<NumberAnswer> numberAsync(
            Object state, Object instructions, Number min, Number max, Number step, List<?> images, String model) {
        return numberAsync(state, instructions, min, max, step, images, null, model);
    }

    public CompletableFuture<NumberAnswer> numberAsync(
            Object state, Object instructions, Number min, Number max, Number step, List<?> images, List<?> videos, String model) {
        return numberAsync(state, instructions, min, max, step, images, videos, model, null);
    }

    public CompletableFuture<NumberAnswer> numberAsync(Object state, Object instructions, Number min, Number max, Number step,
            List<?> images, List<?> videos, String model, List<?> boosters) {
        return single(state, "number", Questions.number(instructions, min, max, step), images, videos, model, boosters,
                NumberAnswer.class);
    }

    private <T extends Answer> CompletableFuture<T> single(Object state, String id, Question q, List<?> images,
            List<?> videos, String model, List<?> boosters, Class<T> type) {
        Plan plan;
        try {
            plan = plan("/v1/decide", DecideRequest.builder().state(state).question(id, q).images(images).videos(videos)
                    .boosters(boosters).model(model).build());
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
        return gate(action, context, images, null, model);
    }

    /** {@link #gate(Object, Object, List, String)} with videos. */
    public Decision gate(Object action, Object context, List<?> images, List<?> videos, String model) {
        return gate(action, context, images, videos, model, null);
    }

    /** {@link #gate(Object, Object, List, List, String)} with boosters. */
    public Decision gate(Object action, Object context, List<?> images, List<?> videos, String model, List<?> boosters) {
        return Transport.await(gateAsync(action, context, images, videos, model, boosters));
    }

    public CompletableFuture<Decision> gateAsync(Object action, Object context) {
        return gateAsync(action, context, null, null);
    }

    public CompletableFuture<Decision> gateAsync(Object action, Object context, List<?> images, String model) {
        return gateAsync(action, context, images, null, model);
    }

    public CompletableFuture<Decision> gateAsync(Object action, Object context, List<?> images, List<?> videos, String model) {
        return gateAsync(action, context, images, videos, model, null);
    }

    public CompletableFuture<Decision> gateAsync(
            Object action, Object context, List<?> images, List<?> videos, String model, List<?> boosters) {
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
                .videos(videos)
                .boosters(boosters)
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
            if (images.size() > MAX_UPLOAD_FILES) {
                // One presign takes 1 to 16 files: its own 400, before anything is sent.
                throw LocalChecks.usage("files: 1 to " + MAX_UPLOAD_FILES + " files, got " + images.size() + ".", null);
            }
            LocalChecks.images(images, limits(null));
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

    // ------------------------------------------------------------------ sessions

    /** Opens a session. See {@link #openSession(SessionRequest)}. */
    public Session openSession(Object state, Map<String, ?> questions) {
        return openSession(SessionRequest.builder().state(state).questions(questions).build());
    }

    /**
     * {@code POST /v1/sessions}: opens a session that answers the same questions about a stream of
     * frames. The model is always sent (the request's, else the client's). Retried as any call,
     * except that a 429 {@code session_limit_exceeded} is never retried and raises
     * {@link SessionLimitError}. Then {@link Session#connect()} opens its socket.
     */
    public Session openSession(SessionRequest request) {
        return Transport.await(openSessionAsync(request));
    }

    public CompletableFuture<Session> openSessionAsync(SessionRequest request) {
        byte[] payload;
        try {
            payload = Json.write(sessionBody(request));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return transport.json("POST", "/v1/sessions", payload, Transport.Policy.SESSION_OPEN)
                .thenApply(r -> new Session(this, r.object()));
    }

    private Map<String, Object> sessionBody(SessionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.questions().isEmpty()) {
            throw new IllegalArgumentException("questions must hold at least one question");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        String m = request.model();
        body.put("model", m != null && !m.isEmpty() ? m : model);
        if (request.instructions() != null) {
            body.put("instructions", request.instructions());
        }
        if (request.state() != null) {
            body.put("state", request.state());
        }
        body.put("questions", request.questions());
        if (request.window() != null) {
            body.put("window", request.window());
        }
        if (request.ttlSeconds() != null) {
            body.put("ttl_seconds", request.ttlSeconds());
        }
        LocalChecks.sessionOpen(body, limits((String) body.get("model")));
        return body;
    }

    /**
     * Runs one long-lived feed through sessions, as a supervisor would: opens a session, sends each
     * reading of {@code feed} as a frame (latest wins on the server), hands every reply and frame
     * error to {@code onReply}, and reopens with the same request when the session closes with a
     * code that calls for it ({@code 1001}, {@code 1006}, {@code 1011}, {@code 4404},
     * {@code 4408}, {@code 4409}, {@code 4410}), after a capped backoff from 1 s to 30 s with
     * jitter ({@code 4408} and {@code 4410} without one). On {@code 1013} it connects again to
     * the same session. It stops on any other code.
     *
     * @return when the feed ends: after the outstanding replies, {@link Session#end()}, and the
     *     last {@link SessionClosed} seen, or null when none was; when it stops on a code: that
     *     {@link SessionClosed}
     */
    public SessionClosed supervise(SessionRequest request, Iterable<Frame> feed, Consumer<SessionEvent> onReply) {
        return new Supervisor(this, request, feed, onReply).run();
    }

    /**
     * {@link #supervise(SessionRequest, Iterable, Consumer)} on a thread of its own, since the feed
     * is read as it produces.
     */
    public CompletableFuture<SessionClosed> superviseAsync(SessionRequest request, Iterable<Frame> feed, Consumer<SessionEvent> onReply) {
        CompletableFuture<SessionClosed> out = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                out.complete(supervise(request, feed, onReply));
            } catch (Throwable e) {
                out.completeExceptionally(e);
            }
        }, "decisionnode-supervise");
        t.setDaemon(true);
        t.start();
        return out;
    }

    // ------------------------------------------------------------------ batches

    /** The Batch API: decisions answered within 24 hours at half the price. */
    public Batches batches() {
        return batches;
    }

    /**
     * Runs a whole batch: creates an empty draft, adds the requests (in calls of at most 10 MiB),
     * finalizes it, polls every {@code pollInterval} (or the server's {@code Retry-After} when it
     * is longer) until the batch has ended, then opens its results. If adding fails, the draft is
     * cancelled and the error raised.
     *
     * @param pollInterval at least 10 seconds
     * @param timeout      how long to wait for the batch to end; past it, {@link TimeoutError}
     * @throws IllegalArgumentException for a poll interval under 10 seconds, before any request
     */
    public BatchRun runBatch(List<BatchRequest> requests, Duration pollInterval, Duration timeout) {
        return Transport.await(runBatchAsync(requests, pollInterval, timeout));
    }

    /** {@link #runBatch(List, Duration, Duration)} polling every 60 seconds for up to 26 hours. */
    public BatchRun runBatch(List<BatchRequest> requests) {
        return runBatch(requests, Batches.DEFAULT_POLL_INTERVAL, Batches.DEFAULT_RUN_TIMEOUT);
    }

    public CompletableFuture<BatchRun> runBatchAsync(List<BatchRequest> requests, Duration pollInterval, Duration timeout) {
        try {
            return batches.run(requests, pollInterval, timeout);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<BatchRun> runBatchAsync(List<BatchRequest> requests) {
        return runBatchAsync(requests, Batches.DEFAULT_POLL_INTERVAL, Batches.DEFAULT_RUN_TIMEOUT);
    }

    // ------------------------------------------------------------------ models, raw

    /** {@code GET /v1/models}: the models with their published limits. */
    public List<ModelInfo> models() {
        return Transport.await(modelsAsync());
    }

    /**
     * {@code GET /v1/models}. The client also keeps the entries, so the limits only this list
     * states (a model without number questions or sessions, a smaller window, the batch limits)
     * are checked before later requests are sent.
     */
    public CompletableFuture<List<ModelInfo>> modelsAsync() {
        return transport.json("GET", "/v1/models", null, Transport.Policy.DEFAULT).thenApply(r -> {
            List<ModelInfo> models = Responses.models(r.object());
            Map<String, Map<String, Object>> byName = new java.util.HashMap<>();
            for (ModelInfo mi : models) {
                if (mi.name() != null) {
                    byName.put(mi.name(), mi.raw());
                }
            }
            held = Collections.unmodifiableMap(byName);
            return models;
        });
    }

    /**
     * The {@code /v1/models} entry of a model (null for the client's) with its name under
     * {@code model}, when an earlier {@link #models()} call gave one: the entry of that name, else
     * the entry that states the name as its {@code alias} or {@code version}. Empty otherwise,
     * and the checks that need it are left to the server.
     */
    Map<String, Object> limits(String name) {
        String n = name == null || name.isEmpty() ? model : name;
        Map<String, Object> entry = held.get(n);
        if (entry == null) {
            // A name the list states as an entry's alias or pinned version holds that entry.
            for (Map<String, Object> e : held.values()) {
                if (n.equals(e.get("alias")) || n.equals(e.get("version"))) {
                    entry = e;
                    break;
                }
            }
        }
        if (entry == null) {
            return Map.of();
        }
        Map<String, Object> out = new java.util.HashMap<>(entry);
        out.put("model", n);
        return out;
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
        private Duration uploadTimeout;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private HttpClient httpClient;
        private Function<String, String> environment = System::getenv;
        private Function<Long, CompletableFuture<Void>> waiter;
        private java.util.function.LongSupplier clock;

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
         * The time allowed for each storage {@code PUT} attempt of an image upload, until the
         * storage answers. Default: 60 seconds, apart from {@link #timeout(Duration)}. A
         * {@code PUT} that outlives it is retried as a timeout, then raises {@link TimeoutError}.
         *
         * @throws IllegalArgumentException when it is not above 0
         */
        public Builder uploadTimeout(Duration uploadTimeout) {
            if (uploadTimeout != null && (uploadTimeout.isNegative() || uploadTimeout.isZero())) {
                throw new IllegalArgumentException("uploadTimeout must be above 0, got " + uploadTimeout);
            }
            this.uploadTimeout = uploadTimeout;
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

        /**
         * The time, in epoch milliseconds, that upload URLs and cached uploads are judged by.
         * Tests replace it to move the client's time with a mock server's.
         */
        Builder clock(java.util.function.LongSupplier clock) {
            this.clock = Objects.requireNonNull(clock);
            return this;
        }

        public DecisionNode build() {
            return new DecisionNode(this);
        }
    }
}
