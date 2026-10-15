package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Depth Vision and Segment Vision readings. */
class VisionReadingsTest {
    private static List<Reading> readings(String booster, String results) throws Exception {
        String body = "{\"model\":\"decisionnode-flash-0.9.0-rc1\",\"answers\":{},\"evidence\":{\"" + booster + "\":{\"version\":\""
                + booster + "-v1\",\"status\":\"ok\",\"latency_ms\":120,\"billed_calls\":1,\"results\":" + results + "}}}";
        Decision d = Responses.decision(Json.asMap(Json.read(body.getBytes(StandardCharsets.UTF_8))),
                HttpHeaders.of(Map.of(), (a, b) -> true));
        return d.evidence(booster).results();
    }

    private static final String DEPTH = "{\"image\":\"hall\",\"ahead\":2.41,\"directions\":[3.1,null,2.4,2.41,2.6,null,5.02],"
            + "\"ground\":true,\"grid_rows\":2,\"grid_cols\":3,\"grid\":[[1.5,2,2.25],[0.9,1.1,1.3]],"
            + "\"depth_min\":0.9,\"depth_max\":12.75,\"depth_median\":2.35,\"width\":1280,\"height\":720,\"extra\":1}";

    @Test
    void depthReadsEveryFieldAsSent() throws Exception {
        List<Reading> r = readings("bynn_depth", "[" + DEPTH + ","
                + DEPTH.replace("\"ahead\":2.41", "\"ahead\":null") + ","
                + DEPTH.replace("\"ahead\":2.41,", "") + "]");
        DepthReading hall = assertInstanceOf(DepthReading.class, r.get(0));
        assertEquals("bynn_depth", hall.booster());
        assertEquals(2.41, hall.ahead());
        assertEquals(Arrays.asList(3.1, null, 2.4, 2.41, 2.6, null, 5.02), hall.directions());
        assertTrue(hall.ground());
        assertEquals(2, hall.gridRows());
        assertEquals(3, hall.gridCols());
        assertEquals(List.of(List.of(1.5, 2.0, 2.25), List.of(0.9, 1.1, 1.3)), hall.grid());
        assertEquals(0.9, hall.depthMin());
        assertEquals(12.75, hall.depthMax());
        assertEquals(2.35, hall.depthMedian());
        assertEquals(1280, hall.width());
        assertEquals(720, hall.height());
        assertNull(assertInstanceOf(DepthReading.class, r.get(1)).ahead());
        assertNull(assertInstanceOf(DepthReading.class, r.get(2)).ahead());
    }

    @Test
    void aMalformedDepthReadingIsGeneric() throws Exception {
        List<Reading> r = readings("bynn_depth", "["
                + "{\"image\":\"hall\",\"readable\":false},"
                + DEPTH.replace("[3.1,null,2.4,2.41,2.6,null,5.02]", "[3.1,2.4]") + ","
                + DEPTH.replace("[3.1,null,", "[\"3.1\",null,") + ","
                + DEPTH.replace("\"ground\":true", "\"ground\":1") + ","
                + DEPTH.replace("\"grid_rows\":2", "\"grid_rows\":2.5") + ","
                + DEPTH.replace("[1.5,2,2.25]", "[1.5,null,2.25]") + ","
                + DEPTH.replace("\"ahead\":2.41", "\"ahead\":\"far\"") + ","
                + DEPTH.replace("\"depth_median\":2.35,", "") + ","
                + DEPTH.replace("\"width\":1280", "\"width\":-1") + "]");
        assertEquals(9, r.size());
        r.forEach(each -> assertEquals("bynn_depth", assertInstanceOf(UnknownReading.class, each).booster()));
    }

    private static final String INSTANCE = "{\"label\":\"door\",\"score\":0.88,\"box\":[0.4,0.1,0.62,0.9],"
            + "\"polygon\":[[0.4,0.1],[0.62,0.1],[0.62,0.9]],\"area\":0.17,\"centroid\":[0.51,0.5],"
            + "\"opening\":{\"polygon\":[[0.43,0.15],[0.59,0.15],[0.59,0.88]],\"centroid\":[0.51,0.52],\"width\":0.16,"
            + "\"height\":0.73,\"offset\":[0,0.02],\"area\":0.11},"
            + "\"depth\":{\"median\":3.2,\"opening_median\":6.8,\"opening_rim_median\":null},"
            + "\"size_m\":{\"width\":0.92,\"height\":2.05,\"opening\":{\"width\":0.8,\"height\":2.0}}}";

