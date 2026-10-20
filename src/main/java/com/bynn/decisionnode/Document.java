package com.bynn.decisionnode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The content of one document of a knowledge base (a collection of type {@code text}), for
 * {@link CollectionsClient#addDocument} and {@link CollectionsClient#replaceDocument}.
 *
 * <pre>{@code
 * Document.fromText("# Claims policy\n...");                  // Markdown, the default
 * Document.fromText("Plain words.", "text");                   // plain text
 * Document.fromPath(Path.of("policy.pdf"));                    // read when the call is made
 * Document.fromBytes(bytes, "application/pdf", "policy.pdf");
 * Document.fromStream(inputStream, null, "handbook.docx");     // read to its end, once
 * Document.fromUpload("upl_...");                              // a knowledge upload made before
 * }</pre>
 *
 * <p>A file is one of the knowledge types: {@code text/plain}, {@code text/markdown},
 * {@code application/pdf}, DOCX, PPTX, XLSX, {@code text/html}, {@code image/png},
 * {@code image/jpeg}, {@code image/tiff} or {@code image/webp}, at most 50 MB. Without a content
 * type it comes from the file name's extension, else from the first bytes; a DOCX, PPTX or XLSX
 * file and text cannot be told by their bytes, so they need a content type or a file name. A text
 * or Markdown file under the client's inline threshold that is UTF-8 goes inline, in one call;
 * every other file is uploaded first and named by its upload id. The file name defaults to a
 * path's base name; the title, unless given, to the file name without its last extension.
 */
public final class Document {
    /** The largest file a document may be, in bytes (50 MB). */
    static final long MAX_BYTES = 52_428_800L;

    /** The knowledge types, in the order the API lists them. */
    static final List<String> CONTENT_TYPES = List.of(
            "text/plain",
            "text/markdown",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/html",
            "image/png",
            "image/jpeg",
            "image/tiff",
            "image/webp");

    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("tif", "image/tiff"),
            Map.entry("tiff", "image/tiff"),
            Map.entry("webp", "image/webp"));

    private final String text;
    private final String format;
    private final Path path;
    private final byte[] data;
    private final InputStream stream;
    private final String contentType;
    private final String filename;
    private final String uploadId;
    /** A stream's bytes once read, so it is read only once. */
    private byte[] read;
    private long readSize = -1;

    private Document(String text, String format, Path path, byte[] data, InputStream stream, String contentType, String filename,
            String uploadId) {
        this.text = text;
        this.format = format;
        this.path = path;
        this.data = data;
        this.stream = stream;
        this.contentType = contentType == null || contentType.isEmpty() ? null : contentType;
        this.filename = filename == null || filename.isEmpty() ? null : filename;
        this.uploadId = uploadId;
    }

    /** Markdown held in memory, the API's default format; sent inline whatever its size. */
    public static Document fromText(String text) {
        return new Document(Objects.requireNonNull(text, "text"), null, null, null, null, null, null, null);
    }

    /** Text held in memory with its {@code format}, {@code markdown} or {@code text}, which is sent. */
    public static Document fromText(String text, String format) {
        return new Document(Objects.requireNonNull(text, "text"), Objects.requireNonNull(format, "format"), null, null, null, null,
                null, null);
    }

    /** A local file, read when the call is made; its type from its extension or first bytes. */
    public static Document fromPath(Path path) {
        return fromPath(path, null, null);
    }

    /** A local file. See {@link #fromPath(Path)}. */
    public static Document fromPath(String path) {
        return fromPath(Path.of(path));
    }

    /**
     * A local file with its content type and file name, each null to take it from the file: the
     * type from the extension or the first bytes, the name the path's base name.
     */
    public static Document fromPath(Path path, String contentType, String filename) {
        return new Document(null, null, Objects.requireNonNull(path, "path"), null, null, contentType, filename, null);
    }

    /** A file's bytes, its type from the first bytes. The bytes are copied. */
    public static Document fromBytes(byte[] data) {
        return fromBytes(data, null, null);
    }

    /**
     * A file's bytes with its content type and file name, each null when not known: the type then
     * comes from the name's extension or the first bytes. The bytes are copied.
     */
    public static Document fromBytes(byte[] data, String contentType, String filename) {
        return new Document(null, null, null, Objects.requireNonNull(data, "data").clone(), null, contentType, filename, null);
    }

    /** A readable stream, its type from the first bytes. See {@link #fromStream(InputStream, String, String)}. */
    public static Document fromStream(InputStream in) {
        return fromStream(in, null, null);
    }

    /**
     * A readable stream with its content type and file name, each null when not known. It is read
     * to its end once, into memory, when the call is made (the upload needs its size and checksums
     * first), and not closed; reading stops past 50 MB, and the call fails as a file over the
     * limit does.
     */
    public static Document fromStream(InputStream in, String contentType, String filename) {
        return new Document(null, null, null, null, Objects.requireNonNull(in, "in"), contentType, filename, null);
    }

    /** A knowledge upload made before ({@link DecisionNode#uploadDocuments}), sent as its upload id. */
    public static Document fromUpload(String uploadId) {
        return new Document(null, null, null, null, null, null, null, Objects.requireNonNull(uploadId, "uploadId"));
    }

    /** The text of a document held in memory, or null. */
    public String text() {
        return text;
    }

    /** The format given with the text, or null. */
    public String format() {
        return format;
    }

    /** The upload id given, or null. */
    public String uploadId() {
        return uploadId;
    }

    /** The file's path, or null. */
    public Path path() {
        return path;
    }

    /** The content type given, or null. */
    public String contentType() {
        return contentType;
    }

    /** The file name: the one given, else a path's base name; null when there is none. */
    public String filename() {
        if (filename != null) {
            return filename;
        }
        if (path != null && path.getFileName() != null) {
            return path.getFileName().toString();
        }
        return null;
    }

    boolean isText() {
        return text != null;
    }

    boolean isUpload() {
        return uploadId != null;
    }

    /** A file whose bytes this client reads: a path, bytes or a stream. */
    boolean isFile() {
        return text == null && uploadId == null;
    }

    // ------------------------------------------------------------------ a file, read

    /** A file document read whole and checked: its bytes, its knowledge type and its name. */
    record File(byte[] bytes, String contentType, String filename) {
        /** True for a text or Markdown file that goes inline: under the threshold, UTF-8 and no NUL. */
        String inlineText(long threshold) {
            if (!("text/plain".equals(contentType) || "text/markdown".equals(contentType)) || bytes.length >= threshold) {
                return null;
            }
            return utf8(bytes);
        }

        /** The title a file sent inline takes: its file name without its last extension; null without a name. */
        String stem() {
            if (filename == null) {
                return null;
            }
            int dot = filename.lastIndexOf('.');
            return dot > 0 ? filename.substring(0, dot) : filename;
        }
    }

    /**
     * Reads the file and checks it as the presign would: one of the knowledge types, at least 1
     * byte, at most 50 MB. A fault is the API's 400 {@code api_usage_error}, raised before anything
     * is sent.
     *
     * @throws IllegalArgumentException when the file cannot be read, or its type cannot be told
     *     from its name or its first bytes
     */
    synchronized File file(int index) {
        byte[] bytes;
        byte[] head;
        long size;
        if (data != null) {
            bytes = data;
            head = data;
            size = data.length;
        } else if (path != null) {
            try {
                size = Files.size(path);
                if (size > MAX_BYTES) {
                    // Over the limit: only its first bytes are read, to tell its type.
                    try (InputStream in = Files.newInputStream(path)) {
                        head = in.readNBytes(16);
                    }
                    bytes = null;
                } else {
                    bytes = Files.readAllBytes(path);
                    head = bytes;
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot read " + path + ": " + e, e);
            }
        } else {
            if (readSize < 0) {
                try {
                    read = stream.readNBytes((int) MAX_BYTES + 1);
                    readSize = read.length;
                } catch (IOException e) {
                    throw new IllegalArgumentException("cannot read the stream: " + e, e);
                }
            }
            size = readSize;
            head = read;
            bytes = readSize > MAX_BYTES ? null : read;
        }
        String type = contentType != null ? contentType : typeOf(filename(), head);
        if (type == null) {
            throw new IllegalArgumentException(
                    "the document's type cannot be told from its name or its first bytes: give its content type or file name");
        }
        if (!CONTENT_TYPES.contains(type)) {
            throw LocalChecks.usage("files[" + index + "].content_type: must be one of " + String.join(", ", CONTENT_TYPES) + ", got "
                    + type + ".", null);
        }
        checkSize(index, size);
        return new File(bytes, type, filename());
    }

    /** A text document as the file {@link DecisionNode#uploadDocuments} uploads: Markdown or plain text by its format. */
    File textFile(int index) {
        String type = "text".equals(format) ? "text/plain" : "text/markdown";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        checkSize(index, bytes.length);
        return new File(bytes, type, null);
    }

    private static void checkSize(int index, long size) {
        if (size < 1) {
            throw LocalChecks.usage("files[" + index + "].byte_size: must be an integer from 1 to " + MAX_BYTES + ".", null);
        }
        if (size > MAX_BYTES) {
            throw LocalChecks.usage("files[" + index + "].byte_size: " + size + " is over the limit of " + MAX_BYTES + " bytes.", null);
        }
    }

    /** The knowledge type of a file without one given: by its name's extension, else by its first bytes. */
    static String typeOf(String filename, byte[] bytes) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0) {
                String byName = EXTENSIONS.get(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
                if (byName != null) {
                    return byName;
                }
            }
        }
        return sniff(bytes);
    }

    /** PDF, PNG, JPEG, TIFF or WebP by their first bytes; null for anything else. */
    static String sniff(byte[] b) {
        if (starts(b, '%', 'P', 'D', 'F', '-')) {
            return "application/pdf";
        }
        if (starts(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (starts(b, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (starts(b, 'I', 'I', '*', 0) || starts(b, 'M', 'M', 0, '*')) {
            return "image/tiff";
        }
        if (b.length >= 12 && starts(b, 'R', 'I', 'F', 'F') && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static boolean starts(byte[] b, int... prefix) {
        if (b.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xff) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** The bytes as a string when they are UTF-8 with no NUL; null otherwise. */
    static String utf8(byte[] bytes) {
        for (byte x : bytes) {
            if (x == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        if (text != null) {
            return "Document(text, " + text.length() + " chars)";
        }
        if (uploadId != null) {
            return "Document(" + uploadId + ")";
        }
        return "Document(" + (path != null ? path : filename() != null ? filename() : "bytes") + ")";
    }
}
