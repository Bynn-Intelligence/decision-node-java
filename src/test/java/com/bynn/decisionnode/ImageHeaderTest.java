package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Pixel sizes read from image headers, against files written by real encoders. */
class ImageHeaderTest {
    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = ImageHeaderTest.class.getResourceAsStream("/images/" + name)) {
            if (in == null) {
                throw new IOException("no fixture " + name);
            }
            return in.readAllBytes();
        }
    }

    private static void size(String name, String type, int w, int h) throws IOException {
        byte[] d = fixture(name);
        assertEquals(type, ImageHeader.sniff(d), name);
        assertArrayEquals(new int[] {w, h}, ImageHeader.dimensions(d, type), name);
    }

    @Test
    void jpegBaseline() throws IOException {
        size("baseline.jpg", "image/jpeg", 37, 23);
    }

    @Test
    void jpegProgressiveAfterExifAndTables() throws IOException {
        // APP0, APP1 (EXIF) and two quantisation tables come before the SOF2 frame header.
        size("progressive-exif.jpg", "image/jpeg", 37, 23);
    }

    @Test
    void png() throws IOException {
        size("rgb.png", "image/png", 37, 23);
    }

    @Test
    void webpLossy() throws IOException {
        size("lossy.webp", "image/webp", 37, 23);
    }

    @Test
    void webpLossless() throws IOException {
        size("lossless.webp", "image/webp", 37, 23);
    }

    @Test
    void webpExtended() throws IOException {
        size("extended.webp", "image/webp", 37, 23);
    }

    @Test
    void pngHeaderOverFortyMegapixels() throws IOException {
        size("huge-header.png", "image/png", 8000, 6000);
    }

    @Test
    void paddingAfterTheImageChangesNothing() throws IOException {
        byte[] d = Arrays.copyOf(fixture("baseline.jpg"), 600_000);
        assertArrayEquals(new int[] {37, 23}, ImageHeader.dimensions(d, "image/jpeg"));
    }

    @Test
    void jpegFillBytesBeforeAMarkerAreSkipped() throws IOException {
        byte[] d = fixture("baseline.jpg");
        byte[] filled = new byte[d.length + 3];
        System.arraycopy(d, 0, filled, 0, 2);
        filled[2] = (byte) 0xff;
        filled[3] = (byte) 0xff;
        filled[4] = (byte) 0xff;
        System.arraycopy(d, 2, filled, 5, d.length - 2);
        assertArrayEquals(new int[] {37, 23}, ImageHeader.dimensions(filled, "image/jpeg"));
    }

    @Test
    void truncatedHeadersAreUnreadable() throws IOException {
        for (String name : new String[] {"baseline.jpg", "rgb.png", "lossy.webp", "lossless.webp", "extended.webp"}) {
            byte[] d = fixture(name);
            String type = ImageHeader.sniff(d);
            assertNull(ImageHeader.dimensions(Arrays.copyOf(d, 20), type), name);
        }
    }

    @Test
    void unknownFormatsAreNotSniffed() {
        assertNull(ImageHeader.sniff("GIF89a....".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertNull(ImageHeader.sniff(new byte[0]));
        assertNull(ImageHeader.dimensions(new byte[40], "image/gif"));
    }
}
