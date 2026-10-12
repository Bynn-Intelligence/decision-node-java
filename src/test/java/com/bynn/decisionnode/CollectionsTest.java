package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A workspace's collections of faces. */
class CollectionsTest {
    record Seen(String method, String path, String body) {}

    record Reply(int status, String body) {}

    private final ConcurrentLinkedQueue<Seen> seen = new ConcurrentLinkedQueue<>();
    private volatile IntFunction<Reply> reply = n -> new Reply(200, "{\"id\":\"col_1\",\"type\":\"face\",\"name\":\"Staff\"}");
    private HttpServer server;
    private String url;

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
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getRawPath()
                + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()), body));
        Reply r = reply.apply(seen.size());
        byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("content-type", "application/json");
        ex.sendResponseHeaders(r.status(), out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private CollectionsClient collections() {
        return DecisionNode.builder().environment(k -> null).apiKey("dn_test").baseUrl(url)
                .waiter(ms -> CompletableFuture.completedFuture(null)).build().collections();
    }

    private static Map<String, Object> json(String text) throws IOException {
        return Json.asMap(Json.read(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void createSendsTheTypeAndTheNameAndReturnsTheObjectAsSent() throws IOException {
        Map<String, Object> c = collections().create("Staff");
        assertEquals("col_1", c.get("id"));
        Seen s = seen.peek();
        assertEquals("POST", s.method());
        assertEquals("/v1/collections", s.path());
        assertEquals(Map.of("type", "face", "name", "Staff"), json(s.body()));
    }

    @Test
    void createAndEnrollRetryOnlyOn429And529() {
        reply = n -> new Reply(500, "{\"detail\":{\"error_type\":\"api_error\",\"message\":\"x\"}}");
        assertThrows(ServerError.class, () -> collections().create("Staff"));
        assertEquals(1, seen.size());
        seen.clear();
        assertThrows(ServerError.class, () -> collections().enroll("col_1", Image.fromUrl("badge", "https://files.example.com/b.jpg"), "emp-1042"));
        assertEquals(1, seen.size());
        seen.clear();
        reply = n -> n == 1 ? new Reply(529, "{\"detail\":{\"error_type\":\"overloaded_error\",\"message\":\"x\"}}")
                : new Reply(200, "{\"id\":\"col_1\"}");
        assertEquals("col_1", collections().create("Staff").get("id"));
        assertEquals(2, seen.size());
        seen.clear();
        reply = n -> n == 1 ? new Reply(500, "{\"detail\":{\"error_type\":\"api_error\",\"message\":\"x\"}}")
                : new Reply(200, "{\"id\":\"col_1\"}");
        assertEquals("col_1", collections().get("col_1").get("id"));
        assertEquals(2, seen.size());
    }

    @Test
    void enrollSendsTheImageAsADecisionDoesAndTheLabelOnlyWhenGiven() throws IOException {
        reply = n -> new Reply(200, "{\"id\":\"itm_1\",\"reference\":\"emp-1042\"}");
        collections().enroll("col_1", Image.fromUrl("badge", "https://files.example.com/b.jpg"), "emp-1042");
        Map<String, Object> body = json(seen.poll().body());
        assertEquals(Map.of("id", "badge", "url", "https://files.example.com/b.jpg"), body.get("image"));
        assertEquals("emp-1042", body.get("reference"));
        assertFalse(body.containsKey("label"));
        collections().enroll("col_1", Image.fromUrl("badge", "https://files.example.com/b.jpg"), "emp-1042", "Reception");
        assertEquals("Reception", json(seen.peek().body()).get("label"));
        assertEquals("/v1/collections/col_1/items", seen.peek().path());
    }

    @Test
    void pathsQueriesAndTheList() {
        reply = n -> new Reply(200, "{\"collections\":[{\"id\":\"col_1\"},{\"id\":\"celebrities\"}]}");
        List<Map<String, Object>> all = collections().list();
        assertEquals(List.of("col_1", "celebrities"), all.stream().map(m -> m.get("id")).toList());
        reply = n -> new Reply(200, "{\"items\":[],\"offset\":10,\"limit\":5,\"total\":0,\"next_offset\":null}");
        collections().listItems("a b/c", 10, 5);
        collections().listItems("col_1");
        collections().deleteItem("col_1", "itm_1");
        collections().get("celebrities");
        List<String> paths = seen.stream().map(Seen::path).toList();
        assertEquals(List.of("/v1/collections", "/v1/collections/a%20b%2Fc/items?offset=10&limit=5", "/v1/collections/col_1/items",
                "/v1/collections/col_1/items/itm_1", "/v1/collections/celebrities"), paths);
    }

    @Test
    void theLocalChecksAreTheApis422Entries() {
        ValidationError type = assertThrows(ValidationError.class, () -> collections().create("Staff", "person"));
        assertEquals(Map.of("type", "literal_error", "loc", List.of("body", "type"), "msg", "Input should be 'face'",
                "input", "person", "ctx", Map.of("expected", "'face'")), type.entries().get(0));
        assertNull(type.status());
        assertEquals("string_too_short", entryType(() -> collections().create("")));
        assertEquals("string_too_long", entryType(() -> collections().create("x".repeat(81))));
        collections().create("𠀀".repeat(80));
        Image badge = Image.fromUrl("badge", "https://files.example.com/b.jpg");
        assertEquals("string_too_short", entryType(() -> collections().enroll("col_1", badge, "")));
        assertEquals("string_too_long", entryType(() -> collections().enroll("col_1", badge, "r".repeat(65))));
        ValidationError pattern = assertThrows(ValidationError.class, () -> collections().enroll("col_1", badge, "emp 1042"));
        Map<?, ?> e = (Map<?, ?>) pattern.entries().get(0);
        assertEquals("string_pattern_mismatch", e.get("type"));
        assertEquals(Map.of("pattern", "^[A-Za-z0-9_.:-]+$"), e.get("ctx"));
        assertEquals("string_too_long", entryType(() -> collections().enroll("col_1", badge, "emp-1042", "l".repeat(129))));
        BadRequestError limit = assertThrows(BadRequestError.class, () -> collections().listItems("col_1", null, 101));
        assertEquals("api_usage_error", limit.errorType());
        assertTrue(seen.stream().noneMatch(s -> s.method().equals("POST") && s.path().endsWith("/items")));
    }

    @Test
    void aConflictIsTheBaseError() {
        reply = n -> new Reply(409, "{\"detail\":{\"error_type\":\"collections_limit_reached\",\"message\":\"x\"}}");
        DecisionNodeError e = assertThrows(DecisionNodeError.class, () -> collections().create("Staff"));
        assertEquals(DecisionNodeError.class, e.getClass());
        assertEquals(409, e.status());
        assertInstanceOf(DecisionNodeError.class, e);
    }

    private static String entryType(Runnable call) {
        ValidationError e = assertThrows(ValidationError.class, call::run);
        return (String) ((Map<?, ?>) e.entries().get(0)).get("type");
    }
}
