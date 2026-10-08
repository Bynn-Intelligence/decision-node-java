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
    @Test
    void validQuestionsPass() {
        LocalChecks.questions(Map.of(
                "a", truth("?"),
                "b", choice("?", "x", "y"),
                "c", score("?", "low", "high"),
                "d", number("How many?", 0, 50),
                "e", number("Rating?", 0, 5, 0.5),
                "f", number("Largest?", 0, 4095)));
    }

    @Test
    void choiceAndScoreLimits() {
        BadRequestError none = assertThrows(BadRequestError.class, () -> LocalChecks.questions(Map.of("q", choice("?", List.of()))));
        assertEquals("Choice question must have at least one choice: q", none.getMessage());
        assertEquals("question 'q': a choice needs at least one option", none.reason());
        assertNull(none.status());
        Map<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i < 256; i++) {
            many.put("o" + i, null);
        }
        assertThrows(BadRequestError.class, () -> LocalChecks.questions(Map.of("q", choice("?", many))));
        assertThrows(BadRequestError.class, () -> LocalChecks.questions(Map.of("q", score("?", Collections.nCopies(11, "l")))));
        ValidationError empty = assertThrows(ValidationError.class, () -> LocalChecks.questions(Map.of("q", score("?", List.of()))));
        assertEquals("too_short", ((Map<?, ?>) empty.entries().get(0)).get("type"));
        assertThrows(BadRequestError.class, () -> LocalChecks.questions(Map.of("q", truth(null))));
    }

    @Test
    void numberGridFaultsAreTheApis422() {
        ValidationError above = assertThrows(ValidationError.class,
                () -> LocalChecks.questions(Map.of("cars", number("How many?", 60, 50))));
        assertEquals("question 'cars': min 60 is above max 50", above.reason());
        assertEquals(Map.of("type", "less_than_equal", "loc", List.of("body", "questions", "cars", "number", "min"),
                "msg", "Input should be less than or equal to 50", "input", 60, "ctx", Map.of("le", 50)), above.entries().get(0));
        assertEquals("", above.getMessage());

        ValidationError tooLong = assertThrows(ValidationError.class,
                () -> LocalChecks.questions(Map.of("p", number("Percent?", 0, 1000, 0.1))));
        assertEquals("question 'p': the grid holds 10001 values, the limit is 4096; narrow the range or widen the step", tooLong.reason());

        assertThrows(ValidationError.class, () -> LocalChecks.questions(Map.of("n", number("?", 0, 10, 0))));
        assertThrows(ValidationError.class, () -> LocalChecks.questions(Map.of("n", number("?", 0.0000001, 1, 1))));
        assertThrows(ValidationError.class, () -> LocalChecks.questions(Map.of("n", number("?", null, Double.NaN, null))));
        // 0 to 0.3 at 0.1 is 4 values in exact decimals, not a float sum.
        LocalChecks.questions(Map.of("n", number("?", 0, 0.3, 0.1)));
        LocalChecks.questions(Map.of("n", number("?", new BigDecimal("0.25"), new BigDecimal("2"), new BigDecimal("0.5"))));
        BadRequestError noText = assertThrows(BadRequestError.class, () -> LocalChecks.questions(Map.of("cars", number(null, 0, 5))));
        assertEquals("Number question must have instructions: cars", noText.getMessage());
    }

    @Test
    void every422ComesBeforeAny400() {
        Map<String, Object> qs = new LinkedHashMap<>();
        qs.put("a", choice("?", List.of()));
        qs.put("b", number("?", 9, 1));
        assertThrows(ValidationError.class, () -> LocalChecks.questions(qs));
    }
}
