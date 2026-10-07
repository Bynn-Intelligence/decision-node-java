package com.bynn.decisionnode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An image sent with a decision, referred to by its id in the state or the questions.
 *
 * <pre>{@code
 * Image.fromPath("receipt", Path.of("receipt.jpg"));          // read and base64-encoded
 * Image.fromBytes("photo", bytes);                            // media type from the first bytes
 * Image.fromBytes("photo", bytes, "image/png");
 * Image.fromUrl("scan", "https://example.com/scan.webp");     // https only
 * }</pre>
 *
 * <p>JPEG, PNG and WebP are accepted. The API enforces its own limits (the number of images per
 * request and the size of each); they are published at {@code GET /v1/models}.
 */
public final class Image {
    private final String id;
    private final byte[] data;
    private final String mediaType;
    private final String url;

    private Image(String id, byte[] data, String mediaType, String url) {
        this.id = Objects.requireNonNull(id, "id");
        this.data = data;
        this.mediaType = mediaType;
        this.url = url;
    }

    /**
     * Reads an image file now, when the {@code Image} is made. Its media type is taken from the
     * file's first bytes.
     *
     * @throws IllegalArgumentException when the file cannot be read or is not a JPEG, PNG or WebP image
     */
    public static Image fromPath(String id, Path path) {
        Objects.requireNonNull(path, "path");
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalArgumentException("image '" + id + "': cannot read " + path + ": " + e, e);
        }
        String type = sniff(data);
        if (type == null) {
            throw new IllegalArgumentException("image '" + id + "': " + path + " is not a JPEG, PNG or WebP image");
        }
        return new Image(id, data, type, null);
    }

    /** Reads an image file. See {@link #fromPath(String, Path)}. */
    public static Image fromPath(String id, String path) {
        return fromPath(id, Path.of(path));
    }

    /**
     * An image from bytes, with its media type: {@code image/jpeg}, {@code image/png} or
     * {@code image/webp}. A null or empty media type is taken from the first bytes, as
     * {@link #fromBytes(String, byte[])} does.
     *
     * @throws IllegalArgumentException when no media type is given and the bytes are not a JPEG,
     *     PNG or WebP image
     */
    public static Image fromBytes(String id, byte[] data, String mediaType) {
        if (mediaType == null || mediaType.isEmpty()) {
            return fromBytes(id, data);
        }
        Objects.requireNonNull(data, "data");
        return new Image(id, data.clone(), mediaType, null);
    }

    /**
     * An image from bytes, its media type taken from the first bytes.
     *
     * @throws IllegalArgumentException when the bytes are not a JPEG, PNG or WebP image
     */
    public static Image fromBytes(String id, byte[] data) {
        String type = sniff(Objects.requireNonNull(data, "data"));
        if (type == null) {
            throw new IllegalArgumentException("image '" + id + "' is not a JPEG, PNG or WebP image");
        }
        return new Image(id, data.clone(), type, null);
    }

    /** An image the API fetches itself. The URL must be {@code https}. */
    public static Image fromUrl(String id, String url) {
        return new Image(id, null, null, Objects.requireNonNull(url, "url"));
    }

    public String id() {
        return id;
    }

    /** The media type, or null for an image given by URL. */
    public String mediaType() {
        return mediaType;
    }

    /** The URL, or null for an image given as bytes. */
    public String url() {
        return url;
    }

    /** The image's bytes, or null for an image given by URL. */
    public byte[] data() {
        return data == null ? null : data.clone();
    }

    /** The wire form: {@code {"id", "media_type", "data"}} or {@code {"id", "url"}}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        if (url != null) {
            m.put("url", url);
        } else {
            m.put("media_type", mediaType);
            m.put("data", Base64.getEncoder().encodeToString(data));
        }
        return m;
    }

    static String sniff(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xff) == 0xff && (d[1] & 0xff) == 0xd8 && (d[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (d.length >= 8 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && d[4] == 0x0d && d[5] == 0x0a && d[6] == 0x1a && d[7] == 0x0a) {
            return "image/png";
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    @Override
    public String toString() {
        return url != null ? "Image{id=" + id + ", url=" + url + "}" : "Image{id=" + id + ", " + mediaType + ", " + data.length + " bytes}";
    }
}
