package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponsesTest {
    private static HttpHeaders headers(String... kv) {
        java.util.Map<String, List<String>> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], List.of(kv[i + 1]));
        }
        return HttpHeaders.of(m, (a, b) -> true);
    }

    private static DecisionNodeError error(int status, String body, String... kv) {
        return Responses.error(status, headers(kv), body.getBytes(StandardCharsets.UTF_8));
    }

    private static Decision decision(String body, String... kv) throws Exception {
        return Responses.decision(Json.asMap(Json.read(body.getBytes(StandardCharsets.UTF_8))), headers(kv));
    }

    @Test
    void errorClassesByStatus() {
        assertInstanceOf(BadRequestError.class, error(400, "{\"detail\":\"bad\"}"));
        assertEquals(ContextLimitError.class,
                error(400, "{\"detail\":{\"error_type\":\"max_tokens_exceeded\",\"message\":\"too long\"}}").getClass());
        assertEquals(AuthenticationError.class, error(401, "{}").getClass());
        assertEquals(InsufficientCreditError.class, error(402, "{}").getClass());
        assertEquals(PermissionDeniedError.class, error(403, "{\"detail\":{\"error_type\":\"permission_error\"}}").getClass());
        assertEquals(NotFoundError.class, error(404, "{}").getClass());
        assertEquals(ValidationError.class, error(422, "{\"detail\":[]}").getClass());
        assertEquals(RateLimitError.class, error(429, "{}").getClass());
        assertEquals(OverloadedError.class, error(529, "{}").getClass());
        assertEquals(ServerError.class, error(503, "<html>").getClass());
        assertEquals(DecisionNodeError.class, error(408, "").getClass());
        assertEquals(DecisionNodeError.class, error(409, "{}").getClass());
        assertEquals(DecisionNodeError.class, error(302, "{}").getClass());
        assertEquals(PermissionDeniedError.class, error(403, "{}").getClass());
    }

    @Test
    void errorFieldsFromBodyAndHeaders() {
        DecisionNodeError e = error(400, "{\"detail\":{\"error_type\":\"invalid_request_error\",\"message\":\"no\"}}",
                "x-request-id", "req_1", "X-DecisionNode-Error", "question 'q': a score takes up to 10 levels, got 11");
        assertEquals(400, e.status());
        assertEquals("invalid_request_error", e.errorType());
        assertEquals("req_1", e.requestId());
        assertEquals("question 'q': a score takes up to 10 levels, got 11", e.reason());
        assertEquals("no", e.getMessage());
        assertEquals(List.of("req_1"), e.headers().get("x-request-id"));
    }

    @Test
    void stringDetailIsTheMessage() {
        DecisionNodeError e = error(400, "{\"detail\":\"Unknown model\"}");
        assertEquals("Unknown model", e.getMessage());
        assertNull(e.errorType());
    }

    @Test
    void refusal() {
        RefusalError e = (RefusalError) error(403,
                "{\"detail\":{\"error_type\":\"refusal_error\",\"message\":\"refused\",\"category\":\"self_harm\"}}",
                "x-decisionnode-safety", "harm=0.01, self_harm=0.97;blocked");
        assertEquals("self_harm", e.category());
        assertEquals(List.of("self_harm"), e.safety().flagged());
    }

    @Test
    void validationEntries() {
        ValidationError e = (ValidationError) error(422, "{\"detail\":[{\"type\":\"missing\",\"msg\":\"Field required\"}]}");
        assertEquals(List.of(Map.of("type", "missing", "msg", "Field required")), e.entries());
    }

    @Test
    void retryAfterMsWinsOverRetryAfter() {
        assertEquals(0.2, ((OverloadedError) error(529, "{}", "retry-after", "1", "retry-after-ms", "200")).retryAfter());
        assertEquals(3.0, ((RateLimitError) error(429, "{}", "Retry-After", "3")).retryAfter());
        assertNull(((RateLimitError) error(429, "{}")).retryAfter());
        assertEquals(60.0, ((RateLimitError) error(429, "{}", "retry-after-ms", "600000")).retryAfter());
        assertEquals(60.0, ((RateLimitError) error(429, "{}", "Retry-After", "86400")).retryAfter());
        String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(30));
        double wait = ((RateLimitError) error(429, "{}", "Retry-After", date)).retryAfter();
        assertTrue(wait > 25 && wait <= 30, String.valueOf(wait));
        String late = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusHours(2));
        assertEquals(60.0, ((RateLimitError) error(429, "{}", "Retry-After", late)).retryAfter());
    }

    @Test
    void answersKeepNumbersAsSent() throws Exception {
        Decision d = decision("{\"model\":\"decisionnode-moe-0.9.0-rc1\",\"answers\":{"
                + "\"c\":{\"type\":\"choice\",\"choice\":\"\",\"confidence\":0.64,\"probabilities\":{\"\":0.8200000000000001,\"b\":0.18}},"
                + "\"s\":{\"type\":\"score\",\"score\":1.7,\"confidence\":0.45,\"legend\":{\"0\":\"low\",\"1\":{\"what\":\"high\"}},"
                + "\"probabilities\":{\"0\":0.060000000000000005,\"1\":0.94}},"
                + "\"t\":{\"type\":\"truth\",\"truth\":1},"
                + "\"n\":{\"type\":\"ranking\",\"order\":[\"b\",\"a\"]}},"
                + "\"usage\":{\"input_tokens\":18,\"output_tokens\":0}}",
                "x-request-id", "req_9", "x-decisionnode-latency-ms", "23");
        assertEquals("", d.choice("c").choice());
        assertEquals(0.8200000000000001, d.choice("c").probabilities().get(""));
        assertEquals(0.060000000000000005, d.score("s").probabilities().get(0));
        assertEquals(Map.of("what", "high"), d.score("s").legend().get(1));
        assertEquals(1.0, d.truth("t").truth());
        UnknownAnswer u = (UnknownAnswer) d.get("n");
        assertEquals("ranking", u.type());
        assertEquals(List.of("b", "a"), u.raw().get("order"));
        assertEquals(18L, d.usage().inputTokens());
        assertEquals("req_9", d.requestId());
        assertEquals(23.0, d.latencyMs());
        assertNull(d.safety());
        assertEquals("decisionnode-moe-0.9.0-rc1", d.model());
    }

    @Test
    void modelsSplitLimits() throws Exception {
        List<ModelInfo> models = Responses.models(Json.asMap(Json.read(
                "{\"models\":[{\"name\":\"decisionnode-moe\",\"description\":\"d\",\"release_date\":\"2026-10-01\",\"max_images\":4}]}"
                        .getBytes(StandardCharsets.UTF_8))));
        assertEquals("decisionnode-moe", models.get(0).name());
        assertEquals("2026-10-01", models.get(0).releaseDate());
        assertEquals(Map.of("max_images", 4), models.get(0).limits());
    }

    @Test
    void modelsCarryTheirPricesAsSentAndInLimits() throws Exception {
        List<ModelInfo> models = Responses.models(Json.asMap(Json.read((
                "{\"models\":[{\"name\":\"decisionnode-moe\",\"description\":\"d\",\"release_date\":\"2026-10-01\","
                + "\"price_per_million_input\":0.042,\"price_per_million_output\":0,\"price_per_million_image\":0.084,"
                + "\"price_per_million_video\":0.0420000000000001,\"batch_price_factor\":0.7},"
                + "{\"name\":\"decisionnode-flash@acme/fraud:3\",\"description\":\"f\",\"release_date\":\"2026-10-09\","
                + "\"price_per_million_input\":\"0.06\",\"batch_price_factor\":null}]}").getBytes(StandardCharsets.UTF_8))));
        ModelInfo moe = models.get(0);
        assertEquals(0.042, moe.pricePerMillionInput());
        assertEquals(0.0, moe.pricePerMillionOutput());
        assertEquals(0.084, moe.pricePerMillionImage());
        assertEquals(0.0420000000000001, moe.pricePerMillionVideo());
        assertEquals(0.7, moe.batchPriceFactor());
        assertEquals(0.042, ((Number) moe.limits().get("price_per_million_input")).doubleValue());
        assertTrue(moe.limits().containsKey("batch_price_factor"));
        ModelInfo tuned = models.get(1);
        assertNull(tuned.pricePerMillionInput(), "a price that is not a number is null");
        assertNull(tuned.pricePerMillionOutput());
        assertNull(tuned.batchPriceFactor());
        assertEquals("0.06", tuned.limits().get("price_per_million_input"));
        ModelInfo old = new ModelInfo("decisionnode-moe", "d", "2026-10-01", Map.of(), Map.of());
        assertNull(old.pricePerMillionInput());
    }

    @Test
    void malformedKnownAnswersAreUnknown() throws Exception {
        Decision d = decision("{\"model\":\"decisionnode-moe-0.9.0-rc1\",\"answers\":{"
                + "\"t1\":{\"type\":\"truth\"},"
                + "\"t2\":{\"type\":\"truth\",\"truth\":\"0.9\"},"
                + "\"c1\":{\"type\":\"choice\",\"choice\":null,\"confidence\":0.5,\"probabilities\":{\"a\":1}},"
                + "\"c2\":{\"type\":\"choice\",\"choice\":\"a\",\"confidence\":0.5,\"probabilities\":{\"a\":\"x\"}},"
                + "\"c3\":{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{\"a\":1}},"
                + "\"s1\":{\"type\":\"score\",\"score\":1,\"confidence\":0.5,\"probabilities\":{\"0\":1}},"
                + "\"s2\":{\"type\":\"score\",\"score\":1,\"confidence\":0.5,\"legend\":{\"0\":\"a\"},\"probabilities\":{\"0\":null}},"
                + "\"s3\":{\"type\":\"score\",\"score\":1,\"confidence\":0.5,\"legend\":[\"a\"],\"probabilities\":{\"0\":1}},"
                + "\"x\":7}}");
        for (String id : List.of("t1", "t2", "c1", "c2", "c3", "s1", "s2", "s3", "x")) {
            assertTrue(d.get(id) instanceof UnknownAnswer, id + " is " + d.get(id));
        }
        assertEquals("score", d.get("s3").type());
        assertEquals("0.9", d.get("t2").raw().get("truth"));
    }
}
