package com.bynn.decisionnode;

import static com.bynn.decisionnode.Questions.choice;
import static com.bynn.decisionnode.Questions.number;
import static com.bynn.decisionnode.Questions.score;
import static com.bynn.decisionnode.Questions.truth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The API's fixed rules, checked before a request is sent, with the API's own errors. */
class LocalChecksTest {
    private static void questions(Map<String, ?> qs) {
        LocalChecks.request(Map.of("questions", qs), LocalChecks.Profile.DECIDE, Map.of());
    }

    @Test
    void validQuestionsPass() {
        questions(Map.of(
                "a", truth("?"),
                "b", choice("?", "x", "y"),
                "c", score("?", "low", "high"),
                "d", number("How many?", 0, 50),
                "e", number("Rating?", 0, 5, 0.5),
                "f", number("Largest?", 0, 4095)));
    }

    @Test
    void choiceAndScoreLimits() {
        BadRequestError none = assertThrows(BadRequestError.class, () -> questions(Map.of("q", choice("?", List.of()))));
        assertEquals("Choice question must have at least one choice: q", none.getMessage());
        assertEquals("question 'q': a choice needs at least one option", none.reason());
        assertNull(none.status());
        Map<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i < 256; i++) {
            many.put("o" + i, null);
        }
        assertThrows(BadRequestError.class, () -> questions(Map.of("q", choice("?", many))));
        assertThrows(BadRequestError.class, () -> questions(Map.of("q", score("?", Collections.nCopies(11, "l")))));
        ValidationError empty = assertThrows(ValidationError.class, () -> questions(Map.of("q", score("?", List.of()))));
        assertEquals("too_short", ((Map<?, ?>) empty.entries().get(0)).get("type"));
        assertThrows(BadRequestError.class, () -> questions(Map.of("q", truth(null))));
    }

    @Test
    void numberGridFaultsAreTheApis422() {
        ValidationError above = assertThrows(ValidationError.class,
                () -> questions(Map.of("cars", number("How many?", 60, 50))));
        assertEquals(Map.of("type", "less_than_equal", "loc", List.of("body", "questions", "cars", "number", "min"),
                "msg", "Input should be less than or equal to 50", "input", 60, "ctx", Map.of("le", 50)), above.entries().get(0));
        assertEquals("", above.getMessage());

        ValidationError tooLong = assertThrows(ValidationError.class,
                () -> questions(Map.of("p", number("Percent?", 0, 1000, 0.1))));
        assertEquals("too_long", ((Map<?, ?>) tooLong.entries().get(0)).get("type"));

        assertThrows(ValidationError.class, () -> questions(Map.of("n", number("?", 0, 10, 0))));
        assertThrows(ValidationError.class, () -> questions(Map.of("n", number("?", 0.0000001, 1, 1))));
        assertThrows(ValidationError.class, () -> questions(Map.of("n", number("?", null, Double.NaN, null))));
        // 0 to 0.3 at 0.1 is 4 values in exact decimals, not a float sum.
        questions(Map.of("n", number("?", 0, 0.3, 0.1)));
        questions(Map.of("n", number("?", new BigDecimal("0.25"), new BigDecimal("2"), new BigDecimal("0.5"))));
        BadRequestError noText = assertThrows(BadRequestError.class, () -> questions(Map.of("cars", number(null, 0, 5))));
        assertEquals("Number question must have instructions: cars", noText.getMessage());
    }

    @Test
    void nestingAndTypes() {
        Object deep = "s";
        for (int i = 0; i < 128; i++) {
            deep = List.of(deep);
        }
        Object tooDeep = deep;
        BadRequestError nested = assertThrows(BadRequestError.class,
                () -> LocalChecks.request(Map.of("state", tooDeep, "questions", Map.of("q", truth("?"))), LocalChecks.Profile.DECIDE, Map.of()));
        assertEquals("Invalid request.", nested.getMessage());
        assertEquals("api_usage_error", nested.errorType());
        BadRequestError unknown = assertThrows(BadRequestError.class, () -> questions(Map.of("q", Map.of("type", "bool"))));
        assertEquals("Invalid request.", unknown.getMessage());
        assertThrows(BadRequestError.class, () -> LocalChecks.request(Map.of("questions", Map.of("n", number("?"))),
                LocalChecks.Profile.SYSTEMONE, Map.of()));
        ValidationError state = assertThrows(ValidationError.class,
                () -> LocalChecks.request(Map.of("state", 42, "questions", Map.of("q", truth("?"))), LocalChecks.Profile.DECIDE, Map.of()));
        assertEquals(3, state.entries().size());
    }

    @Test
    void every422ComesBeforeAny400() {
        Map<String, Object> qs = new LinkedHashMap<>();
        qs.put("a", choice("?", List.of()));
        qs.put("b", number("?", 9, 1));
        assertThrows(ValidationError.class, () -> questions(qs));
    }
}
