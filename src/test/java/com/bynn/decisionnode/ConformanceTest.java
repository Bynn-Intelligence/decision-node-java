package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs every case of the DecisionNode conformance suite against its mock server, once through
 * the blocking methods and once through the {@code ...Async} methods.
 *
 * <p>Set {@code DECISIONNODE_CONFORMANCE_DIR} to a checkout of the suite and run
 * {@code ./gradlew conformance}. Python 3.10 or later starts the mock ({@code PYTHON} overrides
 * the {@code python3} command).
 */
@Tag("conformance")
class ConformanceTest {
    private static final String[] KINDS = {"requests", "responses", "errors", "behaviour", "constants"};
    private static final Set<String> EXACT = Set.of("probabilities", "legend", "raw", "entries", "safety", "body", "limits");
    private static final Pattern UPLOAD_ID = Pattern.compile("upl_[0-9a-f]{32}");

    private static Path root;
    private static Process mock;
    private static String mockUrl;
    private static final HttpClient CONTROL = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeAll
    static void startMock() throws IOException {
        String dir = System.getProperty("decisionnode.conformance.dir");
        if (dir == null || dir.isEmpty()) {
            fail("set DECISIONNODE_CONFORMANCE_DIR to a checkout of the conformance suite");
        }
        root = Path.of(dir).toAbsolutePath();
        String python = System.getProperty("decisionnode.conformance.python", "python3");
        mock = new ProcessBuilder(python, root.resolve("mock/server.py").toString(), "--port", "0")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        BufferedReader out = new BufferedReader(new InputStreamReader(mock.getInputStream(), StandardCharsets.UTF_8));
        String line = out.readLine();
        if (line == null || !line.startsWith("listening on ")) {
            mock.destroyForcibly();
            fail("the mock did not start: " + line);
        }
        mockUrl = line.substring("listening on ".length()).trim();
        Thread drain = new Thread(() -> {
            try {
                while (out.readLine() != null) {
                    // discard
                }
            } catch (IOException ignored) {
                // the mock stopped
            }
        });
        drain.setDaemon(true);
        drain.start();
    }

    @AfterAll
    static void stopMock() {
        if (mock != null) {
            mock.destroy();
        }
    }

    @TestFactory
    Stream<DynamicNode> conformance() throws IOException {
        List<DynamicNode> forms = new ArrayList<>();
        for (boolean async : new boolean[] {false, true}) {
            List<DynamicNode> kinds = new ArrayList<>();
            for (String kind : KINDS) {
                List<DynamicNode> tests = new ArrayList<>();
                File[] files = root.resolve("cases").resolve(kind).toFile().listFiles((d, n) -> n.endsWith(".json"));
                if (files == null) {
                    fail("no cases directory " + kind + " in " + root);
                }
                Arrays.sort(files);
                for (File f : files) {
                    Map<String, Object> c = Json.asMap(Json.read(Files.readAllBytes(f.toPath())));
                    String id = (String) c.get("id");
                    assertEquals(f.getName(), id + ".json", "case id matches its file name");
                    tests.add(DynamicTest.dynamicTest(id, () -> run(kind, c, async)));
                }
                kinds.add(DynamicContainer.dynamicContainer(kind, tests));
            }
            forms.add(DynamicContainer.dynamicContainer(async ? "async" : "blocking", kinds));
        }
        return forms.stream();
    }

    private static void run(String kind, Map<String, Object> c, boolean async) throws Exception {
        switch (kind) {
            case "requests" -> runRequest(c, async);
            case "responses" -> runResponse(c, async);
            case "errors" -> runError(c, async);
            case "constants" -> runConstant(c);
            default -> runBehaviour(c, async);
        }
    }

    // ------------------------------------------------------------------ the five kinds

    private static void runRequest(Map<String, Object> c, boolean async) throws Exception {
        String session = UUID.randomUUID().toString();
        Map<String, String> extra = new HashMap<>();
        extra.put("x-mock-session", session);
        if (c.get("mock") != null) {
            extra.put("x-mock-scenario", "request/" + c.get("id"));
        }
        DecisionNode client = builder(c, extra).build();
        materialise(invoke(client, map(c.get("call")), async, new HashMap<>()));
        Thread.sleep(20);
        List<Object> log = requests(session);
        Map<String, Object> exp = map(c.get("expect"));
        if (exp.containsKey("request")) {
            if (log.size() != 1) {
                fail("expected 1 request, the mock received " + log.size() + ": " + summary(log));
            }
            checkRequest(map(exp.get("request")), map(log.get(0)), "request", false);
        } else {
            checkRequestList(list(exp.get("requests")), log);
        }
    }

    private static void runResponse(Map<String, Object> c, boolean async) throws Exception {
        String session = UUID.randomUUID().toString();
        Map<String, Object> spec = Map.of("client", Map.of("api_key", "dn_test_conformance"));
        DecisionNode client = builder(spec, Map.of("x-mock-session", session, "x-mock-scenario", "response/" + c.get("id"))).build();
        Object result = materialise(invoke(client, map(c.get("call")), async, new HashMap<>()));
        Map<String, Object> exp = map(c.get("expect"));
        if (exp.containsKey("decision")) {
            match(exp.get("decision"), normDecision((Decision) result), "decision");
        } else if (exp.containsKey("answer")) {
            match(exp.get("answer"), normAnswer((Answer) result), "answer");
        } else if (exp.containsKey("models")) {
            List<Object> got = new ArrayList<>();
            for (Object o : (List<?>) result) {
                ModelInfo m = (ModelInfo) o;
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("name", m.name());
                n.put("description", m.description());
                n.put("release_date", m.releaseDate());
                n.put("limits", m.limits());
                got.add(n);
            }
            if (!same(exp.get("models"), got)) {
                fail("models: expected " + exp.get("models") + ", got " + got);
            }
        } else if (exp.containsKey("raw")) {
            if (!same(exp.get("raw"), result)) {
                fail("raw: expected " + exp.get("raw") + ", got " + result);
            }
        } else if (exp.containsKey("batch")) {
            match(exp.get("batch"), ((Batch) result).raw(), "batch");
        } else if (exp.containsKey("batch_page")) {
            match(exp.get("batch_page"), ((BatchPage) result).raw(), "batch_page");
        } else if (exp.containsKey("batch_results")) {
            checkBatchResults(list(exp.get("batch_results")), result, "batch_results");
        } else if (exp.containsKey("session")) {
            match(exp.get("session"), normSession((Session) result), "session");
        } else if (exp.containsKey("upload_ids")) {
            if (!same(exp.get("upload_ids"), result)) {
                fail("upload_ids: expected " + exp.get("upload_ids") + ", got " + result);
            }
        } else {
            fail("no expectation this harness knows: " + exp.keySet());
        }
    }

    private static void runError(Map<String, Object> c, boolean async) throws Exception {
        String session = UUID.randomUUID().toString();
        DecisionNode client = builder(c, Map.of("x-mock-session", session, "x-mock-scenario", "error/" + c.get("id"))).build();
        try {
            materialise(invoke(client, map(c.get("call")), async, new HashMap<>()));
            fail("no error was raised");
        } catch (DecisionNodeError err) {
            // The catch clause is written against the exported base name, DecisionNodeError;
            // catch_as names it, and it must be a public base of the class raised.
            Map<String, Object> spec = map(map(c.get("expect")).get("error"));
            if (spec.get("catch_as") != null) {
                Class<?> named = Class.forName(DecisionNode.class.getPackageName() + "." + spec.get("catch_as"));
                if (!java.lang.reflect.Modifier.isPublic(named.getModifiers()) || !named.isInstance(err)) {
                    fail("catch_as " + spec.get("catch_as") + " is not a public base of " + err.getClass().getName());
                }
            }
            checkError(spec, err, "error");
        }
        List<Object> log = requests(session);
        assertEquals(1, log.size(), "attempts with max_retries 0: " + summary(log));
    }

