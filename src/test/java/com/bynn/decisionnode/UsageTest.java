package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Per-key usage, and the spend cap's 429. */
class UsageTest {
    record Reply(int status, Map<String, String> headers, String body) {}

    private final ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
    private volatile IntFunction<Reply> reply = n -> new Reply(200, Map.of(), "{\"items\":[],\"next_cursor\":null}");
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
        ex.getRequestBody().readAllBytes();
        String q = ex.getRequestURI().getRawQuery();
        seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath() + (q == null ? "" : "?" + q));
        Reply r = reply.apply(seen.size());
        r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(r.status(), out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private DecisionNode client() {
        return DecisionNode.builder().environment(k -> null).apiKey("dn_test").baseUrl(url)
                .waiter(ms -> CompletableFuture.completedFuture(null)).build();
    }

    private static final String START = "2026-09-01T00:00:00Z";
    private static final String END = "2026-10-01T00:00:00Z";

    @Test
    void keySendsOnlyTheParametersGiven() {
        reply = n -> new Reply(200, Map.of(), "{\"key\":{\"id\":\"key_1\"},\"totals\":{\"requests\":3}}");
        Map<String, Object> usage = client().usage().key("key_00000000000000000000000000000d01", START, END, "day", null);
        assertEquals(Map.of("requests", 3), usage.get("totals"));
        client().usage().key("key_00000000000000000000000000000d01");
        assertEquals(List.of(
                "GET /v1/keys/key_00000000000000000000000000000d01/usage?start=2026-09-01T00%3A00%3A00Z&end=2026-10-01T00%3A00%3A00Z&granularity=day",
                "GET /v1/keys/key_00000000000000000000000000000d01/usage"), List.copyOf(seen));
    }

    @Test
    void listSortsLabelsAndRepeatsKeyIds() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("plan", "pro");
        labels.put("customer", "acme");
        client().usage().list(UsageQuery.builder(START, END).labels(labels).deviceId("drone-1")
                .keyIds(List.of("key_00000000000000000000000000000d01", "key_00000000000000000000000000000d02"))
                .scope("decide").status("all").includeEmpty(false).limit(50).cursor("c1").build());
        assertEquals("GET /v1/usage?start=2026-09-01T00%3A00%3A00Z&end=2026-10-01T00%3A00%3A00Z&label=customer%3Aacme"
                + "&label=plan%3Apro&device_id=drone-1&key_id=key_00000000000000000000000000000d01"
                + "&key_id=key_00000000000000000000000000000d02&scope=decide&status=all&include_empty=false&limit=50&cursor=c1",
                seen.peek());
    }

    @Test
    void allFollowsTheCursorUntilNull() {
        reply = n -> n == 1 ? new Reply(200, Map.of(), "{\"items\":[{\"key\":{\"id\":\"a\"}}],\"next_cursor\":\"c2\"}")
                : new Reply(200, Map.of(), "{\"items\":[{\"key\":{\"id\":\"b\"}},{\"key\":{\"id\":\"c\"}}],\"next_cursor\":null}");
        List<Map<String, Object>> rows = client().usage().all(UsageQuery.builder(START, END).limit(2).cursor("ignored").build());
        assertEquals(3, rows.size());
        List<String> requests = new ArrayList<>(seen);
        assertTrue(!requests.get(0).contains("cursor="), requests.get(0));
        assertTrue(requests.get(1).endsWith("&cursor=c2"), requests.get(1));
    }

    @Test
    void theApisQueryRulesAreCheckedFirst() {
        UsageClient u = client().usage();
        assertEquals("granularity is none, hour, day or month.",
                assertThrows(BadRequestError.class, () -> u.key("key_00000000000000000000000000000d01", null, null, "week", null)).getMessage());
        assertEquals("group_by is model or channel.",
                assertThrows(BadRequestError.class, () -> u.list(UsageQuery.builder(START, END).groupBy("device").build())).getMessage());
        assertEquals("A label filter is name:value, as labels are written.",
                assertThrows(BadRequestError.class, () -> u.list(UsageQuery.builder(START, END).label("Customer", "acme").build())).getMessage());
        assertEquals("device_id is 1 to 128 printable ASCII characters, not starting with a space.",
                assertThrows(BadRequestError.class, () -> u.list(UsageQuery.builder(START, END).deviceId(" drone").build())).getMessage());
        assertEquals("key_id is a key's id, key_ and 32 hex digits.",
                assertThrows(BadRequestError.class, () -> u.list(UsageQuery.builder(START, END).keyIds(List.of("key_ABC")).build())).getMessage());
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(String.format("key_%032x", i));
        }
        assertEquals("At most 100 key_id filters.",
                assertThrows(BadRequestError.class, () -> u.list(UsageQuery.builder(START, END).keyIds(many).build())).getMessage());
        assertEquals("scope is full, decide or keys.",
                assertThrows(BadRequestError.class, () -> u.list(UsageQuery.builder(START, END).scope("publishable").build())).getMessage());
        assertEquals("status is active, expired, revoked or all.",
                assertThrows(BadRequestError.class, () -> u.list(UsageQuery.builder(START, END).status("deleted").build())).getMessage());
        BadRequestError limit = assertThrows(BadRequestError.class, () -> u.list(UsageQuery.builder(START, END).limit(0).build()));
        assertEquals("limit is a whole number from 1 to 1000.", limit.getMessage());
        assertEquals("api_usage_error", limit.errorType());
        assertNull(limit.status());
        assertTrue(seen.isEmpty());
    }

    @Test
    void theSpendCapIsRaisedAtOnceButOther429sRetry() {
        reply = n -> new Reply(429, Map.of("Retry-After", "3600"),
                "{\"detail\":{\"error_type\":\"key_spend_cap_reached\",\"message\":\"This key reached its daily spend cap.\"}}");
        RateLimitError cap = assertThrows(RateLimitError.class, () -> client().usage().list(UsageQuery.builder(START, END).build()));
        assertEquals("key_spend_cap_reached", cap.errorType());
        assertEquals(1, seen.size());
        seen.clear();
        reply = n -> n == 1 ? new Reply(429, Map.of("retry-after-ms", "5"), "{\"detail\":{\"error_type\":\"rate_limit_error\",\"message\":\"x\"}}")
                : new Reply(200, Map.of(), "{\"items\":[],\"next_cursor\":null}");
        client().usage().list(UsageQuery.builder(START, END).build());
        assertEquals(2, seen.size());
    }
}
