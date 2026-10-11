package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The ocr booster's reading. */
class OcrReadingTest {
    private static List<Reading> readings(String results) throws Exception {
        String body = "{\"model\":\"decisionnode-moe-0.9.0-rc1\",\"answers\":{},\"evidence\":{\"ocr\":{\"version\":\"ocr-v1\","
                + "\"status\":\"ok\",\"latency_ms\":420,\"billed_calls\":1,\"results\":" + results + "}}}";
        Decision d = Responses.decision(Json.asMap(Json.read(body.getBytes(StandardCharsets.UTF_8))),
                HttpHeaders.of(Map.of(), (a, b) -> true));
        return d.evidence("ocr").results();
    }

    @Test
    void textAndEmptyText() throws Exception {
        List<Reading> r = readings("[{\"image\":\"receipt\",\"text\":\"Total 740.00 SEK\",\"truncated\":false},{\"image\":\"blank\",\"text\":\"\",\"truncated\":false,"
                + "\"region\":{\"x\":4}}]");
        OcrReading read = assertInstanceOf(OcrReading.class, r.get(0));
        assertEquals("receipt", read.image());
        assertEquals("Total 740.00 SEK", read.text());
        assertFalse(read.truncated());
        assertEquals("ocr", read.booster());
        OcrReading blank = assertInstanceOf(OcrReading.class, r.get(1));
        assertEquals("", blank.text());
    }

    @Test
    void truncatedTrue() throws Exception {
        OcrReading r = assertInstanceOf(OcrReading.class,
                readings("[{\"image\":\"receipt\",\"text\":\"{\\\"total\\\": \\\"740.00\\\"}\",\"truncated\":true}]").get(0));
        assertTrue(r.truncated());
        assertEquals("{\"total\": \"740.00\"}", r.text());
    }

    @Test
    void malformedReadingsAreGeneric() throws Exception {
        List<Reading> r = readings("[{\"image\":\"receipt\",\"text\":740,\"truncated\":false},{\"image\":\"receipt\",\"text\":\"Total\",\"truncated\":\"yes\"},"
                + "{\"image\":\"receipt\",\"truncated\":false},{\"image\":3,\"text\":\"Total\",\"truncated\":false},"
                + "{\"image\":\"receipt\",\"text\":\"Total\"},{\"image\":\"receipt\",\"text\":\"Total\",\"truncated\":null}]");
        for (Reading each : r) {
            UnknownReading u = assertInstanceOf(UnknownReading.class, each);
            assertEquals("ocr", u.booster());
        }
        assertEquals(6, r.size());
        assertEquals(Map.of("image", "receipt", "text", 740, "truncated", false), ((UnknownReading) r.get(0)).raw());
    }
}
