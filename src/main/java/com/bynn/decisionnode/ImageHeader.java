package com.bynn.decisionnode;

/**
 * Reads an image's format from its magic bytes and its pixel size from its header, without
 * decoding it. JPEG (the first start-of-frame segment), PNG ({@code IHDR}) and WebP ({@code VP8},
 * {@code VP8L} and {@code VP8X}) are read; nothing else is accepted by the API.
 */
final class ImageHeader {
    static final String JPEG = "image/jpeg";
    static final String PNG = "image/png";
    static final String WEBP = "image/webp";

    private ImageHeader() {}

    /** The media type from the first bytes, or null when they are not JPEG, PNG or WebP. */
    static String sniff(byte[] d) {
        if (d.length >= 3 && u8(d, 0) == 0xff && u8(d, 1) == 0xd8 && u8(d, 2) == 0xff) {
            return JPEG;
        }
        if (d.length >= 8 && u8(d, 0) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && d[4] == 0x0d && d[5] == 0x0a && d[6] == 0x1a && d[7] == 0x0a) {
            return PNG;
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return WEBP;
        }
        return null;
    }

    /**
     * The width and height in pixels read from the header of an image of this media type, or
     * null when the header cannot be read.
     */
    static int[] dimensions(byte[] d, String mediaType) {
        if (JPEG.equals(mediaType)) {
            return jpeg(d);
        }
        if (PNG.equals(mediaType)) {
            return png(d);
        }
        if (WEBP.equals(mediaType)) {
            return webp(d);
        }
        return null;
    }

    /** PNG: the first chunk is {@code IHDR}, width and height as 4-byte big-endian integers. */
    private static int[] png(byte[] d) {
        if (d.length < 24 || d[12] != 'I' || d[13] != 'H' || d[14] != 'D' || d[15] != 'R') {
            return null;
        }
        long w = be32(d, 16);
        long h = be32(d, 20);
        if (w == 0 || h == 0 || w > Integer.MAX_VALUE || h > Integer.MAX_VALUE) {
            return null;
        }
        return new int[] {(int) w, (int) h};
    }

    /**
     * JPEG: walk the segments from after the start-of-image marker to the first start-of-frame
     * (baseline, extended, progressive, lossless, arithmetic or hierarchical), which holds the
     * height and width. Segments without a length (restart markers, TEM) and fill bytes are
     * skipped.
     */
    private static int[] jpeg(byte[] d) {
        int i = 2;
        while (i + 1 < d.length) {
            if (u8(d, i) != 0xff) {
                return null;
            }
            int marker = u8(d, i + 1);
            if (marker == 0xff) {
                i++; // a fill byte before the marker
                continue;
            }
            if (marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
                i += 2;
                continue;
            }
            if (marker == 0xd9 || marker == 0xda) {
                return null; // end of image or start of scan before any frame header
            }
            if (i + 3 >= d.length) {
                return null;
            }
            int length = be16(d, i + 2);
            if (length < 2) {
                return null;
            }
            boolean sof = marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
            if (sof) {
                if (i + 8 >= d.length) {
                    return null;
                }
                int h = be16(d, i + 5);
                int w = be16(d, i + 7);
                return w == 0 || h == 0 ? null : new int[] {w, h};
            }
            i += 2 + length;
        }
        return null;
    }

    /** WebP: the first chunk after the RIFF header says how the size is stored. */
    private static int[] webp(byte[] d) {
        if (d.length < 30) {
            return null;
        }
        String chunk = new String(d, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        switch (chunk) {
            case "VP8 ": {
                // A key frame: the start code 9d 01 2a, then 14-bit width and height.
                if (u8(d, 23) != 0x9d || u8(d, 24) != 0x01 || u8(d, 25) != 0x2a) {
                    return null;
                }
                int w = le16(d, 26) & 0x3fff;
                int h = le16(d, 28) & 0x3fff;
                return w == 0 || h == 0 ? null : new int[] {w, h};
            }
            case "VP8L": {
                // The signature 0x2f, then width - 1 and height - 1 in 14 bits each.
                if (u8(d, 20) != 0x2f) {
                    return null;
                }
                long bits = u8(d, 21) | (u8(d, 22) << 8) | (u8(d, 23) << 16) | ((long) u8(d, 24) << 24);
                int w = (int) (bits & 0x3fff) + 1;
                int h = (int) ((bits >> 14) & 0x3fff) + 1;
                return new int[] {w, h};
            }
            case "VP8X": {
                // The canvas: width - 1 and height - 1 in 24 bits each.
                int w = (u8(d, 24) | (u8(d, 25) << 8) | (u8(d, 26) << 16)) + 1;
                int h = (u8(d, 27) | (u8(d, 28) << 8) | (u8(d, 29) << 16)) + 1;
                return new int[] {w, h};
            }
            default:
                return null;
        }
    }

    private static int u8(byte[] d, int i) {
        return d[i] & 0xff;
    }

    private static int be16(byte[] d, int i) {
        return (u8(d, i) << 8) | u8(d, i + 1);
    }

    private static int le16(byte[] d, int i) {
        return u8(d, i) | (u8(d, i + 1) << 8);
    }

    private static long be32(byte[] d, int i) {
        return ((long) u8(d, i) << 24) | (u8(d, i + 1) << 16) | (u8(d, i + 2) << 8) | u8(d, i + 3);
    }
}
