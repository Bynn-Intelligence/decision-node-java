package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Knowledge bases: text collections, documents, waiting, search and the knowledge booster. */
class KnowledgeTest {
    record Seen(String method, String path, String body, byte[] bytes, String key) {}

    record Reply(int status, String body, Map<String, String> headers) {
        Reply(int status, String body) {
            this(status, body, Map.of());
        }
    }

    private final ConcurrentLinkedQueue<Seen> seen = new ConcurrentLinkedQueue<>();
    private final List<Long> waits = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<Seen, Reply> reply = s -> new Reply(202, "{\"id\":\"itm_1\",\"status\":\"queued\"}");
    private HttpServer server;
    private String url;

    @TempDir
    Path dir;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        Seen s = new Seen(ex.getRequestMethod(), ex.getRequestURI().getRawPath()
                + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()),
                new String(bytes, StandardCharsets.UTF_8), bytes, ex.getRequestHeaders().getFirst("Idempotency-Key"));
        seen.add(s);
        Reply r = s.path().startsWith("/storage/") ? new Reply(200, "") : reply.apply(s);
        byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("content-type", "application/json");
        r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.sendResponseHeaders(r.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            ex.getResponseBody().write(out);
        }
        ex.close();
    }

    private DecisionNode client() {
        return DecisionNode.builder().environment(k -> null).apiKey("dn_test").baseUrl(url)
                .waiter(ms -> {
                    waits.add(ms);
                    return CompletableFuture.completedFuture(null);
                }).build();
    }

    private CollectionsClient collections() {
        return client().collections();
    }

    private static Map<String, Object> json(String text) throws IOException {
        return Json.asMap(Json.read(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** A presign answer of one upload per file, each to this server's storage. */
    private Reply presigned(Seen s, int first) throws IOException {
        List<Object> files = Json.asList(json(s.body()).get("files"));
        StringBuilder out = new StringBuilder("{\"uploads\":[");
        for (int i = 0; i < files.size(); i++) {
            String id = "upl_" + String.format("%032d", first + i);
            out.append(i > 0 ? "," : "").append("{\"upload_id\":\"").append(id).append("\",\"purpose\":\"knowledge\",\"url\":\"")
                    .append(url).append("/storage/").append(id).append("\",\"method\":\"PUT\",\"headers\":{\"x-amz-test\":\"1\"},")
                    .append("\"expires_at\":\"2099-01-01T00:00:00Z\"}");
        }
        return new Reply(200, out.append("],\"expires_in\":300}").toString());
    }

    private List<Seen> api() {
        return seen.stream().filter(s -> !s.path().startsWith("/storage/")).toList();
    }

    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII);

    // ------------------------------------------------------------------ collections

    @Test
    void aTextCollectionIsCreatedWithItsScopeKey() throws IOException {
        reply = s -> new Reply(201, "{\"id\":\"col_1\",\"type\":\"text\",\"scope_key\":\"mission\"}");
        Map<String, Object> c = collections().create("Claims policy", "text", "mission");
        assertEquals("mission", c.get("scope_key"));
        Seen s = seen.peek();
        assertEquals(Map.of("type", "text", "name", "Claims policy", "scope_key", "mission"), json(s.body()));
        assertTrue(s.key() != null);
        seen.clear();
        collections().create("Policies", "text");
        assertEquals(Map.of("type", "text", "name", "Policies"), json(seen.peek().body()));
        seen.clear();
        ValidationError e = assertThrows(ValidationError.class, () -> collections().create("Policies", "text", "Mission"));
        assertEquals(Map.of("type", "string_pattern_mismatch", "loc", List.of("body", "scope_key"),
                "msg", "String should match pattern '^[a-z0-9_]{1,40}$'", "input", "Mission"), e.entries().get(0));
        assertTrue(seen.isEmpty());
    }

    // ------------------------------------------------------------------ documents

    @Test
    void textGoesInlineWithItsTitleAndFieldsUnderOneKey() throws IOException {
        Map<String, Object> item = collections().addDocument("col_1", Document.fromText("# Receipts\nAlways."),
                DocumentOptions.builder().title("Claims policy").reference("policy-2026").pinned(true).pinRank(2)
                        .label("Policy").metadata(Map.of("mission", "M-42")).build());
        assertEquals("queued", item.get("status"));
        Seen s = seen.peek();
        assertEquals("POST", s.method());
        assertEquals("/v1/collections/col_1/items", s.path());
        assertEquals(Map.of("title", "Claims policy", "text", "# Receipts\nAlways.", "reference", "policy-2026", "pinned", true,
                "pin_rank", 2, "label", "Policy", "metadata", Map.of("mission", "M-42")), json(s.body()));
        assertTrue(s.key() != null);
        seen.clear();
        collections().addDocument("col_1", Document.fromText("Plain.", "text"),
                DocumentOptions.builder().title("Note").waitUntilReady(true).build());
        assertEquals("/v1/collections/col_1/items?wait=true", seen.peek().path());
        assertEquals(Map.of("title", "Note", "text", "Plain.", "format", "text"), json(seen.peek().body()));
    }

    @Test
    void aRetriedAddKeepsItsKeyAndANewCallTakesANewOne() {
        reply = s -> seen.size() == 1 ? new Reply(500, "{\"detail\":{\"error_type\":\"api_error\",\"message\":\"x\"}}")
                : new Reply(202, "{\"id\":\"itm_1\"}");
        CollectionsClient c = collections();
        c.addDocument("col_1", Document.fromText("x"), DocumentOptions.builder().title("T").build());
        List<String> keys = seen.stream().map(Seen::key).toList();
        assertEquals(2, keys.size());
        assertEquals(keys.get(0), keys.get(1));
        c.replaceDocument("col_1", "itm_1", Document.fromText("y"), DocumentOptions.builder().title("T").build());
        Seen last = new ArrayList<>(seen).get(2);
        assertEquals("PUT", last.method());
        assertEquals("/v1/collections/col_1/items/itm_1", last.path());
        assertNotEquals(keys.get(0), last.key());
    }

    @Test
    void theLocalChecksSendNothing() {
        CollectionsClient c = collections();
        Map<String, Object> missing = new java.util.LinkedHashMap<>();
        missing.put("type", "missing");
        missing.put("loc", List.of("body", "title"));
        missing.put("msg", "Field required");
        missing.put("input", null);
        assertEquals(missing, assertThrows(ValidationError.class, () -> c.addDocument("col_1", Document.fromText("x"))).entries().get(0));
        ValidationError empty = assertThrows(ValidationError.class,
                () -> c.addDocument("col_1", Document.fromText(""), DocumentOptions.builder().title("T").build()));
        assertEquals(Map.of("type", "string_too_short", "loc", List.of("body", "text"), "msg", "The text is empty", "input", ""),
                empty.entries().get(0));
        ValidationError title = assertThrows(ValidationError.class,
                () -> c.addDocument("col_1", Document.fromText("x"), DocumentOptions.builder().title("  ").build()));
        assertEquals("A title is 1 to 200 characters", ((Map<?, ?>) title.entries().get(0)).get("msg"));
        c.addDocument("col_1", Document.fromText("x"), DocumentOptions.builder().title("𠀀".repeat(200)).build());
        seen.clear();
        assertEquals("string_too_long", entryType(() -> c.addDocument("col_1", Document.fromText("x"),
                DocumentOptions.builder().title("𠀀".repeat(201)).build())));
        assertEquals("int_range", entryType(() -> c.addDocument("col_1", Document.fromText("x"),
                DocumentOptions.builder().title("T").pinRank(1001).build())));
        assertEquals("literal_error", entryType(() -> c.addDocument("col_1", Document.fromText("x", "html"),
                DocumentOptions.builder().title("T").build())));
        assertEquals("string_pattern_mismatch", entryType(() -> c.addDocument("col_1", Document.fromText("x"),
                DocumentOptions.builder().title("T").reference("a b").build())));
        assertEquals("int_range", entryType(() -> c.updateItem("col_1", "itm_1", ItemUpdate.builder().pinRank(-1).build())));
        assertEquals("string_too_long", entryType(() -> c.updateItem("col_1", "itm_1", ItemUpdate.builder().title("").build())));
        assertThrows(BadRequestError.class, () -> c.listChunks("col_1", "itm_1", -1, null));
        assertThrows(BadRequestError.class, () -> c.listChunks("col_1", "itm_1", null, 101));
        assertTrue(seen.isEmpty(), "nothing sent: " + seen);
    }

    private static String entryType(Runnable call) {
        try {
            call.run();
        } catch (ValidationError e) {
            return (String) ((Map<?, ?>) e.entries().get(0)).get("type");
        }
        throw new AssertionError("no ValidationError");
    }

    @Test
    void aSmallTextFileGoesInlineTitledByItsStem() throws IOException {
        Path md = dir.resolve("Claims policy.v2.md");
        Files.writeString(md, "# Receipts\n");
        collections().addDocument("col_1", Document.fromPath(md));
        assertEquals(Map.of("title", "Claims policy.v2", "text", "# Receipts\n", "format", "markdown"), json(seen.peek().body()));
        seen.clear();
        collections().addDocument("col_1", Document.fromBytes("Plain.".getBytes(StandardCharsets.UTF_8), "text/plain", null),
                DocumentOptions.builder().title("Note").build());
        assertEquals(Map.of("title", "Note", "text", "Plain.", "format", "text"), json(seen.peek().body()));
        seen.clear();
        assertThrows(ValidationError.class, () -> collections().addDocument("col_1",
                Document.fromBytes("Plain.".getBytes(StandardCharsets.UTF_8), "text/plain", null)));
        assertTrue(seen.isEmpty());
    }

    @Test
    void aFileIsUploadedWithTheKnowledgePurposeThenNamedByItsUploadId() throws IOException {
        reply = s -> {
            try {
                return s.path().equals("/v1/uploads") ? presigned(s, 1) : new Reply(202, "{\"id\":\"itm_1\",\"status\":\"queued\"}");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        };
        Path pdf = dir.resolve("policy.pdf");
        Files.write(pdf, PDF);
        collections().addDocument("col_1", Document.fromPath(pdf), DocumentOptions.builder().reference("policy").build());
        List<Seen> all = new ArrayList<>(seen);
        assertEquals(3, all.size());
        Map<String, Object> file = Json.asMap(Json.asList(json(all.get(0).body()).get("files")).get(0));
        assertEquals("knowledge", file.get("purpose"));
        assertEquals("application/pdf", file.get("content_type"));
        assertEquals(PDF.length, ((Number) file.get("byte_size")).intValue());
        assertEquals("policy.pdf", file.get("filename"));
        assertEquals(Base64.getEncoder().encodeToString(Image.digest("MD5", PDF)), file.get("checksum"));
        assertEquals(Base64.getEncoder().encodeToString(Image.digest("SHA-256", PDF)), file.get("sha256"));
        assertEquals("PUT", all.get(1).method());
        assertTrue(java.util.Arrays.equals(PDF, all.get(1).bytes()));
        assertNull(all.get(1).key());
        assertEquals(Map.of("upload_id", "upl_" + String.format("%032d", 1), "reference", "policy"), json(all.get(2).body()));
        assertTrue(all.get(2).key() != null);
    }

    @Test
    void textThatIsNotUtf8OrAtTheThresholdIsUploaded() throws IOException {
        reply = s -> {
            try {
                return s.path().equals("/v1/uploads") ? presigned(s, 1) : new Reply(202, "{\"id\":\"itm_1\"}");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        };
        DecisionNode dn = DecisionNode.builder().environment(k -> null).apiKey("dn_test").baseUrl(url).inlineThreshold(8).build();
        dn.collections().addDocument("col_1", Document.fromBytes("1234567".getBytes(StandardCharsets.UTF_8), "text/plain", "a.txt"));
        assertEquals(1, api().size());
        seen.clear();
        dn.collections().addDocument("col_1", Document.fromBytes("12345678".getBytes(StandardCharsets.UTF_8), "text/plain", "a.txt"));
        assertEquals("/v1/uploads", api().get(0).path());
        assertEquals(Map.of("upload_id", "upl_" + String.format("%032d", 1)), json(api().get(1).body()));
        seen.clear();
        dn.collections().addDocument("col_1", Document.fromBytes(new byte[] {(byte) 0xC3, 0x28}, "text/markdown", "a.md"));
        assertEquals("/v1/uploads", api().get(0).path());
    }

    @Test
    void aStaleUploadThisCallPlacedIsUploadedAgainOnceUnderANewKey() {
        String stale = "{\"detail\":{\"error_type\":\"upload_expired\",\"message\":\"The upload expired.\"}}";
        int[] items = {0};
        reply = s -> {
            try {
                if (s.path().equals("/v1/uploads")) {
                    return presigned(s, seen.size());
                }
                return ++items[0] == 1 ? new Reply(400, stale) : new Reply(202, "{\"id\":\"itm_1\"}");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        };
        collections().addDocument("col_1", Document.fromBytes(PDF, null, "p.pdf"));
        List<Seen> calls = api().stream().filter(s -> s.path().endsWith("/items")).toList();
        assertEquals(2, calls.size());
        assertNotEquals(calls.get(0).key(), calls.get(1).key());
        assertEquals(2, api().stream().filter(s -> s.path().equals("/v1/uploads")).count());

        seen.clear();
        reply = s -> {
            try {
                return s.path().equals("/v1/uploads") ? presigned(s, 1) : new Reply(400, stale);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        };
        BadRequestError twice = assertThrows(BadRequestError.class, () -> collections().addDocument("col_1", Document.fromBytes(PDF)));
        assertEquals("upload_expired", twice.errorType());
        assertEquals(4, api().size());

        seen.clear();
        BadRequestError given = assertThrows(BadRequestError.class,
                () -> collections().addDocument("col_1", Document.fromUpload("upl_given")));
        assertEquals("upload_expired", given.errorType());
        assertEquals(1, api().size());

        seen.clear();
        reply = s -> {
            try {
                return s.path().equals("/v1/uploads") ? presigned(s, 1)
                        : new Reply(400, "{\"detail\":{\"error_type\":\"upload_purpose_mismatch\",\"message\":\"x\"}}");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        };
        assertEquals("upload_purpose_mismatch",
                assertThrows(BadRequestError.class, () -> collections().addDocument("col_1", Document.fromBytes(PDF))).errorType());
        assertEquals(2, api().size());
    }

    @Test
    void aFileIsCheckedBeforeAnythingIsSent() throws IOException {
        BadRequestError empty = assertThrows(BadRequestError.class,
                () -> collections().addDocument("col_1", Document.fromBytes(new byte[0], "application/pdf", "a.pdf")));
        assertEquals("files[0].byte_size: must be an integer from 1 to 52428800.", empty.getMessage());
        assertEquals("api_usage_error", empty.errorType());
        assertThrows(IllegalArgumentException.class,
                () -> collections().addDocument("col_1", Document.fromBytes(new byte[] {'P', 'K', 3, 4})));
        BadRequestError type = assertThrows(BadRequestError.class,
                () -> collections().addDocument("col_1", Document.fromBytes(new byte[] {'P', 'K', 3, 4}, "application/zip", null)));
        assertEquals("files[0].content_type: must be one of text/plain, text/markdown, application/pdf, "
                + "application/vnd.openxmlformats-officedocument.wordprocessingml.document, "
                + "application/vnd.openxmlformats-officedocument.presentationml.presentation, "
                + "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, text/html, image/png, image/jpeg, image/tiff, "
                + "image/webp, got application/zip.", type.getMessage());
        Path big = dir.resolve("big.pdf");
        try (RandomAccessFile f = new RandomAccessFile(big.toFile(), "rw")) {
            f.setLength(52_428_801L);
        }
        BadRequestError size = assertThrows(BadRequestError.class, () -> collections().addDocument("col_1", Document.fromPath(big)));
        assertEquals("files[0].byte_size: 52428801 is over the limit of 52428800 bytes.", size.getMessage());
        assertTrue(seen.isEmpty());
    }

    @Test
    void theContentTypeComesFromTheNameThenTheBytes() {
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", Document.typeOf("A.DOCX", new byte[0]));
        assertEquals("text/markdown", Document.typeOf("a.markdown", new byte[0]));
        assertEquals("text/html", Document.typeOf("a.htm", new byte[0]));
        assertEquals("image/tiff", Document.typeOf("scan.tif", new byte[0]));
        assertEquals("application/pdf", Document.typeOf("noext", PDF));
        assertEquals("image/png", Document.sniff(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}));
        assertEquals("image/jpeg", Document.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}));
        assertEquals("image/tiff", Document.sniff(new byte[] {'I', 'I', '*', 0}));
        assertEquals("image/tiff", Document.sniff(new byte[] {'M', 'M', 0, '*'}));
        assertEquals("image/webp", Document.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1)));
        assertNull(Document.sniff("PK\u0003\u0004".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void aStreamIsReadOnceAndUploadDocumentsReturnsOneIdEach() throws IOException {
        reply = s -> {
            try {
                return presigned(s, 1);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        };
        List<String> ids = client().uploadDocuments(List.of(Document.fromStream(new ByteArrayInputStream(PDF)),
                Document.fromText("# Policy")));
        assertEquals(List.of("upl_" + String.format("%032d", 1), "upl_" + String.format("%032d", 2)), ids);
        List<Seen> api = api();
        assertEquals(1, api.size());
        List<Object> files = Json.asList(Json.asMap(Json.read(api.get(0).bytes())).get("files"));
        assertEquals("application/pdf", Json.asMap(files.get(0)).get("content_type"));
        assertEquals("text/markdown", Json.asMap(files.get(1)).get("content_type"));
        assertFalse(Json.asMap(files.get(1)).containsKey("filename"));
    }

    // ------------------------------------------------------------------ reads

    @Test
    void deleteFailedItemsDeletesEveryFailedDocument() {
        reply = s -> new Reply(200, "{\"deleted\":3}");
        assertEquals(Map.of("deleted", 3), collections().deleteFailedItems("col_1"));
        assertEquals("DELETE", seen.peek().method());
        assertEquals("/v1/collections/col_1/items?status=failed", seen.peek().path());
        assertNull(seen.peek().key());
    }

    @Test
    void readsSendTheirQueryOnlyWhenAsked() {
        reply = s -> new Reply(200, "{\"id\":\"itm_1\"}");
        CollectionsClient c = collections();
        c.getItem("col_1", "itm_1");
        c.getItem("col_1", "itm_1", true);
        c.listChunks("col_1", "itm_1");
        c.listChunks("col_1", "itm_1", 20, 10);
        assertEquals(List.of("/v1/collections/col_1/items/itm_1", "/v1/collections/col_1/items/itm_1?include=text",
                "/v1/collections/col_1/items/itm_1/chunks", "/v1/collections/col_1/items/itm_1/chunks?offset=20&limit=10"),
                seen.stream().map(Seen::path).toList());
    }

    @Test
    void waitUntilReadyPollsWithAGrowingInterval() {
        reply = s -> new Reply(200, seen.size() < 3 ? "{\"id\":\"itm_1\",\"status\":\"processing\"}"
                : "{\"id\":\"itm_1\",\"status\":\"ready\"}");
        assertEquals("ready", collections().waitUntilReady("col_1", "itm_1").get("status"));
        assertEquals(3, seen.size());
        assertEquals(List.of(1000L, 1500L), waits);

        seen.clear();
        waits.clear();
        reply = s -> new Reply(200, "{\"id\":\"itm_1\",\"status\":\"failed\",\"error\":\"unsupported_type\"}");
        assertEquals("unsupported_type", collections().waitUntilReady("col_1", "itm_1").get("error"));
        assertTrue(waits.isEmpty());

        seen.clear();
        reply = s -> new Reply(200, seen.size() < 2 ? "{\"id\":\"col_1\",\"status_counts\":{\"queued\":1,\"processing\":0}}"
                : "{\"id\":\"col_1\",\"status_counts\":{\"queued\":0,\"processing\":0,\"ready\":3}}");
        assertEquals("col_1", collections().waitUntilReady("col_1").get("id"));
        assertEquals("/v1/collections/col_1", seen.peek().path());

        seen.clear();
        reply = s -> new Reply(200, "{\"id\":\"itm_1\",\"status\":\"uploading\"}");
        // The timeout is a wall-clock deadline from the call: with real waits of 0.2 s and 0.3 s, the
        // third wait is cut to the 0.6 s deadline, and the read made at it raises.
        DecisionNode real = DecisionNode.builder().environment(k -> null).apiKey("dn_test").baseUrl(url).build();
        long started = System.nanoTime();
        assertThrows(TimeoutError.class, () -> real.collections().waitUntilReady("col_1", "itm_1", java.time.Duration.ofMillis(600),
                java.time.Duration.ofMillis(200)));
        long ms = (System.nanoTime() - started) / 1_000_000;
        assertTrue(ms >= 600 && ms < 3000, ms + " ms");
        assertEquals(4, seen.size());

        seen.clear();
        assertThrows(IllegalArgumentException.class,
                () -> collections().waitUntilReady("col_1", "itm_1", null, java.time.Duration.ofMillis(99)));
        assertThrows(IllegalArgumentException.class, () -> collections().waitUntilReady("col_1", "itm_1", java.time.Duration.ZERO, null));
        assertTrue(seen.isEmpty());
    }

    // ------------------------------------------------------------------ search and the booster

    private static final String READING = "{\"collection\":\"col_1\",\"collection_name\":\"Claims policy\",\"corpus\":{\"version\":41},"
            + "\"query\":{\"source\":\"request\",\"tokens\":9,\"text\":\"receipts\"},"
            + "\"budget\":{\"mode\":\"auto\",\"tokens\":96,\"entry\":\"short\",\"cause\":null},"
            + "\"scope\":{\"key\":\"mission\",\"value\":\"M-42\"},\"warnings\":[\"budget_floor\"],"
            + "\"passages\":[{\"n\":1,\"pinned\":true,\"item\":\"itm_1\",\"reference\":\"policy\",\"title\":\"Policy\",\"heading\":\"Receipts\","
            + "\"chunk\":3,\"byte_start\":1840,\"byte_end\":2310,\"tokens\":96,\"score\":null,\"lexical\":false,\"text\":\"Reject.\"},"
            + "{\"n\":2,\"pinned\":false,\"item\":\"itm_2\",\"reference\":null,\"title\":null,\"heading\":null,"
            + "\"chunk\":0,\"byte_start\":0,\"byte_end\":12,\"tokens\":4,\"score\":0.8123,\"lexical\":true,\"text\":\"Photos.\"}],"
            + "\"omitted\":{\"over_budget\":1},\"pending_items\":0,\"search\":{\"plan\":\"exact\",\"candidates\":50,\"fallback\":false}}";

    @Test
    void searchSendsTheOptionsAsGivenAndReadsTheAnswer() throws IOException {
        reply = s -> new Reply(200, READING);
        KnowledgeReading r = collections().search("col_1", "receipts", SearchOptions.builder().scope("M-42")
                .match(Map.of("region", "SE")).filter(Map.of("key", "year", "op", "gte", "value", 2026)).topK(5).pinned(false)
                .maxTokens("auto").build());
        Seen s = seen.peek();
        assertEquals("/v1/collections/col_1/search", s.path());
        assertNull(s.key());
        assertEquals(Map.of("query", "receipts", "scope", "M-42", "match", Map.of("region", "SE"),
                "filter", Map.of("key", "year", "op", "gte", "value", 2026), "top_k", 5, "pinned", false, "max_tokens", "auto"),
                json(s.body()));
        assertEquals("Claims policy", r.collectionName());
        assertEquals(41L, r.corpus().version());
        assertEquals("receipts", r.query().text());
        assertEquals("mission", r.scope().key());
        assertEquals("M-42", r.scope().value());
        assertEquals(List.of("budget_floor"), r.warnings());
        assertEquals(2, r.passages().size());
        KnowledgePassage pin = r.passages().get(0);
        assertTrue(pin.pinned());
        assertNull(pin.score());
        assertEquals(1840L, pin.byteStart());
        assertEquals(0.8123, r.passages().get(1).score());
        assertEquals("Photos.", r.passages().get(1).text());
        assertNull(r.truncated());
        assertNull(r.passages().get(1).rendered());
        seen.clear();
        collections().search("col_1", "receipts");
        assertEquals(Map.of("query", "receipts"), json(seen.peek().body()));
    }

    @Test
    void theKnowledgeBoosterIsAlwaysTheObjectForm() {
        assertEquals(Map.of("name", "knowledge", "options", Map.of("collection", "col_1")), Booster.knowledge("col_1").wire());
        Booster all = KnowledgeBooster.of("col_1").query("q").scope("M-42").match(Map.of("a", 1)).filter(Map.of("and", List.of()))
                .topK(0).maxTokens(512).pinned(false).minScore(0.5).includeText(true).require(true).build();
        Map<String, Object> wire = all.toMap();
        assertEquals(List.of("name", "require", "options"), new ArrayList<>(wire.keySet()));
        assertEquals(true, wire.get("require"));
        assertEquals(List.of("collection", "query", "scope", "match", "filter", "top_k", "max_tokens", "pinned", "min_score",
                "include_text"), new ArrayList<>(((Map<?, ?>) wire.get("options")).keySet()));
        assertFalse(KnowledgeBooster.of("col_1").require(false).build().toMap().containsKey("require"));
    }

    @Test
    void aKnowledgeReadingIsTypedAndAMalformedOneIsGeneric() throws IOException {
        Reading r = Responses.reading("knowledge", json(READING));
        assertInstanceOf(KnowledgeReading.class, r);
        assertEquals("knowledge", r.booster());
        Map<String, Object> noItem = json(READING.replace("\"item\":\"itm_2\",", ""));
        assertInstanceOf(UnknownReading.class, Responses.reading("knowledge", noItem));
        Map<String, Object> noCollection = json(READING.replace("\"collection\":\"col_1\",", ""));
        assertInstanceOf(UnknownReading.class, Responses.reading("knowledge", noCollection));
        Map<String, Object> floatChunk = json(READING.replace("\"chunk\":3", "\"chunk\":3.5"));
        assertInstanceOf(UnknownReading.class, Responses.reading("knowledge", floatChunk));
    }

    // ------------------------------------------------------------------ errors

    @Test
    void anIngestionDelayIsRaisedAtOnceAndAPlainKnowledge529IsRetried() {
        reply = s -> new Reply(529, "{\"detail\":{\"error_type\":\"booster_unavailable\",\"message\":\"x\","
                + "\"details\":{\"reason\":\"ingestion_delayed\"}}}", Map.of("retry-after", "60"));
        OverloadedError e = assertThrows(OverloadedError.class, () -> client().decide(DecideRequest.builder()
                .booster(Booster.knowledge("col_1")).question("q", Questions.truth("Is it allowed?")).build()));
        assertEquals("ingestion_delayed", e.details().get("reason"));
        assertEquals(1, seen.size());
        seen.clear();
        waits.clear();
        reply = s -> new Reply(529, "{\"detail\":{\"error_type\":\"booster_unavailable\",\"message\":\"x\"}}",
                Map.of("retry-after", "5"));
        assertThrows(OverloadedError.class, () -> client().decide(DecideRequest.builder()
                .booster(Booster.knowledge("col_1")).question("q", Questions.truth("Is it allowed?")).build()));
        assertEquals(3, seen.size());
        assertEquals(List.of(5000L, 5000L), waits);
    }
}