    @Test
    void segmentReadsInstancesInOrderAsSent() throws Exception {
        String plain = "{\"label\":\"chair\",\"score\":0.4,\"box\":[0.1,0.6,0.2,0.8],\"polygon\":[],\"area\":0.02,"
                + "\"centroid\":[0.15,0.7],\"opening\":null,\"depth\":null}";
        List<Reading> r = readings("bynn_segment", "[{\"image\":\"hall\",\"width\":1280,\"height\":720,"
                + "\"prompts\":[\"door\",\"chair\"],\"instances\":[" + INSTANCE + "," + plain + "],"
                + "\"depth_source\":\"bynn_depth\",\"assumed_hfov_deg\":70},"
                + "{\"image\":\"wall\",\"width\":640,\"height\":480,\"prompts\":[\"door\"],\"instances\":[]}]");
        SegmentReading hall = assertInstanceOf(SegmentReading.class, r.get(0));
        assertEquals("bynn_segment", hall.booster());
        assertEquals(List.of("door", "chair"), hall.prompts());
        assertEquals("bynn_depth", hall.depthSource());
        assertEquals(70.0, hall.assumedHfovDeg());
        SegmentInstance door = hall.instances().get(0);
        assertEquals("door", door.label());
        assertEquals(new SegmentBox(0.4, 0.1, 0.62, 0.9), door.box());
        assertEquals(new SegmentPoint(0.62, 0.9), door.polygon().get(2));
        assertEquals(new SegmentPoint(0.51, 0.5), door.centroid());
        assertEquals(new SegmentPoint(0, 0.02), door.opening().offset());
        assertEquals(0.73, door.opening().height());
        assertEquals(new SegmentDepth(3.2, 6.8, null), door.depth());
        assertEquals(new SegmentSize(0.92, 2.05, new SegmentSize.Opening(0.8, 2.0)), door.sizeM());
        SegmentInstance chair = hall.instances().get(1);
        assertNull(chair.opening());
        assertNull(chair.depth());
        assertNull(chair.sizeM());
        assertTrue(chair.polygon().isEmpty());
        SegmentReading wall = assertInstanceOf(SegmentReading.class, r.get(1));
        assertTrue(wall.instances().isEmpty());
        assertNull(wall.depthSource());
        assertNull(wall.assumedHfovDeg());
    }

    @Test
    void aMalformedSegmentReadingIsGeneric() throws Exception {
        String head = "{\"image\":\"hall\",\"width\":1280,\"height\":720,\"prompts\":[\"door\"],\"instances\":[";
        List<Reading> r = readings("bynn_segment", "["
                + "{\"image\":\"hall\",\"readable\":false},"
                + head + INSTANCE.replace("[0.4,0.1,0.62,0.9]", "[0.4,0.1,0.62]") + "]},"
                + head + INSTANCE.replace("\"centroid\":[0.51,0.5]", "\"centroid\":[0.51]") + "]},"
                + head + INSTANCE.replace("\"offset\":[0,0.02],", "") + "]},"
                + head + INSTANCE.replace("\"opening_rim_median\":null", "\"opening_rim_median\":\"x\"") + "]},"
                + head + INSTANCE.replace("{\"width\":0.8,\"height\":2.0}", "{\"width\":0.8}") + "]},"
                + head + INSTANCE.replace("\"label\":\"door\",", "") + "]},"
                + "{\"image\":\"hall\",\"width\":1280,\"height\":720,\"prompts\":[1],\"instances\":[]},"
                + "{\"image\":\"hall\",\"width\":1280,\"height\":720,\"prompts\":[\"door\"],\"instances\":[],\"depth_source\":5},"
                + "{\"image\":\"hall\",\"width\":1280,\"height\":-720,\"prompts\":[\"door\"],\"instances\":[]}]");
        assertEquals(10, r.size());
        r.forEach(each -> assertEquals("bynn_segment", assertInstanceOf(UnknownReading.class, each).booster()));
        assertFalse(r.isEmpty());
    }
}
