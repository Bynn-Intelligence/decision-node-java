package com.bynn.decisionnode;

import static com.bynn.decisionnode.Questions.truth;
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
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Boosters on requests, evidence and usage on decisions, the boosters header on errors, fine-tuned models. */
class BoostersTest {
    private static final String OK = "{\"model\":\"decisionnode-1.0\",\"answers\":{\"q\":{\"type\":\"truth\",\"truth\":0.5}},"
            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}";

    record Reply(int status, Map<String, String> headers, String body) {}

    private HttpServer server;
    private String url;
    private final ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();
    private volatile Function<String, Reply> handler = s -> new Reply(200, Map.of(), OK);

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
        bodies.add(body);
        Reply r = handler.apply(body);
        r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(r.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            ex.getResponseBody().write(out);
        }
        ex.close();
    }

    private DecisionNode client() {
        return DecisionNode.builder().environment(k -> null).apiKey("dn_test").baseUrl(url).waiter(ms -> java.util.concurrent.CompletableFuture.completedFuture(null)).build();
    }

    private Map<String, Object> lastBody() throws IOException {
        List<String> all = new ArrayList<>(bodies);
        return Json.asMap(Json.read(all.get(all.size() - 1).getBytes(StandardCharsets.UTF_8)));
    }

    private static Decision decision(String body, String... kv) throws IOException {
        Map<String, List<String>> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], List.of(kv[i + 1]));
        }
        return Responses.decision(Json.asMap(Json.read(body.getBytes(StandardCharsets.UTF_8))), HttpHeaders.of(m, (a, b) -> true));
    }

    // ------------------------------------------------------------------ requests

    @Test
    void boosterWireForms() {
        assertEquals(Map.of("name", "age"), Booster.of("age").toMap());
        // No setting: the bare name, so a list of them is the plain list of names.
        assertEquals("age", Booster.of("age").wire());
        assertEquals(List.of("age", "ai_generated"), Booster.wires(List.of(Booster.of("age"), Booster.of("ai_generated"))));
        assertEquals(new String(Json.write(Booster.of("age")), StandardCharsets.UTF_8), "\"age\"");
        Map<String, Object> full = Booster.of("ai_generated").images("c3d4").require(true).options(Map.of("k", 1)).toMap();
        assertEquals(List.of("name", "images", "require", "options"), new ArrayList<>(full.keySet()));
        assertEquals(List.of("c3d4"), full.get("images"));
        assertEquals(true, full.get("require"));
        assertEquals(Map.of("k", 1), full.get("options"));
        assertEquals(Map.of("name", "age", "require", false), Booster.of("age").require(false).toMap());
        assertEquals(new String(Json.write(Booster.of("age").images("a")), StandardCharsets.UTF_8), "{\"name\":\"age\",\"images\":[\"a\"]}");
    }

    @Test
    void boostersAreSentInOrderInEveryForm() throws IOException {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("name", "custom");
        wire.put("options", Map.of("level", 2));
        client().decide(DecideRequest.builder()
                .state("s")
                .question("q", truth("?"))
                .image(Image.fromUrl("c3d4", "https://files.example.com/c3d4.jpg"))
                .booster("age")
                .booster(Booster.of("ai_generated").images("c3d4").require(true))
                .boosters(List.of(wire))
                .model("decisionnode-1.0-flash@acme/fraud:3")
                .build());
        Map<String, Object> body = lastBody();
        assertEquals(List.of("age", Map.of("name", "ai_generated", "images", List.of("c3d4"), "require", true),
                Map.of("name", "custom", "options", Map.of("level", 2))), body.get("boosters"));
        assertEquals("decisionnode-1.0-flash@acme/fraud:3", body.get("model"));
    }

    @Test
    void noBoostersKeyWhenNoneAreGiven() throws IOException {
        client().decide(DecideRequest.builder().state("s").question("q", truth("?")).boosters(List.of()).build());
        assertFalse(lastBody().containsKey("boosters"));
        client().decide("s", Map.of("q", truth("?")), null, null, null, null);
        assertFalse(lastBody().containsKey("boosters"));
    }

    @Test
    void helpersAndDecideManyCarryBoosters() throws IOException {
        handler = s -> new Reply(200, Map.of(), "{\"answers\":{\"check\":{\"type\":\"truth\",\"truth\":0.9}}}");
        client().check("s", "Adult?", null, null, null, null, null, List.of("age"));
        assertEquals(List.of("age"), lastBody().get("boosters"));
        handler = s -> new Reply(200, Map.of(), OK);
        client().gate("deploy", null, null, null, null, List.of(Booster.of("age").require(true)));
        assertEquals(List.of(Map.of("name", "age", "require", true)), lastBody().get("boosters"));
        client().decideMany(List.of(DecideRequest.builder().state("s").question("q", truth("?")).booster("ai_generated").build()));
        assertEquals(List.of("ai_generated"), lastBody().get("boosters"));
    }

    @Test
    void boostersAreNotCheckedLocally() {
        // Five entries, a name twice, an unknown name, a key in options: all the API's to answer.
        DecideRequest r = DecideRequest.builder().state("s").question("q", truth("?"))
                .boosters(List.of("age", "age", "nope", "x", Booster.of("age").options(Map.of("k", 1)))).build();
        client().checkRequest(r);
    }

    @Test
    void theNestingLimitCountsBoosterOptions() {
        Object deep = "x";
        // The body's object, the boosters array, the booster object and options: 4 levels; 124
        // more fill the 128 the API takes.
        for (int i = 0; i < 124; i++) {
            deep = List.of(deep);
        }
        DecideRequest fits = DecideRequest.builder().state("s").question("q", truth("?"))
                .booster(Booster.of("age").options(Map.of("k", deep))).build();
        client().checkRequest(fits);
        DecideRequest over = DecideRequest.builder().state("s").question("q", truth("?"))
                .booster(Booster.of("age").options(Map.of("k", List.of(deep)))).build();
        BadRequestError e = assertThrows(BadRequestError.class, () -> client().checkRequest(over));
        assertEquals("api_usage_error", e.errorType());
        assertNull(e.status());
    }

    @Test
    void batchLinesCarryBoosters() {
        BatchRequest line = BatchRequest.of("r1",
                DecideRequest.builder().state("s").question("q", truth("?")).booster("age").build());
        assertEquals(List.of("age"), line.boosters());
        client().batches().checkCreateRequest(List.of(line));
    }

    @Test
    void aNullBoosterIsAnArgumentError() {
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> client().checkRequest(
                DecideRequest.builder().state("s").question("q", truth("?")).boosters(withNull).build()));
    }

    // ------------------------------------------------------------------ responses

    @Test
    void evidenceIsReadByBoosterInOrder() throws IOException {
        Decision d = decision("{\"model\":\"decisionnode-1.0\",\"answers\":{\"adult\":{\"type\":\"truth\",\"truth\":0.97}},"
                + "\"evidence\":{"
                + "\"age\":{\"version\":\"age-v3\",\"status\":\"ok\",\"latency_ms\":212,\"billed_calls\":2,\"results\":["
                + "{\"image\":\"a1b2\",\"age_years\":27,\"low\":22,\"high\":33,\"confidence\":0.81,\"faces\":1},"
                + "{\"image\":\"c3d4\",\"faces\":0}]},"
                + "\"ai_generated\":{\"version\":\"ai_generated-v2\",\"status\":\"ok\",\"latency_ms\":340,\"results\":["
                + "{\"image\":\"c3d4\",\"generated\":0.93,\"confidence\":0.86,\"method\":\"inference\","
                + "\"top_generator\":{\"name\":\"Generator A\",\"probability\":0.71},\"c2pa_signer\":null},"
                + "{\"image\":\"e5f6\",\"generated\":1.0,\"confidence\":1.0,\"method\":\"c2pa\","
                + "\"top_generator\":null,\"c2pa_signer\":\"Example Signer\"},"
                + "{\"image\":\"g7\",\"generated\":0.1,\"confidence\":0.8,\"method\":\"inference\","
                + "\"top_generator\":null,\"c2pa_signer\":null,\"c2pa\":\"untrusted\",\"region\":{\"x\":4}}]}},"
                + "\"usage\":{\"input_tokens\":341,\"output_tokens\":0,\"booster_tokens\":72,\"boosters\":3}}");
        assertEquals(List.of("age", "ai_generated"), new ArrayList<>(d.evidence().keySet()));
        Evidence age = d.evidence("age");
        assertEquals("age-v3", age.version());
        assertEquals("ok", age.status());
        assertTrue(age.ok());
        assertEquals(212.0, age.latencyMs());
        assertEquals(2L, age.billedCalls());
        assertNull(d.evidence("ai_generated").billedCalls());
        AgeReading a = assertInstanceOf(AgeReading.class, age.results().get(0));
        assertEquals("a1b2", a.image());
        assertEquals(1, a.faces());
        assertEquals(27L, a.ageYears());
        assertEquals(22L, a.low());
        assertEquals(33L, a.high());
        assertEquals(0.81, a.confidence());
        AgeReading none = assertInstanceOf(AgeReading.class, age.results().get(1));
        assertEquals(0, none.faces());
        assertNull(none.ageYears());
        assertNull(none.confidence());
        AiGeneratedReading g = assertInstanceOf(AiGeneratedReading.class, d.evidence("ai_generated").results().get(0));
        assertEquals(0.93, g.generated());
        assertEquals("inference", g.method());
        assertEquals(new TopGenerator("Generator A", 0.71), g.topGenerator());
        assertNull(g.c2paSigner());
        AiGeneratedReading c = assertInstanceOf(AiGeneratedReading.class, d.evidence("ai_generated").results().get(1));
        assertEquals("c2pa", c.method());
        assertEquals("Example Signer", c.c2paSigner());
        assertNull(c.topGenerator());
        assertNull(c.c2pa());
        AiGeneratedReading photo = assertInstanceOf(AiGeneratedReading.class, d.evidence("ai_generated").results().get(2));
        assertNull(photo.topGenerator());
        assertEquals("untrusted", photo.c2pa());
        assertEquals(72L, d.usage().boosterTokens());
        assertEquals(3L, d.usage().boosters());
        assertEquals(341L, d.usage().inputTokens());
    }

    @Test
    void timeoutAndSkippedCarryEmptyResults() throws IOException {
        Decision d = decision("{\"answers\":{},\"evidence\":{"
                + "\"age\":{\"version\":\"age-v3\",\"status\":\"timeout\",\"latency_ms\":800,\"results\":[]},"
                + "\"ai_generated\":{\"version\":\"ai_generated-v2\",\"status\":\"skipped\",\"latency_ms\":0,\"results\":[]},"
                + "\"later\":{\"version\":\"later-v1\",\"status\":\"queued\",\"results\":[]}}}");
        assertEquals("timeout", d.evidence("age").status());
        assertFalse(d.evidence("age").ok());
        assertEquals(List.of(), d.evidence("age").results());
        assertEquals("skipped", d.evidence("ai_generated").status());
        assertEquals("queued", d.evidence("later").status());
        assertNull(d.evidence("later").latencyMs());
    }

    @Test
    void unknownBoostersAndMalformedReadingsAreUnknownReadings() throws IOException {
        Decision d = decision("{\"answers\":{\"q\":{\"type\":\"truth\",\"truth\":0.5}},\"evidence\":{"
                + "\"fresh\":{\"version\":\"fresh-v1\",\"status\":\"ok\",\"latency_ms\":5,\"results\":[{\"image\":\"a\",\"score\":0.2}]},"
                + "\"age\":{\"version\":\"age-v3\",\"status\":\"ok\",\"latency_ms\":5,\"results\":["
                + "{\"image\":\"a\",\"faces\":1},"
                + "{\"image\":\"b\",\"faces\":\"one\"},"
                + "{\"image\":\"c\",\"faces\":1,\"age_years\":27.5,\"low\":22,\"high\":33,\"confidence\":0.8},"
                + "7]},"
                + "\"ai_generated\":{\"version\":\"ai_generated-v2\",\"status\":\"ok\",\"latency_ms\":5,\"results\":["
                + "{\"image\":\"a\",\"generated\":true,\"confidence\":0.5,\"method\":\"inference\",\"c2pa_signer\":null},"
                + "{\"image\":\"a\",\"generated\":0.5,\"confidence\":0.5,\"method\":\"inference\",\"top_generator\":{\"name\":3,\"probability\":0.1}},"
                + "{\"image\":\"a\",\"generated\":0.5,\"confidence\":0.5,\"method\":\"inference\",\"c2pa_signer\":5},"
                + "{\"image\":\"a\",\"generated\":0.5,\"confidence\":0.5,\"method\":\"inference\",\"c2pa_signer\":null},"
                + "{\"image\":\"a\",\"generated\":0.5,\"confidence\":0.5,\"method\":\"inference\",\"top_generator\":\"x\",\"c2pa_signer\":null},"
                + "{\"image\":\"a\",\"generated\":0.5,\"confidence\":0.5,\"method\":\"inference\",\"top_generator\":null,\"c2pa_signer\":null,\"c2pa\":true},"
                + "{\"image\":\"a\",\"generated\":0.5,\"confidence\":0.5,\"method\":\"inference\","
                + "\"top_generator\":{\"name\":\"Generator A\",\"probability\":0.1}}]}}}");
        UnknownReading fresh = assertInstanceOf(UnknownReading.class, d.evidence("fresh").results().get(0));
        assertEquals("fresh", fresh.booster());
        assertEquals(Map.of("image", "a", "score", 0.2), fresh.raw());
        for (Reading r : d.evidence("age").results()) {
            assertInstanceOf(UnknownReading.class, r);
            assertEquals("age", r.booster());
        }
        assertEquals(7, d.evidence("age").results().get(3).raw());
        for (Reading r : d.evidence("ai_generated").results()) {
            assertInstanceOf(UnknownReading.class, r);
        }
        assertEquals(0.5, d.truth("q").truth());
    }

    @Test
    void noEvidenceAndNoBoosterUsageWithoutBoosters() throws IOException {
        Decision d = decision(OK);
        assertNull(d.evidence());
        assertNull(d.evidence("age"));
        assertNull(d.usage().boosterTokens());
        assertNull(d.usage().boosters());
    }

    @Test
    void aPinnedFineTunedNameIsTheModel() throws IOException {
        Decision d = decision("{\"model\":\"decisionnode-1.0-flash@acme/fraud:3\",\"answers\":{}}");
        assertEquals("decisionnode-1.0-flash@acme/fraud:3", d.model());
    }

    @Test
    void modelsCarryTheFineTunedFields() {
        handler = s -> new Reply(200, Map.of(), "{\"models\":["
                + "{\"name\":\"decisionnode-1.0-flash\",\"description\":\"Fast\",\"release_date\":\"2026-10-01\",\"version\":\"decisionnode-1.0-flash\",\"max_images\":16},"
                + "{\"name\":\"decisionnode-1.0-flash@acme/fraud:3\",\"description\":\"Claims fraud\",\"release_date\":\"2026-10-09\","
                + "\"version\":\"decisionnode-1.0-flash@acme/fraud:3\",\"fine_tuned\":true,\"base\":\"decisionnode-1.0-flash\","
                + "\"adapter_version\":3,\"status\":\"deployed\"},"
                + "{\"name\":\"decisionnode-1.0-flash@acme/fraud:4\",\"description\":\"Claims fraud\",\"release_date\":\"2026-10-09\","
                + "\"fine_tuned\":true,\"base\":\"decisionnode-1.0-flash\",\"adapter_version\":4,\"status\":\"ready\"}]}");
        List<ModelInfo> models = client().models();
        ModelInfo catalogue = models.get(0);
        assertNull(catalogue.fineTuned());
        assertNull(catalogue.base());
        assertNull(catalogue.adapterVersion());
        assertNull(catalogue.status());
        ModelInfo deployed = models.get(1);
        assertEquals(true, deployed.fineTuned());
        assertEquals("decisionnode-1.0-flash", deployed.base());
        assertEquals(3L, deployed.adapterVersion());
        assertEquals("deployed", deployed.status());
        assertEquals("decisionnode-1.0-flash@acme/fraud:3", deployed.limits().get("version"));
        assertEquals("ready", models.get(2).status());
        assertEquals(4L, models.get(2).adapterVersion());
    }

    // ------------------------------------------------------------------ errors

    @Test
    void boosterErrorsMapToTheirClasses() {
        handler = s -> new Reply(400, Map.of("x-decisionnode-boosters", "age=error:0"),
                "{\"detail\":{\"error_type\":\"booster_unknown\",\"message\":\"Unknown booster: nope\"}}");
        BadRequestError unknown = assertThrows(BadRequestError.class,
                () -> client().decide(DecideRequest.builder().state("s").question("q", truth("?")).booster("nope").build()));
        assertEquals("booster_unknown", unknown.errorType());
        assertEquals(1, bodies.size());

        handler = s -> new Reply(400, Map.of(),
                "{\"detail\":{\"error_type\":\"booster_input_mismatch\",\"message\":\"The booster 'age' needs at least one image.\"}}");
        assertEquals("booster_input_mismatch", assertThrows(BadRequestError.class,
                () -> client().decide(DecideRequest.builder().state("s").question("q", truth("?")).booster("age").build())).errorType());

        bodies.clear();
        handler = s -> new Reply(403, Map.of("x-decisionnode-boosters", "age=skipped:0"),
                "{\"detail\":{\"error_type\":\"booster_not_enabled\",\"message\":\"The booster 'age' is not enabled for this workspace.\"}}");
        PermissionDeniedError off = assertThrows(PermissionDeniedError.class,
                () -> client().decide(DecideRequest.builder().state("s").question("q", truth("?")).booster("age").build()));
        assertEquals("booster_not_enabled", off.errorType());
        assertEquals(1, bodies.size(), "a 403 is not retried");
    }

    @Test
    void boosterUnavailableIsRetriedThenRaisedWithTheHeader() {
        handler = s -> new Reply(529, Map.of("retry-after-ms", "1", "x-decisionnode-boosters", "age=ok:212,ai_generated=timeout:800"),
                "{\"detail\":{\"error_type\":\"booster_unavailable\",\"message\":\"The booster 'ai_generated' did not answer in time.\"}}");
        OverloadedError e = assertThrows(OverloadedError.class, () -> client().decide(DecideRequest.builder()
                .state("s").question("q", truth("?")).booster("age").booster(Booster.of("ai_generated").require(true)).build()));
        assertEquals(3, bodies.size());
        assertEquals("booster_unavailable", e.errorType());
        assertEquals(List.of("age", "ai_generated"), new ArrayList<>(e.boosters().keySet()));
        assertEquals(new BoosterStatus("ok", 212.0), e.boosters().get("age"));
        assertEquals(new BoosterStatus("timeout", 800.0), e.boosters().get("ai_generated"));
    }

    @Test
    void theBoostersHeaderParse() {
        assertEquals(Map.of(), Responses.boosters(null));
        Map<String, BoosterStatus> m = Responses.boosters(" age=ok:212 , junk, ai_generated=error, x=timeout:soon");
        assertEquals(List.of("age", "ai_generated", "x"), new ArrayList<>(m.keySet()));
        assertEquals(new BoosterStatus("ok", 212.0), m.get("age"));
        assertEquals(new BoosterStatus("error", null), m.get("ai_generated"));
        assertEquals(new BoosterStatus("timeout", null), m.get("x"));
    }

    @Test
    void errorsWithoutTheHeaderHaveNoBoosters() {
        handler = s -> new Reply(400, Map.of(), "{\"detail\":\"bad\"}");
        BadRequestError e = assertThrows(BadRequestError.class, () -> client().decide("s", Map.of("q", truth("?"))));
        assertEquals(Map.of(), e.boosters());
        assertEquals(Map.of(), new DecisionNodeError("local").boosters());
    }

    @Test
    void aRefusalIsStillARefusal() {
        handler = s -> new Reply(403, Map.of(),
                "{\"detail\":{\"error_type\":\"refusal_error\",\"message\":\"refused\",\"category\":\"harm\"}}");
        assertInstanceOf(RefusalError.class, assertThrows(DecisionNodeError.class, () -> client().decide("s", Map.of("q", truth("?")))));
        handler = s -> new Reply(403, Map.of(), "{\"detail\":{\"error_type\":\"workspace_suspended\",\"message\":\"suspended\"}}");
        assertInstanceOf(PermissionDeniedError.class, assertThrows(DecisionNodeError.class, () -> client().decide("s", Map.of("q", truth("?")))));
    }
}
