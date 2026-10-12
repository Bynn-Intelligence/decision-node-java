package com.bynn.decisionnode;

import static com.bynn.decisionnode.Questions.box;
import static com.bynn.decisionnode.Questions.point;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Several places in one answer: max on a point or box question, PointsAnswer and BoxesAnswer. */
class PlacesTest {
    private static Decision decision(String answers) throws Exception {
        String body = "{\"model\":\"decisionnode-moe-0.9.0-rc1\",\"answers\":" + answers + "}";
        return Responses.decision(Json.asMap(Json.read(body.getBytes(StandardCharsets.UTF_8))),
                HttpHeaders.of(Map.of(), (a, b) -> true));
    }

    private static DecisionNode client() {
        return DecisionNode.builder().environment(k -> null).apiKey("dn_test").baseUrl("http://127.0.0.1:1").build();
    }

    private static List<Object> entries(Object question) {
        DecideRequest r = DecideRequest.builder().question("cars", question)
                .image(Image.fromUrl("lot", "https://files.example.com/lot/0917.jpg")).build();
        ValidationError e = assertThrows(ValidationError.class, () -> client().checkRequest(r));
        assertNull(e.status());
        return List.copyOf(e.entries());
    }

    @Test
    void maxIsSentOnlyWhenGiven() {
        assertEquals(Map.of("type", "box", "instructions", "Every car.", "max", 8), box("Every car.", null, null, 8).toMap());
        assertFalse(box("Every car.", null, null).toMap().containsKey("max"));
        assertEquals(Map.of("type", "point", "instructions", "Each sign.", "units", "normalized", "max", 4),
                point("Each sign.", null, "normalized", 4).toMap());
    }

    @Test
    void maxIsCheckedBeforeSending() {
        List<Object> loc = List.of("body", "questions", "cars", "box", "max");
        Map<?, ?> low = (Map<?, ?>) entries(box("Every car.", null, null, 1)).get(0);
        assertEquals("greater_than_equal", low.get("type"));
        assertEquals(loc, low.get("loc"));
        assertEquals(Map.of("ge", 2), low.get("ctx"));
        Map<?, ?> high = (Map<?, ?>) entries(box("Every car.", null, null, 17)).get(0);
        assertEquals("less_than_equal", high.get("type"));
        assertEquals(Map.of("le", 16), high.get("ctx"));
        for (Object bad : new Object[] {2.5, "8", true, 2.0}) {
            Map<?, ?> e = (Map<?, ?>) entries(Map.of("type", "box", "instructions", "Every car.", "max", bad)).get(0);
            assertEquals("int_type", e.get("type"));
            assertEquals(bad, e.get("input"));
        }
        DecideRequest ok = DecideRequest.builder().question("cars", box("Every car.", null, null, 16))
                .image(Image.fromUrl("lot", "https://files.example.com/lot/0917.jpg")).build();
        client().checkRequest(ok);
    }

    @Test
    void severalPlacesAreToldByTheirList() throws Exception {
        Decision d = decision("{"
                + "\"signs\":{\"type\":\"point\",\"count\":3,\"present\":0.99,\"complete\":0.81,\"points\":["
                + "{\"x\":0.1563,\"y\":0.2,\"confidence\":0.9},{\"x\":0.5,\"y\":0.25,\"confidence\":0.8}],"
                + "\"image\":\"lot\",\"width\":1920,\"height\":1080},"
                + "\"cars\":{\"type\":\"box\",\"count\":0,\"present\":0.03,\"complete\":0.97,\"boxes\":[],"
                + "\"image\":\"lot\",\"width\":1920,\"height\":1080},"
                + "\"one\":{\"type\":\"box\",\"x1\":1,\"y1\":2,\"x2\":3,\"y2\":4,\"present\":0.9,\"confidence\":0.8,"
                + "\"image\":\"lot\",\"width\":1920,\"height\":1080}}");
        PointsAnswer signs = d.points("signs");
        assertEquals(3, signs.count());
        assertEquals(List.of(new PlacePoint(0.1563, 0.2, 0.9), new PlacePoint(0.5, 0.25, 0.8)), signs.points());
        assertEquals("point", signs.type());
        BoxesAnswer cars = d.boxes("cars");
        assertEquals(0, cars.count());
        assertEquals(List.of(), cars.boxes());
        assertInstanceOf(BoxAnswer.class, d.get("one"));
    }

    @Test
    void malformedPlacesAreUnknown() throws Exception {
        String tail = "\"image\":\"lot\",\"width\":1920,\"height\":1080}";
        Decision d = decision("{"
                + "\"a\":{\"type\":\"box\",\"count\":1.0,\"present\":0.9,\"complete\":0.9,\"boxes\":[]," + tail + ","
                + "\"b\":{\"type\":\"box\",\"count\":-1,\"present\":0.9,\"complete\":0.9,\"boxes\":[]," + tail + ","
                + "\"c\":{\"type\":\"box\",\"count\":1,\"present\":0.9,\"boxes\":[]," + tail + ","
                + "\"d\":{\"type\":\"box\",\"count\":1,\"present\":0.9,\"complete\":0.9,\"boxes\":[{\"x1\":1,\"y1\":2,\"x2\":3,\"confidence\":0.5}]," + tail + ","
                + "\"e\":{\"type\":\"point\",\"count\":1,\"present\":0.9,\"complete\":0.9,\"boxes\":[]," + tail + ","
                + "\"f\":{\"type\":\"point\",\"count\":1,\"present\":0.9,\"complete\":0.9,\"points\":[{\"x\":1,\"y\":\"2\",\"confidence\":0.5}]," + tail + ","
                + "\"g\":{\"type\":\"point\",\"count\":1,\"present\":0.9,\"complete\":0.9,\"points\":{}," + tail + "}");
        for (String id : List.of("a", "b", "c", "d", "e", "f", "g")) {
            assertInstanceOf(UnknownAnswer.class, d.get(id), id);
        }
    }
}