    private static void runBehaviour(Map<String, Object> c, boolean async) throws Exception {
        String session = UUID.randomUUID().toString();
        Map<String, String> extra = new HashMap<>();
        extra.put("x-mock-session", session);
        if (c.get("mock") != null) {
            extra.put("x-mock-scenario", "behaviour/" + c.get("id"));
        }
        Map<String, Object> exp = map(c.get("expect"));
        List<Long> waits = Collections.synchronizedList(new ArrayList<>());
        List<Map<String, Object>> events = new ArrayList<>();
        long started = System.nanoTime();
        Object result = null;
        try {
            // A bad client argument (timeout, max_retries, base URL, an HTTP client that follows
            // redirects) is refused when the client is built, before any request.
            DecisionNode.Builder builder = builder(c, extra);
            if (exp.containsKey("scheduled_waits_ms")) {
                // Record each wait and go on at once, so a 60 s wait is checked without taking 60 s.
                builder.waiter(ms -> {
                    waits.add(ms);
                    return CompletableFuture.completedFuture(null);
                });
            }
            DecisionNode client = builder.build();
            if (c.containsKey("steps")) {
                runSteps(c, client, session, events, async);
            } else {
                result = materialise(invoke(client, map(c.get("call")), async, new HashMap<>()));
            }
        } catch (DecisionNodeError | IllegalArgumentException err) {
            result = err;
        }
        double elapsed = (System.nanoTime() - started) / 1e6;
        Thread.sleep(50); // let the mock finish recording dropped connections
        Map<String, Object> info = control("/_mock/requests?session=" + session);
        List<Object> all = list(info.get("requests"));
        List<Object> log = new ArrayList<>();
        int pongs = 0;
        for (Object o : all) {
            if ("PONG".equals(map(o).get("method"))) {
                pongs++;
            } else {
                log.add(o);
            }
        }
        if (exp.containsKey("one_of")) {
            // The rule allows more than one way to comply: one pair must match.
            List<String> misses = new ArrayList<>();
            boolean matched = false;
            for (Object o : list(exp.get("one_of"))) {
                Map<String, Object> alt = map(o);
                try {
                    assertEquals(((Number) alt.get("attempts")).intValue(), log.size(), "attempts");
                    checkOutcome(map(alt.get("outcome")), result, "outcome");
                    matched = true;
                    break;
                } catch (AssertionError miss) {
                    misses.add(miss.getMessage());
                }
            }
            if (!matched) {
                fail("no alternative matched: " + misses);
            }
        } else if (exp.containsKey("attempts")) {
            assertEquals(((Number) exp.get("attempts")).intValue(), log.size(), "attempts: " + summary(log));
        }
        if (exp.containsKey("request_sequence")) {
            checkSequence(list(exp.get("request_sequence")), log);
        }
        if (exp.containsKey("request_counts")) {
            checkCounts(list(exp.get("request_counts")), log);
        }
        if (exp.containsKey("session_events")) {
            checkEvents(list(exp.get("session_events")), events);
        }
        if (exp.containsKey("pongs")) {
            inRange(map(exp.get("pongs")), pongs, "pongs");
        }
        if (exp.containsKey("outcome")) {
            checkOutcome(map(exp.get("outcome")), result, "outcome");
        }
        if (exp.containsKey("results")) {
            List<Object> want = list(exp.get("results"));
            if (!(result instanceof List) || ((List<?>) result).size() != want.size()) {
                fail("results: expected " + want.size() + " results, got " + result);
            }
            List<?> got = (List<?>) result;
            for (int i = 0; i < want.size(); i++) {
                DecisionResult r = (DecisionResult) got.get(i);
                checkOutcome(map(want.get(i)), r.isSuccess() ? r.decision() : r.error(), "results[" + i + "]");
            }
        }
        if (exp.containsKey("gaps_ms")) {
            List<Object> want = list(exp.get("gaps_ms"));
            for (int i = 0; i < want.size(); i++) {
                double a = ((Number) map(log.get(i)).get("time")).doubleValue();
                double b = ((Number) map(log.get(i + 1)).get("time")).doubleValue();
                inRange(map(want.get(i)), (b - a) * 1000, "gap " + (i + 1) + " (ms)");
            }
        }
        if (exp.containsKey("elapsed_ms")) {
            inRange(map(exp.get("elapsed_ms")), elapsed, "elapsed (ms)");
        }
        if (exp.containsKey("max_in_flight")) {
            inRange(map(exp.get("max_in_flight")), ((Number) info.get("max_in_flight")).doubleValue(), "max in flight");
        }
        if (exp.containsKey("scheduled_waits_ms")) {
            List<Object> want = list(exp.get("scheduled_waits_ms"));
            assertEquals(want.size(), waits.size(), "scheduled waits " + waits);
            for (int i = 0; i < want.size(); i++) {
                inRange(map(want.get(i)), waits.get(i), "scheduled wait " + (i + 1) + " (ms)");
            }
        }
        if (c.containsKey("steps") && result instanceof Throwable) {
            fail("the steps raised " + result);
        }
        for (Object path : listOrEmpty(exp.get("unrequested_paths"))) {
            String q = "session=*&path=" + URLEncoder.encode((String) path, StandardCharsets.UTF_8);
            List<Object> reached = list(control("/_mock/requests?" + q).get("requests"));
            assertEquals(0, reached.size(), "requests that reached " + path);
        }
    }

