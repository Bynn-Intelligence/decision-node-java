package com.bynn.decisionnode;

import static com.bynn.decisionnode.Questions.truth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The API's video rules, checked before a request is sent, with the API's own messages. */
class VideoChecksTest {
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    private static Video dock() {
        return Video.of("dock", List.of(VideoFrame.fromBytes(0, JPEG)));
    }

    private static String refused(List<?> videos) {
        return refused(videos, List.of());
    }

    private static String refused(List<?> videos, List<?> images) {
        BadRequestError e = assertThrows(BadRequestError.class, () -> LocalChecks.videos(videos, images, Map.of()));
        assertEquals("api_usage_error", e.errorType());
        assertNull(e.status());
        assertNull(e.requestId());
        return e.getMessage();
    }

    @Test
    void theTableOfChecks() {
        List<Video> five = new ArrayList<>();
        for (String id : List.of("a", "b", "c", "d", "e")) {
            five.add(Video.of(id, List.of(VideoFrame.fromBytes(0, JPEG))));
        }
        // Five videos are a 422 entry of the request, after its questions' entries.
        LocalChecks.videos(five, List.of(), Map.of());
        Map<String, Object> choice = new java.util.LinkedHashMap<>();
        choice.put("type", "choice");
        choice.put("instructions", "?");
        ValidationError e = assertThrows(ValidationError.class, () -> LocalChecks.request(
                Map.of("questions", Map.of("route", choice)), LocalChecks.Profile.DECIDE, Map.of(), List.of(), five));
        assertEquals(2, e.entries().size());
        assertEquals("missing", ((Map<?, ?>) e.entries().get(0)).get("type"));
        Map<?, ?> count = (Map<?, ?>) e.entries().get(1);
        assertEquals(List.of("body", "videos"), count.get("loc"));
        assertEquals(Map.of("field_type", "List", "max_length", 4, "actual_length", 5), count.get("ctx"));
        assertEquals("e", ((Map<?, ?>) ((List<?>) count.get("input")).get(4)).get("id"));
        assertEquals("Video 0 needs an id.", refused(List.of(Video.of("", List.of(VideoFrame.fromBytes(0, JPEG))))));
        String twice = "Video id 'dock' is used twice (ids are unique across images and videos).";
        assertEquals(twice, refused(List.of(dock(), dock())));
        assertEquals(twice, refused(List.of(dock()), List.of(Image.fromBytes("dock", JPEG))));
        assertEquals("Video 'dock': frames must be a non-empty list of frames.", refused(List.of(Video.of("dock", List.of()))));
        assertEquals("Video \"it's\": frames must be a non-empty list of frames.", refused(List.of(Video.of("it's", List.of()))));
        assertEquals("Video 'dock': frame 0: t must be a number of seconds, at least 0.",
                refused(List.of(Video.of("dock", List.of(VideoFrame.fromBytes(-1, JPEG))))));
        assertEquals("Video 'dock': frame 0: t must be a number of seconds, at least 0.",
                refused(List.of(Video.of("dock", List.of(VideoFrame.fromBytes(Double.NaN, JPEG))))));
        assertEquals("Video 'dock': frame 2: t must be larger than the frame before it (1.5).",
                refused(List.of(Video.of("dock", List.of(VideoFrame.fromBytes(0.5, JPEG), VideoFrame.fromBytes(1.5, JPEG),
                        VideoFrame.fromBytes(1.5, JPEG))))));
        String types = "Video 'dock': frame 0: media_type must be one of image/jpeg, image/png, image/webp.";
        assertEquals(types, refused(List.of(Video.of("dock", List.of(VideoFrame.fromBytes(0, "GIF89a".getBytes()))))));
        assertEquals(types, refused(List.of(Video.of("dock", List.of(VideoFrame.fromBase64(0, "AAAA", null))))));
        String fps = "Video 'dock': fps must be a number above 0 and at most 4.";
        assertEquals(fps, refused(List.of(dock().fps(4.5))));
        assertEquals(fps, refused(List.of(dock().fps(0))));
        LocalChecks.videos(List.of(dock().fps(4)), List.of(), Map.of());
        assertEquals("Video 'dock': start must be a number of seconds, at least 0.", refused(List.of(dock().start(-1))));
        assertEquals("Video 'dock': end must be after start.", refused(List.of(dock().end(0))));
        assertEquals("Video 'dock': end must be after start.", refused(List.of(dock().start(3).end(2))));
    }

    @Test
    void wireFormsByTheSameRules() {
        assertEquals("Video 'cam': frames must be a non-empty list of frames.",
                refused(List.of(Map.of("id", "cam", "url", "https://example.com/a.mp4"))));
        assertEquals("Video 'cam': needs exactly one of data, url and frames.",
                refused(List.of(Map.of("id", "cam", "url", "https://example.com/a.mp4", "frames", List.of()))));
        assertEquals("Video 'cam': frame 0 must be an object with t, media_type and data.",
                refused(List.of(Map.of("id", "cam", "frames", List.of(1)))));
        assertEquals("Video 'cam': frame 0: t must be a number of seconds, at least 0.",
                refused(List.of(Map.of("id", "cam", "frames", List.of(Map.of("t", "0", "media_type", "image/png", "data", ""))))));
        assertEquals("Video 'cam': frame 0: data must be base64 text.",
                refused(List.of(Map.of("id", "cam", "frames", List.of(Map.of("t", 0, "media_type", "image/png", "data", 1))))));
        assertEquals("Video 'cam': fps must be a number above 0 and at most 4.",
                refused(List.of(Map.of("id", "cam", "fps", "2", "frames", List.of(Map.of("t", 0, "media_type", "image/png", "data", ""))))));
        assertThrows(IllegalArgumentException.class, () -> LocalChecks.videos(List.of("dock.mp4"), List.of(), Map.of()));
    }

    @Test
    void notCheckedLocally() {
        // The frame count and the bytes against their type are the server's to judge.
        List<VideoFrame> many = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            many.add(VideoFrame.fromBytes(i / 2.0, "x".getBytes(), "image/png"));
        }
        DecisionNode dn = DecisionNode.builder().apiKey("dn_test").build();
        dn.checkRequest(DecideRequest.builder().question("q", truth("?")).video(Video.of("dock", many)).build());
        // A video breaking a rule fails the check of the whole request.
        assertThrows(BadRequestError.class,
                () -> dn.checkRequest(DecideRequest.builder().question("q", truth("?")).video(Video.of("dock", List.of())).build()));
    }

    @Test
    void numbersAndQuotes() {
        String[][] g = {{"1.5", "1.5"}, {"4", "4"}, {"1.69679e+09", "1696789012.5"}, {"0.333333", "0.333333333"},
            {"1e+06", "1000000"}, {"1.23457e+06", "1234567.5"}, {"1e+06", "999999.5"}, {"0.0001", "0.0001"},
            {"1e-05", "0.00001"}, {"30.1235", "30.123456"}, {"100000", "100000"}, {"0", "0"}, {"1", "1.0"}};
        for (String[] c : g) {
            assertEquals(c[0], LocalChecks.messageNumber(Double.parseDouble(c[1])), c[1]);
        }
        assertEquals("'dock'", LocalChecks.pyRepr("dock"));
        assertEquals("'a\\'b\"c'", LocalChecks.pyRepr("a'b\"c"));
        assertEquals("'a\\\\b\\n'", LocalChecks.pyRepr("a\\b\n"));
    }
}
