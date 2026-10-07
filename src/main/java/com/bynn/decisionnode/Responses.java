package com.bynn.decisionnode;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Turns response bodies and headers into the SDK's types. */
final class Responses {
    private Responses() {}

    static Decision decision(Map<String, Object> body, HttpHeaders headers) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        Map<String, Object> raw = Json.asMap(body.get("answers"));
        if (raw != null) {
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                Map<String, Object> a = Json.asMap(e.getValue());
                answers.put(e.getKey(), answer(a == null ? Map.of() : a));
            }
        }
        Map<String, Object> usage = Json.asMap(body.get("usage"));
        Usage u = usage == null
                ? new Usage(null, null)
                : new Usage(Json.asLong(usage.get("input_tokens")), Json.asLong(usage.get("output_tokens")));
        Object model = body.get("model");
        return new Decision(
                answers,
                model instanceof String ? (String) model : null,
                u,
                header(headers, "x-request-id"),
                latency(header(headers, "x-decisionnode-latency-ms")),
                Safety.parse(header(headers, "x-decisionnode-safety")),
                body);
    }

    /**
     * One answer. A known type whose fields are missing or of the wrong type is returned as an
     * {@link UnknownAnswer} with its raw fields, as an unknown type is, so the decision does not fail.
     */
    static Answer answer(Map<String, Object> raw) {
        Object type = raw.get("type");
        Map<String, Object> view = Collections.unmodifiableMap(raw);
        if ("truth".equals(type)) {
            Double truth = Json.asDouble(raw.get("truth"));
            if (truth != null) {
                return new TruthAnswer(truth, view);
            }
        } else if ("choice".equals(type)) {
            Object choice = raw.get("choice");
            Double confidence = Json.asDouble(raw.get("confidence"));
            Map<String, Object> probs = Json.asMap(raw.get("probabilities"));
            if (choice instanceof String && confidence != null && probs != null) {
                Map<String, Double> p = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : probs.entrySet()) {
                    Double v = Json.asDouble(e.getValue());
                    if (v == null) {
                        return new UnknownAnswer((String) type, view);
                    }
                    p.put(e.getKey(), v);
                }
                return new ChoiceAnswer((String) choice, confidence, Collections.unmodifiableMap(p), view);
            }
        } else if ("score".equals(type)) {
            Double score = Json.asDouble(raw.get("score"));
            Double confidence = Json.asDouble(raw.get("confidence"));
            Map<Integer, Double> p = indexed(raw.get("probabilities"), true);
            Map<Integer, Object> legend = indexed(raw.get("legend"), false);
            if (score != null && confidence != null && p != null && legend != null) {
                return new ScoreAnswer(score, confidence, p, legend, view);
            }
        }
        return new UnknownAnswer(type instanceof String ? (String) type : null, view);
    }

    /**
     * A map keyed "0".."K-1", re-keyed by integer index; null when it is not one, or when
     * {@code numbers} is set and a value is not a number.
     */
    @SuppressWarnings("unchecked")
    private static <V> Map<Integer, V> indexed(Object value, boolean numbers) {
        Map<String, Object> m = Json.asMap(value);
        if (m == null) {
            return null;
        }
        Map<Integer, V> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            int index;
            try {
                index = Integer.parseInt(e.getKey());
            } catch (NumberFormatException ex) {
                return null;
            }
            Object v = e.getValue();
            if (numbers) {
                v = Json.asDouble(v);
                if (v == null) {
                    return null;
                }
            }
            out.put(index, (V) v);
        }
        return Collections.unmodifiableMap(out);
    }

    static List<ModelInfo> models(Map<String, Object> body) {
        List<ModelInfo> out = new ArrayList<>();
        List<Object> list = Json.asList(body.get("models"));
        if (list == null) {
            return out;
        }
        for (Object item : list) {
            Map<String, Object> m = Json.asMap(item);
            if (m == null) {
                continue;
            }
            Map<String, Object> limits = new LinkedHashMap<>(m);
            limits.remove("name");
            limits.remove("description");
            limits.remove("release_date");
            out.add(new ModelInfo(
                    str(m.get("name")),
                    str(m.get("description")),
                    str(m.get("release_date")),
                    Collections.unmodifiableMap(limits),
                    Collections.unmodifiableMap(m)));
        }
        return Collections.unmodifiableList(out);
    }

    static DecisionNodeError error(int status, HttpHeaders headers, byte[] bodyBytes) {
        Object body = Json.readOrNull(bodyBytes);
        Map<String, Object> obj = Json.asMap(body);
        Object detail = obj == null ? null : obj.get("detail");
        String errorType = null;
        String message = null;
        String category = null;
        List<Object> entries = null;
        Map<String, Object> d = Json.asMap(detail);
        if (d != null) {
            errorType = str(d.get("error_type"));
            message = str(d.get("message"));
            category = str(d.get("category"));
        } else if (detail instanceof String) {
            message = (String) detail;
        } else if (detail instanceof List) {
            entries = Json.asList(detail);
        }
        ErrorInfo info = new ErrorInfo(
                status, errorType, message == null ? "" : message, header(headers, "x-request-id"), header(headers, "x-decisionnode-error"),
                body, lowerCased(headers));
        if (status == 400) {
            return "max_tokens_exceeded".equals(errorType) ? new ContextLimitError(info) : new BadRequestError(info);
        }
        if (status == 401) {
            return new AuthenticationError(info);
        }
        if (status == 402) {
            return new InsufficientCreditError(info);
        }
        if (status == 403) {
            if ("refusal_error".equals(errorType)) {
                return new RefusalError(info, category, Safety.parse(header(headers, "x-decisionnode-safety")));
            }
            return new PermissionDeniedError(info);
        }
        if (status == 404) {
            return new NotFoundError(info);
        }
        if (status == 422) {
            return new ValidationError(info, entries);
        }
        if (status == 429) {
            return new RateLimitError(info, retryAfter(headers));
        }
        if (status == 529) {
            return new OverloadedError(info, retryAfter(headers));
        }
        if (status >= 500 && status <= 599) {
            return new ServerError(info);
        }
        return new DecisionNodeError(info);
    }

    /** A helper's own answer missing or malformed in a success response. */
    static ServerError malformedAnswer(int status, HttpHeaders headers, Map<String, Object> body, String message) {
        return new ServerError(new ErrorInfo(
                status, null, message, header(headers, "x-request-id"),
                header(headers, "x-decisionnode-error"), body, lowerCased(headers)));
    }

    static ServerError notJson(int status, HttpHeaders headers, byte[] bodyBytes) {
        return new ServerError(new ErrorInfo(
                status, null, "", header(headers, "x-request-id"),
                header(headers, "x-decisionnode-error"), null, lowerCased(headers)));
    }

    /** The longest wait a server can ask for, in seconds. */
    static final double MAX_RETRY_AFTER = 60.0;

    /**
     * Seconds to wait: {@code retry-after-ms} / 1000 when sent, else {@code Retry-After} in seconds
     * or as an HTTP date, else null. Capped at {@link #MAX_RETRY_AFTER}.
     */
    static Double retryAfter(HttpHeaders headers) {
        Double seconds = requestedWait(headers);
        return seconds == null || seconds.isNaN() ? null : Math.min(MAX_RETRY_AFTER, seconds);
    }

    private static Double requestedWait(HttpHeaders headers) {
        String ms = header(headers, "retry-after-ms");
        if (ms != null) {
            try {
                return Math.max(0.0, Double.parseDouble(ms.trim()) / 1000.0);
            } catch (NumberFormatException ignored) {
                // fall through to Retry-After
            }
        }
        String s = header(headers, "retry-after");
        if (s != null) {
            try {
                return Math.max(0.0, Double.parseDouble(s.trim()));
            } catch (NumberFormatException ignored) {
                try {
                    ZonedDateTime at = ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
                    long millis = Duration.between(ZonedDateTime.now(at.getZone()), at).toMillis();
                    return Math.max(0.0, millis / 1000.0);
                } catch (DateTimeParseException ignoredToo) {
                    return null;
                }
            }
        }
        return null;
    }

    static Double latency(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String header(HttpHeaders headers, String name) {
        return headers == null ? null : headers.firstValue(name).orElse(null);
    }

    private static Map<String, List<String>> lowerCased(HttpHeaders headers) {
        Map<String, List<String>> out = new TreeMap<>();
        if (headers != null) {
            headers.map().forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT), List.copyOf(v)));
        }
        return Collections.unmodifiableMap(out);
    }

    private static String str(Object v) {
        return v instanceof String ? (String) v : null;
    }
}
