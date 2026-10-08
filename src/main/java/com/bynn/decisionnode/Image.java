package com.bynn.decisionnode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An image sent with a decision, referred to by its id in the state or the questions.
 *
 * <pre>{@code
 * Image.fromUrl("scan", "https://example.com/scan.webp");     // recommended: nothing is uploaded
 * Image.fromPath("receipt", Path.of("receipt.jpg"));          // read now, checked before sending
 * Image.fromBytes("photo", bytes);                            // media type from the first bytes
 * Image.fromBase64("photo", base64Text);
 * Image.fromStream("photo", inputStream);
 * Image.fromUpload("photo", "upl_...");                       // an upload made before
 * }</pre>
 *
 * <p>An image by URL is the fastest form: the API fetches it, so nothing travels through this
 * client. The URL must be {@code https} and at most 2,048 characters.
 *
 * <p>Local bytes (a file, bytes, base64 text or a stream) are checked before any request: JPEG,
 * PNG or WebP by their first bytes (a media type you give must match them), at most 10 MB, and at
 * most 40 megapixels as read from the image header. A fault raises the API's own
 * {@link BadRequestError} before anything is sent. The request's local images then go inline as
 * base64 when they total under the client's inline threshold
 * ({@link DecisionNode#DEFAULT_INLINE_THRESHOLD} by default), and are uploaded once and named by
 * their upload id at or above it. Bytes uploaded before are named by their cached upload id
 * whatever their size. No image is resized or re-encoded.
 */
public final class Image {
    /** The largest image the API accepts, in bytes (10 MB). */
    static final int MAX_BYTES = 10 * 1024 * 1024;

    private final String id;
    private final byte[] data;
    private final long size;
    private final String declared;
    private final String mediaType;
    private final String url;
    private final String uploadId;
    private final int[] dimensions;
    private volatile byte[] sha256;

    private Image(String id, byte[] data, long size, String declared, String url, String uploadId) {
        this.id = Objects.requireNonNull(id, "id");
        this.data = data;
        this.size = size;
        this.declared = declared == null || declared.isEmpty() ? null : declared;
        this.url = url;
        this.uploadId = uploadId;
        if (data != null) {
            String found = ImageHeader.sniff(data);
            this.mediaType = found != null ? found : this.declared;
            this.dimensions = found != null ? ImageHeader.dimensions(data, found) : null;
        } else {
            this.mediaType = this.declared;
            this.dimensions = null;
        }
    }

    /**
     * Reads an image file now, when the {@code Image} is made. Its media type is taken from the
     * file's first bytes. A file over 10 MB is not read; the request that carries it fails with
     * {@link BadRequestError} before anything is sent.
     *
     * @throws IllegalArgumentException when the file cannot be read
     */
    public static Image fromPath(String id, Path path) {
        Objects.requireNonNull(path, "path");
        try {
            long size = Files.size(path);
            if (size > MAX_BYTES) {
                return new Image(id, null, size, null, null, null);
            }
            byte[] data = Files.readAllBytes(path);
            return new Image(id, data, data.length, null, null, null);
        } catch (IOException e) {
            throw new IllegalArgumentException("image '" + id + "': cannot read " + path + ": " + e, e);
        }
    }

    /** Reads an image file. See {@link #fromPath(String, Path)}. */
    public static Image fromPath(String id, String path) {
        return fromPath(id, Path.of(path));
    }

    /**
     * An image from bytes, with its media type: {@code image/jpeg}, {@code image/png} or
     * {@code image/webp}. A null or empty media type is taken from the first bytes; one given must
     * match them. The bytes are copied.
     */
    public static Image fromBytes(String id, byte[] data, String mediaType) {
        Objects.requireNonNull(data, "data");
        return new Image(id, data.clone(), data.length, mediaType, null, null);
    }

    /** An image from bytes, its media type taken from the first bytes. The bytes are copied. */
    public static Image fromBytes(String id, byte[] data) {
        return fromBytes(id, data, null);
    }

    /**
     * An image from base64 text (standard alphabet; line breaks are ignored).
     *
     * @throws IllegalArgumentException when the text is not base64
     */
    public static Image fromBase64(String id, String base64, String mediaType) {
        Objects.requireNonNull(base64, "base64");
        byte[] data;
        try {
            data = Base64.getMimeDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("image '" + id + "': not valid base64: " + e.getMessage(), e);
        }
        return new Image(id, data, data.length, mediaType, null, null);
    }

    /** An image from base64 text, its media type taken from the decoded bytes. */
    public static Image fromBase64(String id, String base64) {
        return fromBase64(id, base64, null);
    }

    /**
     * Reads an image from a stream now, to its end or just past 10 MB, whichever comes first. The
     * stream is not closed. A stream longer than 10 MB is not kept; the request that carries it
     * fails with {@link BadRequestError} before anything is sent.
     *
     * @throws IllegalArgumentException when the stream cannot be read
     */
    public static Image fromStream(String id, InputStream in, String mediaType) {
        Objects.requireNonNull(in, "in");
        try {
            byte[] data = in.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) {
                return new Image(id, null, data.length, mediaType, null, null);
            }
            return new Image(id, data, data.length, mediaType, null, null);
        } catch (IOException e) {
            throw new IllegalArgumentException("image '" + id + "': cannot read the stream: " + e, e);
        }
    }

    /** Reads an image from a stream, its media type taken from the first bytes. */
    public static Image fromStream(String id, InputStream in) {
        return fromStream(id, in, null);
    }

    /**
     * An image the API fetches itself, the recommended form: nothing is uploaded. Checked before
     * sending: {@code https} and at most 2,048 characters.
     */
    public static Image fromUrl(String id, String url) {
        return new Image(id, null, -1, null, Objects.requireNonNull(url, "url"), null);
    }

    /** An image uploaded before ({@link DecisionNode#upload(java.util.List)}), sent as its upload id. */
    public static Image fromUpload(String id, String uploadId) {
        return fromUpload(id, uploadId, null);
    }

    /** An image uploaded before, with the media type it was uploaded as. */
    public static Image fromUpload(String id, String uploadId, String mediaType) {
        return new Image(id, null, -1, mediaType, null, Objects.requireNonNull(uploadId, "uploadId"));
    }

    public String id() {
        return id;
    }

    /**
     * The media type: read from the bytes of a local image (or the one given, when the bytes are
     * not a known format), the one given for an upload id, null for a URL.
     */
    public String mediaType() {
        return mediaType;
    }

    /** The URL, or null for an image that is not given by URL. */
    public String url() {
        return url;
    }

    /** The upload id given, or null. */
    public String uploadId() {
        return uploadId;
    }

    /** A copy of the image's bytes, or null for an image given by URL or upload id. */
    public byte[] data() {
        return data == null ? null : data.clone();
    }

    /** True for an image held as local bytes (a file, bytes, base64 or a stream). */
    boolean isLocal() {
        return size >= 0;
    }

    /** The bytes, not copied; null for a URL, an upload id or an oversized image. */
    byte[] bytes() {
        return data;
    }

    /** The size in bytes of a local image, -1 otherwise. */
    long size() {
        return size;
    }

    String declared() {
        return declared;
    }

    /** The sniffed media type of local bytes; null when they are not JPEG, PNG or WebP. */
    String sniffed() {
        return data == null ? null : ImageHeader.sniff(data);
    }

    int[] dimensions() {
        return dimensions;
    }

    /** The SHA-256 of the bytes, computed once. */
    byte[] sha256() {
        byte[] h = sha256;
        if (h == null) {
            h = digest("SHA-256", data);
            sha256 = h;
        }
        return h;
    }

    static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }

    /**
     * The wire form as given: {@code {"id", "url"}}, {@code {"id", "upload_id"}} (with
     * {@code media_type} when one was given) or, for local bytes, the inline form
     * {@code {"id", "media_type", "data"}}.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        if (url != null) {
            m.put("url", url);
        } else if (uploadId != null) {
            m.put("upload_id", uploadId);
            if (mediaType != null) {
                m.put("media_type", mediaType);
            }
        } else {
            m.put("media_type", mediaType);
            m.put("data", data == null ? null : Base64.getEncoder().encodeToString(data));
        }
        return m;
    }

    /** The inline wire form; the JSON writer encodes the bytes as base64 without a copy. */
    Map<String, Object> inline(boolean withId) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (withId) {
            m.put("id", id);
        }
        m.put("media_type", mediaType);
        m.put("data", data);
        return m;
    }

    @Override
    public String toString() {
        if (url != null) {
            return "Image{id=" + id + ", url=" + url + "}";
        }
        if (uploadId != null) {
            return "Image{id=" + id + ", upload_id=" + uploadId + "}";
        }
        return "Image{id=" + id + ", " + mediaType + ", " + size + " bytes}";
    }
}
