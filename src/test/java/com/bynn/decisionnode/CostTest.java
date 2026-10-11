package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** usage.cost on decisions, batch result lines and session replies, read as sent. */
class CostTest {
    private static Map<String, Object> json(String text) throws Exception {
        return Json.asMap(Json.read(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Decision decision(String usage) throws Exception {
        return Responses.decision(json("{\"model\":\"decisionnode-moe-0.9.0-rc1\",\"answers\":{},\"usage\":" + usage + "}"),
                HttpHeaders.of(Map.of(), (a, b) -> true));
    }

    @Test
    void aBoostedAnswerKeepsTheBookedAmountAsSent() throws Exception {
        Cost c = decision("{\"input_tokens\":341,\"output_tokens\":0,\"booster_tokens\":72,\"boosters\":3,"
                + "\"cost\":{\"usd\":0.036014322,\"model_usd\":0.000014322,\"boosters_usd\":0.036,\"free_tier\":false}}")
                .usage().cost();
        assertEquals(0.036014322, c.usd());
        assertEquals(0.000014322, c.modelUsd());
        assertEquals(0.036, c.boostersUsd());
        assertFalse(c.freeTier());
        // The parts summed as doubles are not the booked amount, so usd is never computed.
        assertNotEquals(c.modelUsd() + c.boostersUsd(), c.usd());
        assertEquals(0.036014322, ((Number) c.raw().get("usd")).doubleValue());
    }

    @Test
    void theFreeTierAndWholeZeros() throws Exception {
        Cost c = decision("{\"input_tokens\":62,\"output_tokens\":0,"
                + "\"cost\":{\"usd\":0,\"model_usd\":0,\"boosters_usd\":0,\"free_tier\":true}}").usage().cost();
        assertTrue(c.freeTier());
        assertEquals(0.0, c.usd());
        assertEquals(0.0, c.modelUsd());
        assertEquals(0.0, c.boostersUsd());
    }

    @Test
    void anAbsentCostIsNullNeverZeros() throws Exception {
        assertNull(decision("{\"input_tokens\":62,\"output_tokens\":0}").usage().cost());
        assertNull(decision("{\"input_tokens\":62,\"cost\":\"0.01\"}").usage().cost());
        Cost odd = decision("{\"cost\":{\"usd\":\"0.01\",\"free_tier\":\"no\"}}").usage().cost();
        assertNull(odd.usd());
        assertNull(odd.modelUsd());
        assertNull(odd.freeTier());
    }

    @Test
    void batchLinesAndSessionRepliesCarryTheirOwnCost() throws Exception {
        BatchResult line = new BatchResult(json("{\"custom_id\":\"t1\",\"status\":200,\"body\":{\"model\":\"decisionnode-moe-0.9.0-rc1\","
                + "\"answers\":{},\"usage\":{\"input_tokens\":62,\"output_tokens\":0,"
                + "\"cost\":{\"usd\":1.302e-06,\"model_usd\":1.302e-06,\"boosters_usd\":0,\"free_tier\":false}}}}"));
        assertEquals(1.302e-06, line.decision().usage().cost().usd());
        DecisionNode dn = DecisionNode.builder().environment(k -> null).apiKey("dn_test").baseUrl("http://127.0.0.1:1").build();
        Session session = new Session(dn, json("{\"session_id\":\"ses_000000000000000000000000\","
                + "\"stream_url\":\"ws://127.0.0.1:1/v1/sessions/ses_000000000000000000000000/stream\",\"questions\":[\"q\"],"
                + "\"usage\":{\"input_tokens\":40,\"output_tokens\":0,"
                + "\"cost\":{\"usd\":0.00000168,\"model_usd\":0.00000168,\"boosters_usd\":0,\"free_tier\":false}}}"));
        assertEquals(0.00000168, session.usage().cost().usd());
    }
}
