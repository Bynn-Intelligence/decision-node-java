package com.bynn.decisionnode;

import static com.bynn.decisionnode.Questions.choice;
import static com.bynn.decisionnode.Questions.options;
import static com.bynn.decisionnode.Questions.score;
import static com.bynn.decisionnode.Questions.truth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionsTest {
    private static String json(Object o) {
        return new String(Json.write(o), StandardCharsets.UTF_8);
    }

    @Test
    void truthWithoutCriteria() {
        assertEquals("{\"type\":\"truth\",\"instructions\":\"Escalate?\"}", json(truth("Escalate?")));
    }

    @Test
    void truthWithOneSide() {
        assertEquals("{\"type\":\"truth\",\"instructions\":\"Refund?\",\"criteria\":{\"false\":\"anything else\"}}",
                json(truth("Refund?", null, "anything else")));
    }

    @Test
    void truthCriteriaOnly() {
        assertEquals("{\"type\":\"truth\",\"criteria\":{\"true\":\"yes\",\"false\":\"no\"}}", json(truth(null, "yes", "no")));
    }

    @Test
    void choiceFromNamesHasNullDescriptions() {
        assertEquals("{\"type\":\"choice\",\"instructions\":\"Route?\",\"criteria\":{\"billing\":null,\"bug\":null}}",
                json(choice("Route?", "billing", "bug")));
        assertEquals(json(choice("Route?", "billing", "bug")), json(choice("Route?", List.of("billing", "bug"))));
    }

    @Test
    void choiceKeepsOptionOrderAndNamesVerbatim() {
        Question q = choice("Pick", options(" padded ", "a", "", "empty", "01", Map.of("k", "v")));
        assertEquals("{\"type\":\"choice\",\"instructions\":\"Pick\",\"criteria\":{\" padded \":\"a\",\"\":\"empty\",\"01\":{\"k\":\"v\"}}}", json(q));
    }

    @Test
    void optionsNeedPairs() {
        assertThrows(IllegalArgumentException.class, () -> options("a"));
        assertThrows(IllegalArgumentException.class, () -> options(1, "a"));
    }

    @Test
    void scoreLevelsLowToHigh() {
        assertEquals("{\"type\":\"score\",\"instructions\":\"How urgent?\",\"criteria\":[\"low\",\"high\"]}",
                json(score("How urgent?", "low", "high")));
        assertEquals(json(score("How urgent?", "low", "high")), json(score("How urgent?", List.of("low", "high"))));
    }

    @Test
    void scoreLevelsMayBeStructured() {
        Question q = score(Map.of("question", "Risk?"), Arrays.asList(Map.of("what", "none"), List.of("high", "risk"), "critical"));
        assertEquals("{\"type\":\"score\",\"instructions\":{\"question\":\"Risk?\"},\"criteria\":[{\"what\":\"none\"},[\"high\",\"risk\"],\"critical\"]}", json(q));
        assertEquals("score", q.type());
    }
}
