package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Device keys: create, list, update, revoke and revoke by labels. */
class KeysTest {
    record Seen(String method, String target, String body, String key) {}

    record Reply(int status, String body) {}

    private static final Pattern UUID_V4 = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final String KEY = "key_7c2e9a41b0d34f6e8a5c1d2b3e4f5a60";

    private final ConcurrentLinkedQueue<Seen> seen = new ConcurrentLinkedQueue<>();
    private volatile IntFunction<Reply> reply = n -> new Reply(200, "{}");
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
        String q = ex.getRequestURI().getRawQuery();
        seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getRawPath() + (q == null ? "" : "?" + q), body,
                ex.getRequestHeaders().getFirst("Idempotency-Key")));
        Reply r = reply.apply(seen.size());
        byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("content-type", "application/json");
        ex.sendResponseHeaders(r.status(), out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private KeysClient keys() {
        return DecisionNode.builder().environment(k -> null).apiKey("dn_test").baseUrl(url)
                .waiter(ms -> CompletableFuture.completedFuture(null)).build().keys();
    }

    private static Map<String, Object> json(String text) throws IOException {
        return Json.asMap(Json.read(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void createSendsOnlyTheFieldsGivenUnderOneKeyAcrossAttempts() throws IOException {
        reply = n -> n == 1 ? new Reply(409, "{\"detail\":{\"error_type\":\"idempotency_in_progress\",\"message\":\"x\"}}")
                : new Reply(201, "{\"key\":{\"id\":\"" + KEY + "\"},\"secret\":\"dn_live_x\"}");
        Map<String, Object> made = keys().create(KeyCreate.builder("Kiosk 0412").deviceId("kiosk-0412").label("store", "118")
                .dailySpendCap(2.0).build());
        assertEquals("dn_live_x", made.get("secret"));
        List<Seen> both = new ArrayList<>(seen);
        assertEquals(2, both.size());
        assertEquals("POST", both.get(0).method());
        assertEquals("/v1/keys", both.get(0).target());
        assertTrue(UUID_V4.matcher(both.get(0).key()).matches(), both.get(0).key());
        assertEquals(both.get(0).key(), both.get(1).key());
        assertEquals(Map.of("name", "Kiosk 0412", "device_id", "kiosk-0412", "labels", Map.of("store", "118"),
                "daily_spend_cap", Map.of("usd", 2.0)), json(both.get(0).body()));
    }

    @Test
    void createTakesTheCallersKeyAndChecksIt() {
        reply = n -> new Reply(201, "{\"key\":{},\"secret\":\"s\"}");
        keys().create(KeyCreate.builder("a").idempotencyKey("provision-kiosk-0412").build());
        assertEquals("provision-kiosk-0412", seen.peek().key());
        seen.clear();
        for (String bad : new String[] {"", "x".repeat(256), "café"}) {
            BadRequestError e = assertThrows(BadRequestError.class,
                    () -> keys().create(KeyCreate.builder("a").idempotencyKey(bad).build()));
            assertEquals("Idempotency-Key must be sent once, as 1 to 255 printable ASCII characters.", e.getMessage());
            assertEquals("api_usage_error", e.errorType());
        }
        assertTrue(seen.isEmpty());
    }

    @Test
    void listAndAll() {
        reply = n -> n == 1 ? new Reply(200, "{\"items\":[{\"id\":\"a\"}],\"next_cursor\":\"c2\"}")
                : new Reply(200, "{\"items\":[{\"id\":\"b\"}],\"next_cursor\":null}");
        List<Map<String, Object>> all = keys().all(KeyListQuery.builder().label("store", "118").label("fw", "2.4")
                .status("all").limit(200).build());
        assertEquals(2, all.size());
        List<Seen> requests = new ArrayList<>(seen);
        assertEquals("/v1/keys?label=fw%3A2.4&label=store%3A118&status=all&limit=200", requests.get(0).target());
        assertTrue(requests.get(1).target().endsWith("&cursor=c2"));
        seen.clear();
        reply = n -> new Reply(200, "{\"items\":[],\"next_cursor\":null}");
        keys().list(KeyListQuery.builder().build());
        assertEquals("/v1/keys", seen.peek().target());
        assertNull(seen.peek().key(), "reads carry no key");
    }

    @Test
    void theListsQueryIsCheckedFirst() {
        assertEquals("limit is a whole number from 1 to 200.",
                assertThrows(BadRequestError.class, () -> keys().list(KeyListQuery.builder().limit(201).build())).getMessage());
        assertEquals("status is active, expired, revoked or all.",
                assertThrows(BadRequestError.class, () -> keys().list(KeyListQuery.builder().status("deleted").build())).getMessage());
        assertEquals("A label filter is name:value, as labels are written.",
                assertThrows(BadRequestError.class, () -> keys().list(KeyListQuery.builder().label("Store", "1").build())).getMessage());
        assertEquals("device_id is 1 to 128 printable ASCII characters, not starting with a space.",
                assertThrows(BadRequestError.class, () -> keys().list(KeyListQuery.builder().deviceId("").build())).getMessage());
        assertTrue(seen.isEmpty());
    }

    @Test
    void updateSendsTheFieldsSetAndNullToClear() throws IOException {
        reply = n -> new Reply(200, "{\"id\":\"" + KEY + "\",\"name\":\"Kiosk 12\"}");
        keys().update(KEY, KeyUpdate.builder().name("Kiosk 12").clearLabels().clearLimits().clearDailySpendCap().clearExpiresAt()
                .maySearchCollections(true).build());
        Seen s = seen.peek();
        assertEquals("PATCH", s.method());
        assertEquals("/v1/keys/" + KEY, s.target());
        Map<String, Object> body = json(s.body());
        assertEquals(6, body.size());
        assertEquals("Kiosk 12", body.get("name"));
        for (String k : List.of("labels", "limits", "daily_spend_cap", "expires_at")) {
            assertTrue(body.containsKey(k) && body.get(k) == null, k);
        }
        assertEquals(true, body.get("may_search_collections"));
        assertEquals("Nothing to change.",
                assertThrows(BadRequestError.class, () -> keys().update(KEY, KeyUpdate.builder().build())).getMessage());
    }

    @Test
    void revokeAndRevokeByLabelsRetryAsAnyCall() throws IOException {
        reply = n -> n == 1 ? new Reply(500, "{\"detail\":{\"error_type\":\"api_error\",\"message\":\"x\"}}")
                : new Reply(200, "{\"id\":\"" + KEY + "\",\"revoked\":true}");
        assertEquals(true, keys().revoke(KEY).get("revoked"));
        assertEquals(2, seen.size());
        assertEquals("DELETE", seen.peek().method());
        seen.clear();
        reply = n -> new Reply(200, "{\"would_revoke\":3,\"more\":false}");
        assertEquals(3, keys().revokeByLabels(Map.of("store", "118"), List.of("kiosk-0412"), true).get("would_revoke"));
        assertEquals(Map.of("labels", Map.of("store", "118"), "device_ids", List.of("kiosk-0412"), "dry_run", true),
                json(seen.peek().body()));
        seen.clear();
        keys().revokeByLabels(Map.of("store", "118"), null, null);
        assertFalse(json(seen.peek().body()).containsKey("dry_run"));
        assertEquals("/v1/keys/revoke", seen.peek().target());
    }

    private static Map<?, ?> firstEntry(Runnable call) {
        ValidationError e = assertThrows(ValidationError.class, call::run);
        assertEquals(1, e.entries().size(), "only the first fault");
        assertNull(e.status());
        return (Map<?, ?>) e.entries().get(0);
    }

    @Test
    void theKeysFixedRulesAreCheckedFirst() {
        Map<?, ?> blank = firstEntry(() -> keys().create(KeyCreate.builder("   ").deviceId(" bad").build()));
        assertEquals("Give the key a name.", blank.get("msg"));
        assertEquals(List.of("body", "name"), blank.get("loc"));
        assertEquals("Key names are at most 48 characters.", firstEntry(() -> keys().create(KeyCreate.builder("n".repeat(49)).build())).get("msg"));
        keys().create(KeyCreate.builder("  " + "n".repeat(48) + "  ").build());
        seen.clear();
        Map<?, ?> device = firstEntry(() -> keys().create(KeyCreate.builder("Drone 9").deviceId(" drone-9").label("Site", "x").build()));
        assertEquals(List.of("body", "device_id"), device.get("loc"));
        assertEquals("A label's name is 1 to 32 of a-z, 0-9, _ . and -.",
                firstEntry(() -> keys().create(KeyCreate.builder("Drone 9").label("Site", "oslo").build())).get("msg"));
        Map<?, ?> value = firstEntry(() -> keys().create(KeyCreate.builder("Drone 9").label("site", "").build()));
        assertEquals(List.of("body", "labels", "site"), value.get("loc"));
        assertNull(value.get("input"), "an object is echoed as null");
        Map<String, String> many = new java.util.TreeMap<>();
        for (int i = 0; i < 17; i++) {
            many.put(String.format("l%02d", i), "v");
        }
        assertEquals("A key has at most 16 labels.", firstEntry(() -> keys().create(KeyCreate.builder("Drone 9").labels(many).build())).get("msg"));
        for (double bad : new double[] {0.005, 1000.01, 0}) {
            assertEquals("daily_spend_cap.usd is from 0.01 to 1000, in cents, or null.",
                    firstEntry(() -> keys().create(KeyCreate.builder("Drone 9").dailySpendCap(bad).build())).get("msg"));
        }
        assertEquals("A bulk revoke names at least one label: every key it revokes has them all.",
                firstEntry(() -> keys().revokeByLabels(Map.of(), null, null)).get("msg"));
        List<String> devices = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            devices.add("d-" + i);
        }
        assertEquals("device_ids is a list of at most 1,000 device ids.",
                firstEntry(() -> keys().revokeByLabels(Map.of("customer", "acme"), devices, null)).get("msg"));
        assertEquals("Give the key a name.", firstEntry(() -> keys().update(KEY, KeyUpdate.builder().name(" ").build())).get("msg"));
        assertTrue(seen.isEmpty());
    }
}
