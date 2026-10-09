package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Error bodies that are not JSON, and image ids of at most 64 characters. */
class NonJsonErrorsTest {
    private static final String PAGE = "<html><body><h1>413 Request Entity Too Large</h1></body></html>";

    private static DecisionNodeError error(int status, String body, String... kv) {
        Map<String, List<String>> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], List.of(kv[i + 1]));
        }
        return Responses.error(status, HttpHeaders.of(m, (a, b) -> true), body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void classifiedByStatusAloneWithTheFixedStatusText() {
        DecisionNodeError e = error(413, PAGE);
        assertEquals(RequestTooLargeError.class, e.getClass());
        assertEquals("HTTP 413 Content Too Large", e.getMessage());
        assertEquals(413, e.status());
        assertNull(e.errorType());
        assertNull(e.requestId());
        assertNull(e.reason());
        assertNull(e.body());

        DecisionNodeError timeout = error(408, "<html>408</html>", "x-request-id", "req_edge_0408");
        assertEquals(DecisionNodeError.class, timeout.getClass());
        assertEquals("HTTP 408 Request Timeout (request id req_edge_0408)", timeout.getMessage());
        assertEquals("req_edge_0408", timeout.requestId());

        assertEquals(BadRequestError.class, error(400, "max_tokens_exceeded").getClass());
        assertEquals(PermissionDeniedError.class, error(403, "refusal_error").getClass());
        assertEquals(AuthenticationError.class, error(401, "").getClass());
        assertEquals(InsufficientCreditError.class, error(402, "").getClass());
        assertEquals(NotFoundError.class, error(404, "Not here").getClass());
        ValidationError invalid = (ValidationError) error(422, "nope");
        assertEquals(List.of(), invalid.entries());
        assertEquals("HTTP 422 Unprocessable Content", invalid.getMessage());
        RateLimitError limited = (RateLimitError) error(429, "slow down", "retry-after-ms", "1500");
        assertEquals(RateLimitError.class, limited.getClass());
        assertEquals(1.5, limited.retryAfter());
        OverloadedError busy = (OverloadedError) error(529, "", "retry-after", "2");
        assertEquals("HTTP 529 Overloaded", busy.getMessage());
        assertEquals(2.0, busy.retryAfter());
        assertEquals(ServerError.class, error(502, "<html>Bad Gateway</html>").getClass());
        assertEquals("HTTP 504 Gateway Timeout", error(504, "").getMessage());
    }

    @Test
    void aStatusOutsideTheTableIsWrittenAsItsNumber() {
        assertEquals("HTTP 418", error(418, "teapot").getMessage());
        assertEquals("HTTP 507 (request id req_1)", error(507, "", "x-request-id", "req_1").getMessage());
        assertEquals("HTTP 302", error(302, "<a href=x>moved</a>").getMessage());
    }

    @Test
    void theReasonHeaderIsKeptAndTheBodyNeverRead() {
        DecisionNodeError e = error(400, "secret page text", "x-decisionnode-error", "the reason");
        assertEquals("the reason", e.reason());
        assertTrue(!e.getMessage().contains("secret") && !e.toString().contains("secret"));
    }

    @Test
    void aJsonBodyKeepsTheEnvelopeRules() {
        assertEquals("", error(400, "{}").getMessage());
        assertEquals("", error(400, "{\"detail\":{\"error_type\":\"api_usage_error\"}}").getMessage());
        assertEquals(ContextLimitError.class,
                error(400, "{\"detail\":{\"error_type\":\"max_tokens_exceeded\"}}").getClass());
    }

    private static String repeat(int codePoint, int times) {
        return new String(Character.toChars(codePoint)).repeat(times);
    }

    private static void images(List<?> images) {
        LocalChecks.images(images, Map.of());
    }

    @Test
    void imageIdsAreCountedInCodePoints() {
        images(List.of(Image.fromUrl("a".repeat(64), "https://example.com/a.jpg")));
        // 64 characters outside the Basic Multilingual Plane: 128 UTF-16 units, sent.
        images(List.of(Image.fromUrl(repeat(0x20000, 64), "https://example.com/a.jpg")));
        BadRequestError tooLong = assertThrows(BadRequestError.class,
                () -> images(List.of(Image.fromUrl(repeat(0x20000, 65), "https://example.com/a.jpg"))));
        assertEquals("Image 0: its id is longer than 64 characters.", tooLong.getMessage());
        assertEquals("api_usage_error", tooLong.errorType());
        assertNull(tooLong.status());
        assertNull(tooLong.requestId());
    }

    @Test
    void theLengthCheckComesAfterTheEmptyIdAndBeforeTheDuplicate() {
        String long65 = "x".repeat(65);
        List<Object> list = new ArrayList<>();
        list.add(Image.fromUrl("first", "https://example.com/a.jpg"));
        list.add(Image.fromUrl(long65, "https://example.com/b.jpg"));
        assertEquals("Image 1: its id is longer than 64 characters.",
                assertThrows(BadRequestError.class, () -> images(list)).getMessage());

        List<Object> dup = List.of(Map.of("id", long65, "url", "https://example.com/a.jpg"),
                Map.of("id", long65, "url", "https://example.com/b.jpg"));
        assertEquals("Image 0: its id is longer than 64 characters.",
                assertThrows(BadRequestError.class, () -> images(dup)).getMessage());

        List<Object> empty = List.of(Map.of("id", "", "url", "https://example.com/a.jpg"));
        assertEquals("Image 0 needs an id.", assertThrows(BadRequestError.class, () -> images(empty)).getMessage());
    }
}
