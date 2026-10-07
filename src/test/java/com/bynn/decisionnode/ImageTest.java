package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void unreadableOrUnknownFilesFailBeforeSending(@TempDir Path dir) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> Image.fromPath("x", dir.resolve("missing.png")));
        Files.writeString(dir.resolve("t.txt"), "hello");
        assertThrows(IllegalArgumentException.class, () -> Image.fromPath("x", dir.resolve("t.txt")));
    }

    @Test
    void bytesWithoutMediaTypeAreDetected() {
        assertEquals("image/png", Image.fromBytes("a", PNG, null).mediaType());
        assertEquals("image/jpeg", Image.fromBytes("b", JPEG, "").mediaType());
        assertEquals("image/webp", Image.fromBytes("c", WEBP).mediaType());
        assertEquals("image/png", Image.fromBytes("d", JPEG, "image/png").mediaType());
        byte[] text = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> Image.fromBytes("e", text));
        assertThrows(IllegalArgumentException.class, () -> Image.fromBytes("e", text, null));
    }

    @Test
    void wireForms() {
        assertEquals(Map.of("id", "p", "media_type", "image/png", "data", "iVBORw0KGgoAAA=="),
                Image.fromBytes("p", PNG, "image/png").toMap());
        assertEquals(Map.of("id", "u", "url", "https://example.com/a.png"),
                Image.fromUrl("u", "https://example.com/a.png").toMap());
    }
}
