package com.bynn.decisionnode;

import static com.bynn.decisionnode.Questions.truth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The API's 25 MiB body limit, checked on the encoded body before sending, and its 413. */
class BodyLimitTest {
    private static final String OK = "{\"model\":\"decisionnode-moe-0.9.0-rc1\",\"answers\":{\"q\":{\"type\":\"truth\",\"truth\":0.5}},"
            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}";
    private static final Map<String, Object> QUESTIONS = Map.of("q", truth("?"));

    private HttpServer server;
    private String url;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger lastLength = new AtomicInteger();
    private volatile int status = 200;
    private volatile String reply = OK;

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
        requests.incrementAndGet();
        lastLength.set(ex.getRequestBody().readAllBytes().length);
        byte[] out = reply.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("x-request-id", "req_1");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private DecisionNode client() {
        return DecisionNode.builder().environment(k -> null).apiKey("dn_test").baseUrl(url)
                .waiter(ms -> CompletableFuture.completedFuture(null)).build();
    }

    /** A state that makes the body of a one-question request exactly {@code bytes} long. */
    private static String stateFor(int bytes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", DecisionNode.DEFAULT_MODEL);
        body.put("state", "");
        body.put("questions", QUESTIONS);
        return "a".repeat(bytes - Json.write(body).length);
    }

    private static void assertTooLarge(RequestTooLargeError e) {
        assertNull(e.status());
        assertNull(e.requestId());
        assertEquals("request_too_large_error", e.errorType());
        assertEquals("The request body is over 26,214,400 bytes (25 MiB). Send large images as uploads "
                + "(POST /v1/uploads) and name them by upload_id.", e.getMessage());
        assertEquals("the body is 26214401 bytes; the limit is 26214400", e.reason());
    }

    @Test
    void aBodyAtTheLimitIsSentAndOneByteOverIsNot() {
        DecisionNode dn = client();
        assertEquals(26_214_400, DecisionNode.MAX_BODY_BYTES);
        dn.decide(stateFor(DecisionNode.MAX_BODY_BYTES), QUESTIONS);
        assertEquals(1, requests.get());
        assertEquals(DecisionNode.MAX_BODY_BYTES, lastLength.get());

        String over = stateFor(DecisionNode.MAX_BODY_BYTES + 1);
        assertTooLarge(assertThrows(RequestTooLargeError.class, () -> dn.decide(over, QUESTIONS)));
        assertTooLarge(assertThrows(RequestTooLargeError.class,
                () -> dn.checkRequest(DecideRequest.of(over, QUESTIONS))));
        CompletableFuture<Decision> async = dn.decideAsync(over, QUESTIONS);
        assertInstanceOf(RequestTooLargeError.class, assertThrows(Exception.class, async::join).getCause());
        assertEquals(1, requests.get());
    }

    @Test
    void decideManyFailsWholeBeforeSendingAny() {
        DecisionNode dn = client();
        List<DecideRequest> many = List.of(
                DecideRequest.of("small", QUESTIONS), DecideRequest.of(stateFor(DecisionNode.MAX_BODY_BYTES + 1), QUESTIONS));
        assertTooLarge(assertThrows(RequestTooLargeError.class, () -> dn.decideMany(many)));
        assertEquals(0, requests.get());
    }

    @Test
    void aBatchLineIsCheckedAsItWouldBeSent() {
        DecisionNode dn = client();
        // The line carries its custom_id beside the decision's body.
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("custom_id", "big");
        line.put("model", DecisionNode.DEFAULT_MODEL);
        line.put("state", "");
        line.put("questions", QUESTIONS);
        String over = "a".repeat(DecisionNode.MAX_BODY_BYTES + 1 - Json.write(line).length);
        List<BatchRequest> big = List.of(BatchRequest.of("big", DecideRequest.of(over, QUESTIONS)));
        assertTooLarge(assertThrows(RequestTooLargeError.class, () -> dn.batches().checkCreateRequest(big)));
        assertTooLarge(assertThrows(RequestTooLargeError.class, () -> dn.batches().create(big)));
        assertEquals(0, requests.get());
    }

    @Test
    void imagesThatWouldBeUploadedCountByTheirUploadId() throws IOException {
        DecisionNode dn = client();
        byte[] jpeg;
        try (InputStream in = getClass().getResourceAsStream("/images/baseline.jpg")) {
            jpeg = Arrays.copyOf(in.readAllBytes(), 9_900_000);
        }
        // Three 9.9 MB images would be about 40 MB as base64; above the inline threshold they are
        // uploaded, so the body names them by upload id and stays far under the limit.
        DecideRequest request = DecideRequest.builder().state("s").questions(QUESTIONS)
                .image(Image.fromBytes("a", jpeg)).image(Image.fromBytes("b", jpeg)).image(Image.fromBytes("c", jpeg))
                .build();
        dn.checkRequest(request);
        assertEquals(0, requests.get());

        // Base64 given in a wire-form map is sent as given, so it counts: two such images of
        // 9.9 MB are about 26.4 MB of base64.
        String data = java.util.Base64.getEncoder().encodeToString(jpeg);
        List<Object> raw = List.of(
                Map.of("id", "x", "media_type", "image/jpeg", "data", data),
                Map.of("id", "y", "media_type", "image/jpeg", "data", data));
        DecideRequest inline = DecideRequest.builder().state("s").questions(QUESTIONS).images(raw).build();
        RequestTooLargeError e = assertThrows(RequestTooLargeError.class, () -> dn.checkRequest(inline));
        assertTrue(e.reason().startsWith("the body is "));
    }

    @Test
    void the413IsARequestTooLargeErrorAndIsNotRetried() {
        status = 413;
        reply = "{\"detail\":{\"error_type\":\"request_too_large_error\",\"message\":\"The request body is over 26,214,400 bytes (25 MiB).\"}}";
        RequestTooLargeError e = assertThrows(RequestTooLargeError.class, () -> client().decide("s", QUESTIONS));
        assertEquals(413, e.status());
        assertEquals("request_too_large_error", e.errorType());
        assertEquals("req_1", e.requestId());
        assertInstanceOf(DecisionNodeError.class, e);
        assertEquals(1, requests.get());
    }
}