    private static void runConstant(Map<String, Object> c) throws Exception {
        String name = (String) mapOrEmpty(c.get("names")).getOrDefault("java", c.get("constant"));
        java.lang.reflect.Field f = DecisionNode.class.getField(name);
        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !java.lang.reflect.Modifier.isFinal(f.getModifiers())) {
            fail(name + " must be a public static final field");
        }
        if (!same(c.get("equals"), f.get(null))) {
            fail(name + ": expected " + c.get("equals") + ", got " + f.get(null));
        }
    }

    // ------------------------------------------------------------------ steps

    private static void runSteps(Map<String, Object> c, DecisionNode client, String session,
            List<Map<String, Object>> events, boolean async) throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        List<Object> steps = list(c.get("steps"));
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = map(steps.get(i));
            String where = "steps[" + i + "]";
            if (step.containsKey("call")) {
                Object result;
                try {
                    result = materialise(invoke(client, map(step.get("call")), async, ctx));
                } catch (DecisionNodeError | IllegalArgumentException err) {
                    result = err;
                }
                if (step.containsKey("outcome")) {
                    checkOutcome(map(step.get("outcome")), result, where + ".outcome");
                } else if (result instanceof Throwable) {
                    throw new AssertionError(where + ": the call raised " + result, (Throwable) result);
                }
                if (step.containsKey("save_as")) {
                    ctx.put((String) step.get("save_as"), result);
                }
            } else if (step.containsKey("connect")) {
                Session s = (Session) ctx.get((String) step.get("connect"));
                if (async) {
                    join(s.connectAsync());
                } else {
                    s.connect();
                }
            } else if (step.containsKey("send")) {
                Map<String, Object> args = new LinkedHashMap<>(map(step.get("send")));
                Session s = (Session) ctx.get((String) args.remove("session"));
                Object sent = null;
                boolean check = Boolean.TRUE.equals(step.get("check"));
                try {
                    Frame frame = frame(args, ctx);
                    if (check) {
                        s.checkFrame(frame);
                        sent = CHECKED;
                    } else if (async) {
                        join(s.sendAsync(frame));
                    } else {
                        s.send(frame);
                    }
                } catch (DecisionNodeError | IllegalArgumentException err) {
                    // A frame that breaks a fixed rule is refused locally and nothing is sent.
                    if (!step.containsKey("outcome")) {
                        throw err;
                    }
                    sent = err;
                }
                if (step.containsKey("outcome")) {
                    if (sent == null) {
                        fail(where + ": expected " + step.get("outcome") + ", the frame was sent");
                    }
                    checkOutcome(map(step.get("outcome")), sent, where + ".outcome");
                }
            } else if (step.containsKey("read")) {
                Map<String, Object> spec = map(step.get("read"));
                Session s = (Session) ctx.get((String) spec.get("session"));
                Integer count = spec.get("count") == null ? null : ((Number) spec.get("count")).intValue();
                long timeoutMs = spec.get("timeout_ms") == null ? 10000 : ((Number) spec.get("timeout_ms")).longValue();
                while (true) {
                    SessionEvent ev;
                    try {
                        if (async) {
                            ev = s.nextEventAsync().get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                        } else {
                            ev = s.nextEvent(Duration.ofMillis(timeoutMs));
                        }
                    } catch (TimeoutError | java.util.concurrent.TimeoutException e) {
                        throw new AssertionError(where + ": no session event arrived in time (" + events.size() + " read so far: " + events + ")");
                    }
                    events.add(normEvent(ev));
                    if (ev instanceof SessionClosed) {
                        break;
                    }
                    if (count != null && --count == 0) {
                        break;
                    }
                }
            } else if (step.containsKey("end")) {
                Session s = (Session) ctx.get((String) step.get("end"));
                try {
                    if (async) {
                        join(s.endAsync());
                    } else {
                        s.end();
                    }
                    events.add(Map.of("ended", true));
                } catch (DecisionNodeError err) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("class", err.getClass().getSimpleName());
                    e.put("status", err.status());
                    events.add(Map.of("end_error", e));
                }
            } else if (step.containsKey("close")) {
                ((Session) ctx.get((String) step.get("close"))).close();
            } else if (step.containsKey("mock_clock")) {
                HttpResponse<byte[]> r = CONTROL.send(HttpRequest.newBuilder(URI.create(mockUrl + "/_mock/clock?session=" + session))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(Json.write(step.get("mock_clock")))).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, r.statusCode(), "mock clock");
            } else if (step.containsKey("sleep_ms")) {
                Thread.sleep(((Number) step.get("sleep_ms")).longValue());
            } else {
                fail(where + ": unknown step " + step.keySet());
            }
        }
    }

    // ------------------------------------------------------------------ requests

    private static void checkRequestList(List<Object> expected, List<Object> log) {
        if (log.size() != expected.size()) {
            fail("expected " + expected.size() + " requests, the mock received " + log.size() + ": " + summary(log));
        }
        int i = 0;
        while (i < expected.size()) {
            if (!Boolean.TRUE.equals(map(expected.get(i)).get("parallel"))) {
                checkRequest(map(expected.get(i)), map(log.get(i)), "requests[" + i + "]", false);
                i++;
                continue;
            }
            int j = i;
            while (j < expected.size() && Boolean.TRUE.equals(map(expected.get(j)).get("parallel"))) {
                j++;
            }
            // Requests sent in parallel may arrive in any order: one assignment must hold.
            if (!assign(expected, log, i, j, new ArrayList<>())) {
                fail("no order of the parallel requests " + i + ".." + (j - 1) + " matches");
            }
            i = j;
        }
    }

    private static boolean assign(List<Object> expected, List<Object> log, int from, int to, List<Integer> used) {
        int k = from + used.size();
        if (k == to) {
            return true;
        }
        for (int g = from; g < to; g++) {
            if (used.contains(g)) {
                continue;
            }
            try {
                checkRequest(map(expected.get(k)), map(log.get(g)), "requests[" + k + "]", false);
            } catch (AssertionError miss) {
                continue;
            }
            used.add(g);
            if (assign(expected, log, from, to, used)) {
                return true;
            }
            used.remove(used.size() - 1);
        }
        return false;
    }

    private static void checkRequest(Map<String, Object> exp, Map<String, Object> got, String where, boolean anyBody) {
        if (!exp.get("method").equals(got.get("method"))) {
            fail(where + " method: expected " + exp.get("method") + ", got " + got.get("method") + " " + got.get("path"));
        }
        if (exp.containsKey("path") && !exp.get("path").equals(got.get("path"))) {
            fail(where + " path: expected " + exp.get("path") + ", got " + got.get("path"));
        }
        if (exp.containsKey("path_pattern") && !Pattern.compile((String) exp.get("path_pattern")).matcher((String) got.get("path")).find()) {
            fail(where + " path: " + got.get("path") + " does not match " + exp.get("path_pattern"));
        }
        if (exp.containsKey("query")) {
            Map<String, Object> q = new LinkedHashMap<>();
            String raw = (String) got.get("query");
            if (raw != null && !raw.isEmpty()) {
                for (String part : raw.split("&")) {
                    int eq = part.indexOf('=');
                    String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
                    String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                    q.putIfAbsent(k, v);
                }
            }
            if (!same(exp.get("query"), q)) {
                fail(where + " query: expected " + exp.get("query") + ", got " + q);
            }
        }
        if (exp.containsKey("status") && !same(exp.get("status"), got.get("status"))) {
            fail(where + " status: the mock answered " + got.get("status") + ", expected " + exp.get("status"));
        }
        Map<String, Object> headers = mapOrEmpty(got.get("headers"));
        for (Map.Entry<String, Object> h : mapOrEmpty(exp.get("headers")).entrySet()) {
            if (!h.getValue().equals(headers.get(h.getKey().toLowerCase()))) {
                fail(where + " header " + h.getKey() + ": expected " + h.getValue() + ", got " + headers.get(h.getKey().toLowerCase()));
            }
        }
        for (Map.Entry<String, Object> h : mapOrEmpty(exp.get("header_patterns")).entrySet()) {
            Object v = headers.get(h.getKey().toLowerCase());
            if (v == null || !Pattern.compile((String) h.getValue()).matcher((String) v).find()) {
                fail(where + " header " + h.getKey() + ": " + v + " does not match " + h.getValue());
            }
        }
        for (Object name : listOrEmpty(exp.get("absent_headers"))) {
            if (headers.containsKey(((String) name).toLowerCase())) {
                fail(where + " header " + name + " must not be sent");
            }
        }
        if (exp.containsKey("body_base64")) {
            if (!exp.get("body_base64").equals(got.get("body_base64"))) {
                fail(where + " body: the bytes differ");
            }
            return;
        }
        if (exp.containsKey("body_sha256")) {
            byte[] raw = Base64.getDecoder().decode(got.get("body_base64") == null ? "" : (String) got.get("body_base64"));
            String digest = HexFormat.of().formatHex(sha256(raw));
            if (!digest.equals(exp.get("body_sha256"))) {
                fail(where + " body: sha256 " + digest + ", expected " + exp.get("body_sha256"));
            }
            return;
        }
        String text = (String) got.get("body_text");
        if (exp.containsKey("jsonl")) {
            List<Object> lines = jsonl(text);
            if (!same(exp.get("jsonl"), lines)) {
                fail(where + " body lines: expected " + exp.get("jsonl") + ", got " + lines);
            }
            return;
        }
        if (exp.containsKey("jsonl_count") || exp.containsKey("jsonl_checks")) {
            List<Object> lines = jsonl(text);
            if (exp.containsKey("jsonl_count") && lines.size() != ((Number) exp.get("jsonl_count")).intValue()) {
                fail(where + " body: expected " + exp.get("jsonl_count") + " lines, got " + lines.size());
            }
            for (Object o : listOrEmpty(exp.get("jsonl_checks"))) {
                Map<String, Object> check = map(o);
                int line = ((Number) check.get("line")).intValue();
                if (line >= lines.size()) {
                    fail(where + " body: no line " + line);
                }
                checkValue(check, lines.get(line), where + " line " + line);
            }
            return;
        }
        Object body = text == null || text.isEmpty() ? null : Json.readOrNull(text.getBytes(StandardCharsets.UTF_8));
        if (exp.containsKey("body")) {
            if (!same(exp.get("body"), body)) {
                fail(where + " body: expected " + exp.get("body") + ", got " + body);
            }
        } else if (!exp.containsKey("body_checks") && text != null && !text.isEmpty() && !anyBody) {
            fail(where + " expected no body, got " + (text.length() > 200 ? text.substring(0, 200) + "..." : text));
        }
        for (Object o : listOrEmpty(exp.get("body_checks"))) {
            checkValue(map(o), body, where);
        }
        for (Object ptr : listOrEmpty(exp.get("ordered_keys"))) {
            Object[] want = pointer(exp.get("body"), (String) ptr);
            Object[] have = pointer(body, (String) ptr);
            List<String> wantKeys = new ArrayList<>(map(want[0]).keySet());
            List<String> haveKeys = have == null ? List.of() : new ArrayList<>(map(have[0]).keySet());
            assertEquals(wantKeys, haveKeys, "key order of body" + ptr);
        }
    }

    private static void checkValue(Map<String, Object> check, Object body, String where) {
        String ptr = (String) check.get("pointer");
        Object[] found = pointer(body, ptr);
        if (Boolean.TRUE.equals(check.get("absent"))) {
            if (found != null) {
                fail(where + " body" + ptr + ": must be absent, got " + found[0]);
            }
            return;
        }
        if (found == null) {
            fail(where + " body" + ptr + ": missing");
        }
        Object val = found[0];
        if (check.containsKey("equals") && !same(check.get("equals"), val)) {
            fail(where + " body" + ptr + ": expected " + check.get("equals") + ", got " + val);
        }
        if (check.containsKey("type") && !isType((String) check.get("type"), val)) {
            fail(where + " body" + ptr + ": expected a " + check.get("type"));
        }
        if (check.containsKey("pattern") && (!(val instanceof String)
                || !Pattern.compile((String) check.get("pattern")).matcher((String) val).find())) {
            fail(where + " body" + ptr + ": " + val + " does not match " + check.get("pattern"));
        }
        if (check.containsKey("keys")) {
            if (!(val instanceof Map) || !new ArrayList<>(((Map<?, ?>) val).keySet()).equals(check.get("keys"))) {
                fail(where + " body" + ptr + ": keys expected " + check.get("keys") + ", got " + val);
            }
        }
    }

    private static List<Object> jsonl(String text) {
        String stripped = text == null ? "" : text.strip();
        if (stripped.isEmpty()) {
            return List.of();
        }
        if (stripped.startsWith("[")) {
            return list(Json.readOrNull(stripped.getBytes(StandardCharsets.UTF_8)));
        }
        List<Object> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                out.add(Json.readOrNull(line.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    private static void checkSequence(List<Object> expected, List<Object> log) {
        if (expected.size() != log.size()) {
            fail("request sequence: expected " + expected.size() + " requests, the mock received " + log.size() + ": " + summary(log));
        }
        Set<String> checks = Set.of("headers", "header_patterns", "absent_headers", "body_checks", "jsonl_count", "jsonl_checks",
                "body_base64", "body_sha256", "query");
        for (int i = 0; i < expected.size(); i++) {
            Map<String, Object> spec = map(expected.get(i));
            Map<String, Object> rec = map(log.get(i));
            String got = rec.get("method") + " " + rec.get("path");
            if (!spec.get("method").equals(rec.get("method"))) {
                fail("request sequence[" + i + "]: expected " + spec.get("method") + ", got " + got + " in " + summary(log));
            }
            if (spec.containsKey("path") && !spec.get("path").equals(rec.get("path"))) {
                fail("request sequence[" + i + "]: expected " + spec.get("method") + " " + spec.get("path") + ", got " + got);
            }
            if (spec.containsKey("path_pattern") && !Pattern.compile((String) spec.get("path_pattern")).matcher((String) rec.get("path")).find()) {
                fail("request sequence[" + i + "]: " + got + " does not match " + spec.get("path_pattern"));
            }
            if (spec.containsKey("status") && !same(spec.get("status"), rec.get("status"))) {
                fail("request sequence[" + i + "]: " + got + " answered " + rec.get("status") + ", expected " + spec.get("status"));
            }
            Map<String, Object> sub = new LinkedHashMap<>();
            for (String k : checks) {
                if (spec.containsKey(k)) {
                    sub.put(k, spec.get(k));
                }
            }
            if (!sub.isEmpty()) {
                sub.put("method", spec.get("method"));
                checkRequest(sub, rec, "request sequence[" + i + "]", true);
            }
        }
    }

    private static void checkCounts(List<Object> expected, List<Object> log) {
        for (Object o : expected) {
            Map<String, Object> spec = map(o);
            int n = 0;
            for (Object r : log) {
                Map<String, Object> rec = map(r);
                if (spec.get("method").equals(rec.get("method"))
                        && (!spec.containsKey("path") || spec.get("path").equals(rec.get("path")))
                        && (!spec.containsKey("path_pattern") || Pattern.compile((String) spec.get("path_pattern")).matcher((String) rec.get("path")).find())) {
                    n++;
                }
            }
            String what = spec.get("method") + " " + (spec.containsKey("path") ? spec.get("path") : spec.get("path_pattern"));
            if (spec.containsKey("count") && n != ((Number) spec.get("count")).intValue()) {
                fail(what + ": expected " + spec.get("count") + " requests, got " + n + " in " + summary(log));
            }
            Map<String, Object> range = new HashMap<>();
            for (String k : List.of("min", "max")) {
                if (spec.containsKey(k)) {
                    range.put(k, spec.get(k));
                }
            }
            inRange(range, n, what);
        }
    }

    private static void checkEvents(List<Object> expected, List<Map<String, Object>> events) {
        if (expected.size() != events.size()) {
            fail("session events: expected " + expected.size() + ", got " + events.size() + ": " + events);
        }
        for (int i = 0; i < expected.size(); i++) {
            Map<String, Object> spec = map(expected.get(i));
            Map<String, Object> ev = events.get(i);
            if (!new ArrayList<>(spec.keySet()).equals(new ArrayList<>(ev.keySet()))) {
                fail("session events[" + i + "]: expected " + spec.keySet() + ", got " + ev);
            }
            String key = spec.keySet().iterator().next();
            if (spec.get(key) instanceof Map) {
                match(spec.get(key), ev.get(key), "session events[" + i + "]." + key);
            } else if (!same(spec.get(key), ev.get(key))) {
                fail("session events[" + i + "]: expected " + spec + ", got " + ev);
            }
        }
    }

    private static String summary(List<Object> log) {
        List<String> out = new ArrayList<>();
        for (Object o : log) {
            Map<String, Object> r = map(o);
            out.add(r.get("method") + " " + r.get("path") + " -> " + r.get("status"));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ outcomes and errors

    /** What a check that found nothing gives, for the outcome check. */
    private static final Object CHECKED = new Object();

    /** What a supervise call returns, for the outcome check. */
    record Supervised(List<SessionEvent> replies, SessionClosed closed) {}

    private static void checkOutcome(Map<String, Object> spec, Object result, String path) {
        if (Boolean.TRUE.equals(spec.get("argument_error"))) {
            if (!(result instanceof IllegalArgumentException)) {
                fail(path + ": expected an IllegalArgumentException, got " + result);
            }
            return;
        }
        if (result instanceof IllegalArgumentException) {
            fail(path + ": unexpected argument error: " + result);
        }
        if (spec.containsKey("result")) {
            if (result instanceof Throwable) {
                throw new AssertionError(path + ": expected " + spec.get("result") + ", got " + result, (Throwable) result);
            }
            String kind = (String) spec.get("result");
            switch (kind) {
                case "decision" -> {
                    if (!(result instanceof Decision)) {
                        fail(path + ": expected a decision, got " + result);
                    }
                    if (spec.containsKey("decision")) {
                        match(spec.get("decision"), normDecision((Decision) result), path + ".decision");
                    }
                }
                case "answer" -> match(mapOrEmpty(spec.get("answer")), normAnswer((Answer) result), path + ".answer");
                case "batch" -> match(mapOrEmpty(spec.get("batch")), ((Batch) result).raw(), path + ".batch");
                case "batch_page" -> match(mapOrEmpty(spec.get("batch_page")), ((BatchPage) result).raw(), path + ".batch_page");
                case "batch_results" -> checkResultsSpec(spec, result, path);
                case "batch_run" -> {
                    RunResult run = (RunResult) result;
                    match(mapOrEmpty(spec.get("batch")), run.batch().raw(), path + ".batch");
                    checkResultsSpec(spec, run.results(), path);
                }
                case "session" -> match(mapOrEmpty(spec.get("session")), normSession((Session) result), path + ".session");
                case "checked" -> {
                    if (result != CHECKED) {
                        fail(path + ": expected a check that found nothing, got " + result);
                    }
                }
                case "upload_ids" -> {
                    List<?> ids = (List<?>) result;
                    if (spec.containsKey("count") && ids.size() != ((Number) spec.get("count")).intValue()) {
                        fail(path + ": expected " + spec.get("count") + " upload ids, got " + ids);
                    }
                    for (Object id : ids) {
                        if (!(id instanceof String) || !UPLOAD_ID.matcher((String) id).matches()) {
                            fail(path + ": " + id + " is not an upload id");
                        }
                    }
                    if (spec.get("distinct") != null && (new HashSet<>(ids).size() == ids.size()) != (Boolean) spec.get("distinct")) {
                        fail(path + ": upload ids " + ids);
                    }
                }
                case "supervised" -> {
                    Supervised s = (Supervised) result;
                    long replies = s.replies().stream().filter(e -> e instanceof Reply).count();
                    inRange(mapOrEmpty(spec.get("replies")), replies, "replies");
                    if (spec.containsKey("closed")) {
                        Map<String, Object> got = s.closed() == null ? null : Map.of("code", s.closed().code(), "reopen", s.closed().reopen());
                        if (!same(spec.get("closed"), got)) {
                            fail(path + ".closed: expected " + spec.get("closed") + ", got " + got);
                        }
                    }
                }
                default -> fail(path + ": unknown result kind " + kind);
            }
        } else {
            if (!(result instanceof DecisionNodeError)) {
                fail(path + ": expected " + spec.get("error") + ", got " + result);
            }
            checkError(spec, (DecisionNodeError) result, path);
        }
    }

    private static void checkError(Map<String, Object> spec, DecisionNodeError err, String path) {
        String cls = (String) (spec.containsKey("error") ? spec.get("error") : spec.get("class"));
        if (Boolean.TRUE.equals(spec.get("subclass_ok"))) {
            boolean found = false;
            for (Class<?> k = err.getClass(); k != null; k = k.getSuperclass()) {
                found |= k.getSimpleName().equals(cls);
            }
            if (!found) {
                fail(path + ": expected " + cls + " or a subclass, got " + err.getClass().getSimpleName());
            }
        } else if (!err.getClass().getSimpleName().equals(cls)) {
            throw new AssertionError(path + ": expected " + cls + ", got " + err.getClass().getSimpleName() + " (" + err.getMessage() + ")", err);
        }
        // An empty server message is "", never null: an expected empty message is "".
        Map<String, Object> fields = new LinkedHashMap<>(mapOrEmpty(spec.get("fields")));
        if (fields.containsKey("message") && fields.get("message") == null) {
            fields.put("message", "");
        }
        match(fields, normError(err), path + ".fields");
    }

    /** The results whole, or their count and a sample checked by index. */
    private static void checkResultsSpec(Map<String, Object> spec, Object result, String path) {
        List<?> results = (List<?>) result;
        if (spec.containsKey("batch_results")) {
            checkBatchResults(list(spec.get("batch_results")), results, path + ".batch_results");
        }
        if (spec.containsKey("count") && results.size() != ((Number) spec.get("count")).intValue()) {
            fail(path + ": expected " + spec.get("count") + " results, got " + results.size());
        }
        for (Object o : listOrEmpty(spec.get("batch_results_sample"))) {
            Map<String, Object> sample = new LinkedHashMap<>(map(o));
            int index = ((Number) sample.remove("index")).intValue();
            if (index >= results.size()) {
                fail(path + ": no result at index " + index);
            }
            checkBatchResults(List.of(sample), List.of(results.get(index)), path + ".batch_results[" + index + "]");
        }
    }

    private static void checkBatchResults(List<Object> expected, Object result, String path) {
        List<?> results = (List<?>) result;
        if (results.size() != expected.size()) {
            fail(path + ": expected " + expected.size() + " results, got " + results.size());
        }
        for (int i = 0; i < expected.size(); i++) {
            Map<String, Object> spec = map(expected.get(i));
            BatchResult r = (BatchResult) results.get(i);
            String where = path + "[" + i + "]";
            if (spec.containsKey("custom_id") && !same(spec.get("custom_id"), r.customId())) {
                fail(where + ".custom_id: expected " + spec.get("custom_id") + ", got " + r.customId());
            }
            if (spec.containsKey("status") && !same(spec.get("status"), r.status())) {
                fail(where + ".status: expected " + spec.get("status") + ", got " + r.status());
            }
            if (spec.containsKey("headers")) {
                match(spec.get("headers"), r.headers(), where + ".headers");
            }
            if (spec.containsKey("decision") && spec.get("decision") != null) {
                if (r.decision() == null) {
                    fail(where + ": expected a decision, got none");
                }
                match(spec.get("decision"), normDecision(r.decision()), where + ".decision");
            }
            if (spec.containsKey("error")) {
                if (r.error() == null) {
                    fail(where + ": expected an error value, got none");
                }
                checkError(map(spec.get("error")), r.error(), where + ".error");
            }
            if (Boolean.TRUE.equals(spec.get("no_decision")) && r.decision() != null) {
                fail(where + ": expected no decision");
            }
        }
    }

    // ------------------------------------------------------------------ normalisation

    static Map<String, Object> normDecision(Decision d) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("model", d.model());
        n.put("request_id", d.requestId());
        n.put("latency_ms", d.latencyMs());
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", d.usage().inputTokens());
        usage.put("output_tokens", d.usage().outputTokens());
        usage.put("video_frames", d.usage().videoFrames());
        usage.put("video_seconds", d.usage().videoSeconds());
        n.put("usage", usage);
        n.put("warnings", d.warnings());
        n.put("safety", normSafety(d.safety()));
        n.put("answers", normAnswers(d.answers()));
        n.put("raw", d.raw());
        return n;
    }

    static Map<String, Object> normAnswers(Map<String, Answer> answers) {
        Map<String, Object> out = new LinkedHashMap<>();
        answers.forEach((k, v) -> out.put(k, normAnswer(v)));
        return out;
    }

    static Map<String, Object> normAnswer(Answer a) {
        Map<String, Object> n = new LinkedHashMap<>();
        if (a instanceof TruthAnswer t) {
            n.put("kind", "truth");
            n.put("truth", t.truth());
        } else if (a instanceof ChoiceAnswer ch) {
            n.put("kind", "choice");
            n.put("choice", ch.choice());
            n.put("confidence", ch.confidence());
            n.put("probabilities", ch.probabilities());
        } else if (a instanceof ScoreAnswer s) {
            n.put("kind", "score");
            n.put("score", s.score());
            n.put("confidence", s.confidence());
            Map<String, Object> p = new LinkedHashMap<>();
            s.probabilities().forEach((k, v) -> p.put(String.valueOf(k), v));
            Map<String, Object> l = new LinkedHashMap<>();
            s.legend().forEach((k, v) -> l.put(String.valueOf(k), v));
            n.put("probabilities", p);
            n.put("legend", l);
        } else if (a instanceof NumberAnswer num) {
            n.put("kind", "number");
            n.put("number", num.number());
            n.put("expected", num.expected());
            n.put("confidence", num.confidence());
            n.put("probabilities", num.probabilities());
        } else {
            n.put("kind", "unknown");
            n.put("type", a.type());
            n.put("raw", a.raw());
        }
        return n;
    }

    static Map<String, Object> normSafety(Safety s) {
        if (s == null) {
            return null;
        }
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("checked", s.checked());
        if (!s.checked()) {
            n.put("reason", s.reason());
            return n;
        }
        n.put("harm", s.harm());
        n.put("self_harm", s.selfHarm());
        n.putAll(s.scores());
        n.put("flagged", s.flagged());
        return n;
    }

    static Map<String, Object> normSession(Session s) {
        Map<String, Object> out = new LinkedHashMap<>(s.raw());
        out.put("answers", normAnswers(s.answers()));
        return out;
    }

    static Map<String, Object> normEvent(SessionEvent ev) {
        if (ev instanceof Reply r) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("seq", r.seq());
            out.put("answers", normAnswers(r.answers()));
            out.put("usage", r.raw().get("usage"));
            out.put("latency_ms", r.latencyMs());
            out.put("safety", r.raw().get("safety"));
            if (r.dropped() != null) {
                out.put("dropped", r.dropped());
            }
            return Map.of("reply", out);
        }
        if (ev instanceof FrameError f) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("seq", f.seq());
            out.put("error_type", f.errorType());
            out.put("message", f.message());
            if (f.category() != null) {
                out.put("category", f.category());
            }
            if (f.retryAfterMs() != null) {
                out.put("retry_after_ms", f.retryAfterMs());
            }
            if (f.raw().get("usage") != null) {
                out.put("usage", f.raw().get("usage"));
            }
            if (f.raw().get("safety") != null) {
                out.put("safety", f.raw().get("safety"));
            }
            return Map.of("frame_error", out);
        }
        SessionClosed c = (SessionClosed) ev;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", c.code());
        out.put("reopen", c.reopen());
        return Map.of("closed", out);
    }

    static Map<String, Object> normError(DecisionNodeError e) {
        Map<String, Object> n = new HashMap<>();
        n.put("status", e.status());
        n.put("error_type", e.errorType());
        String message = e.getMessage();
        if (message == null) {
            fail("an error's message is never null: " + e);
        }
        n.put("message", message);
        n.put("request_id", e.requestId());
        n.put("reason", e.reason());
        n.put("category", e instanceof RefusalError r ? r.category() : null);
        n.put("safety", e instanceof RefusalError r ? normSafety(r.safety()) : null);
        n.put("entries", e instanceof ValidationError v ? v.entries() : null);
        Double retry = null;
        if (e instanceof RateLimitError r) {
            retry = r.retryAfter();
        } else if (e instanceof OverloadedError o) {
            retry = o.retryAfter();
        }
        n.put("retry_after", retry);
        return n;
    }

    // ------------------------------------------------------------------ calls

    private static DecisionNode.Builder builder(Map<String, Object> c, Map<String, String> extraHeaders) {
        Map<String, String> env = new HashMap<>();
        for (Map.Entry<String, Object> e : mapOrEmpty(c.get("env")).entrySet()) {
            env.put(e.getKey(), substitute((String) e.getValue()));
        }
        Map<String, Object> spec = mapOrEmpty(c.get("client"));
        DecisionNode.Builder b = DecisionNode.builder().environment(env::get);
        if (spec.get("api_key") != null) {
            b.apiKey((String) spec.get("api_key"));
        }
        if (spec.get("model") != null) {
            b.model((String) spec.get("model"));
        }
        if ("caller_following_redirects".equals(spec.get("http_client"))) {
            b.httpClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
        }
        if (spec.get("timeout") != null) {
            b.timeout(Duration.ofMillis(Math.round(((Number) spec.get("timeout")).doubleValue() * 1000)));
        }
        if (spec.get("max_retries") != null) {
            b.maxRetries(((Number) spec.get("max_retries")).intValue());
        }
        if ("mock".equals(spec.get("clock"))) {
            // The SDK's injectable clock reads the mock's clock for this session, so a case that
            // moves the mock's time moves the client's with it.
            String session = extraHeaders.get("x-mock-session");
            b.clock(() -> {
                try {
                    Number epoch = (Number) control("/_mock/clock?session=" + session).get("epoch_seconds");
                    return Math.round(epoch.doubleValue() * 1000);
                } catch (Exception e) {
                    throw new AssertionError("cannot read the mock's clock", e);
                }
            });
        }
        if (spec.get("upload_timeout") != null) {
            b.uploadTimeout(Duration.ofMillis(Math.round(((Number) spec.get("upload_timeout")).doubleValue() * 1000)));
        }
        if (spec.get("inline_threshold") != null) {
            b.inlineThreshold(((Number) spec.get("inline_threshold")).longValue());
        }
        if (spec.containsKey("base_url")) {
            if (spec.get("base_url") != null) {
                b.baseUrl(substitute((String) spec.get("base_url")));
            }
        } else {
            b.baseUrl(mockUrl);
        }
        for (Map.Entry<String, Object> h : mapOrEmpty(spec.get("headers")).entrySet()) {
            b.header(h.getKey(), (String) h.getValue());
        }
        extraHeaders.forEach(b::header);
        return b;
    }

    private static String substitute(String value) {
        return value.replace("{mock_url}", mockUrl).replace("{mock_port}", String.valueOf(URI.create(mockUrl).getPort()));
    }

    /** The results of a batch run, read whole so they can be compared after the call. */
    record RunResult(Batch batch, List<BatchResult> results) {}

    /** Reads streamed results whole, so the comparison happens after the call. */
    private static Object materialise(Object result) {
        if (result instanceof BatchResults r) {
            return r.toList();
        }
        if (result instanceof BatchRun run) {
            return new RunResult(run.batch(), run.results().toList());
        }
        return result;
    }

    /** Waits for an async call; its error is thrown as it is, as the blocking form throws it. */
    private static <T> T join(CompletableFuture<T> f) {
        try {
            return f.get();
        } catch (ExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof RuntimeException r) {
                throw r;
            }
            throw new AssertionError("unexpected error", t);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static Object invoke(DecisionNode client, Map<String, Object> call, boolean async, Map<String, Object> ctx) {
        String method = (String) call.get("method");
        Map<String, Object> raw = mapOrEmpty(call.get("args"));
        if (method.equals("decide_raw")) {
            return async ? join(client.decideRawAsync(raw.get("body"))) : client.decideRaw(raw.get("body"));
        }
        Map<String, Object> args = new LinkedHashMap<>();
        raw.forEach((k, v) -> args.put(k, decode(v, ctx)));
        Object state = args.get("state");
        @SuppressWarnings("unchecked")
        List<Object> images = (List<Object>) args.get("images");
        @SuppressWarnings("unchecked")
        List<Object> videos = (List<Object>) args.get("videos");
        String model = (String) args.get("model");
        if (Boolean.TRUE.equals(call.get("check"))) {
            // The same request handed to the SDK's check instead of being sent.
            switch (method) {
                case "decide" -> client.checkRequest(decideRequest(args));
                case "systemone" -> client.checkSystemoneRequest(decideRequest(args));
                case "open_session" -> client.checkRequest(sessionRequest(args));
                case "batches.create" -> client.batches().checkCreateRequest(batchRequests(args.get("requests")));
                case "batches.add" -> client.batches().checkAddRequest(batchRequests(args.get("requests")));
                default -> throw new AssertionError("no check for " + method);
            }
            return CHECKED;
        }
        switch (method) {
            case "decide":
                return async ? join(client.decideAsync(decideRequest(args))) : client.decide(decideRequest(args));
            case "systemone":
                return async ? join(client.systemoneAsync(decideRequest(args))) : client.systemone(decideRequest(args));
            case "decide_many": {
                List<DecideRequest> reqs = new ArrayList<>();
                for (Object r : list(args.get("requests"))) {
                    reqs.add(decideRequest(map(r)));
                }
                int concurrency = args.get("concurrency") != null ? ((Number) args.get("concurrency")).intValue() : DecisionNode.DEFAULT_CONCURRENCY;
                return async ? join(client.decideManyAsync(reqs, concurrency)) : client.decideMany(reqs, concurrency);
            }
            case "check":
                return async
                        ? join(client.checkAsync(state, args.get("instructions"), args.get("true"), args.get("false"), images, videos, model))
                        : client.check(state, args.get("instructions"), args.get("true"), args.get("false"), images, videos, model);
            case "classify": {
                Object options = args.get("options");
                if (options instanceof List) {
                    List<String> names = new ArrayList<>();
                    for (Object o : (List<?>) options) {
                        names.add((String) o);
                    }
                    return async ? join(client.classifyAsync(state, args.get("instructions"), names, images, videos, model))
                            : client.classify(state, args.get("instructions"), names, images, videos, model);
                }
                return async ? join(client.classifyAsync(state, args.get("instructions"), map(options), images, videos, model))
                        : client.classify(state, args.get("instructions"), map(options), images, videos, model);
            }
            case "score":
                return async ? join(client.scoreAsync(state, args.get("instructions"), list(args.get("levels")), images, videos, model))
                        : client.score(state, args.get("instructions"), list(args.get("levels")), images, videos, model);
            case "number":
                return async
                        ? join(client.numberAsync(state, args.get("instructions"), (Number) args.get("min"), (Number) args.get("max"),
                                (Number) args.get("step"), images, videos, model))
                        : client.number(state, args.get("instructions"), (Number) args.get("min"), (Number) args.get("max"),
                                (Number) args.get("step"), images, videos, model);
            case "gate":
                return async ? join(client.gateAsync(args.get("action"), args.get("context"), images, videos, model))
                        : client.gate(args.get("action"), args.get("context"), images, videos, model);
            case "models":
                return async ? join(client.modelsAsync()) : client.models();
            case "upload": {
                List<Image> imgs = new ArrayList<>();
                for (Object o : images) {
                    imgs.add((Image) o);
                }
                return async ? join(client.uploadAsync(imgs)) : client.upload(imgs);
            }
            case "open_session":
                return async ? join(client.openSessionAsync(sessionRequest(args))) : client.openSession(sessionRequest(args));
            case "supervise":
                return supervise(client, args, async);
            case "run_batch": {
                List<BatchRequest> reqs = batchRequests(args.get("requests"));
                Duration poll = args.get("poll_interval") == null ? Duration.ofSeconds(60) : seconds(args.get("poll_interval"));
                Duration timeout = args.get("timeout") == null ? Duration.ofHours(26) : seconds(args.get("timeout"));
                return async ? join(client.runBatchAsync(reqs, poll, timeout)) : client.runBatch(reqs, poll, timeout);
            }
            case "batches.create":
                return async ? join(client.batches().createAsync(batchRequests(args.get("requests"))))
                        : client.batches().create(batchRequests(args.get("requests")));
            case "batches.add":
                return async ? join(client.batches().addAsync((String) args.get("batch_id"), batchRequests(args.get("requests"))))
                        : client.batches().add((String) args.get("batch_id"), batchRequests(args.get("requests")));
            case "batches.finalize":
                return async ? join(client.batches().finalizeAsync((String) args.get("batch_id")))
                        : client.batches().finalize((String) args.get("batch_id"));
            case "batches.get":
                return async ? join(client.batches().getAsync((String) args.get("batch_id")))
                        : client.batches().get((String) args.get("batch_id"));
            case "batches.list": {
                Integer offset = args.get("offset") == null ? null : ((Number) args.get("offset")).intValue();
                Integer limit = args.get("limit") == null ? null : ((Number) args.get("limit")).intValue();
                return async ? join(client.batches().listAsync(offset, limit)) : client.batches().list(offset, limit);
            }
            case "batches.results":
                return async ? join(client.batches().resultsAsync((String) args.get("batch_id")))
                        : client.batches().results((String) args.get("batch_id"));
            case "batches.cancel":
                return async ? join(client.batches().cancelAsync((String) args.get("batch_id")))
                        : client.batches().cancel((String) args.get("batch_id"));
            default:
                throw new AssertionError("unknown method " + method);
        }
    }

    private static Duration seconds(Object n) {
        return Duration.ofMillis(Math.round(((Number) n).doubleValue() * 1000));
    }

    private static Supervised supervise(DecisionNode client, Map<String, Object> args, boolean async) {
        SessionRequest body = sessionRequest(map(args.get("body")));
        List<Object> readings = list(args.get("feed"));
        long interval = args.get("feed_interval_ms") == null ? 0 : ((Number) args.get("feed_interval_ms")).longValue();
        Iterable<Frame> feed = () -> new Iterator<>() {
            private int i;

            @Override
            public boolean hasNext() {
                return i < readings.size();
            }

            @Override
            public Frame next() {
                if (i >= readings.size()) {
                    throw new NoSuchElementException();
                }
                if (i > 0 && interval > 0) {
                    try {
                        Thread.sleep(interval);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return frame(map(readings.get(i++)), Map.of());
            }
        };
        List<SessionEvent> replies = Collections.synchronizedList(new ArrayList<>());
        SessionClosed closed = async ? join(client.superviseAsync(body, feed, replies::add)) : client.supervise(body, feed, replies::add);
        return new Supervised(replies, closed);
    }

    private static Frame frame(Map<String, Object> args, Map<String, Object> ctx) {
        Frame.Builder f = Frame.builder();
        if (args.get("frame") != null) {
            f.frame(decode(args.get("frame"), ctx));
        }
        if (args.get("image") != null) {
            f.image(decode(args.get("image"), ctx));
        }
        if (args.get("questions") != null) {
            List<String> qs = new ArrayList<>();
            for (Object o : list(args.get("questions"))) {
                qs.add((String) o);
            }
            f.questions(qs);
        }
        if (args.get("reset") != null) {
            f.reset((Boolean) args.get("reset"));
        }
        if (args.get("seq") != null) {
            f.seq(((Number) args.get("seq")).longValue());
        }
        return f.build();
    }

    private static SessionRequest sessionRequest(Map<String, Object> args) {
        SessionRequest.Builder b = SessionRequest.builder();
        Map<String, Object> a = new LinkedHashMap<>();
        args.forEach((k, v) -> a.put(k, decode(v, Map.of())));
        if (a.get("state") != null) {
            b.state(a.get("state"));
        }
        if (a.get("questions") != null) {
            b.questions(map(a.get("questions")));
        }
        if (a.get("instructions") != null) {
            b.instructions(a.get("instructions"));
        }
        b.model((String) a.get("model"));
        if (a.get("window") != null) {
            b.window(((Number) a.get("window")).intValue());
        }
        if (a.get("ttl_seconds") != null) {
            b.ttlSeconds(((Number) a.get("ttl_seconds")).intValue());
        }
        return b.build();
    }

    private static List<BatchRequest> batchRequests(Object value) {
        List<BatchRequest> out = new ArrayList<>();
        for (Object o : listOrEmpty(value)) {
            Map<String, Object> r = map(o);
            BatchRequest.Builder b = BatchRequest.builder((String) r.get("custom_id"));
            if (r.get("state") != null) {
                b.state(r.get("state"));
            }
            if (r.get("questions") != null) {
                b.questions(map(r.get("questions")));
            }
            if (r.get("images") != null) {
                b.images(list(r.get("images")));
            }
            if (r.get("videos") != null) {
                b.videos(list(r.get("videos")));
            }
            b.model((String) r.get("model"));
            out.add(b.build());
        }
        return out;
    }

    private static DecideRequest decideRequest(Map<String, Object> args) {
        DecideRequest.Builder b = DecideRequest.builder();
        if (args.containsKey("state")) {
            b.state(args.get("state"));
        }
        Map<String, Object> qs = map(args.get("questions"));
        if (qs != null) {
            b.questions(qs);
        }
        if (args.get("images") != null) {
            b.images(list(args.get("images")));
        }
        if (args.get("videos") != null) {
            b.videos(list(args.get("videos")));
        }
        if (args.get("model") != null) {
            b.model((String) args.get("model"));
        }
        return b.build();
    }

    private static Object decode(Object value, Map<String, Object> ctx) {
        if (value instanceof Map<?, ?> m) {
            if (m.size() == 1) {
                Map.Entry<?, ?> e = m.entrySet().iterator().next();
                String key = String.valueOf(e.getKey());
                switch (key) {
                    case "$ref":
                        return resolve((String) e.getValue(), ctx);
                    case "$list": {
                        // count copies of item, with {i} in its strings replaced by the 0-based index
                        Map<String, Object> a = map(e.getValue());
                        List<Object> out = new ArrayList<>();
                        for (int i = 0; i < ((Number) a.get("count")).intValue(); i++) {
                            out.add(decode(withIndex(a.get("item"), i), ctx));
                        }
                        return out;
                    }
                    case "$nest": {
                        // value inside depth arrays: [[[...value...]]]
                        Map<String, Object> a = map(e.getValue());
                        Object out = decode(a.get("value"), ctx);
                        for (int i = ((Number) a.get("depth")).intValue(); i > 0; i--) {
                            out = new ArrayList<>(List.of(out));
                        }
                        return out;
                    }
                    case "$text": {
                        Map<String, Object> a = map(e.getValue());
                        return ((String) a.get("repeat")).repeat(((Number) a.get("times")).intValue());
                    }
                    case "$truth": {
                        Map<String, Object> a = map(decode(e.getValue(), ctx));
                        return Questions.truth(a.get("instructions"), a.get("true"), a.get("false"));
                    }
                    case "$choice": {
                        Map<String, Object> a = map(decode(e.getValue(), ctx));
                        Object options = a.get("options");
                        if (options instanceof List) {
                            List<String> names = new ArrayList<>();
                            for (Object o : (List<?>) options) {
                                names.add((String) o);
                            }
                            return Questions.choice(a.get("instructions"), names);
                        }
                        return Questions.choice(a.get("instructions"), map(options));
                    }
                    case "$score": {
                        Map<String, Object> a = map(decode(e.getValue(), ctx));
                        return Questions.score(a.get("instructions"), list(a.get("levels")));
                    }
                    case "$number": {
                        Map<String, Object> a = map(decode(e.getValue(), ctx));
                        return Questions.number(a.get("instructions"), (Number) a.get("min"), (Number) a.get("max"), (Number) a.get("step"));
                    }
                    case "$image":
                        return image(map(decode(e.getValue(), ctx)));
                    case "$video": {
                        Map<String, Object> a = map(decode(e.getValue(), ctx));
                        Video v = Video.of((String) a.get("id"), listOrEmpty(a.get("frames")));
                        if (a.get("fps") != null) {
                            v = v.fps(((Number) a.get("fps")).doubleValue());
                        }
                        if (a.get("start") != null) {
                            v = v.start(((Number) a.get("start")).doubleValue());
                        }
                        if (a.get("end") != null) {
                            v = v.end(((Number) a.get("end")).doubleValue());
                        }
                        return v;
                    }
                    case "$frame":
                        return videoFrame(map(decode(e.getValue(), ctx)));
                    default:
                        break;
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put((String) k, decode(v, ctx)));
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object v : l) {
                out.add(decode(v, ctx));
            }
            return out;
        }
        return value;
    }

    private static Object withIndex(Object value, int i) {
        if (value instanceof String str) {
            return str.replace("{i}", String.valueOf(i));
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put((String) k, withIndex(v, i)));
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object v : l) {
                out.add(withIndex(v, i));
            }
            return out;
        }
        return value;
    }

    private static VideoFrame videoFrame(Map<String, Object> a) {
        double t = ((Number) a.get("t")).doubleValue();
        String mediaType = (String) a.get("media_type");
        if (a.containsKey("data_text")) {
            return VideoFrame.fromBase64(t, (String) a.get("data_text"), mediaType);
        }
        byte[] data;
        try {
            data = a.containsKey("path") ? Files.readAllBytes(root.resolve((String) a.get("path")))
                    : Base64.getDecoder().decode((String) a.get("data_base64"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return VideoFrame.fromBytes(t, data, mediaType);
    }

    private static Image image(Map<String, Object> a) {
        String id = (String) a.get("id");
        String mediaType = (String) a.get("media_type");
        if (a.containsKey("url")) {
            return Image.fromUrl(id, (String) a.get("url"));
        }
        if (a.containsKey("upload_id")) {
            return Image.fromUpload(id, (String) a.get("upload_id"), mediaType);
        }
        if (a.containsKey("path") && !a.containsKey("pad_to")) {
            return Image.fromPath(id, root.resolve((String) a.get("path")));
        }
        byte[] data;
        try {
            data = a.containsKey("path") ? Files.readAllBytes(root.resolve((String) a.get("path")))
                    : Base64.getDecoder().decode((String) a.get("data_base64"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        if (a.containsKey("pad_to")) {
            // The image's own bytes followed by zero bytes: the header (type, pixels) is unchanged.
            data = Arrays.copyOf(data, Math.max(data.length, ((Number) a.get("pad_to")).intValue()));
        }
        return Image.fromBytes(id, data, mediaType);
    }

    private static Object resolve(String path, Map<String, Object> ctx) {
        String[] parts = path.split("\\.");
        Object cur = ctx.get(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (cur instanceof Batch b) {
                cur = b.raw();
            } else if (cur instanceof Session s) {
                cur = s.raw();
            }
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(parts[i]);
            } else if (cur instanceof List<?> l) {
                cur = l.get(Integer.parseInt(parts[i]));
            } else {
                throw new AssertionError("cannot resolve " + path);
            }
        }
        return cur;
    }

    // ------------------------------------------------------------------ comparison

    /** JSON equality: numbers compared exactly as doubles, booleans apart from numbers, maps by key. */
    static boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return a.equals(b);
        }
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (!x.keySet().equals(y.keySet())) {
                return false;
            }
            for (Object k : x.keySet()) {
                if (!same(x.get(k), y.get(k))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!same(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.getClass() == b.getClass() && a.equals(b);
    }

    /** Every key of expected must be in actual with the same value; keys in EXACT compare whole. */
    static void match(Object expected, Object actual, String path) {
        if (expected instanceof Map<?, ?> e && actual instanceof Map<?, ?> a) {
            for (Map.Entry<?, ?> entry : e.entrySet()) {
                String k = (String) entry.getKey();
                if (!a.containsKey(k)) {
                    fail(path + "." + k + ": missing (expected " + entry.getValue() + ")");
                }
                if (EXACT.contains(k)) {
                    if (!same(entry.getValue(), a.get(k))) {
                        fail(path + "." + k + ": expected " + entry.getValue() + ", got " + a.get(k));
                    }
                } else {
                    match(entry.getValue(), a.get(k), path + "." + k);
                }
            }
            return;
        }
        if (!same(expected, actual)) {
            fail(path + ": expected " + expected + ", got " + actual);
        }
    }

    private static Object[] pointer(Object doc, String ptr) {
        if (ptr.isEmpty()) {
            return new Object[] {doc};
        }
        Object cur = doc;
        for (String raw : ptr.substring(1).split("/", -1)) {
            String part = raw.replace("~1", "/").replace("~0", "~");
            if (cur instanceof Map<?, ?> m && m.containsKey(part)) {
                cur = m.get(part);
            } else if (cur instanceof List<?> l && part.matches("\\d+") && Integer.parseInt(part) < l.size()) {
                cur = l.get(Integer.parseInt(part));
            } else {
                return null;
            }
        }
        return new Object[] {cur};
    }

    private static boolean isType(String type, Object v) {
        return switch (type) {
            case "string" -> v instanceof String;
            case "number" -> v instanceof Number;
            case "integer" -> v instanceof Integer || v instanceof Long || v instanceof java.math.BigInteger;
            case "boolean" -> v instanceof Boolean;
            case "null" -> v == null;
            case "array" -> v instanceof List;
            case "object" -> v instanceof Map;
            default -> false;
        };
    }

    private static void inRange(Map<String, Object> r, double value, String what) {
        if (r.containsKey("min") && value < ((Number) r.get("min")).doubleValue()) {
            fail(what + " " + Math.round(value) + " is below " + r.get("min"));
        }
        if (r.containsKey("max") && value > ((Number) r.get("max")).doubleValue()) {
            fail(what + " " + Math.round(value) + " is above " + r.get("max"));
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------ the mock

    private static List<Object> requests(String session) throws Exception {
        List<Object> out = new ArrayList<>();
        for (Object o : list(control("/_mock/requests?session=" + session).get("requests"))) {
            if (!"PONG".equals(map(o).get("method"))) {
                out.add(o);
            }
        }
        return out;
    }

    private static Map<String, Object> control(String pathAndQuery) throws Exception {
        HttpResponse<byte[]> r = CONTROL.send(
                HttpRequest.newBuilder(URI.create(mockUrl + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return map(Json.read(r.body()));
    }

    private static Map<String, Object> map(Object o) {
        return Json.asMap(o);
    }

    private static Map<String, Object> mapOrEmpty(Object o) {
        Map<String, Object> m = Json.asMap(o);
        return m == null ? Map.of() : m;
    }

    private static List<Object> list(Object o) {
        return Json.asList(o);
    }

    private static List<Object> listOrEmpty(Object o) {
        List<Object> l = Json.asList(o);
        return l == null ? List.of() : l;
    }
}
