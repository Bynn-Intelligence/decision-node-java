package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
                java.util.Arrays.sort(files);
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
        DecisionNode client = makeClient(c, Map.of("x-mock-session", session));
        invoke(client, map(c.get("call")), async);
        List<Object> log = list(requests(session).get("requests"));
        if (log.size() != 1) {
            fail("expected 1 request, the mock received " + log.size());
        }
        Map<String, Object> got = map(log.get(0));
        Map<String, Object> exp = map(map(c.get("expect")).get("request"));
        assertEquals(exp.get("method"), got.get("method"), "method");
        assertEquals(exp.get("path"), got.get("path"), "path");
        Map<String, Object> headers = map(got.get("headers"));
        for (Map.Entry<String, Object> h : mapOrEmpty(exp.get("headers")).entrySet()) {
            assertEquals(h.getValue(), headers.get(h.getKey().toLowerCase()), "header " + h.getKey());
        }
        for (Map.Entry<String, Object> h : mapOrEmpty(exp.get("header_patterns")).entrySet()) {
            Object v = headers.get(h.getKey().toLowerCase());
            if (v == null || !Pattern.compile((String) h.getValue()).matcher((String) v).find()) {
                fail("header " + h.getKey() + ": " + v + " does not match " + h.getValue());
            }
        }
        for (Object name : listOrEmpty(exp.get("absent_headers"))) {
            if (headers.containsKey(((String) name).toLowerCase())) {
                fail("header " + name + " must not be sent");
            }
        }
        String text = (String) got.get("body_text");
        Object body = text == null || text.isEmpty() ? null : Json.read(text.getBytes(StandardCharsets.UTF_8));
        if (exp.containsKey("body")) {
            if (!same(exp.get("body"), body)) {
                fail("body: expected " + exp.get("body") + ", got " + body);
            }
        } else if (!exp.containsKey("body_checks") && text != null && !text.isEmpty()) {
            fail("expected no body, got " + text);
        }
        for (Object o : listOrEmpty(exp.get("body_checks"))) {
            Map<String, Object> check = map(o);
            String ptr = (String) check.get("pointer");
            Object[] found = pointer(body, ptr);
            if (found == null) {
                fail("body" + ptr + ": missing");
            }
            Object val = found[0];
            if (check.containsKey("equals") && !same(check.get("equals"), val)) {
                fail("body" + ptr + ": expected " + check.get("equals") + ", got " + val);
            }
            if (check.containsKey("type") && !isType((String) check.get("type"), val)) {
                fail("body" + ptr + ": expected a " + check.get("type"));
            }
            if (check.containsKey("keys")) {
                if (!(val instanceof Map) || !new ArrayList<>(((Map<?, ?>) val).keySet()).equals(check.get("keys"))) {
                    fail("body" + ptr + ": keys expected " + check.get("keys") + ", got " + val);
                }
            }
        }
        for (Object ptr : listOrEmpty(exp.get("ordered_keys"))) {
            Object[] want = pointer(exp.get("body"), (String) ptr);
            Object[] have = pointer(body, (String) ptr);
            List<String> wantKeys = new ArrayList<>(map(want[0]).keySet());
            List<String> haveKeys = have == null ? List.of() : new ArrayList<>(map(have[0]).keySet());
            assertEquals(wantKeys, haveKeys, "key order of body" + ptr);
        }
    }

    private static void runResponse(Map<String, Object> c, boolean async) throws Exception {
        String session = UUID.randomUUID().toString();
        Map<String, Object> spec = Map.of("client", Map.of("api_key", "dn_test_conformance"));
        DecisionNode client = makeClient(spec, Map.of("x-mock-session", session, "x-mock-scenario", "response/" + c.get("id")));
        Object result = invoke(client, map(c.get("call")), async);
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
        }
    }

    private static void runError(Map<String, Object> c, boolean async) throws Exception {
        String session = UUID.randomUUID().toString();
        DecisionNode client = makeClient(c, Map.of("x-mock-session", session, "x-mock-scenario", "error/" + c.get("id")));
        try {
            invoke(client, map(c.get("call")), async);
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
        List<Object> log = list(requests(session).get("requests"));
        assertEquals(1, log.size(), "attempts with max_retries 0");
    }

    private static void runBehaviour(Map<String, Object> c, boolean async) throws Exception {
        String session = UUID.randomUUID().toString();
        Map<String, String> extra = new HashMap<>();
        extra.put("x-mock-session", session);
        if (c.get("mock") != null) {
            extra.put("x-mock-scenario", "behaviour/" + c.get("id"));
        }
        Map<String, Object> exp = map(c.get("expect"));
        List<Long> waits = java.util.Collections.synchronizedList(new ArrayList<>());
        long started = System.nanoTime();
        Object result;
        try {
            // A bad client argument (timeout, max_retries, base URL, an HTTP client that follows
            // redirects) is refused when the client is built, before any request.
            DecisionNode.Builder builder = builder(c, extra);
            if (exp.containsKey("scheduled_waits_ms")) {
                // Record each wait between attempts and go on at once, so a 60 s wait is checked
                // without taking 60 s.
                builder.waiter(ms -> {
                    waits.add(ms);
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                });
            }
            DecisionNode client = builder.build();
            result = invoke(client, map(c.get("call")), async);
        } catch (DecisionNodeError | IllegalArgumentException err) {
            result = err;
        }
        double elapsed = (System.nanoTime() - started) / 1e6;
        Thread.sleep(50); // let the mock finish recording dropped connections
        Map<String, Object> info = requests(session);
        List<Object> log = list(info.get("requests"));
        if (exp.containsKey("one_of")) {
            // The rule allows more than one way to comply: one pair must match.
            List<String> misses = new ArrayList<>();
            for (Object o : list(exp.get("one_of"))) {
                Map<String, Object> alt = map(o);
                try {
                    assertEquals(((Number) alt.get("attempts")).intValue(), log.size(), "attempts");
                    checkOutcome(map(alt.get("outcome")), result, "outcome");
                    misses = null;
                    break;
                } catch (AssertionError miss) {
                    misses.add(miss.getMessage());
                }
            }
            if (misses != null) {
                fail("no alternative matched: " + misses);
            }
        } else {
            assertEquals(((Number) exp.get("attempts")).intValue(), log.size(), "attempts");
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
        for (Object path : listOrEmpty(exp.get("unrequested_paths"))) {
            String q = "session=*&path=" + java.net.URLEncoder.encode((String) path, StandardCharsets.UTF_8);
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

    // ------------------------------------------------------------------ outcomes and errors

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
            if (!(result instanceof Decision)) {
                fail(path + ": expected a decision, got " + result);
            }
            if (spec.containsKey("decision")) {
                match(spec.get("decision"), normDecision((Decision) result), path + ".decision");
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
            fail(path + ": expected " + cls + ", got " + err.getClass().getSimpleName() + " (" + err.getMessage() + ")");
        }
        // An empty server message is "", never null: an expected empty message is "".
        Map<String, Object> fields = new LinkedHashMap<>(mapOrEmpty(spec.get("fields")));
        if (fields.containsKey("message") && fields.get("message") == null) {
            fields.put("message", "");
        }
        match(fields, normError(err), path + ".fields");
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
        n.put("usage", usage);
        n.put("safety", normSafety(d.safety()));
        Map<String, Object> answers = new LinkedHashMap<>();
        d.answers().forEach((k, v) -> answers.put(k, normAnswer(v)));
        n.put("answers", answers);
        n.put("raw", d.raw());
        return n;
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

    private static DecisionNode makeClient(Map<String, Object> c, Map<String, String> extraHeaders) {
        return builder(c, extraHeaders).build();
    }

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

    private static Object invoke(DecisionNode client, Map<String, Object> call, boolean async) {
        return async ? join(invokeAsync(client, call)) : invoke(client, call);
    }

    /** Waits for an async call; its error is thrown as it is, as the blocking form throws it. */
    private static Object join(java.util.concurrent.CompletableFuture<?> f) {
        try {
            return f.get();
        } catch (java.util.concurrent.ExecutionException e) {
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

    private static java.util.concurrent.CompletableFuture<?> invokeAsync(DecisionNode client, Map<String, Object> call) {
        String method = (String) call.get("method");
        Map<String, Object> args = mapOrEmpty(call.get("args"));
        switch (method) {
            case "decide":
                return client.decideAsync(decideRequest(args));
            case "decide_many": {
                List<DecideRequest> reqs = new ArrayList<>();
                for (Object r : list(args.get("requests"))) {
                    reqs.add(decideRequest(map(r)));
                }
                return args.get("concurrency") != null
                        ? client.decideManyAsync(reqs, ((Number) args.get("concurrency")).intValue())
                        : client.decideManyAsync(reqs);
            }
            case "check":
                return client.checkAsync(decode(args.get("state")), decode(args.get("instructions")),
                        decode(args.get("true")), decode(args.get("false")), images(args), (String) args.get("model"));
            case "classify": {
                Object options = decode(args.get("options"));
                if (options instanceof List) {
                    List<String> names = new ArrayList<>();
                    for (Object o : (List<?>) options) {
                        names.add((String) o);
                    }
                    return client.classifyAsync(decode(args.get("state")), decode(args.get("instructions")), names,
                            images(args), (String) args.get("model"));
                }
                return client.classifyAsync(decode(args.get("state")), decode(args.get("instructions")), map(options),
                        images(args), (String) args.get("model"));
            }
            case "score":
                return client.scoreAsync(decode(args.get("state")), decode(args.get("instructions")),
                        list(decode(args.get("levels"))), images(args), (String) args.get("model"));
            case "gate":
                return client.gateAsync(decode(args.get("action")), decode(args.get("context")),
                        images(args), (String) args.get("model"));
            case "models":
                return client.modelsAsync();
            case "decide_raw":
                return client.decideRawAsync(args.get("body"));
            default:
                throw new IllegalArgumentException("unknown method " + method);
        }
    }

    private static Object invoke(DecisionNode client, Map<String, Object> call) {
        String method = (String) call.get("method");
        Map<String, Object> args = mapOrEmpty(call.get("args"));
        switch (method) {
            case "decide":
                return client.decide(decideRequest(args));
            case "decide_many": {
                List<DecideRequest> reqs = new ArrayList<>();
                for (Object r : list(args.get("requests"))) {
                    reqs.add(decideRequest(map(r)));
                }
                return args.get("concurrency") != null
                        ? client.decideMany(reqs, ((Number) args.get("concurrency")).intValue())
                        : client.decideMany(reqs);
            }
            case "check":
                return client.check(decode(args.get("state")), decode(args.get("instructions")),
                        decode(args.get("true")), decode(args.get("false")), images(args), (String) args.get("model"));
            case "classify": {
                Object options = decode(args.get("options"));
                if (options instanceof List) {
                    List<String> names = new ArrayList<>();
                    for (Object o : (List<?>) options) {
                        names.add((String) o);
                    }
                    return client.classify(decode(args.get("state")), decode(args.get("instructions")), names,
                            images(args), (String) args.get("model"));
                }
                return client.classify(decode(args.get("state")), decode(args.get("instructions")), map(options),
                        images(args), (String) args.get("model"));
            }
            case "score":
                return client.score(decode(args.get("state")), decode(args.get("instructions")),
                        list(decode(args.get("levels"))), images(args), (String) args.get("model"));
            case "gate":
                return client.gate(decode(args.get("action")), decode(args.get("context")),
                        images(args), (String) args.get("model"));
            case "models":
                return client.models();
            case "decide_raw":
                return client.decideRaw(args.get("body"));
            default:
                throw new IllegalArgumentException("unknown method " + method);
        }
    }

    private static List<Object> images(Map<String, Object> args) {
        return args.get("images") == null ? null : list(decode(args.get("images")));
    }

    private static DecideRequest decideRequest(Map<String, Object> args) {
        DecideRequest.Builder b = DecideRequest.builder();
        if (args.containsKey("state")) {
            b.state(decode(args.get("state")));
        }
        Map<String, Object> qs = map(decode(args.get("questions")));
        if (qs != null) {
            b.questions(qs);
        }
        if (args.get("images") != null) {
            b.images(list(decode(args.get("images"))));
        }
        if (args.get("model") != null) {
            b.model((String) args.get("model"));
        }
        return b.build();
    }

    private static Object decode(Object value) {
        if (value instanceof Map<?, ?> m) {
            if (m.size() == 1) {
                Map.Entry<?, ?> e = m.entrySet().iterator().next();
                Map<String, Object> a = map(e.getValue());
                switch (String.valueOf(e.getKey())) {
                    case "$truth":
                        return Questions.truth(a.get("instructions"), a.get("true"), a.get("false"));
                    case "$choice": {
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
                    case "$score":
                        return Questions.score(a.get("instructions"), list(a.get("levels")));
                    case "$image":
                        if (a.containsKey("path")) {
                            return Image.fromPath((String) a.get("id"), root.resolve((String) a.get("path")));
                        }
                        if (a.containsKey("url")) {
                            return Image.fromUrl((String) a.get("id"), (String) a.get("url"));
                        }
                        return Image.fromBytes((String) a.get("id"),
                                Base64.getDecoder().decode((String) a.get("data_base64")), (String) a.get("media_type"));
                    default:
                        break;
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put((String) k, decode(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object v : l) {
                out.add(decode(v));
            }
            return out;
        }
        return value;
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
            return new java.math.BigDecimal(x.toString()).compareTo(new java.math.BigDecimal(y.toString())) == 0;
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

    // ------------------------------------------------------------------ the mock

    private static Map<String, Object> requests(String session) throws Exception {
        return control("/_mock/requests?session=" + session);
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
