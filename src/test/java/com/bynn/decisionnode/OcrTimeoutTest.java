package com.bynn.decisionnode;

import static com.bynn.decisionnode.Questions.truth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A decision naming ocr: 30 s per attempt by default, the caller's timeout as given, no retry after a timeout. */
class OcrTimeoutTest {
    private static final String OK = "{\"model\":\"decisionnode-moe-0.9.0-rc1\",\"answers\":{\"q\":{\"type\":\"truth\",\"truth\":0.5}},"
            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}";

    /** What the server does on each attempt, from 1: a delay in ms (then the answer), or a status. */
    private volatile IntFunction<int[]> plan = n -> new int[] {0, 200};
    private final AtomicInteger attempts = new AtomicInteger();
    private HttpServer server;
    private String url;

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
        ex.getRequestBody().readAllBytes();
        int[] step = plan.apply(attempts.incrementAndGet());
        try {
            Thread.sleep(step[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] out = (step[1] == 200 ? OK : "{\"detail\":{\"error_type\":\"overloaded_error\",\"message\":\"busy\"}}")
                .getBytes(StandardCharsets.UTF_8);
        if (step[1] != 200) {
            ex.getResponseHeaders().add("retry-after-ms", "1");
        }
        try {
            ex.sendResponseHeaders(step[1], out.length);
            ex.getResponseBody().write(out);
        } catch (IOException gone) {
            // The client gave up on this attempt.
        }
        ex.close();
    }

    private DecisionNode client(Duration timeout) {
        return DecisionNode.builder().environment(k -> null).apiKey("dn_test").baseUrl(url).timeout(timeout)
                .waiter(ms -> CompletableFuture.completedFuture(null)).build();
    }

    private static DecideRequest request(Object booster) {
        DecideRequest.Builder b = DecideRequest.builder().state("s").question("q", truth("?"))
                .images(List.of(Image.fromUrl("receipt", "https://files.example.com/receipt.jpg")));
        return booster == null ? b.build() : b.boosters(List.of(booster)).build();
    }

    @Test
    void aDecisionNamingOcrIsNotRetriedAfterATimeout() {
        plan = n -> new int[] {700, 200};
        assertThrows(TimeoutError.class, () -> client(Duration.ofMillis(300)).decide(request("ocr")));
        assertEquals(1, attempts.get());
    }

    @Test
    void theObjectFormAndTheHelpersFollowTheSameRule() {
        plan = n -> new int[] {700, 200};
        DecisionNode dn = client(Duration.ofMillis(300));
        Booster ocr = Booster.of("ocr").options(Map.of("prompt", "the invoice total"));
        assertThrows(TimeoutError.class, () -> dn.check("s", "Does the total match?", null, null,
                List.of(Image.fromUrl("receipt", "https://files.example.com/receipt.jpg")), null, null, List.of(ocr)));
        assertEquals(1, attempts.get());
        attempts.set(0);
        assertThrows(TimeoutError.class, () -> dn.decide(request(Map.of("name", "ocr"))));
        assertEquals(1, attempts.get());
    }

    @Test
    void aDecisionWithoutOcrIsStillRetriedAfterATimeout() {
        plan = n -> new int[] {700, 200};
        assertThrows(TimeoutError.class, () -> client(Duration.ofMillis(300)).decide(request("age")));
        assertEquals(3, attempts.get());
    }

    @Test
    void otherRetriesAreKept() {
        plan = n -> n == 1 ? new int[] {0, 529} : new int[] {0, 200};
        assertEquals(0.5, client(Duration.ofSeconds(5)).decide(request("ocr")).truth("q").truth());
        assertEquals(2, attempts.get());
    }

    @Test
    void inDecideManyTheRuleIsPerItem() {
        plan = n -> new int[] {700, 200};
        List<DecisionResult> results = client(Duration.ofMillis(300)).decideMany(List.of(request("ocr")), 1);
        assertTrue(results.get(0).error() instanceof TimeoutError);
        assertEquals(1, attempts.get());
    }

    @Test
    void thirtySecondsOnlyWhenTheCallerSetNoTimeout() {
        DecisionNode defaulted = DecisionNode.builder().environment(k -> null).apiKey("dn_test").baseUrl(url).build();
        assertFalse(defaulted.transport().timeoutSet);
        assertEquals(Duration.ofSeconds(30), defaulted.transport().decisionTimeout(true));
        assertEquals(Duration.ofSeconds(10), defaulted.transport().decisionTimeout(false));
        DecisionNode set = client(Duration.ofSeconds(5));
        assertEquals(Duration.ofSeconds(5), set.transport().decisionTimeout(true));
        assertEquals(Duration.ofSeconds(5), set.transport().decisionTimeout(false));
        assertTrue(Booster.namesOcr(List.of("age", Map.of("name", "ocr"))));
        assertTrue(Booster.namesOcr(List.of("ocr")));
        assertFalse(Booster.namesOcr(List.of("age", Map.of("booster", "ocr"))));
        assertFalse(Booster.namesOcr(null));
    }
}
