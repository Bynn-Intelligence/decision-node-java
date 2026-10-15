package com.bynn.decisionnode;

import java.io.IOException;
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

    /** Reads a header by its lower-case name; null when absent. */
    interface Headers {
        String get(String lowerCaseName);
    }

    static Headers of(HttpHeaders headers) {
        return name -> header(headers, name);
    }

    /** Headers kept as a map of lower-case names to values, as a batch result line carries them. */
    static Headers of(Map<String, String> lowerCased) {
        return name -> lowerCased == null ? null : lowerCased.get(name);
    }

    static Decision decision(Map<String, Object> body, HttpHeaders headers) {
        return decision(body, of(headers));
    }

    static Decision decision(Map<String, Object> body, Headers headers) {
        Map<String, Object> usage = Json.asMap(body.get("usage"));
        Object model = body.get("model");
        return new Decision(
                answers(body.get("answers")),
                model instanceof String ? (String) model : null,
                usage(usage),
                headers.get("x-request-id"),
                latency(headers.get("x-decisionnode-latency-ms")),
                Safety.parse(headers.get("x-decisionnode-safety")),
                warnings(body.get("warnings")),
                evidence(body.get("evidence")),
                fallback(headers),
                body);
    }

    /** A fallback answer's two headers; null when {@code x-decisionnode-fallback} is absent. */
    static Fallback fallback(Headers headers) {
        String served = headers.get("x-decisionnode-fallback");
        return served == null ? null : new Fallback(served, headers.get("x-decisionnode-requested-model"));
    }

    /**
     * What the boosters read, by booster name in the order sent; null when the body carries no
     * {@code evidence} object.
     */
    static Map<String, Evidence> evidence(Object value) {
        Map<String, Object> raw = Json.asMap(value);
        if (raw == null) {
            return null;
        }
        Map<String, Evidence> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String booster = e.getKey();
            Map<String, Object> entry = Json.asMap(e.getValue());
            Map<String, Object> m = entry == null ? Map.of() : entry;
            List<Reading> results = new ArrayList<>();
            List<Object> list = Json.asList(m.get("results"));
            if (list != null) {
                for (Object r : list) {
                    results.add(reading(booster, r));
                }
            }
            out.put(booster, new Evidence(str(m.get("version")), str(m.get("status")), Json.asDouble(m.get("latency_ms")),
                    whole(m.get("billed_calls")),
                    Collections.unmodifiableList(results), Collections.unmodifiableMap(m)));
        }
        return out;
    }

    /**
     * One booster reading, typed by the booster's name. A reading of a booster this SDK does not
     * know, or one with a field missing or of the wrong type, is an {@link UnknownReading} with its
     * raw fields, so the decision does not fail.
     */
    static Reading reading(String booster, Object value) {
        Map<String, Object> raw = Json.asMap(value);
        if (raw != null) {
            Map<String, Object> view = Collections.unmodifiableMap(raw);
            Reading typed = "age".equals(booster) ? ageReading(view)
                    : "ai_generated".equals(booster) ? aiGeneratedReading(view)
                    : "face_detection".equals(booster) ? faceDetectionReading(view)
                    : "face_recognition".equals(booster) ? faceRecognitionReading(view)
                    : "ocr".equals(booster) ? ocrReading(view)
                    : "bynn_depth".equals(booster) ? depthReading(view)
                    : "bynn_segment".equals(booster) ? segmentReading(view)
                    : null;
            if (typed != null) {
                return typed;
            }
        }
        return new UnknownReading(booster, raw == null ? value : Collections.unmodifiableMap(raw));
    }

    /**
     * A face_detection reading: a string image, faces a whole number of at least 0 (a JSON integer,
     * never 3.0), and detections a list of {box, confidence}.
     */
    private static FaceDetectionReading faceDetectionReading(Map<String, Object> raw) {
        Object image = raw.get("image");
        Long faces = integer(raw.get("faces"));
        List<Object> found = Json.asList(raw.get("detections"));
        if (!(image instanceof String) || faces == null || faces < 0 || found == null) {
            return null;
        }
        List<FaceDetection> detections = new ArrayList<>(found.size());
        for (Object o : found) {
            Map<String, Object> d = Json.asMap(o);
            FaceBox box = d == null ? null : faceBox(d.get("box"));
            Double confidence = d == null ? null : Json.asDouble(d.get("confidence"));
            if (box == null || confidence == null) {
                return null;
            }
            detections.add(new FaceDetection(box, confidence));
        }
        return new FaceDetectionReading((String) image, faces, Collections.unmodifiableList(detections), raw);
    }

    /**
     * A face_recognition reading: a string image, faces a whole number of at least 0, and matches a
     * list of {box, score, collection, reference, name?, label?, collection_name?, metadata?}: name,
     * label and collection_name strings or absent, metadata an object or absent; the reading's own
     * collection_name a string or absent.
     */
    private static FaceRecognitionReading faceRecognitionReading(Map<String, Object> raw) {
        Object image = raw.get("image");
        Long faces = integer(raw.get("faces"));
        List<Object> found = Json.asList(raw.get("matches"));
        Object collectionName = raw.get("collection_name");
        if (!(image instanceof String) || faces == null || faces < 0 || found == null
                || (collectionName != null && !(collectionName instanceof String))) {
            return null;
        }
        List<FaceMatch> matches = new ArrayList<>(found.size());
        for (Object o : found) {
            Map<String, Object> m = Json.asMap(o);
            if (m == null) {
                return null;
            }
            FaceBox box = faceBox(m.get("box"));
            Double score = Json.asDouble(m.get("score"));
            Object collection = m.get("collection");
            Object reference = m.get("reference");
            Object name = m.get("name");
            Object label = m.get("label");
            Object matchCollection = m.get("collection_name");
            Object metadata = m.get("metadata");
            if (box == null || score == null || !(collection instanceof String) || !(reference instanceof String)
                    || (name != null && !(name instanceof String)) || (label != null && !(label instanceof String))
                    || (matchCollection != null && !(matchCollection instanceof String))
                    || (metadata != null && Json.asMap(metadata) == null)) {
                return null;
            }
            matches.add(new FaceMatch(box, score, (String) collection, (String) reference, (String) name, (String) label,
                    (String) matchCollection, metadata == null ? null : Collections.unmodifiableMap(Json.asMap(metadata))));
        }
        return new FaceRecognitionReading((String) image, faces, Collections.unmodifiableList(matches), (String) collectionName,
                raw);
    }

    /**
     * A Depth Vision reading: image, exactly 7 directions (numbers or null), ground, the grid's size
     * and rows of numbers, the three depths and the picture's size; ahead a number or null.
     */
    private static DepthReading depthReading(Map<String, Object> raw) {
        Object image = raw.get("image");
        List<Object> dirs = Json.asList(raw.get("directions"));
        Object ground = raw.get("ground");
        Long rows = integer(raw.get("grid_rows"));
        Long cols = integer(raw.get("grid_cols"));
        List<Object> grid = Json.asList(raw.get("grid"));
        double[] depths = numbers(raw, "depth_min", "depth_max", "depth_median");
        Long width = integer(raw.get("width"));
        Long height = integer(raw.get("height"));
        Object ahead = raw.get("ahead");
        if (!(image instanceof String) || dirs == null || dirs.size() != 7 || !(ground instanceof Boolean) || rows == null
                || cols == null || grid == null || depths == null || width == null || height == null
                || (ahead != null && Json.asDouble(ahead) == null)) {
            return null;
        }
        List<Double> directions = new ArrayList<>(7);
        for (Object d : dirs) {
            if (d != null && Json.asDouble(d) == null) {
                return null;
            }
            directions.add(d == null ? null : Json.asDouble(d));
        }
        List<List<Double>> cells = new ArrayList<>(grid.size());
        for (Object row : grid) {
            List<Double> values = numberList(row);
            if (values == null) {
                return null;
            }
            cells.add(values);
        }
        return new DepthReading((String) image, ahead == null ? null : Json.asDouble(ahead),
                Collections.unmodifiableList(directions), (Boolean) ground, rows, cols, Collections.unmodifiableList(cells),
                depths[0], depths[1], depths[2], width, height, raw);
    }

    /**
     * A Segment Vision reading: image, the picture's size, the prompts read and the instances, each
     * well formed; depth_source a string or null and assumed_hfov_deg a number or null.
     */
    private static SegmentReading segmentReading(Map<String, Object> raw) {
        Object image = raw.get("image");
        Long width = integer(raw.get("width"));
        Long height = integer(raw.get("height"));
        List<Object> prompts = Json.asList(raw.get("prompts"));
        List<Object> found = Json.asList(raw.get("instances"));
        Object source = raw.get("depth_source");
        Object hfov = raw.get("assumed_hfov_deg");
        if (!(image instanceof String) || width == null || height == null || prompts == null || found == null
                || (source != null && !(source instanceof String)) || (hfov != null && Json.asDouble(hfov) == null)) {
            return null;
        }
        List<String> phrases = new ArrayList<>(prompts.size());
        for (Object p : prompts) {
            if (!(p instanceof String text)) {
                return null;
            }
            phrases.add(text);
        }
        List<SegmentInstance> instances = new ArrayList<>(found.size());
        for (Object o : found) {
            SegmentInstance instance = segmentInstance(Json.asMap(o));
            if (instance == null) {
                return null;
            }
            instances.add(instance);
        }
        return new SegmentReading((String) image, width, height, Collections.unmodifiableList(phrases),
                Collections.unmodifiableList(instances), (String) source, hfov == null ? null : Json.asDouble(hfov), raw);
    }

    /** One Segment Vision instance; null when a required field is missing or of the wrong type. */
    private static SegmentInstance segmentInstance(Map<String, Object> m) {
        if (m == null || !(m.get("label") instanceof String label)) {
            return null;
        }
        Double score = Json.asDouble(m.get("score"));
        Double area = Json.asDouble(m.get("area"));
        List<Double> box = numberList(m.get("box"));
        List<SegmentPoint> polygon = points(m.get("polygon"));
        SegmentPoint centroid = point(m.get("centroid"));
        if (score == null || area == null || box == null || box.size() != 4 || polygon == null || centroid == null) {
            return null;
        }
        SegmentOpening opening = null;
        if (m.get("opening") != null) {
            Map<String, Object> o = Json.asMap(m.get("opening"));
            List<SegmentPoint> outline = o == null ? null : points(o.get("polygon"));
            SegmentPoint centre = o == null ? null : point(o.get("centroid"));
            SegmentPoint offset = o == null ? null : point(o.get("offset"));
            double[] n = o == null ? null : numbers(o, "width", "height", "area");
            if (outline == null || centre == null || offset == null || n == null) {
                return null;
            }
            opening = new SegmentOpening(outline, centre, n[0], n[1], offset, n[2]);
        }
        SegmentDepth depth = null;
        if (m.get("depth") != null) {
            Map<String, Object> d = Json.asMap(m.get("depth"));
            if (d == null) {
                return null;
            }
            Double[] v = new Double[3];
            String[] names = {"median", "opening_median", "opening_rim_median"};
            for (int i = 0; i < 3; i++) {
                Object x = d.get(names[i]);
                if (x != null && Json.asDouble(x) == null) {
                    return null;
                }
                v[i] = x == null ? null : Json.asDouble(x);
            }
            depth = new SegmentDepth(v[0], v[1], v[2]);
        }
        SegmentSize size = null;
        if (m.get("size_m") != null) {
            Map<String, Object> s = Json.asMap(m.get("size_m"));
            double[] wh = s == null ? null : numbers(s, "width", "height");
            if (wh == null) {
                return null;
            }
            SegmentSize.Opening hole = null;
            if (s.get("opening") != null) {
                Map<String, Object> so = Json.asMap(s.get("opening"));
                double[] owh = so == null ? null : numbers(so, "width", "height");
                if (owh == null) {
                    return null;
                }
                hole = new SegmentSize.Opening(owh[0], owh[1]);
            }
            size = new SegmentSize(wh[0], wh[1], hole);
        }
        return new SegmentInstance(label, score, new SegmentBox(box.get(0), box.get(1), box.get(2), box.get(3)),
                polygon, area, centroid, opening, depth, size);
    }

    /** A JSON array of numbers, as doubles; null when it is not one. */
    private static List<Double> numberList(Object value) {
        List<Object> list = Json.asList(value);
        if (list == null) {
            return null;
        }
        List<Double> out = new ArrayList<>(list.size());
        for (Object o : list) {
            Double d = Json.asDouble(o);
            if (d == null) {
                return null;
            }
            out.add(d);
        }
        return Collections.unmodifiableList(out);
    }

    /** {@code [x, y]}; null when it is not two numbers. */
    private static SegmentPoint point(Object value) {
        List<Double> xy = numberList(value);
        return xy == null || xy.size() != 2 ? null : new SegmentPoint(xy.get(0), xy.get(1));
    }

    /** {@code [[x, y], ...]}; null when any entry is not a point. */
    private static List<SegmentPoint> points(Object value) {
        List<Object> list = Json.asList(value);
        if (list == null) {
            return null;
        }
        List<SegmentPoint> out = new ArrayList<>(list.size());
        for (Object o : list) {
            SegmentPoint p = point(o);
            if (p == null) {
                return null;
            }
            out.add(p);
        }
        return Collections.unmodifiableList(out);
    }

    /** A face's box: four numbers, as sent; null when a corner is missing or not a number. */
    private static FaceBox faceBox(Object value) {
        Map<String, Object> b = Json.asMap(value);
        if (b == null) {
            return null;
        }
        Double x1 = Json.asDouble(b.get("x1"));
        Double y1 = Json.asDouble(b.get("y1"));
        Double x2 = Json.asDouble(b.get("x2"));
        Double y2 = Json.asDouble(b.get("y2"));
        return x1 == null || y1 == null || x2 == null || y2 == null ? null : new FaceBox(x1, y1, x2, y2);
    }

    /** A JSON integer (never a float such as 3.0), as a long; null for anything else. */
    private static Long integer(Object v) {
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof java.math.BigInteger b) {
            return b.bitLength() < 64 ? b.longValue() : null;
        }
        return null;
    }

    /** An ocr reading: a string image and text (empty allowed), and truncated a boolean, all always sent. */
    private static OcrReading ocrReading(Map<String, Object> raw) {
        Object image = raw.get("image");
        Object text = raw.get("text");
        Object truncated = raw.get("truncated");
        if (!(image instanceof String) || !(text instanceof String) || !(truncated instanceof Boolean)) {
            return null;
        }
        return new OcrReading((String) image, (String) text, (Boolean) truncated, raw);
    }

    private static AgeReading ageReading(Map<String, Object> raw) {
        Object image = raw.get("image");
        Long faces = whole(raw.get("faces"));
        if (!(image instanceof String) || faces == null || faces < 0) {
            return null;
        }
        Object age = raw.get("age_years");
        Object low = raw.get("low");
        Object high = raw.get("high");
        Object confidence = raw.get("confidence");
        // With no face the age fields are left out (or null); with a face each must be there.
        boolean none = faces == 0;
        if (!optional(age, none, true) || !optional(low, none, true) || !optional(high, none, true)
                || !optional(confidence, none, false)) {
            return null;
        }
        return new AgeReading((String) image, faces, whole(age), whole(low), whole(high), Json.asDouble(confidence), raw);
    }

    /** A field that may be absent or null when {@code mayBeAbsent}, and is otherwise a JSON number (whole when asked). */
    private static boolean optional(Object v, boolean mayBeAbsent, boolean wholeNumber) {
        if (v == null) {
            return mayBeAbsent;
        }
        return wholeNumber ? whole(v) != null : Json.asDouble(v) != null;
    }

    private static AiGeneratedReading aiGeneratedReading(Map<String, Object> raw) {
        Object image = raw.get("image");
        Double generated = Json.asDouble(raw.get("generated"));
        Double confidence = Json.asDouble(raw.get("confidence"));
        Object method = raw.get("method");
        // The top_generator and c2pa_signer keys are always sent, null when unset; c2pa only when
        // the file carries Content Credentials.
        Object signer = raw.get("c2pa_signer");
        Object c2pa = raw.get("c2pa");
        if (!(image instanceof String) || generated == null || confidence == null || !(method instanceof String)
                || !raw.containsKey("c2pa_signer") || (signer != null && !(signer instanceof String))
                || (c2pa != null && !(c2pa instanceof String)) || !raw.containsKey("top_generator")) {
            return null;
        }
        TopGenerator top = null;
        Object t = raw.get("top_generator");
        if (t != null) {
            Map<String, Object> m = Json.asMap(t);
            Double p = m == null ? null : Json.asDouble(m.get("probability"));
            if (m == null || !(m.get("name") instanceof String) || p == null) {
                return null;
            }
            top = new TopGenerator((String) m.get("name"), p);
        }
        return new AiGeneratedReading(
                (String) image, generated, confidence, (String) method, top, (String) signer, (String) c2pa, raw);
    }

    /** A JSON number with no fractional part, as a long; null for anything else. */
    private static Long whole(Object v) {
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof java.math.BigInteger b) {
            return b.bitLength() < 64 ? b.longValue() : null;
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            return Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 9.0e15 ? (long) d : null;
        }
        return null;
    }

    /**
     * The {@code x-decisionnode-boosters} header, {@code age=ok:212,ai_generated=timeout:800}: each
     * booster's status and time, in the order sent. Empty when the header is absent. An entry
     * without {@code =} is skipped; a time that is not a number is null.
     */
    static Map<String, BoosterStatus> boosters(String header) {
        Map<String, BoosterStatus> out = new LinkedHashMap<>();
        if (header == null) {
            return Collections.unmodifiableMap(out);
        }
        for (String part : header.split(",")) {
            String item = part.trim();
            int eq = item.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = item.substring(0, eq).trim();
            String rest = item.substring(eq + 1).trim();
            int colon = rest.lastIndexOf(':');
            String status = colon < 0 ? rest : rest.substring(0, colon).trim();
            Double ms = null;
            if (colon >= 0) {
                try {
                    double d = Double.parseDouble(rest.substring(colon + 1).trim());
                    ms = Double.isFinite(d) ? d : null;
                } catch (NumberFormatException ignored) {
                    // no time
                }
            }
            out.put(name, new BoosterStatus(status, ms));
        }
        return Collections.unmodifiableMap(out);
    }

    /** The server's notes, exactly as sent: the strings of the list; empty when there is none. */
    static List<String> warnings(Object value) {
        List<Object> list = Json.asList(value);
        if (list == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object w : list) {
            if (w instanceof String) {
                out.add((String) w);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Typed answers by question id, in the order sent; empty when the value is not an object. */
    static Map<String, Answer> answers(Object value) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        Map<String, Object> raw = Json.asMap(value);
        if (raw != null) {
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                Map<String, Object> a = Json.asMap(e.getValue());
                answers.put(e.getKey(), answer(a == null ? Map.of() : a));
            }
        }
        return answers;
    }

    static Usage usage(Map<String, Object> usage) {
        return usage == null
                ? new Usage(null, null)
                : new Usage(Json.asLong(usage.get("input_tokens")), Json.asLong(usage.get("output_tokens")),
                        Json.asLong(usage.get("video_frames")), Json.asDouble(usage.get("video_seconds")),
                        Json.asLong(usage.get("booster_tokens")), Json.asLong(usage.get("boosters")),
                        cost(Json.asMap(usage.get("cost"))));
    }

    /** {@code usage.cost}, every amount as sent and never summed; null when absent. */
    static Cost cost(Map<String, Object> cost) {
        if (cost == null) {
            return null;
        }
        Object free = cost.get("free_tier");
        return new Cost(Json.asDouble(cost.get("usd")), Json.asDouble(cost.get("model_usd")),
                Json.asDouble(cost.get("boosters_usd")), free instanceof Boolean b ? b : null,
                Collections.unmodifiableMap(cost));
    }

    /**
     * One answer. A known type whose fields are missing or of the wrong type is returned as an
     * {@link UnknownAnswer} with its raw fields, as an unknown type is, so the decision does not fail.
     */
    static Answer answer(Map<String, Object> raw) {
        Object type = raw.get("type");
        Map<String, Object> view = Collections.unmodifiableMap(raw);
        if ("truth".equals(type) || "noul".equals(type)) {
            // An answer under the older name of truth carries its value in the field of that
            // name: the same answer.
            Double truth = Json.asDouble(raw.get((String) type));
            if (truth != null) {
                return new TruthAnswer(truth, view);
            }
        } else if ("number".equals(type)) {
            Double number = Json.asDouble(raw.get("number"));
            Double expected = Json.asDouble(raw.get("expected"));
            Double confidence = Json.asDouble(raw.get("confidence"));
            Map<String, Double> p = numbers(raw.get("probabilities"));
            if (number != null && expected != null && confidence != null && p != null) {
                return new NumberAnswer(number, expected, confidence, p, view);
            }
        } else if ("choice".equals(type)) {
            Object choice = raw.get("choice");
            Double confidence = Json.asDouble(raw.get("confidence"));
            Map<String, Double> p = numbers(raw.get("probabilities"));
            if (choice instanceof String && confidence != null && p != null) {
                return new ChoiceAnswer((String) choice, confidence, p, view);
            }
        } else if (("point".equals(type) || "box".equals(type)) && (raw.containsKey("points") || raw.containsKey("boxes"))) {
            // Several places, told by the answer's own list: points for a point, boxes for a box.
            Answer places = places((String) type, raw, view);
            if (places != null) {
                return places;
            }
        } else if ("point".equals(type)) {
            double[] n = numbers(raw, "x", "y", "present", "confidence", "width", "height");
            if (n != null && raw.get("image") instanceof String) {
                return new PointAnswer(n[0], n[1], n[2], n[3], (String) raw.get("image"), n[4], n[5], view);
            }
        } else if ("box".equals(type)) {
            double[] n = numbers(raw, "x1", "y1", "x2", "y2", "present", "confidence", "width", "height");
            if (n != null && raw.get("image") instanceof String) {
                return new BoxAnswer(n[0], n[1], n[2], n[3], n[4], n[5], (String) raw.get("image"), n[6], n[7], view);
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
     * A {@link PointsAnswer} or {@link BoxesAnswer}: count a JSON integer of at least 0, present,
     * complete, width and height numbers, image a string, and the list of the question's type
     * (points or boxes) whose every entry has its numbers; null when anything is missing or of
     * another type.
     */
    private static Answer places(String type, Map<String, Object> raw, Map<String, Object> view) {
        boolean point = type.equals("point");
        Long count = integer(raw.get("count"));
        double[] n = numbers(raw, "present", "complete", "width", "height");
        List<Object> found = Json.asList(raw.get(point ? "points" : "boxes"));
        if (count == null || count < 0 || n == null || found == null || !(raw.get("image") instanceof String)) {
            return null;
        }
        String image = (String) raw.get("image");
        if (point) {
            List<PlacePoint> points = new ArrayList<>(found.size());
            for (Object o : found) {
                Map<String, Object> p = Json.asMap(o);
                double[] v = p == null ? null : numbers(p, "x", "y", "confidence");
                if (v == null) {
                    return null;
                }
                points.add(new PlacePoint(v[0], v[1], v[2]));
            }
            return new PointsAnswer(count, n[0], n[1], Collections.unmodifiableList(points), image, n[2], n[3], view);
        }
        List<PlaceBox> boxes = new ArrayList<>(found.size());
        for (Object o : found) {
            Map<String, Object> b = Json.asMap(o);
            double[] v = b == null ? null : numbers(b, "x1", "y1", "x2", "y2", "confidence");
            if (v == null) {
                return null;
            }
            boxes.add(new PlaceBox(v[0], v[1], v[2], v[3], v[4]));
        }
        return new BoxesAnswer(count, n[0], n[1], Collections.unmodifiableList(boxes), image, n[2], n[3], view);
    }

    /** The numbers of these fields, in order; null when one is missing or not a JSON number. */
    private static double[] numbers(Map<String, Object> raw, String... fields) {
        double[] out = new double[fields.length];
        for (int i = 0; i < fields.length; i++) {
            Double v = Json.asDouble(raw.get(fields[i]));
            if (v == null) {
                return null;
            }
            out[i] = v;
        }
        return out;
    }

    /** A map of string keys to numbers, keys as sent and in order; null when it is not one. */
    private static Map<String, Double> numbers(Object value) {
        Map<String, Object> m = Json.asMap(value);
        if (m == null) {
            return null;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Double v = Json.asDouble(e.getValue());
            if (v == null) {
                return null;
            }
            out.put(e.getKey(), v);
        }
        return Collections.unmodifiableMap(out);
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
            Object fineTuned = m.get("fine_tuned");
            out.add(new ModelInfo(
                    str(m.get("name")),
                    str(m.get("description")),
                    str(m.get("release_date")),
                    fineTuned instanceof Boolean ? (Boolean) fineTuned : null,
                    str(m.get("base")),
                    whole(m.get("adapter_version")),
                    str(m.get("status")),
                    Json.asDouble(m.get("price_per_million_input")),
                    Json.asDouble(m.get("price_per_million_output")),
                    Json.asDouble(m.get("price_per_million_image")),
                    Json.asDouble(m.get("price_per_million_video")),
                    Json.asDouble(m.get("batch_price_factor")),
                    Collections.unmodifiableMap(limits),
                    Collections.unmodifiableMap(m)));
        }
        return Collections.unmodifiableList(out);
    }

    static DecisionNodeError error(int status, HttpHeaders headers, byte[] bodyBytes) {
        Object body;
        try {
            body = Json.read(bodyBytes);
        } catch (IOException e) {
            return notJsonError(status, of(headers), lowerCased(headers));
        }
        return error(status, of(headers), lowerCased(headers), body);
    }

    /** The status texts of an error whose body is not JSON, the same in every DecisionNode SDK. */
    private static final Map<Integer, String> STATUS_TEXT = Map.ofEntries(
            Map.entry(400, "Bad Request"),
            Map.entry(401, "Unauthorized"),
            Map.entry(402, "Payment Required"),
            Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"),
            Map.entry(405, "Method Not Allowed"),
            Map.entry(408, "Request Timeout"),
            Map.entry(409, "Conflict"),
            Map.entry(410, "Gone"),
            Map.entry(413, "Content Too Large"),
            Map.entry(422, "Unprocessable Content"),
            Map.entry(429, "Too Many Requests"),
            Map.entry(500, "Internal Server Error"),
            Map.entry(502, "Bad Gateway"),
            Map.entry(503, "Service Unavailable"),
            Map.entry(504, "Gateway Timeout"),
            Map.entry(529, "Overloaded"));

    /**
     * An error status whose body is not JSON (an HTML or text page of the edge or a proxy, or an
     * empty body): the class by status alone, no error type, and the client's own description,
     * {@code HTTP 413 Content Too Large (request id req_1)}. The body is never read into any field.
     */
    static DecisionNodeError notJsonError(int status, Headers headers, Map<String, List<String>> allHeaders) {
        String requestId = headers.get("x-request-id");
        String text = STATUS_TEXT.get(status);
        String message = "HTTP " + status + (text == null ? "" : " " + text)
                + (requestId == null ? "" : " (request id " + requestId + ")");
        ErrorInfo info = new ErrorInfo(
                status, null, message, requestId, headers.get("x-decisionnode-error"), null, allHeaders);
        return switch (status) {
            case 400 -> new BadRequestError(info);
            case 401 -> new AuthenticationError(info);
            case 402 -> new InsufficientCreditError(info);
            case 403 -> new PermissionDeniedError(info);
            case 404 -> new NotFoundError(info);
            case 413 -> new RequestTooLargeError(info);
            case 422 -> new ValidationError(info, List.of());
            case 429 -> new RateLimitError(info, retryAfter(headers));
            case 529 -> new OverloadedError(info, retryAfter(headers));
            default -> status >= 500 && status <= 599 ? new ServerError(info) : new DecisionNodeError(info);
        };
    }

    /**
     * The error class and fields a status, its headers and its parsed body give: the rules of a
     * live response, used for a batch result line too.
     */
    static DecisionNodeError error(int status, Headers headers, Map<String, List<String>> allHeaders, Object body) {
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
                status, errorType, message == null ? "" : message, headers.get("x-request-id"), headers.get("x-decisionnode-error"),
                body, allHeaders);
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
                return new RefusalError(info, category, Safety.parse(headers.get("x-decisionnode-safety")));
            }
            return new PermissionDeniedError(info);
        }
        if (status == 404) {
            return new NotFoundError(info);
        }
        if (status == 413) {
            return new RequestTooLargeError(info);
        }
        if (status == 422) {
            return new ValidationError(info, entries);
        }
        if (status == 429) {
            if (SessionLimitError.ERROR_TYPE.equals(errorType)) {
                return new SessionLimitError(info, retryAfter(headers));
            }
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
        return retryAfter(of(headers));
    }

    static Double retryAfter(Headers headers) {
        Double seconds = requestedWait(headers);
        return seconds == null || seconds.isNaN() ? null : Math.min(MAX_RETRY_AFTER, seconds);
    }

    private static Double requestedWait(Headers headers) {
        String ms = headers.get("retry-after-ms");
        if (ms != null) {
            try {
                return Math.max(0.0, Double.parseDouble(ms.trim()) / 1000.0);
            } catch (NumberFormatException ignored) {
                // fall through to Retry-After
            }
        }
        String s = headers.get("retry-after");
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

    static Map<String, List<String>> lowerCased(HttpHeaders headers) {
        Map<String, List<String>> out = new TreeMap<>();
        if (headers != null) {
            headers.map().forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT), List.copyOf(v)));
        }
        return Collections.unmodifiableMap(out);
    }

    static String str(Object v) {
        return v instanceof String ? (String) v : null;
    }
}
