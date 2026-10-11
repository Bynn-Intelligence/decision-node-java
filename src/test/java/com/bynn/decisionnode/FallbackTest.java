package com.bynn.decisionnode;

import static com.bynn.decisionnode.Questions.truth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The fallback option on requests and the fallback headers on decisions. */
class FallbackTest {
    private static final String OK = "{\"model\":\"decisionnode-moe-0.9.0-rc1\",\"answers\":{\"q\":{\"type\":\"truth\",\"truth\":0.5}},"
            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}";

    record Reply(int status, Map<String, String> headers, String body) {}

    private HttpServer server;
    private String url;
    private final ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();
    private volatile Function<String, Reply> handler = s -> new Reply(200, Map.of(), OK);

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
        bodies.add(body);
        Reply r = handler.apply(body);
        r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(r.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            ex.getResponseBody().write(out);
        }
        ex.close();
    }

    private DecisionNode client() {
        return DecisionNode.builder().environment(k -> null).apiKey("dn_test").baseUrl(url).waiter(ms -> java.util.concurrent.CompletableFuture.completedFuture(null)).build();
    }

    private Map<String, Object> lastBody() throws IOException {
        List<String> all = new ArrayList<>(bodies);
        return Json.asMap(Json.read(all.get(all.size() - 1).getBytes(StandardCharsets.UTF_8)));
    }

    private static Decision decision(String body, String... kv) throws IOException {
        Map<String, List<String>> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], List.of(kv[i + 1]));
        }
        return Responses.decision(Json.asMap(Json.read(body.getBytes(StandardCharsets.UTF_8))), HttpHeaders.of(m, (a, b) -> true));
    }

    @Test
    void fallbackIsSentOnlyWhenGiven() throws IOException {
        DecisionNode dn = client();
        dn.decide(DecideRequest.builder().state("s").question("q", truth("?")).build());
        assertFalse(lastBody().containsKey("fallback"));
        dn.decide(DecideRequest.builder().state("s").question("q", truth("?")).fallback(false).build());
        assertEquals(false, lastBody().get("fallback"));
        dn.decide("s", Map.of("q", truth("?")), null, null, "decisionnode-moe", null, true);
        assertEquals(true, lastBody().get("fallback"));
        dn.decideMany(List.of(DecideRequest.builder().state("s").question("q", truth("?")).fallback(false).build()));
        assertEquals(false, lastBody().get("fallback"));
        assertEquals(false, BatchRequest.of("a", DecideRequest.builder().state("s").question("q", truth("?"))
                .fallback(false).build()).fallback());
    }

    @Test
    void helpersTakeFallback() throws IOException {
        handler = s -> new Reply(200, Map.of(), "{\"answers\":{\"check\":{\"type\":\"truth\",\"truth\":0.9}}}");
        client().check("s", "Refund?", null, null, null, null, "decisionnode-moe", null, false);
        Map<String, Object> body = lastBody();
        assertEquals(false, body.get("fallback"));
        assertEquals("decisionnode-moe", body.get("model"));
        client().check("s", "Refund?", null, null, null, null, null, null);
        assertFalse(lastBody().containsKey("fallback"));
        handler = s -> new Reply(200, Map.of(), OK);
        client().gate("deploy", null, null, null, null, null, false);
        assertEquals(false, lastBody().get("fallback"));
    }

    @Test
    void aFallbackAnswerNamesBothModels() throws IOException {
        Decision d = decision(OK.replace("decisionnode-moe-0.9.0-rc1", "decisionnode-flash-0.9.0-rc1"),
                "x-decisionnode-fallback", "decisionnode-flash-0.9.0-rc1",
                "x-decisionnode-requested-model", "decisionnode-moe");
        assertEquals(new Fallback("decisionnode-flash-0.9.0-rc1", "decisionnode-moe"), d.fallback());
        assertEquals("decisionnode-flash-0.9.0-rc1", d.model());
        assertNull(decision(OK).fallback());
        assertEquals(new Fallback("decisionnode-flash-0.9.0-rc1", null),
                decision(OK, "x-decisionnode-fallback", "decisionnode-flash-0.9.0-rc1").fallback());
    }

    @Test
    void anOptedOutRequestsOverloadIsRetriedAsEvery529() {
        handler = s -> new Reply(529, Map.of("retry-after-ms", "1"),
                "{\"detail\":{\"error_type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");
        OverloadedError e = assertThrows(OverloadedError.class,
                () -> client().decide(DecideRequest.builder().state("s").question("q", truth("?")).fallback(false).build()));
        assertEquals(529, e.status());
        assertEquals(3, bodies.size());
    }
}
