package com.bynn.decisionnode;

import static com.bynn.decisionnode.Questions.truth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The image and video shares of the input tokens, read from their headers. */
class TokenSharesTest {
    private static final String IMAGE = "x-decisionnode-image-tokens";
    private static final String VIDEO = "x-decisionnode-video-tokens";
    private static final String BODY = "{\"model\":\"decisionnode-flash\",\"answers\":{\"q\":{\"type\":\"truth\",\"truth\":0.9}},"
            + "\"usage\":{\"input_tokens\":1300,\"output_tokens\":0}}";

    private static Map<String, Object> json(String text) {
        try {
            return Json.asMap(Json.read(text.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Decision decision(String... kv) {
        Map<String, List<String>> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], List.of(kv[i + 1]));
        }
        Map<String, Object> body = json(BODY);
        Decision d = Responses.decision(body, HttpHeaders.of(m, (a, b) -> true));
        assertEquals(1300L, d.usage().inputTokens(), "never changed by the shares");
        assertEquals(body, d.raw(), "raw is the body as sent");
        return d;
    }

    @Test
    void bothHeadersPresent() {
        Usage u = decision(IMAGE, "1024", VIDEO, "256").usage();
        assertEquals(1024L, u.imageTokens());
        assertEquals(256L, u.videoTokens());
    }

    @Test
    void bothHeadersAbsent() {
        Usage u = decision().usage();
        assertNull(u.imageTokens());
        assertNull(u.videoTokens());
    }

    @Test
    void eachHeaderOnItsOwn() {
        Map<String, Long> good = Map.of("512", 512L, "0", 0L, "  77 ", 77L, "2147483647", 2147483647L);
        List<String> bad = List.of("", "   ", "-1", "-0", "+5", "12.0", "1e3", "abc", "12 3", "0x10", "2147483648",
                "99999999999999999999999");
        for (String name : List.of(IMAGE, VIDEO)) {
            for (Map.Entry<String, Long> e : good.entrySet()) {
                assertEquals(e.getValue(), share(name, e.getKey()), name + "=\"" + e.getKey() + "\"");
            }
            for (String value : bad) {
                assertNull(share(name, value), name + "=\"" + value + "\"");
            }
        }
    }

    /** The share one header gives, checking the other share stays null. */
    private static Long share(String name, String value) {
        Usage u = decision(name, value).usage();
        if (name.equals(IMAGE)) {
            assertNull(u.videoTokens(), name + "=\"" + value + "\" set the video share");
            return u.imageTokens();
        }
        assertNull(u.imageTokens(), name + "=\"" + value + "\" set the image share");
        return u.videoTokens();
    }

    @Test
    void neverReadFromTheBody() {
        Decision d = Responses.decision(json("{\"answers\":{},\"usage\":{\"input_tokens\":10,\"image_tokens\":4,\"video_tokens\":3}}"),
                HttpHeaders.of(Map.of(), (a, b) -> true));
        assertNull(d.usage().imageTokens());
        assertNull(d.usage().videoTokens());
    }

    @Test
    void aBatchResultReadsEmpty() {
        BatchResult line = new BatchResult(json("{\"custom_id\":\"a\",\"status\":200,\"body\":" + BODY
                + ",\"headers\":{\"x-request-id\":\"req_1\"}}"));
        assertEquals("req_1", line.decision().requestId());
        assertNull(line.decision().usage().imageTokens());
        assertNull(line.decision().usage().videoTokens());
    }

    @Test
    void decideAndDecideAsyncReadThem() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] out = BODY.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.getResponseHeaders().add(IMAGE, "1024");
            ex.getResponseHeaders().add(VIDEO, "300");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            DecisionNode dn = DecisionNode.builder().environment(k -> null).apiKey("dn_test")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            for (Decision d : List.of(dn.decide("s", Map.of("q", truth("?"))),
                    dn.decideAsync("s", Map.of("q", truth("?"))).get())) {
                assertEquals(1300L, d.usage().inputTokens());
                assertEquals(1024L, d.usage().imageTokens());
                assertEquals(300L, d.usage().videoTokens());
                assertFalse(Json.asMap(d.raw().get("usage")).containsKey("image_tokens"));
            }
        } finally {
            server.stop(0);
        }
    }
}
