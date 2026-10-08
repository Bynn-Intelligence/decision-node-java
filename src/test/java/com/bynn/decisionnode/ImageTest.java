package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0};
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

    @Test
    void mediaTypeFromFirstBytes(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("a"), PNG);
        Files.write(dir.resolve("b"), JPEG);
        Files.write(dir.resolve("c"), WEBP);
        assertEquals("image/png", Image.fromPath("a", dir.resolve("a")).mediaType());
        assertEquals("image/jpeg", Image.fromPath("b", dir.resolve("b").toString()).mediaType());
        assertEquals("image/webp", Image.fromBytes("c", WEBP).mediaType());
    }

    @Test
    void anUnreadableFileFailsWhereTheImageIsMade(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> Image.fromPath("x", dir.resolve("missing.png")));
    }

    @Test
    void bytesWithoutMediaTypeAreDetected() {
        assertEquals("image/png", Image.fromBytes("a", PNG, null).mediaType());
        assertEquals("image/jpeg", Image.fromBytes("b", JPEG, "").mediaType());
        assertEquals("image/webp", Image.fromBytes("c", WEBP).mediaType());
        assertEquals("image/jpeg", Image.fromBytes("d", JPEG, "image/png").mediaType());
    }

    @Test
    void contentFaultsRaiseTheApiErrorBeforeSending(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("t.txt"), "hello");
        Image text = Image.fromPath("t", dir.resolve("t.txt"));
        BadRequestError e = assertThrows(BadRequestError.class, () -> LocalChecks.image(text, Map.of()));
        assertEquals("api_usage_error", e.errorType());
        assertNull(e.status());
        assertNull(e.requestId());
        assertThrows(BadRequestError.class, () -> LocalChecks.image(Image.fromBytes("m", JPEG, "image/png"), Map.of()));
        assertThrows(BadRequestError.class, () -> LocalChecks.image(Image.fromUrl("u", "http://example.com/a.png"), Map.of()));
        BadRequestError longUrl = assertThrows(BadRequestError.class,
                () -> LocalChecks.image(Image.fromUrl("u", "https://example.com/" + "a".repeat(2100)), Map.of()));
        assertEquals("image_url_refused", longUrl.errorType());
        for (String url : new String[] {"https://user@example.com/a", "https://example.com:8443/a", "https://10.1.2.3/a",
                "https://[::1]/a", "https://[::ffff:192.168.1.1]/a", "https://169.254.169.254/a"}) {
            assertEquals("image_url_refused",
                    assertThrows(BadRequestError.class, () -> LocalChecks.image(Image.fromUrl("u", url), Map.of())).errorType(), url);
        }
        LocalChecks.image(Image.fromUrl("u", "https://example.com:443/a.png"), Map.of());
        LocalChecks.image(Image.fromUrl("u", "https://8.8.8.8/a.png"), Map.of());
        assertThrows(BadRequestError.class, () -> LocalChecks.frame(
                Frame.builder().image(Image.fromUrl("u", "https://example.com/a.png")).build(), null, Map.of()));
        java.util.List<Image> seventeen = new java.util.ArrayList<>();
        for (int i = 0; i < 17; i++) {
            seventeen.add(Image.fromUrl("u" + i, "https://e.com/a"));
        }
        LocalChecks.images(seventeen.subList(0, 16), Map.of());
        assertEquals("Too many images: 17, the limit is 16.",
                assertThrows(BadRequestError.class, () -> LocalChecks.images(seventeen, Map.of())).getMessage());
        assertThrows(BadRequestError.class, () -> LocalChecks.images(seventeen.subList(0, 5), Map.of("max_images", 4)));
        assertThrows(BadRequestError.class, () -> LocalChecks.images(seventeen.subList(0, 5), Map.of("images", Map.of("max_images", 4))));
        assertThrows(BadRequestError.class, () -> LocalChecks.images(java.util.List.of(
                Image.fromUrl("u", "https://e.com/a"), Image.fromUrl("u", "https://e.com/b")), Map.of()));
    }

    @Test
    void anOversizedStreamIsNotKept() {
        byte[] big = java.util.Arrays.copyOf(PNG, Image.MAX_BYTES + 10);
        Image img = Image.fromStream("big", new java.io.ByteArrayInputStream(big));
        assertNull(img.data());
        BadRequestError e = assertThrows(BadRequestError.class, () -> LocalChecks.image(img, Map.of()));
        assertEquals("Image big is larger than 10 MB.", e.getMessage());
    }

    @Test
    void base64AndUploadForms() {
        String b64 = java.util.Base64.getEncoder().encodeToString(PNG);
        assertEquals("image/png", Image.fromBase64("p", b64).mediaType());
        assertThrows(IllegalArgumentException.class, () -> Image.fromBase64("p", "not base64!"));
        assertEquals(Map.of("id", "x", "upload_id", "upl_1"), Image.fromUpload("x", "upl_1").toMap());
        assertEquals(Map.of("id", "x", "upload_id", "upl_1", "media_type", "image/png"),
                Image.fromUpload("x", "upl_1", "image/png").toMap());
    }

    @Test
    void wireForms() {
        assertEquals(Map.of("id", "p", "media_type", "image/png", "data", "iVBORw0KGgoAAA=="),
                Image.fromBytes("p", PNG, "image/png").toMap());
        assertEquals(Map.of("id", "u", "url", "https://example.com/a.png"),
                Image.fromUrl("u", "https://example.com/a.png").toMap());
    }
}
