package com.bynn.decisionnode;

import static com.bynn.decisionnode.Questions.box;
import static com.bynn.decisionnode.Questions.point;
import static com.bynn.decisionnode.Questions.truth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Point and box questions: wire forms, answers and the API's rules checked before sending. */
class PointBoxTest {
    private static final Image BELT = Image.fromUrl("belt", "https://files.example.com/belt.jpg");
    private static final Image DOCK = Image.fromUrl("dock", "https://files.example.com/dock.jpg");
    private final DecisionNode dn = DecisionNode.builder().apiKey("dn_test").build();

    @Test
    void wireForms() {
        assertEquals(Map.of("type", "point", "instructions", "Where?"), point("Where?").toMap());
        assertEquals(Map.of("type", "box", "instructions", "Where?", "image", "belt", "units", "normalized"),
                box("Where?", "belt", "normalized").toMap());
    }

    @Test
    void answers() {
        Map<String, Object> body = Json.asMap(Json.readOrNull(("{\"answers\":{"
                + "\"pick\":{\"type\":\"point\",\"x\":812,\"y\":455,\"present\":0.97,\"confidence\":0.91,\"image\":\"belt\",\"width\":1920,\"height\":1080},"
                + "\"damage\":{\"type\":\"box\",\"x1\":0.1563,\"y1\":0.1111,\"x2\":0.2708,\"y2\":0.3796,\"present\":0.95,\"confidence\":0.88,"
                + "\"image\":\"belt\",\"width\":1920,\"height\":1080},"
                + "\"odd\":{\"type\":\"point\",\"x\":true,\"y\":455,\"present\":0.97,\"confidence\":0.91,\"image\":\"belt\",\"width\":1920,\"height\":1080},"
                + "\"no_image\":{\"type\":\"box\",\"x1\":1,\"y1\":1,\"x2\":2,\"y2\":2,\"present\":0.5,\"confidence\":0.5,\"width\":4,\"height\":4}}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Decision d = Responses.decision(body, Responses.of(Map.of()));
        PointAnswer p = d.point("pick");
        assertEquals(List.of(812.0, 455.0, 0.97, 0.91, 1920.0, 1080.0), List.of(p.x(), p.y(), p.present(), p.confidence(), p.width(), p.height()));
        assertEquals("belt", p.image());
        BoxAnswer b = d.box("damage");
        assertEquals(List.of(0.1563, 0.1111, 0.2708, 0.3796), List.of(b.x1(), b.y1(), b.x2(), b.y2()));
        assertInstanceOf(UnknownAnswer.class, d.get("odd"));
        assertInstanceOf(UnknownAnswer.class, d.get("no_image"));
    }

    private List<String> entries(DecideRequest r) {
        ValidationError e = assertThrows(ValidationError.class, () -> dn.checkRequest(r));
        assertNull(e.status());
        List<String> out = new ArrayList<>();
        for (Object o : e.entries()) {
            Map<?, ?> m = (Map<?, ?>) o;
            out.add(m.get("type") + " " + m.get("loc"));
        }
        return out;
    }

    @Test
    void localChecks() {
        assertEquals(List.of("image_required [body, questions, pick, point]"),
                entries(DecideRequest.builder().question("pick", point("Where?")).build()));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("type", "box");
        raw.put("instructions", "?");
        raw.put("image", 7);
        DecideRequest two = DecideRequest.builder()
                .image(BELT).image(DOCK)
                .video(Video.of("cam", List.of(VideoFrame.fromBase64(0, "AA==", "image/png"))))
                .question("pick", point("Where?", "cam", "inches"))
                .question("any", truth("Damaged?"))
                .question("damage", box("Where?"))
                .question("raw", raw)
                .build();
        assertEquals(List.of(
                "unknown_image [body, questions, pick, point, image]",
                "literal_error [body, questions, pick, point, units]",
                "missing [body, questions, damage, box, image]",
                "string_type [body, questions, raw, box, image]"), entries(two));
        // One image needs no image field; missing instructions are the API's plain 400.
        dn.checkRequest(DecideRequest.builder().image(BELT).question("pick", point("Where?")).build());
        BadRequestError e = assertThrows(BadRequestError.class,
                () -> dn.checkRequest(DecideRequest.builder().image(BELT).question("pick", box(null)).build()));
        assertEquals("Box question must have instructions: pick", e.getMessage());
        assertNull(e.errorType());
        // The strict path takes neither type; a session's open skips the image rules.
        assertThrows(BadRequestError.class,
                () -> dn.checkSystemoneRequest(DecideRequest.builder().image(BELT).question("pick", point("Where?")).build()));
        dn.checkRequest(SessionRequest.builder().question("pick", point("Where?")).build());
    }
}
