package com.bynn.decisionnode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The API's fixed, documented request rules, checked before any request so a request the API
 * would refuse costs no round trip. The order is the server's: the nesting limit, then every 422
 * entry of the request, then the first 400 in question order, then the images. A fault raises
 * what the API would answer: the same class and {@code error_type}, the documented message, and
 * for a 422 the API's entries, without a status or a request id. A rule whose value only
 * {@code GET /v1/models} states is checked against the value the client holds from an earlier
 * {@link DecisionNode#models()} call, and skipped when it holds none. Token counts and anything
 * that depends on server state stay the server's to judge.
 */
final class LocalChecks {
    static final int MAX_IMAGES = 16;
    static final int MAX_VIDEOS = 4;
    static final double MAX_FPS = 4;
    static final long MAX_PIXELS = 40_000_000L;
    static final int MAX_URL_CHARS = 2048;
    static final int MAX_CHOICE_OPTIONS = 255;
    static final int MAX_SCORE_LEVELS = 10;
    static final int MAX_GRID_VALUES = 4096;
    static final int MAX_DECIMALS = 6;
    static final int MAX_NESTING = 128;
    static final int MAX_WINDOW = 64;
    static final int MAX_TTL_SECONDS = 3600;
    static final long MAX_SEQ = 9_007_199_254_740_991L;
    static final int MAX_CUSTOM_ID_CHARS = 512;
    static final int LIST_LIMIT_MAX = 100;
    private static final BigDecimal TWO_53 = BigDecimal.valueOf(9007199254740992L);
    /** The question types the API takes (the older name of truth included, for a question in its wire form). */
    private static final Set<String> DECIDE_TYPES = Set.of("truth", "noul", "choice", "score", "number", "point", "box");
    private static final Set<String> UNITS = Set.of("pixels", "normalized");
    private static final Set<String> MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private LocalChecks() {}

    // ------------------------------------------------------------------ requests

    /**
     * A decision body (or a batch line's, or the questions of a session): nesting, the state, and
     * every question. {@code body} holds the request's images as placeholders, so the nesting
     * counts them; the images themselves are checked by {@link #images}.
     */
    static void request(Map<String, Object> body, Map<String, Object> limits) {
        checkBody(body, limits, List.of(), null, List.of());
    }

    /**
     * {@link #request(Map, Map)} with the request's images, which a {@code point} or
     * {@code box} question must name among ({@link Image}s or wire-form maps), and its videos:
     * more images or videos than a request takes are 422 entries after the questions'.
     */
    static void request(Map<String, Object> body, Map<String, Object> limits, List<?> images,
            List<?> videos) {
        List<String> ids = new ArrayList<>(images.size());
        for (Object o : images) {
            Object id = o instanceof Image ? ((Image) o).id() : wireMap(o).get("id");
            if (id instanceof String) {
                ids.add((String) id);
            }
        }
        List<Map<String, Object>> extra = new ArrayList<>();
        checkBody(body, limits, extra, ids, counts(images, videos, limits));
    }

    /**
     * The API's 422 {@code too_long} entries for more images (16, or the held limit) or more videos
     * (4) than a request takes, the images' first. Each entry's input is the list in its wire form,
     * a local image as {@code {"id", "media_type"}}: nothing is encoded or uploaded for an error.
     */
    private static List<Map<String, Object>> counts(List<?> images, List<?> videos, Map<String, Object> limits) {
        List<Map<String, Object>> out = new ArrayList<>();
        int mostImages = held(limits, "max_images", held(limits, "images", "max_images", MAX_IMAGES));
        if (images.size() > mostImages) {
            List<Object> given = new ArrayList<>(images.size());
            for (Object o : images) {
                given.add(o instanceof Image ? imageInput((Image) o) : o);
            }
            out.add(tooLong("images", given, mostImages));
        }
        int mostVideos = held(limits, "videos", "max_videos", MAX_VIDEOS);
        if (videos.size() > mostVideos) {
            List<Object> given = new ArrayList<>(videos.size());
            for (Object o : videos) {
                given.add(o instanceof Video ? ((Video) o).toMap() : o);
            }
            out.add(tooLong("videos", given, mostVideos));
        }
        return out;
    }

    private static Map<String, Object> tooLong(String name, List<Object> given, int most) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("field_type", "List");
        ctx.put("max_length", most);
        ctx.put("actual_length", given.size());
        return entry("too_long", List.of("body", name),
                "List should have at most " + most + " items after validation, not " + given.size(), given, ctx);
    }

    /** An image as a 422 entry lists it: its wire form, a local image's bytes left out. */
    private static Map<String, Object> imageInput(Image img) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", img.id());
        if (img.url() != null) {
            out.put("url", img.url());
            return out;
        }
        if (img.uploadId() != null) {
            out.put("upload_id", img.uploadId());
        }
        if (img.mediaType() != null) {
            out.put("media_type", img.mediaType());
        }
        return out;
    }

    /**
     * {@code imageIds} null leaves out the point and box rules on images (a session's open);
     * {@code tail} holds the 422 entries that follow the questions' (the counts).
     */
    private static void checkBody(Map<String, Object> body, Map<String, Object> limits,
            List<Map<String, Object>> extra, List<String> imageIds, List<Map<String, Object>> tail) {
        nesting(body, 0);
        List<Map<String, Object>> entries = new ArrayList<>(extra);
        Object state = body.get("state");
        if (state != null && !sdl(state)) {
            entries.addAll(sdlEntries(List.of("body", "state"), state));
        }
        DecisionNodeError deferred = null;
        Map<String, Object> questions = Json.asMap(body.get("questions"));
        if (questions != null) {
            for (Map.Entry<String, Object> e : questions.entrySet()) {
                DecisionNodeError fault = question(e.getKey(), e.getValue(), entries, limits, imageIds);
                if (deferred == null) {
                    deferred = fault;
                }
            }
        }
        entries.addAll(tail);
        if (!entries.isEmpty()) {
            throw invalid(entries);
        }
        if (deferred != null) {
            throw deferred;
        }
    }

    /** The decision body as the server reads it, the images as placeholders. */
    static Map<String, Object> probe(Map<String, Object> head, int images) {
        return probe(head, images, List.of());
    }

    /**
     * The decision body as the server reads it, the images and the built videos as placeholders
     * of their depth, a video given as a map as it is.
     */
    static Map<String, Object> probe(Map<String, Object> head, int images, List<?> videos) {
        if (images == 0 && videos.isEmpty()) {
            return head;
        }
        Map<String, Object> p = new LinkedHashMap<>(head);
        if (images > 0) {
            List<Object> placeholders = new ArrayList<>(images);
            for (int i = 0; i < images; i++) {
                placeholders.add(Map.of("id", ""));
            }
            p.put("images", placeholders);
        }
        if (!videos.isEmpty()) {
            List<Object> placeholders = new ArrayList<>(videos.size());
            for (Object v : videos) {
                placeholders.add(v instanceof Video ? Map.of("frames", List.of(Map.of())) : v);
            }
            p.put("videos", placeholders);
        }
        return p;
    }

    /** The wire form of a question given as a {@link Question} or a map; null for anything else. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> wire(Object q) {
        if (q instanceof Question) {
            return ((Question) q).toMap();
        }
        return q instanceof Map ? (Map<String, Object>) q : null;
    }

    private static DecisionNodeError question(String qid, Object value, List<Map<String, Object>> entries,
            Map<String, Object> limits, List<String> imageIds) {
        List<Object> base = List.of("body", "questions", qid);
        Map<String, Object> q = wire(value);
        if (q == null && value != null && !scalar(value) && !(value instanceof Collection) && !value.getClass().isArray()) {
            q = Json.asMap(Json.MAPPER.convertValue(value, Object.class));
        }
        if (q == null) {
            entries.add(entry("model_type", base, "Input should be a valid dictionary or object", value, null));
            return null;
        }
        Object type = q.get("type");
        if (type == null) {
            entries.add(entry("missing", append(base, "type"), "Field required", q, null));
            return null;
        }
        if (!(type instanceof String) || !DECIDE_TYPES.contains(type)) {
            // An unknown type is the API's 400 "Invalid request.".
            return usage("Invalid request.", "question '" + qid + "': unknown type " + jsonText(type));
        }
        List<Object> loc = append(base, type);
        Object instructions = q.get("instructions");
        if (instructions != null && !sdl(instructions)) {
            entries.addAll(sdlEntries(append(loc, "instructions"), instructions));
        }
        Object criteria = q.get("criteria");
        switch ((String) type) {
            case "point":
            case "box":
                return place(qid, (String) type, q, loc, entries, imageIds);
            case "number": {
                Long most = Json.asLong(limits.get("number_max_grid_values"));
                if (most != null && most == 0) {
                    return usage("Model " + limits.get("model") + " does not answer number questions.",
                            "question '" + qid + "': model " + limits.get("model") + " does not answer number questions");
                }
                return number(qid, q, loc, entries, most != null ? most.intValue() : MAX_GRID_VALUES);
            }
            case "choice": {
                int most = held(limits, "max_choice_options", MAX_CHOICE_OPTIONS);
                if (criteria == null) {
                    entries.add(entry("missing", append(loc, "criteria"), "Field required", q, null));
                    return null;
                }
                if (!(criteria instanceof Map)) {
                    entries.add(entry("dict_type", append(loc, "criteria"), "Input should be a valid dictionary", criteria, null));
                    return null;
                }
                Map<?, ?> options = (Map<?, ?>) criteria;
                for (Map.Entry<?, ?> o : options.entrySet()) {
                    if (o.getValue() != null && !sdl(o.getValue())) {
                        entries.addAll(sdlEntries(append(append(loc, "criteria"), String.valueOf(o.getKey())), o.getValue()));
                    }
                }
                if (options.isEmpty()) {
                    return plain("Choice question must have at least one choice: " + qid,
                            "question '" + qid + "': a choice needs at least one option");
                }
                if (options.size() > most) {
                    return plain("Too many choices. Must have at most " + most + " choices.",
                            "question '" + qid + "': a choice takes up to " + most + " options, got " + options.size());
                }
                return null;
            }
            case "score": {
                int most = held(limits, "max_score_levels", MAX_SCORE_LEVELS);
                if (criteria == null) {
                    entries.add(entry("missing", append(loc, "criteria"), "Field required", q, null));
                    return null;
                }
                List<Object> levels = levels(criteria);
                if (levels == null) {
                    entries.add(entry("list_type", append(loc, "criteria"), "Input should be a valid list", criteria, null));
                    return null;
                }
                if (levels.isEmpty()) {
                    entries.add(entry("too_short", append(loc, "criteria"), "List should have at least 1 item after validation, not 0",
                            criteria, null));
                    return null;
                }
                for (int i = 0; i < levels.size(); i++) {
                    if (!sdl(levels.get(i))) {
                        entries.addAll(sdlEntries(append(append(loc, "criteria"), i), levels.get(i)));
                    }
                }
                if (levels.size() > most) {
                    return plain("Too many score levels. Must have at most " + most + " levels.",
                            "question '" + qid + "': a score takes up to " + most + " levels, got " + levels.size());
                }
                return null;
            }
            default: { // truth, and its older name in a question's wire form
                if (criteria != null && !(criteria instanceof Map)) {
                    entries.add(entry("dict_type", append(loc, "criteria"), "Input should be a valid dictionary", criteria, null));
                    return null;
                }
                Map<?, ?> crit = criteria == null ? Map.of() : (Map<?, ?>) criteria;
                for (String side : new String[] {"true", "false"}) {
                    Object v = crit.get(side);
                    if (v != null && !sdl(v)) {
                        entries.addAll(sdlEntries(append(append(loc, "criteria"), side), v));
                    }
                }
                if (instructions == null && crit.get("true") == null && crit.get("false") == null) {
                    String word = "truth".equals(type) ? "Truth" : "Noul";
                    return plain(word + " question must have criteria or instructions: " + qid,
                            "question '" + qid + "': a " + type + " question needs instructions or criteria");
                }
                return null;
            }
        }
    }

    /** Score levels: a list, or on /v1/decide an object keyed "0" to "K-1"; null when neither. */
    private static List<Object> levels(Object criteria) {
        if (criteria instanceof Collection) {
            return new ArrayList<>((Collection<?>) criteria);
        }
        if (criteria instanceof Object[]) {
            return List.of((Object[]) criteria);
        }
        if (criteria instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) criteria;
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < m.size(); i++) {
                if (!m.containsKey(String.valueOf(i))) {
                    return null;
                }
                out.add(m.get(String.valueOf(i)));
            }
            return out;
        }
        return null;
    }

    /** A number grid, checked as the API checks it, in exact decimals and in its order. */
    private static DecisionNodeError number(String qid, Map<String, Object> q, List<Object> loc,
            List<Map<String, Object>> entries, int maxValues) {
        String[] names = {"min", "max", "step"};
        BigDecimal[] defaults = {BigDecimal.ZERO, BigDecimal.valueOf(255), BigDecimal.ONE};
        BigDecimal[] values = new BigDecimal[3];
        boolean ok = true;
        for (int i = 0; i < 3; i++) {
            String name = names[i];
            Object sent = q.get(name);
            List<Object> floc = append(loc, name);
            if (sent == null) {
                values[i] = defaults[i];
                continue;
            }
            if (!(sent instanceof Number)) {
                entries.add(entry("float_type", floc, "Input should be a valid number", sent, null));
                ok = false;
                continue;
            }
            BigDecimal dec = decimal((Number) sent);
            if (dec == null) {
                entries.add(entry("finite_number", floc, "Input should be a finite number", sent, null));
                ok = false;
                continue;
            }
            if (i < 2 && dec.compareTo(TWO_53.negate()) < 0) {
                entries.add(entry("greater_than_equal", floc, "Input should be greater than or equal to -9007199254740992",
                        sent, Map.of("ge", -9007199254740992L)));
                ok = false;
                continue;
            }
            if (i < 2 && dec.compareTo(TWO_53) > 0) {
                entries.add(entry("less_than_equal", floc, "Input should be less than or equal to 9007199254740992",
                        sent, Map.of("le", 9007199254740992L)));
                ok = false;
                continue;
            }
            if (i == 2 && dec.signum() <= 0) {
                entries.add(entry("greater_than", floc, "Input should be greater than 0", sent, Map.of("gt", 0)));
                ok = false;
                continue;
            }
            if (i != 1 && places(dec) > MAX_DECIMALS) {
                entries.add(entry("decimal_max_places", floc, "Decimal input should have no more than " + MAX_DECIMALS + " decimal places",
                        sent, Map.of("decimal_places", MAX_DECIMALS)));
                ok = false;
                continue;
            }
            values[i] = dec;
        }
        if (!ok) {
            return null;
        }
        BigDecimal lo = values[0];
        BigDecimal hi = values[1];
        BigDecimal step = values[2];
        int d = Math.max(places(lo), places(step));
        if (d > 0) {
            BigDecimal bound = BigDecimal.TEN.pow(15 - d).subtract(BigDecimal.ONE.movePointLeft(d));
            String b = text(bound);
            for (int i = 0; i < 2; i++) {
                BigDecimal v = i == 0 ? lo : hi;
                if (v.compareTo(bound) > 0) {
                    entries.add(entry("less_than_equal", append(loc, names[i]), "Input should be less than or equal to " + b,
                            q.get(names[i]), Map.of("le", jsonNumber(bound))));
                    return null;
                }
                if (v.compareTo(bound.negate()) < 0) {
                    entries.add(entry("greater_than_equal", append(loc, names[i]), "Input should be greater than or equal to -" + b,
                            q.get(names[i]), Map.of("ge", jsonNumber(bound.negate()))));
                    return null;
                }
            }
        }
        if (lo.compareTo(hi) > 0) {
            Object maxSent = q.get("max") != null ? q.get("max") : 255;
            Object minSent = q.get("min") != null ? q.get("min") : 0;
            entries.add(entry("less_than_equal", append(loc, "min"), "Input should be less than or equal to " + jsonText(maxSent),
                    minSent, Map.of("le", maxSent)));
            return null;
        }
        BigInteger m = lo.movePointRight(d).toBigIntegerExact();
        BigInteger s = step.movePointRight(d).toBigIntegerExact();
        BigInteger top = hi.movePointRight(d).setScale(0, RoundingMode.FLOOR).toBigIntegerExact();
        BigInteger n = top.subtract(m).divide(s).add(BigInteger.ONE);
        if (n.compareTo(BigInteger.valueOf(maxValues)) > 0) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("field_type", "Grid");
            ctx.put("max_length", maxValues);
            ctx.put("actual_length", n.longValue());
            entries.add(entry("too_long", loc, "Grid should have at most " + maxValues + " values, not " + n, q, ctx));
            return null;
        }
        Object instructions = q.get("instructions");
        if (instructions == null || "".equals(instructions)) {
            return plain("Number question must have instructions: " + qid, "question '" + qid + "': a number question needs instructions");
        }
        return null;
    }

    // ------------------------------------------------------------------ nesting

    /**
     * Refuses a value with more than 128 objects and arrays inside each other, the outermost
     * included; {@code outer} levels already surround it.
     */
    static void nesting(Object value, int outer) {
        int depth = outer + depth(value);
        if (depth > MAX_NESTING) {
            throw usage("Invalid request.", "the request nests " + depth + " objects and arrays inside each other; the limit is " + MAX_NESTING);
        }
    }

    /** How many objects and arrays sit inside each other in a value, the outermost included. */
    static int depth(Object value) {
        int deepest = 0;
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[] {value, 1});
        while (!stack.isEmpty()) {
            Object[] top = stack.pop();
            Object node = top[0];
            int d = (Integer) top[1];
            Iterable<?> children;
            if (node instanceof Question) {
                children = ((Question) node).toMap().values();
            } else if (node instanceof Map) {
                children = ((Map<?, ?>) node).values();
            } else if (node instanceof Collection) {
                children = (Collection<?>) node;
            } else if (node instanceof Object[]) {
                children = List.of((Object[]) node);
            } else if (node == null || scalar(node) || node instanceof byte[]) {
                continue;
            } else if (node.getClass().isArray()) {
                children = List.of(); // an array of primitives: one level
            } else {
                // Any other value is written by the JSON mapper: count what it writes.
                Object tree = Json.MAPPER.convertValue(node, Object.class);
                if (!(tree instanceof Map) && !(tree instanceof Collection)) {
                    continue;
                }
                stack.push(new Object[] {tree, d});
                continue;
            }
            deepest = Math.max(deepest, d);
            for (Object c : children) {
                if (c != null && !scalar(c) && !(c instanceof byte[])) {
                    stack.push(new Object[] {c, d + 1});
                }
            }
        }
        return deepest;
    }

    /**
     * A {@code point} or {@code box} question: its image among the request's (the 422 entries),
     * its units and its max (422 entries), then its instructions (the plain 400). Without image ids (a
     * session's open) the image rules are left out.
     */
    /** The fewest and the most places a point or box question's {@code max} asks for. */
    static final int PLACES_MIN = 2;
    static final int PLACES_MAX = 16;

    private static DecisionNodeError place(String qid, String type, Map<String, Object> q, List<Object> loc,
            List<Map<String, Object>> entries, List<String> imageIds) {
        if (imageIds != null) {
            Object image = q.get("image");
            if (imageIds.isEmpty()) {
                entries.add(entry("image_required", loc, "A " + type + " question needs an image in the request", q, null));
            } else if (image == null) {
                if (imageIds.size() >= 2) {
                    entries.add(entry("missing", append(loc, "image"), "Field required", q, null));
                }
            } else if (!(image instanceof String)) {
                entries.add(entry("string_type", append(loc, "image"), "Input should be a valid string", image, null));
            } else if (!imageIds.contains(image)) {
                entries.add(entry("unknown_image", append(loc, "image"), "Input should be the id of an image of the request",
                        image, null));
            }
        }
        Object units = q.get("units");
        if (units != null && !(units instanceof String && UNITS.contains(units))) {
            entries.add(entry("literal_error", append(loc, "units"), "Input should be 'pixels' or 'normalized'", units,
                    Map.of("expected", "'pixels' or 'normalized'")));
        }
        Object max = q.get("max");
        if (max != null) {
            // Several places: max a JSON integer (never 2.5, "8" or true) from 2 to 16. Whether the
            // model answers several places is the server's to say.
            boolean integer = max instanceof Integer || max instanceof Long || max instanceof Short || max instanceof Byte
                    || max instanceof java.math.BigInteger;
            long most = integer ? new java.math.BigInteger(max.toString()).max(java.math.BigInteger.valueOf(-1))
                    .min(java.math.BigInteger.valueOf(PLACES_MAX + 1L)).longValue() : 0;
            if (!integer) {
                entries.add(entry("int_type", append(loc, "max"), "Input should be a valid integer", max, null));
            } else if (most < PLACES_MIN) {
                entries.add(entry("greater_than_equal", append(loc, "max"), "Input should be greater than or equal to " + PLACES_MIN,
                        max, Map.of("ge", PLACES_MIN)));
            } else if (most > PLACES_MAX) {
                entries.add(entry("less_than_equal", append(loc, "max"), "Input should be less than or equal to " + PLACES_MAX,
                        max, Map.of("le", PLACES_MAX)));
            }
        }
        if (q.get("instructions") == null) {
            String name = type.equals("point") ? "Point" : "Box";
            return plain(name + " question must have instructions: " + qid,
                    "question '" + qid + "': a " + type + " question needs instructions");
        }
        return null;
    }

    // ------------------------------------------------------------------ images

    /**
     * Every image of a request: ids, URLs, upload references and local bytes. Their count is one
     * of the request's 422 entries ({@link #request(Map, Map, List, List)}).
     */
    /** The longest image id the API takes, in characters (Unicode code points). */
    static final int MAX_IMAGE_ID_CHARS = 64;

    static void images(List<?> images, Map<String, Object> limits) {
        images(images, limits, true);
    }

    /**
     * The image rules; {@code idLength} false leaves out the id's length, for {@code upload},
     * which sends no id.
     */
    static void images(List<?> images, Map<String, Object> limits, boolean idLength) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < images.size(); i++) {
            Object o = images.get(i);
            Object id = o instanceof Image ? ((Image) o).id() : wireMap(o).get("id");
            if (!(id instanceof String) || ((String) id).isEmpty()) {
                throw usage("Image " + i + " needs an id.", null);
            }
            String text = (String) id;
            // Counted in code points, as the API counts characters, never UTF-16 units.
            if (idLength && text.codePointCount(0, text.length()) > MAX_IMAGE_ID_CHARS) {
                throw usage("Image " + i + ": its id is longer than " + MAX_IMAGE_ID_CHARS + " characters.", null);
            }
            if (!ids.add(text)) {
                throw usage("Duplicate image id: " + id, null);
            }
            if (o instanceof Image) {
                image((Image) o, limits);
            }
        }
    }

    /** One image: a URL, an upload reference, or local bytes. */
    static void image(Image img, Map<String, Object> limits) {
        String id = img.id();
        if (img.url() != null) {
            url(id, img.url());
            return;
        }
        if (img.uploadId() != null) {
            if (img.mediaType() != null && !MEDIA_TYPES.contains(img.mediaType())) {
                throw usage("Image " + id + ": media_type must be image/jpeg, image/png or image/webp.", null);
            }
            return;
        }
        if (!img.isLocal()) {
            return;
        }
        long biggest = held(limits, "max_image_bytes", held(limits, "images", "max_image_bytes", Image.MAX_BYTES));
        if (img.size() > biggest || img.bytes() == null) {
            throw usage("Image " + id + " is larger than 10 MB.", null);
        }
        String found = img.sniffed();
        if (found == null) {
            throw usage("Image " + id + " is not a JPEG, PNG or WebP image.", null);
        }
        if (img.declared() != null && !img.declared().equals(found)) {
            throw usage("Image " + id + " could not be decoded as " + img.declared() + ".", null);
        }
        int[] dims = img.dimensions();
        if (dims == null) {
            throw usage("Image " + id + ": its header cannot be read.", null);
        }
        if ((long) dims[0] * dims[1] > MAX_PIXELS) {
            throw usage("Image " + id + " is " + dims[0] + "x" + dims[1] + " pixels, over 40 megapixels.", null);
        }
    }

    /**
     * The URL rules that need no network: {@code https} (else the API's
     * {@code api_usage_error}), then {@code image_url_refused} for the length, user information,
     * a port other than 443, no host, or a literal address in a blocked range. A DNS name is the
     * server's to resolve.
     */
    static void url(String id, String url) {
        if (!url.startsWith("https://")) {
            throw usage("Image " + id + ": only https URLs are accepted.", null);
        }
        String reason = null;
        String rest = url.substring(8);
        int end = rest.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int k = rest.indexOf(c);
            if (k >= 0 && k < end) {
                end = k;
            }
        }
        String authority = rest.substring(0, end);
        if (url.length() > MAX_URL_CHARS) {
            reason = "the URL is " + url.length() + " characters, over the limit of " + MAX_URL_CHARS;
        } else if (authority.contains("@")) {
            reason = "the URL carries user information";
        } else {
            String host = authority;
            String port = null;
            if (host.startsWith("[")) {
                int close = host.indexOf(']');
                if (close < 0) {
                    host = "";
                } else {
                    port = host.length() > close + 1 && host.charAt(close + 1) == ':' ? host.substring(close + 2) : null;
                    host = host.substring(1, close);
                }
            } else if (host.contains(":")) {
                port = host.substring(host.indexOf(':') + 1);
                host = host.substring(0, host.indexOf(':'));
            }
            if (port != null && !port.isEmpty() && !port.equals("443")) {
                reason = "the URL uses a port other than 443";
            } else if (host.isEmpty()) {
                reason = "the URL has no host";
            } else if (blocked(host)) {
                reason = "the URL resolves to a private address";
            }
        }
        if (reason != null) {
            String message = "Image '" + id + "': " + reason + ".";
            throw new BadRequestError(info("image_url_refused", message, message));
        }
    }

    private static final String[] BLOCKED = {
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
        "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15",
        "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32",
        "::/128", "::1/128", "fc00::/7", "fe80::/10", "ff00::/8", "2001:db8::/32", "fd00:ec2::254/128",
    };

    /** True for a literal IP address in a blocked range; a DNS name is never looked up here. */
    static boolean blocked(String host) {
        byte[] addr;
        if (IPV4.matcher(host).matches()) {
            addr = literal(host);
        } else if (host.contains(":")) {
            addr = literal(host);
        } else {
            return false;
        }
        if (addr == null) {
            return false;
        }
        if (addr.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= addr[i] == 0;
            }
            mapped &= (addr[10] & 0xff) == 0xff && (addr[11] & 0xff) == 0xff;
            boolean nat64 = (addr[0] & 0xff) == 0x00 && (addr[1] & 0xff) == 0x64 && (addr[2] & 0xff) == 0xff
                    && (addr[3] & 0xff) == 0x9b;
            for (int i = 4; i < 12 && nat64; i++) {
                nat64 = addr[i] == 0;
            }
            if (mapped || nat64) {
                addr = new byte[] {addr[12], addr[13], addr[14], addr[15]};
            }
        }
        for (String net : BLOCKED) {
            int slash = net.indexOf('/');
            byte[] base = literal(net.substring(0, slash));
            int bits = Integer.parseInt(net.substring(slash + 1));
            if (base != null && base.length == addr.length && inRange(addr, base, bits)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] literal(String host) {
        if (IPV4.matcher(host).matches()) {
            String[] parts = host.split("\\.");
            byte[] out = new byte[4];
            for (int i = 0; i < 4; i++) {
                int v = Integer.parseInt(parts[i]);
                if (v > 255) {
                    return null;
                }
                out[i] = (byte) v;
            }
            return out;
        }
        try {
            // An IPv6 literal is parsed, never resolved.
            byte[] a = InetAddress.getByName(host.contains("%") ? host.substring(0, host.indexOf('%')) : host).getAddress();
            if (a.length == 4) {
                // The JDK reads an IPv4-mapped literal as IPv4: put it back in mapped form.
                byte[] mapped = new byte[16];
                mapped[10] = (byte) 0xff;
                mapped[11] = (byte) 0xff;
                System.arraycopy(a, 0, mapped, 12, 4);
                return mapped;
            }
            return a;
        } catch (UnknownHostException | RuntimeException e) {
            return null;
        }
    }

    private static boolean inRange(byte[] addr, byte[] base, int bits) {
        for (int i = 0; i < bits; i++) {
            int mask = 0x80 >> (i % 8);
            if ((addr[i / 8] & mask) != (base[i / 8] & mask)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ sessions

    /**
     * The open body: nesting, {@code instructions} and {@code state} types, {@code window} 1 to
     * 64 and {@code ttl_seconds} 1 to 3,600 (a held value replaces the bound), every question,
     * then a model that the client knows serves no sessions.
     */
    static void sessionOpen(Map<String, Object> body, Map<String, Object> limits) {
        nesting(body, 0);
        Map<String, Object> sessions = Json.asMap(limits.get("sessions"));
        sessions = sessions == null ? Map.of() : sessions;
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String name : new String[] {"instructions", "state"}) {
            Object v = body.get(name);
            if (v != null && !sdl(v)) {
                entries.addAll(sdlEntries(List.of("body", name), v));
            }
        }
        Map<String, Integer> bounds = new LinkedHashMap<>();
        bounds.put("window", held(sessions, "max_window", MAX_WINDOW));
        bounds.put("ttl_seconds", held(sessions, "max_ttl_seconds", MAX_TTL_SECONDS));
        for (Map.Entry<String, Integer> b : bounds.entrySet()) {
            Object v = body.get(b.getKey());
            if (v == null) {
                continue;
            }
            List<Object> loc = List.of("body", b.getKey());
            if (!(v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte || v instanceof BigInteger)) {
                entries.add(entry("int_type", loc, "Input should be a valid integer", v, null));
            } else if (((Number) v).longValue() < 1) {
                entries.add(entry("greater_than_equal", loc, "Input should be greater than or equal to 1", v, Map.of("ge", 1)));
            } else if (((Number) v).longValue() > b.getValue()) {
                entries.add(entry("less_than_equal", loc, "Input should be less than or equal to " + b.getValue(), v,
                        Map.of("le", b.getValue())));
            }
        }
        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("questions", body.get("questions"));
        checkBody(questions, limits, entries, null, List.of());
        if (Boolean.FALSE.equals(sessions.get("enabled"))) {
            throw usage("Model " + limits.get("model") + " does not serve sessions.", null);
        }
    }

    /**
     * One frame, by the rules of the frame error the server would send: {@code seq} 0 to 2^53 -
     * 1; a frame value or an image; a frame value that is a string, an object or an array and not
     * empty; {@code questions} non-empty and of the session's ids; the nesting limit; an image by
     * bytes or upload, never by URL, within the image rules.
     */
    static void frame(Frame f, Object sessionQuestions, Map<String, Object> limits) {
        if (f.seq() != null && (f.seq() < 0 || f.seq() > MAX_SEQ)) {
            throw usage("seq: must be an integer from 0 to " + MAX_SEQ + ".", null);
        }
        Object frame = f.frame();
        if (frame == null && f.image() == null) {
            throw usage("frame: a frame needs a frame value or an image.", null);
        }
        if (frame != null && (!sdl(frame) || "".equals(frame))) {
            throw usage("frame: must be a string, an object or an array, not empty.", null);
        }
        if (f.questions() != null) {
            if (f.questions().isEmpty()) {
                throw usage("questions: must be a non-empty list of the session's question ids.", null);
            }
            if (sessionQuestions instanceof Collection) {
                for (String qid : f.questions()) {
                    if (!((Collection<?>) sessionQuestions).contains(qid)) {
                        throw usage("questions: unknown question id '" + qid + "'", null);
                    }
                }
            }
        }
        if (frame != null && depth(frame) + 1 > MAX_NESTING) {
            throw usage("The frame nests more than " + MAX_NESTING + " objects and arrays inside each other.", null);
        }
        if (f.image() instanceof Image) {
            Image img = (Image) f.image();
            if (img.url() != null) {
                throw usage("image: a frame takes bytes or an upload, not a url.", null);
            }
            image(img, limits);
        }
    }

    // ------------------------------------------------------------------ batches

    /**
     * The {@code custom_id} rules of one create or add call, lines numbered from 1 within it: one
     * present, of 1 to 512 characters (or the held {@code max_custom_id_chars}), not repeated;
     * an add with no requests; more requests than a held {@code requests_per_batch}.
     */
    static void lines(List<BatchRequest> requests, boolean allowEmpty, Map<String, Object> limits) {
        if (requests.isEmpty() && !allowEmpty) {
            throw usage("The call holds no requests.", null);
        }
        Map<String, Object> batch = Json.asMap(limits.get("batch"));
        batch = batch == null ? Map.of() : batch;
        Long most = Json.asLong(batch.get("requests_per_batch"));
        if (most != null && requests.size() > most) {
            throw usage("The batch would hold " + requests.size() + " requests. The limit is " + most + ".", null);
        }
        Long longest = Json.asLong(batch.get("max_custom_id_chars"));
        Set<String> seen = new HashSet<>();
        for (int n = 1; n <= requests.size(); n++) {
            BatchRequest r = requests.get(n - 1);
            if (r == null) {
                throw new IllegalArgumentException("requests[" + (n - 1) + "] is null");
            }
            String cid = r.customId();
            if (cid == null) {
                throw usage("Line " + n + " has no custom_id.", null);
            }
            if (cid.isEmpty() || (longest != null && cid.length() > longest)) {
                throw usage("Line " + n + ": custom_id must be a string of 1 to " + (longest != null ? longest : MAX_CUSTOM_ID_CHARS)
                        + " characters.", null);
            }
            if (!seen.add(cid)) {
                String shown = jsonText(cid);
                throw usage("Line " + n + ": custom_id " + (shown.length() <= 64 ? shown : shown.substring(0, 64) + "...")
                        + " is already in the batch.", null);
            }
        }
    }

    /** {@code offset} 0 or more, {@code limit} 1 to 100. */
    /** The longest collection name, item reference and item label, in characters. */
    static final int COLLECTION_NAME_MAX = 80;
    static final int ITEM_REFERENCE_MAX = 64;
    static final int ITEM_LABEL_MAX = 128;
    static final String ITEM_REFERENCE_PATTERN = "^[A-Za-z0-9_.:-]{1,64}$";

    /** A collection's create: type {@code face}, name 1 to 80 characters (the API's 422 entries). */
    static void collectionCreate(Object type, Object name) {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (!"face".equals(type)) {
            entries.add(entry("literal_error", List.of("body", "type"), "Input should be 'face'", echoed(type),
                    Map.of("expected", "'face'")));
        }
        entries.addAll(stringEntries(List.of("body", "name"), name, COLLECTION_NAME_MAX, null));
        if (!entries.isEmpty()) {
            throw invalid(entries);
        }
    }

    /**
     * An enrollment: an image, a reference of 1 to 64 of {@code [A-Za-z0-9_.:-]} (its length first,
     * then the pattern) and, when given, a label of 1 to 128 characters (the API's 422 entries). Of
     * the image only a URL's rules are checked here.
     */
    static void collectionEnroll(Object image, Object reference, Object label) {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (image == null) {
            entries.add(entry("missing", List.of("body", "image"), "Field required", null, null));
        }
        entries.addAll(stringEntries(List.of("body", "reference"), reference, ITEM_REFERENCE_MAX, ITEM_REFERENCE_PATTERN));
        if (label != null) {
            entries.addAll(stringEntries(List.of("body", "label"), label, ITEM_LABEL_MAX, null));
        }
        if (!entries.isEmpty()) {
            throw invalid(entries);
        }
    }

    /** How many characters of a string input a collections 422 entry echoes. */
    static final int ECHO_CHARS = 64;

    /**
     * An input as a collections 422 entry echoes it, as the API does: a string cut to its first 64
     * characters (Unicode code points) with "..." added when it was longer, an object or an array
     * never (null), anything else as given.
     */
    static Object echoed(Object value) {
        if (value instanceof String text) {
            if (text.codePointCount(0, text.length()) <= ECHO_CHARS) {
                return text;
            }
            return text.substring(0, text.offsetByCodePoints(0, ECHO_CHARS)) + "...";
        }
        if (value instanceof Map || value instanceof List) {
            return null;
        }
        return value;
    }

    /**
     * A collections string field's 422 entry, as the API answers it: missing, not a string, empty,
     * longer than {@code most} characters (Unicode code points), or not matching {@code pattern};
     * its input echoed within bounds.
     */
    private static List<Map<String, Object>> stringEntries(List<Object> loc, Object value, int most, String pattern) {
        if (value == null) {
            return List.of(entry("missing", loc, "Field required", null, null));
        }
        if (!(value instanceof String text)) {
            return List.of(entry("string_type", loc, "Input should be a valid string", echoed(value), null));
        }
        if (text.isEmpty()) {
            return List.of(entry("string_too_short", loc, "String should have at least 1 character", text, Map.of("min_length", 1)));
        }
        if (text.codePointCount(0, text.length()) > most) {
            return List.of(entry("string_too_long", loc, "String should have at most " + most + " characters", echoed(text),
                    Map.of("max_length", most)));
        }
        if (pattern != null && !java.util.regex.Pattern.compile(pattern).matcher(text).matches()) {
            return List.of(entry("string_pattern_mismatch", loc, "String should match pattern '" + pattern + "'", text,
                    Map.of("pattern", pattern)));
        }
        return List.of();
    }

    static void listParams(Integer offset, Integer limit) {
        if (offset != null && offset < 0) {
            throw usage("Query parameter offset must be an integer of 0 or more, got " + offset + ".", null);
        }
        if (limit != null && (limit < 1 || limit > LIST_LIMIT_MAX)) {
            throw usage("Query parameter limit must be an integer from 1 to " + LIST_LIMIT_MAX + ", got " + limit + ".", null);
        }
    }

    // ------------------------------------------------------------------ values

    /** The exact decimal of a JSON number as it is written; null for a value that is not finite. */
    static BigDecimal decimal(Number n) {
        if (n instanceof BigDecimal) {
            return (BigDecimal) n;
        }
        if (n instanceof BigInteger) {
            return new BigDecimal((BigInteger) n);
        }
        if (n instanceof Double || n instanceof Float) {
            double v = n.doubleValue();
            if (!Double.isFinite(v)) {
                return null;
            }
            // The shortest text that reads back as this number: what the JSON writer sends.
            return new BigDecimal(n instanceof Float ? Float.toString(n.floatValue()) : Double.toString(v));
        }
        return BigDecimal.valueOf(n.longValue());
    }

    private static int places(BigDecimal v) {
        return Math.max(0, v.stripTrailingZeros().scale());
    }

    /** The shortest plain decimal form: no exponent, no trailing zeros. */
    private static String text(BigDecimal v) {
        return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static Object jsonNumber(BigDecimal v) {
        String t = text(v);
        return t.contains(".") ? (Object) Double.parseDouble(t) : (Object) new BigInteger(t);
    }

    private static String jsonText(Object v) {
        return new String(Json.write(v), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A value the JSON writer turns into a number, a boolean or a string. */
    private static boolean scalar(Object v) {
        return v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character || v instanceof Enum;
    }

    /** A string, an object or an array once written: everything but a number or a boolean. */
    private static boolean sdl(Object v) {
        return !(v instanceof Number) && !(v instanceof Boolean);
    }

    private static int held(Map<String, Object> limits, String name, int dflt) {
        Long v = Json.asLong(limits.get(name));
        return v != null && v > 0 ? v.intValue() : dflt;
    }

    /** A limit the API publishes in an object of its own, such as {@code images.max_images}. */
    private static int held(Map<String, Object> limits, String group, String name, int dflt) {
        Map<String, Object> g = Json.asMap(limits.get(group));
        return g == null ? dflt : held(g, name, dflt);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> wireMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    // ------------------------------------------------------------------ videos

    /**
     * Every video of a request, after its images, in the server's order (their count is one of the
     * request's 422 entries): each video in turn (an id present and unique across the images and the videos, at least one
     * frame, frame times at least 0 and strictly increasing, a JPEG, PNG or WebP media type for
     * every frame, {@code fps} above 0 and at most 4, {@code start} and {@code end} at least 0 and
     * {@code end} after {@code start}). The frame counts, bytes and pixels are the server's to
     * judge. Returns the videos' wire forms.
     *
     * @throws IllegalArgumentException for a video that is neither a {@link Video} nor a map
     */
    static List<Object> videos(List<?> videos, List<?> images, Map<String, Object> limits) {
        if (videos.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        for (Object o : images) {
            Object id = o instanceof Image ? ((Image) o).id() : wireMap(o).get("id");
            if (id instanceof String) {
                seen.add((String) id);
            }
        }
        List<Object> wires = new ArrayList<>(videos.size());
        for (int i = 0; i < videos.size(); i++) {
            Object o = videos.get(i);
            Map<String, Object> v;
            if (o instanceof Video) {
                v = ((Video) o).toMap();
            } else if (o instanceof Map) {
                v = wireMap(o);
            } else {
                throw new IllegalArgumentException("video " + i + " must be a Video or a wire-form map, got "
                        + (o == null ? "null" : o.getClass().getSimpleName()));
            }
            video(i, v, seen);
            wires.add(v);
        }
        return wires;
    }

    private static void video(int i, Map<String, Object> v, Set<String> seen) {
        Object idValue = v.get("id");
        if (!(idValue instanceof String) || ((String) idValue).isEmpty()) {
            throw usage("Video " + i + " needs an id.", "video " + i + " has no id");
        }
        String id = (String) idValue;
        if (!seen.add(id)) {
            throw usage("Video id " + pyRepr(id) + " is used twice (ids are unique across images and videos).",
                    "video id " + pyRepr(id) + " is used twice");
        }
        int forms = 0;
        for (String name : List.of("data", "url", "frames")) {
            forms += v.get(name) != null ? 1 : 0;
        }
        if (forms > 1) {
            throw videoProblem(id, "needs exactly one of data, url and frames");
        }
        List<Object> frames = Json.asList(v.get("frames"));
        if (frames == null || frames.isEmpty()) {
            throw videoProblem(id, "frames must be a non-empty list of frames");
        }
        Double before = null;
        for (int n = 0; n < frames.size(); n++) {
            Map<String, Object> f = Json.asMap(frames.get(n));
            if (f == null) {
                throw videoProblem(id, "frame " + n + " must be an object with t, media_type and data");
            }
            Double t = finite(f.get("t"));
            if (t == null || t < 0) {
                throw videoProblem(id, "frame " + n + ": t must be a number of seconds, at least 0");
            }
            if (before != null && t <= before) {
                throw videoProblem(id, "frame " + n + ": t must be larger than the frame before it (" + messageNumber(before) + ")");
            }
            before = t;
            if (!(f.get("data") instanceof String)) {
                throw videoProblem(id, "frame " + n + ": data must be base64 text");
            }
            Object mediaType = f.get("media_type");
            if (!(mediaType instanceof String) || !MEDIA_TYPES.contains(mediaType)) {
                throw videoProblem(id, "frame " + n + ": media_type must be one of image/jpeg, image/png, image/webp");
            }
        }
        // A bound present but not a number is refused as one out of range.
        Double fps = bound(v, "fps");
        if (fps != null && (fps.isNaN() || fps <= 0 || fps > MAX_FPS)) {
            throw videoProblem(id, "fps must be a number above 0 and at most " + messageNumber(MAX_FPS));
        }
        for (String name : List.of("start", "end")) {
            Double x = bound(v, name);
            if (x != null && (x.isNaN() || x < 0)) {
                throw videoProblem(id, name + " must be a number of seconds, at least 0");
            }
        }
        Double start = bound(v, "start");
        Double end = bound(v, "end");
        if (end != null && end <= (start == null ? 0 : start)) {
            throw videoProblem(id, "end must be after start");
        }
    }

    private static Double bound(Map<String, Object> v, String name) {
        Object x = v.get(name);
        if (x == null) {
            return null;
        }
        Double d = finite(x);
        return d == null ? Double.NaN : d;
    }

    /** A JSON number (never a boolean or a string) that is finite, else null. */
    private static Double finite(Object x) {
        Double d = Json.asDouble(x);
        return d != null && !d.isNaN() && !d.isInfinite() ? d : null;
    }

    /**
     * A number as the API's messages write it, as printf's {@code %g}: six significant digits
     * without trailing zeros ({@code 1.5}, {@code 4}, {@code 1.69679e+09}).
     */
    static String messageNumber(double x) {
        if (x == 0 || Double.isNaN(x) || Double.isInfinite(x)) {
            return x == 0 ? "0" : Double.toString(x);
        }
        BigDecimal r = new BigDecimal(x).round(new MathContext(6, RoundingMode.HALF_EVEN));
        int exp = r.precision() - r.scale() - 1;
        if (exp < -4 || exp >= 6) {
            String mantissa = r.movePointLeft(exp).stripTrailingZeros().toPlainString();
            return mantissa + "e" + (exp < 0 ? "-" : "+") + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
        }
        return r.stripTrailingZeros().toPlainString();
    }

    private static BadRequestError videoProblem(String id, String problem) {
        return usage("Video " + pyRepr(id) + ": " + problem + ".", "video " + pyRepr(id) + ": " + problem);
    }

    /**
     * An id quoted as the API's messages quote it: in single quotes, or in double quotes when it
     * holds a single quote and no double quote, with backslashes, the quote and characters that
     * do not print escaped.
     */
    static String pyRepr(String s) {
        char quote = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder b = new StringBuilder(s.length() + 2).append(quote);
        s.codePoints().forEach(c -> {
            if (c == '\\' || c == quote) {
                b.append('\\').appendCodePoint(c);
            } else if (c == '\t') {
                b.append("\\t");
            } else if (c == '\n') {
                b.append("\\n");
            } else if (c == '\r') {
                b.append("\\r");
            } else if (c < 0x20 || (c >= 0x7f && c < 0xa0)) {
                b.append(String.format("\\x%02x", c));
            } else {
                b.appendCodePoint(c);
            }
        });
        return b.append(quote).toString();
    }

    // ------------------------------------------------------------------ errors

    private static List<Map<String, Object>> sdlEntries(List<Object> loc, Object value) {
        return List.of(
                entry("string_type", append(loc, "str"), "Input should be a valid string", value, null),
                entry("dict_type", append(loc, "dict[any,any]"), "Input should be a valid dictionary", value, null),
                entry("list_type", append(loc, "list[any]"), "Input should be a valid list", value, null));
    }

    private static Map<String, Object> entry(String type, List<Object> loc, String msg, Object input, Map<String, Object> ctx) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("loc", loc);
        e.put("msg", msg);
        e.put("input", input);
        if (ctx != null) {
            e.put("ctx", ctx);
        }
        return e;
    }

    private static List<Object> append(List<Object> loc, Object part) {
        List<Object> out = new ArrayList<>(loc);
        out.add(part);
        return List.copyOf(out);
    }

    /** The API's 422: its entries, no message, as the server's body has none. */
    private static ValidationError invalid(List<Map<String, Object>> entries) {
        return new ValidationError(new ErrorInfo(null, null, "", null, null, Map.of("detail", entries), Map.of()), List.copyOf(entries));
    }

    /** A 400 whose body is a plain string, as the API answers a question it cannot use. */
    private static BadRequestError plain(String message, String reason) {
        return new BadRequestError(new ErrorInfo(null, null, message, null, reason, Map.of("detail", message), Map.of()));
    }

    /** What the API answers a body over its limit with. */
    static final String BODY_TOO_LARGE = "The request body is over 26,214,400 bytes (25 MiB). "
            + "Send large images as uploads (POST /v1/uploads) and name them by upload_id.";

    /**
     * The API's body limit, on the encoded body as it would be sent: over it, the API's 413 is
     * raised here, before anything is sent or uploaded.
     */
    static void bodySize(long bytes) {
        if (bytes > DecisionNode.MAX_BODY_BYTES) {
            throw new RequestTooLargeError(info(
                    "request_too_large_error", BODY_TOO_LARGE,
                    "the body is " + bytes + " bytes; the limit is " + DecisionNode.MAX_BODY_BYTES));
        }
    }

    /** A 400 {@code api_usage_error}. */
    static BadRequestError usage(String message, String reason) {
        return new BadRequestError(info("api_usage_error", message, reason == null ? message : reason));
    }

    private static ErrorInfo info(String errorType, String message, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("error_type", errorType);
        detail.put("message", message);
        return new ErrorInfo(null, errorType, message, null, reason, Map.of("detail", detail), Map.of());
    }
}
