package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The face_detection and face_recognition readings. */
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
    void faceRecognitionMatches() throws Exception {
        List<Reading> r = readings("face_recognition", "[{\"image\":\"door\",\"faces\":2,\"matches\":["
                + "{\"box\":{\"x1\":10,\"y1\":20,\"x2\":110,\"y2\":140},\"score\":0.91,\"collection\":\"col_1\","
                + "\"reference\":\"emp-1042\",\"label\":\"Reception\"},"
                + "{\"box\":{\"x1\":200,\"y1\":20,\"x2\":300,\"y2\":140},\"score\":0.88,\"collection\":\"celebrities\","
                + "\"reference\":\"Q42\",\"name\":\"A Public Figure\"}]},"
                + "{\"image\":\"hall\",\"faces\":1,\"matches\":[]}]");
        FaceRecognitionReading door = assertInstanceOf(FaceRecognitionReading.class, r.get(0));
        assertEquals("face_recognition", door.booster());
        assertEquals(2, door.faces());
        assertEquals(new FaceMatch(new FaceBox(10, 20, 110, 140), 0.91, "col_1", "emp-1042", null, "Reception"), door.matches().get(0));
        assertEquals(new FaceMatch(new FaceBox(200, 20, 300, 140), 0.88, "celebrities", "Q42", "A Public Figure", null),
                door.matches().get(1));
        assertTrue(assertInstanceOf(FaceRecognitionReading.class, r.get(1)).matches().isEmpty());
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
        String match = "\"box\":" + box + ",\"score\":0.9";
        List<Reading> matches = readings("face_recognition", "["
                + "{\"image\":\"a\",\"faces\":1},"
                + "{\"image\":\"a\",\"matches\":[]},"
                + "{\"image\":\"a\",\"faces\":1,\"matches\":[{" + match + ",\"reference\":\"r\"}]},"
                + "{\"image\":\"a\",\"faces\":1,\"matches\":[{" + match + ",\"collection\":5,\"reference\":\"r\"}]},"
                + "{\"image\":\"a\",\"faces\":1,\"matches\":[{" + match + ",\"collection\":\"c\"}]},"
                + "{\"image\":\"a\",\"faces\":1,\"matches\":[{" + match + ",\"collection\":\"c\",\"reference\":7}]},"
                + "{\"image\":\"a\",\"faces\":1,\"matches\":[{" + match + ",\"collection\":\"c\",\"reference\":\"r\",\"name\":3}]},"
                + "{\"image\":\"a\",\"faces\":1,\"matches\":[{" + match + ",\"collection\":\"c\",\"reference\":\"r\",\"label\":true}]},"
                + "{\"image\":\"a\",\"faces\":1,\"matches\":[{\"box\":" + box + ",\"collection\":\"c\",\"reference\":\"r\"}]}]");
        assertEquals(9, matches.size());
        matches.forEach(each -> assertEquals("face_recognition", assertInstanceOf(UnknownReading.class, each).booster()));
    }

    @Test
    void theCollectionAndThresholdOptionsAreSentAsGiven() {
        Booster b = Booster.of("face_recognition").options(Map.of("collection", "col_1", "threshold", 0.85));
        assertEquals(Map.of("name", "face_recognition", "options", Map.of("collection", "col_1", "threshold", 0.85)), b.wire());
    }
}
