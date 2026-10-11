package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The face_detection and wanted_person readings. */
class FaceReadingsTest {
    private static List<Reading> readings(String booster, String results) throws Exception {
        String body = "{\"model\":\"decisionnode-moe-0.9.0-rc1\",\"answers\":{},\"evidence\":{\"" + booster + "\":{\"version\":\""
                + booster + "-v1\",\"status\":\"ok\",\"latency_ms\":120,\"billed_calls\":1,\"results\":" + results + "}}}";
        Decision d = Responses.decision(Json.asMap(Json.read(body.getBytes(StandardCharsets.UTF_8))),
                HttpHeaders.of(Map.of(), (a, b) -> true));
        return d.evidence(booster).results();
    }

    @Test
    void faceDetectionInTheOrderSent() throws Exception {
        List<Reading> r = readings("face_detection", "[{\"image\":\"crowd\",\"faces\":3,\"detections\":["
                + "{\"box\":{\"x1\":812,\"y1\":455,\"x2\":901,\"y2\":560},\"confidence\":0.97},"
                + "{\"box\":{\"x1\":0.1563,\"y1\":0.1111,\"x2\":0.2708,\"y2\":0.3796},\"confidence\":0.61}],\"extra\":1},"
                + "{\"image\":\"wall\",\"faces\":0,\"detections\":[]}]");
        FaceDetectionReading crowd = assertInstanceOf(FaceDetectionReading.class, r.get(0));
        assertEquals("face_detection", crowd.booster());
        assertEquals(3, crowd.faces());
        assertEquals(2, crowd.detections().size());
        assertEquals(new FaceDetection(new FaceBox(812, 455, 901, 560), 0.97), crowd.detections().get(0));
        assertEquals(new FaceBox(0.1563, 0.1111, 0.2708, 0.3796), crowd.detections().get(1).box());
        FaceDetectionReading wall = assertInstanceOf(FaceDetectionReading.class, r.get(1));
        assertEquals(0, wall.faces());
        assertTrue(wall.detections().isEmpty());
    }

    @Test
    void wantedPersonMatches() throws Exception {
        List<Reading> r = readings("wanted_person", "[{\"image\":\"door\",\"faces\":2,\"matches\":["
                + "{\"box\":{\"x1\":10,\"y1\":20,\"x2\":110,\"y2\":140},\"score\":0.91,\"reference\":\"ref_00A1:7\"}]},"
                + "{\"image\":\"hall\",\"faces\":1,\"matches\":[]}]");
        WantedPersonReading door = assertInstanceOf(WantedPersonReading.class, r.get(0));
        assertEquals("wanted_person", door.booster());
        assertEquals(2, door.faces());
        assertEquals(new WantedMatch(new FaceBox(10, 20, 110, 140), 0.91, "ref_00A1:7"), door.matches().get(0));
        assertTrue(assertInstanceOf(WantedPersonReading.class, r.get(1)).matches().isEmpty());
    }

    @Test
    void malformedFaceReadingsAreGeneric() throws Exception {
        String box = "{\"x1\":1,\"y1\":2,\"x2\":3,\"y2\":4}";
        List<Reading> detections = readings("face_detection", "["
                + "{\"image\":\"a\",\"faces\":-1,\"detections\":[]},"
                + "{\"image\":\"a\",\"faces\":1.0,\"detections\":[]},"
                + "{\"image\":\"a\",\"faces\":\"1\",\"detections\":[]},"
                + "{\"image\":\"a\",\"faces\":1},"
                + "{\"image\":\"a\",\"faces\":1,\"detections\":{}},"
                + "{\"image\":\"a\",\"faces\":1,\"detections\":[{\"box\":{\"x1\":1,\"y1\":2,\"x2\":3},\"confidence\":0.5}]},"
                + "{\"image\":\"a\",\"faces\":1,\"detections\":[{\"box\":{\"x1\":1,\"y1\":2,\"x2\":3,\"y2\":\"4\"},\"confidence\":0.5}]},"
                + "{\"image\":\"a\",\"faces\":1,\"detections\":[{\"box\":" + box + "}]},"
                + "{\"image\":\"a\",\"faces\":1,\"detections\":[{\"box\":" + box + ",\"confidence\":true}]},"
                + "{\"image\":7,\"faces\":1,\"detections\":[]}]");
        assertEquals(10, detections.size());
        detections.forEach(each -> assertEquals("face_detection", assertInstanceOf(UnknownReading.class, each).booster()));
        List<Reading> matches = readings("wanted_person", "["
                + "{\"image\":\"a\",\"faces\":1},"
                + "{\"image\":\"a\",\"faces\":1,\"matches\":[{\"box\":" + box + ",\"score\":0.9}]},"
                + "{\"image\":\"a\",\"faces\":1,\"matches\":[{\"box\":" + box + ",\"score\":0.9,\"reference\":5}]},"
                + "{\"image\":\"a\",\"faces\":1,\"matches\":[{\"box\":" + box + ",\"reference\":\"r\"}]},"
                + "{\"image\":\"a\",\"faces\":1,\"matches\":[{\"score\":0.9,\"reference\":\"r\"}]}]");
        assertEquals(5, matches.size());
        matches.forEach(each -> assertEquals("wanted_person", assertInstanceOf(UnknownReading.class, each).booster()));
    }

    @Test
    void theThresholdOptionIsSentAsGiven() {
        Booster b = Booster.of("wanted_person").options(Map.of("threshold", 0.85));
        assertEquals(Map.of("name", "wanted_person", "options", Map.of("threshold", 0.85)), b.wire());
    }
}
