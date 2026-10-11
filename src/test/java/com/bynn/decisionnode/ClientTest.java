package com.bynn.decisionnode;

import static com.bynn.decisionnode.Questions.truth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The client against a small in-process HTTP server. */
class ClientTest {
    private static final String OK = "{\"model\":\"decisionnode-moe-0.9.0-rc1\",\"answers\":{\"q\":{\"type\":\"truth\",\"truth\":0.5}},"
            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}";

    record Seen(String method, String path, Map<String, List<String>> headers, String body) {}

    private HttpServer server;
    private String url;
    private final ConcurrentLinkedQueue<Seen> seen = new ConcurrentLinkedQueue<>();
    private volatile Function<Seen, Reply> handler = s -> new Reply(200, Map.of("x-request-id", "req_ok"), OK);

    record Reply(int status, Map<String, String> headers, String body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Seen s = new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestHeaders(), body);
        seen.add(s);
        Reply r = handler.apply(s);
        r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(r.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            ex.getResponseBody().write(out);
        }
        ex.close();
    }

    private DecisionNode.Builder client(Map<String, String> env) {
        return DecisionNode.builder().environment(env::get).apiKey("dn_test").baseUrl(url);
    }

    private DecisionNode client() {
        return client(Map.of()).build();
    }

    @Test
    void versionMatchesTheBuild() {
        String expected = System.getProperty("decisionnode.version");
        if (expected != null) {
            assertEquals(expected, DecisionNode.VERSION);
        }
    }

    @Test
    void sendsTheRequest() {
        Decision d = client().decide("s", Map.of("q", truth("?")));
        assertEquals(0.5, d.truth("q").truth());
        assertEquals("req_ok", d.requestId());
        Seen s = seen.peek();
        assertEquals("POST", s.method());
        assertEquals("/v1/decide", s.path());
        assertEquals("Bearer dn_test", s.headers().get("Authorization").get(0));
        assertEquals("decisionnode-java/" + DecisionNode.VERSION, s.headers().get("User-Agent").get(0));
        assertEquals("application/json", s.headers().get("Content-Type").get(0));
        assertEquals("{\"model\":\"decisionnode-moe\",\"state\":\"s\",\"questions\":{\"q\":{\"type\":\"truth\",\"instructions\":\"?\"}}}", s.body());
    }

    @Test
    void argumentsBeatTheEnvironmentWhichBeatsDefaults() {
        Map<String, String> env = Map.of("DECISIONNODE_API_KEY", "dn_env", "DECISIONNODE_MODEL", "decisionnode-flash",
                "DECISIONNODE_BASE_URL", "https://example.invalid/");
        DecisionNode fromEnv = DecisionNode.builder().environment(env::get).build();
        assertEquals("https://example.invalid", fromEnv.baseUrl());
        assertEquals("decisionnode-flash", fromEnv.model());
        DecisionNode explicit = DecisionNode.builder().environment(env::get).model("decisionnode-moe").baseUrl(url).build();
        assertEquals("decisionnode-moe", explicit.model());
        DecisionNode none = DecisionNode.builder().environment(k -> null).build();
        assertEquals(DecisionNode.DEFAULT_BASE_URL, none.baseUrl());
        assertEquals(DecisionNode.DEFAULT_MODEL, none.model());
        assertEquals(Duration.ofSeconds(10), none.timeout());
        assertEquals(2, none.maxRetries());
    }

    @Test
    void noKeyFailsBeforeSending() {
        DecisionNode dn = DecisionNode.builder().environment(k -> null).baseUrl(url).build();
        assertThrows(AuthenticationError.class, () -> dn.decide("s", Map.of("q", truth("?"))));
        assertTrue(seen.isEmpty());
    }

    @Test
    void emptyQuestionsFailBeforeSending() {
        assertThrows(IllegalArgumentException.class, () -> client().decide("s", Map.of()));
        assertTrue(seen.isEmpty());
    }

    @Test
    void unserialisableStateFailsBeforeSending() {
        Object notJson = new Object();
        assertThrows(IllegalArgumentException.class, () -> client().decide(notJson, Map.of("q", truth("?"))));
        assertTrue(seen.isEmpty());
    }

    @Test
    void asyncFormCompletesWithTheDecision() throws Exception {
        CompletableFuture<Decision> f = client().decideAsync("s", Map.of("q", truth("?")));
        assertEquals(0.5, f.get().truth("q").truth());
    }

    @Test
    void asyncFormFailsWithTheTypedError() {
        handler = s -> new Reply(404, Map.of(), "{\"detail\":{\"error_type\":\"not_found_error\",\"message\":\"nope\"}}");
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> client().decideAsync("s", Map.of("q", truth("?"))).get());
        assertInstanceOf(NotFoundError.class, e.getCause());
    }

    @Test
    void retriesHonourRetryAfterMs() {
        AtomicInteger n = new AtomicInteger();
        handler = s -> n.getAndIncrement() < 2
                ? new Reply(529, Map.of("retry-after-ms", "10", "retry-after", "5"), "{\"detail\":{\"error_type\":\"overloaded_error\"}}")
                : new Reply(200, Map.of(), OK);
        long t0 = System.nanoTime();
        client().decide("s", Map.of("q", truth("?")));
        assertEquals(3, seen.size());
        assertTrue(System.nanoTime() - t0 < 3_000_000_000L, "used retry-after-ms, not Retry-After");
    }

    @Test
    void retriesRunOutWithTheLastError() {
        handler = s -> new Reply(429, Map.of("retry-after-ms", "5"), "{\"detail\":{\"error_type\":\"rate_limit_error\"}}");
        RateLimitError e = assertThrows(RateLimitError.class,
                () -> client(Map.of()).maxRetries(1).build().decide("s", Map.of("q", truth("?"))));
        assertEquals(0.005, e.retryAfter());
        assertEquals(2, seen.size());
    }

    @Test
    void neverRetriesA400() {
        handler = s -> new Reply(400, Map.of(), "{\"detail\":\"bad\"}");
        assertThrows(BadRequestError.class, () -> client().decide("s", Map.of("q", truth("?"))));
        assertEquals(1, seen.size());
    }

    @Test
    void timeoutIsRetriedThenThrown() {
        handler = s -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Reply(200, Map.of(), OK);
        };
        DecisionNode dn = client(Map.of()).timeout(Duration.ofMillis(150)).maxRetries(1).build();
        assertThrows(TimeoutError.class, () -> dn.decide("s", Map.of("q", truth("?"))));
    }

    @Test
    void decideManyKeepsInputOrderAndIsolatesFailures() {
        handler = s -> s.body().contains("\"state\":\"b\"")
                ? new Reply(422, Map.of(), "{\"detail\":[]}")
                : new Reply(200, Map.of(), OK);
        List<DecideRequest> reqs = new ArrayList<>();
        for (String st : List.of("a", "b", "c")) {
            reqs.add(DecideRequest.of(st, Map.of("q", truth("?"))));
        }
        List<DecisionResult> out = client().decideMany(reqs, 2);
        assertEquals(3, out.size());
        assertTrue(out.get(0).isSuccess());
        assertFalse(out.get(1).isSuccess());
        assertInstanceOf(ValidationError.class, out.get(1).error());
        assertThrows(ValidationError.class, () -> out.get(1).getOrThrow());
        assertTrue(out.get(2).isSuccess());
        assertNull(out.get(1).decision());
    }

    @Test
    void decideManyWithNothingReturnsNothing() {
        assertEquals(Collections.emptyList(), client().decideMany(List.of()));
    }

    @Test
    void decideRawSendsTheBodyAsGiven() {
        Map<String, Object> out = client().decideRaw(Map.of("questions", Map.of("q", truth("?"))));
        assertEquals("decisionnode-moe-0.9.0-rc1", out.get("model"));
        assertEquals("{\"questions\":{\"q\":{\"type\":\"truth\",\"instructions\":\"?\"}}}", seen.peek().body());
    }

    @Test
    void customHeadersAreSent() {
        client(Map.of()).header("x-decisionnode-safety-body", "true").build().decide("s", Map.of("q", truth("?")));
        assertEquals("true", seen.peek().headers().get("X-decisionnode-safety-body").get(0));
    }

    @Test
    void nonJsonSuccessIsAServerError() {
        handler = s -> new Reply(200, Map.of(), "<html>");
        assertThrows(ServerError.class, () -> client().models());
    }

    @Test
    void helpersReturnTheirAnswer() {
        handler = s -> new Reply(200, Map.of(), s.body().contains("\"check\"")
                ? "{\"model\":\"m\",\"answers\":{\"check\":{\"type\":\"truth\",\"truth\":0.25}}}"
                : "{\"model\":\"m\",\"answers\":{}}");
        assertEquals(0.25, client().check("s", "?").truth());
        assertThrows(ServerError.class, () -> client().classify("s", "?", List.of("a", "b")));
    }

    @Test
    void specVersion() {
        assertEquals("1.3", DecisionNode.SPEC_VERSION);
        assertEquals(524_288, DecisionNode.DEFAULT_INLINE_THRESHOLD);
    }

    @Test
    void helpersSendImagesAndModel() {
        handler = s -> new Reply(200, Map.of(),
                "{\"model\":\"m\",\"answers\":{\"score\":{\"type\":\"score\",\"score\":0.5,\"confidence\":0.5,"
                        + "\"legend\":{\"0\":\"low\",\"1\":\"high\"},\"probabilities\":{\"0\":0.5,\"1\":0.5}}}}");
        client().score("s", "?", List.of("low", "high"),
                List.of(Image.fromUrl("img", "https://example.com/a.png")), "decisionnode-flash");
        String body = seen.peek().body();
        assertTrue(body.startsWith("{\"model\":\"decisionnode-flash\""), body);
        assertTrue(body.contains("\"images\":[{\"id\":\"img\",\"url\":\"https://example.com/a.png\"}]"), body);
    }

    @Test
    void videosAreSentInlineAfterTheImages() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        Video dock = Video.of("dock", List.of(VideoFrame.fromBytes(30, jpeg), VideoFrame.fromBase64(30.5, "AAEC", "image/webp"))).fps(2);
        Map<String, Object> wire = Map.of("id", "yard", "frames", List.of(Map.of("t", 1, "media_type", "image/png", "data", "AA==")));
        client().decide("Warehouse camera 4.", Map.of("q", truth("A fall?")), null, List.of(dock, wire), null);
        Map<String, Object> body = Json.asMap(Json.readOrNull(seen.peek().body().getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of(
                Map.of("id", "dock", "fps", 2.0, "frames", List.of(
                        Map.of("t", 30.0, "media_type", "image/jpeg", "data", "/9j/"),
                        Map.of("t", 30.5, "media_type", "image/webp", "data", "AAEC"))),
                wire), body.get("videos"));
        assertNull(body.get("images"));
        // A helper carries videos too.
        handler = s -> new Reply(200, Map.of(), "{\"answers\":{\"check\":{\"type\":\"truth\",\"truth\":0.5}}}");
        client().check("s", "A fall?", null, null, null, List.of(dock), null);
        assertTrue(new ArrayList<>(seen).get(1).body().contains("\"videos\":[{\"id\":\"dock\""));
    }

    @Test
    void videoUsageAndWarnings() {
        handler = s -> new Reply(200, Map.of(), "{\"answers\":{\"q\":{\"type\":\"truth\",\"truth\":0.5}},"
                + "\"usage\":{\"input_tokens\":1300,\"output_tokens\":0,\"video_frames\":20,\"video_seconds\":10.000000000000002},"
                + "\"warnings\":[\"Video 'dock' was read at 1 frame a second.\"]}");
        Decision d = client().decide(Map.of("q", truth("?")));
        assertEquals(20L, d.usage().videoFrames());
        assertEquals(10.000000000000002, d.usage().videoSeconds());
        assertEquals(List.of("Video 'dock' was read at 1 frame a second."), d.warnings());
        handler = s -> new Reply(200, Map.of(), OK);
        Decision plain = client().decide(Map.of("q", truth("?")));
        assertNull(plain.usage().videoFrames());
        assertNull(plain.usage().videoSeconds());
        assertEquals(List.of(), plain.warnings());
    }

    @Test
    void emptyValuesCountAsNotGiven() {
        Map<String, String> env = Map.of("DECISIONNODE_API_KEY", "dn_env", "DECISIONNODE_MODEL", "decisionnode-flash",
                "DECISIONNODE_BASE_URL", url);
        DecisionNode dn = DecisionNode.builder().environment(env::get).apiKey("").baseUrl("").model("").build();
        assertEquals(url, dn.baseUrl());
        assertEquals("decisionnode-flash", dn.model());
        dn.decide(DecideRequest.builder().state("s").question("q", truth("?")).model("").build());
        Seen s = seen.peek();
        assertEquals("Bearer dn_env", s.headers().get("Authorization").get(0));
        assertTrue(s.body().startsWith("{\"model\":\"decisionnode-flash\""), s.body());
    }

    @Test
    void decideManyRefusesConcurrencyBelowOne() {
        List<DecideRequest> reqs = List.of(DecideRequest.of("s", Map.of("q", truth("?"))));
        assertThrows(IllegalArgumentException.class, () -> client().decideMany(reqs, 0));
        assertThrows(IllegalArgumentException.class, () -> client().decideManyAsync(reqs, -1));
        assertTrue(seen.isEmpty());
    }

    @Test
    void redirectsAreNotFollowed() {
        handler = s -> new Reply(307, Map.of("Location", url + "/elsewhere"), "{\"detail\":\"Moved.\"}");
        DecisionNodeError e = assertThrows(DecisionNodeError.class, () -> client().decide("s", Map.of("q", truth("?"))));
        assertEquals(DecisionNodeError.class, e.getClass());
        assertEquals(307, e.status());
        assertEquals(1, seen.size());
        java.net.http.HttpClient following = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
        assertThrows(IllegalArgumentException.class, () -> client(Map.of()).httpClient(following).build());
    }

    @Test
    void waitsTheServerAsksForAreCappedAtSixtySeconds() {
        List<Long> waits = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger n = new AtomicInteger();
        handler = s -> n.getAndIncrement() == 0
                ? new Reply(429, Map.of("Retry-After", "3600"), "{}")
                : new Reply(200, Map.of(), OK);
        client(Map.of()).waiter(ms -> {
            waits.add(ms);
            return CompletableFuture.completedFuture(null);
        }).build().decide("s", Map.of("q", truth("?")));
        assertEquals(List.of(60_000L), waits);
    }

    @Test
    void plainHttpOnlyForThisMachine() {
        for (String ok : List.of("https://api.example.com", "http://localhost:8080", "http://127.0.0.1:1",
                "http://[::1]:9000/", "HTTPS://Example.com")) {
            DecisionNode.builder().environment(k -> null).baseUrl(ok).build();
        }
        for (String bad : List.of("http://api.example.com", "http://10.0.0.5", "http://localhost.example.com",
                "ftp://example.com", "api.example.com", "https://")) {
            assertThrows(IllegalArgumentException.class,
                    () -> DecisionNode.builder().environment(k -> null).baseUrl(bad).build(), bad);
        }
        assertThrows(IllegalArgumentException.class,
                () -> DecisionNode.builder().environment(Map.of("DECISIONNODE_BASE_URL", "http://example.com")::get).build());
    }

    @Test
    void timeoutAndRetriesMustBeInRange() {
        assertThrows(IllegalArgumentException.class, () -> DecisionNode.builder().timeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> DecisionNode.builder().timeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> DecisionNode.builder().maxRetries(-1));
        assertEquals(0, client(Map.of()).maxRetries(0).build().maxRetries());
    }

    @Test
    void timeoutCoversTheWholeBody() throws Exception {
        // Headers arrive at once; the body trickles in for longer than the timeout.
        server.createContext("/v1/models", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 0);
            try (var out = ex.getResponseBody()) {
                for (int i = 0; i < 20; i++) {
                    out.write(' ');
                    out.flush();
                    Thread.sleep(100);
                }
                out.write("{\"models\":[]}".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException | IOException ignored) {
                // the client went away
            }
        });
        DecisionNode dn = client(Map.of()).timeout(Duration.ofMillis(400)).maxRetries(0).build();
        long started = System.nanoTime();
        assertThrows(TimeoutError.class, dn::models);
        long ms = (System.nanoTime() - started) / 1_000_000;
        assertTrue(ms < 1500, "took " + ms + " ms");
        TimeoutError async = assertThrows(TimeoutError.class, () -> {
            try {
                dn.modelsAsync().get();
            } catch (java.util.concurrent.ExecutionException e) {
                throw e.getCause();
            }
        });
        assertEquals(null, async.status());
    }

    @Test
    void emptyServerMessageIsEmptyNotNull() {
        handler = s -> new Reply(400, Map.of(), "{\"detail\":{\"error_type\":\"max_tokens_exceeded\"}}");
        DecisionNodeError e = assertThrows(ContextLimitError.class, () -> client().decide("s", Map.of("q", truth("?"))));
        assertEquals("", e.getMessage());
        handler = s -> new Reply(500, Map.of(), "{}");
        e = assertThrows(ServerError.class, () -> client(Map.of()).maxRetries(0).build().decide("s", Map.of("q", truth("?"))));
        assertEquals("", e.getMessage());
        // An empty body is not JSON: the client describes the status itself.
        handler = s -> new Reply(500, Map.of(), "");
        e = assertThrows(ServerError.class, () -> client(Map.of()).maxRetries(0).build().decide("s", Map.of("q", truth("?"))));
        assertEquals("HTTP 500 Internal Server Error", e.getMessage());
        handler = s -> new Reply(200, Map.of(), "not json");
        e = assertThrows(ServerError.class, () -> client().decide("s", Map.of("q", truth("?"))));
        assertEquals("", e.getMessage());
    }

    @Test
    void decideManyChecksEveryRequestBeforeSendingAny() {
        List<DecideRequest> reqs = List.of(
                DecideRequest.of("a", Map.of("q", truth("?"))),
                DecideRequest.of("b", Map.of("q", truth("?"))),
                DecideRequest.of("c", Map.of()));
        DecisionNode dn = client();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> dn.decideMany(reqs, 1));
        assertEquals(IllegalArgumentException.class, e.getClass());
        assertThrows(IllegalArgumentException.class, () -> dn.decideManyAsync(reqs, 1));
        List<DecideRequest> unserialisable = List.of(
                DecideRequest.of("a", Map.of("q", truth("?"))),
                DecideRequest.of(new Object(), Map.of("q", truth("?"))));
        assertThrows(IllegalArgumentException.class, () -> dn.decideMany(unserialisable, 1));
        assertTrue(seen.isEmpty());
    }

    @Test
    void helperWithAMalformedAnswerIsAServerErrorWithTheReason() {
        for (String answers : List.of("{\"check\":{\"type\":\"truth\",\"truth\":\"0.9\"}}", "{}")) {
            handler = s -> new Reply(200, Map.of("x-request-id", "req_h", "x-decisionnode-error", "could not be completed"),
                    "{\"model\":\"decisionnode-moe-0.9.0-rc1\",\"answers\":" + answers + "}");
            ServerError e = assertThrows(ServerError.class, () -> client().check("s", "?"));
            assertEquals(200, e.status());
            assertEquals("req_h", e.requestId());
            assertEquals("could not be completed", e.reason());
            ServerError async = assertThrows(ServerError.class, () -> {
                try {
                    client().checkAsync("s", "?").get();
                } catch (ExecutionException x) {
                    throw x.getCause();
                }
            });
            assertEquals("could not be completed", async.reason());
        }
        // decide returns the same malformed answer as an UnknownAnswer.
        handler = s -> new Reply(200, Map.of(),
                "{\"model\":\"decisionnode-moe-0.9.0-rc1\",\"answers\":{\"q\":{\"type\":\"truth\",\"truth\":\"0.9\"}}}");
        assertInstanceOf(UnknownAnswer.class, client().decide("s", Map.of("q", truth("?"))).get("q"));
    }

    @Test
    void anHttpClientOfUnknownTypeIsRefused() {
        java.net.http.HttpClient real = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpClient lying = new DelegatingClient(real);
        assertEquals(java.net.http.HttpClient.Redirect.NEVER, lying.followRedirects());
        assertThrows(IllegalArgumentException.class, () -> client(Map.of()).httpClient(lying).build());
        client(Map.of()).httpClient(real).build();
    }

    @Test
    void tlsChecksMustStayOn() throws Exception {
        javax.net.ssl.SSLContext trustAll = javax.net.ssl.SSLContext.getInstance("TLS");
        trustAll.init(null, new javax.net.ssl.TrustManager[] {new javax.net.ssl.X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        }}, null);
        java.net.http.HttpClient insecure = java.net.http.HttpClient.newBuilder().sslContext(trustAll).build();
        assertThrows(IllegalArgumentException.class, () -> DecisionNode.builder().environment(k -> null)
                .baseUrl("https://api.example.com").httpClient(insecure).build());
        // Plain http to this machine does not use TLS, so the context is not consulted.
        client(Map.of()).httpClient(insecure).build();

        javax.net.ssl.SSLContext own = javax.net.ssl.SSLContext.getInstance("TLS");
        own.init(null, null, null); // the JDK's trust managers: certificates are checked
        java.net.http.HttpClient checked = java.net.http.HttpClient.newBuilder().sslContext(own).build();
        DecisionNode.builder().environment(k -> null).baseUrl("https://api.example.com").httpClient(checked).build();
        DecisionNode.builder().environment(k -> null).baseUrl("https://api.example.com")
                .httpClient(java.net.http.HttpClient.newHttpClient()).build();

        String before = System.getProperty(TransportCheck.HOSTNAME_PROPERTY);
        System.setProperty(TransportCheck.HOSTNAME_PROPERTY, "");
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> DecisionNode.builder().environment(k -> null).baseUrl("https://api.example.com").build());
            client(Map.of()).build(); // plain http to this machine
        } finally {
            if (before == null) {
                System.clearProperty(TransportCheck.HOSTNAME_PROPERTY);
            } else {
                System.setProperty(TransportCheck.HOSTNAME_PROPERTY, before);
            }
        }
    }

    /** An HttpClient subclass the SDK cannot inspect: it says NEVER but could do anything. */
    private static final class DelegatingClient extends java.net.http.HttpClient {
        private final java.net.http.HttpClient d;

        DelegatingClient(java.net.http.HttpClient d) {
            this.d = d;
        }

        public java.util.Optional<java.net.CookieHandler> cookieHandler() { return d.cookieHandler(); }
        public java.util.Optional<Duration> connectTimeout() { return d.connectTimeout(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public java.util.Optional<java.net.ProxySelector> proxy() { return d.proxy(); }
        public javax.net.ssl.SSLContext sslContext() { return d.sslContext(); }
        public javax.net.ssl.SSLParameters sslParameters() { return d.sslParameters(); }
        public java.util.Optional<java.net.Authenticator> authenticator() { return d.authenticator(); }
        public Version version() { return d.version(); }
        public java.util.Optional<java.util.concurrent.Executor> executor() { return d.executor(); }
        public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest r,
                java.net.http.HttpResponse.BodyHandler<T> h) throws IOException, InterruptedException {
            return d.send(r, h);
        }
        public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest r,
                java.net.http.HttpResponse.BodyHandler<T> h) {
            return d.sendAsync(r, h);
        }
        public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest r,
                java.net.http.HttpResponse.BodyHandler<T> h, java.net.http.HttpResponse.PushPromiseHandler<T> p) {
            return d.sendAsync(r, h, p);
        }
    }
}
