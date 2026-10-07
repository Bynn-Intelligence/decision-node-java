package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SafetyTest {
    @Test
    void absentHeaderIsNull() {
        assertNull(Safety.parse(null));
    }

    @Test
    void checked() {
        Safety s = Safety.parse("harm=0.031, self_harm=0.004");
        assertTrue(s.checked());
        assertEquals(0.031, s.harm());
        assertEquals(0.004, s.selfHarm());
        assertEquals(List.of(), s.flagged());
        assertFalse(s.isFlagged());
        assertNull(s.reason());
    }

    @Test
    void flaggedAndBlockedInHeaderOrder() {
        Safety s = Safety.parse("harm=0.964;blocked, self_harm=0.71;flagged");
        assertEquals(List.of("harm", "self_harm"), s.flagged());
        assertTrue(s.isFlagged());
    }

    @Test
    void unchecked() {
        Safety s = Safety.parse("unchecked;reason=\"timeout\"");
        assertFalse(s.checked());
        assertEquals("timeout", s.reason());
        assertNull(s.harm());
    }
}
