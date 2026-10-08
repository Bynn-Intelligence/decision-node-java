package com.bynn.decisionnode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The API's fixed request rules, checked before any request so a request the API would refuse
 * costs no round trip. A fault raises the class, error type, message and reason the API itself
 * would answer with, without a status or a request id. Token limits depend on the model version
 * and stay the server's to judge.
 */
final class LocalChecks {
    static final int MAX_IMAGES = 4;
    static final long MAX_PIXELS = 40_000_000L;
    static final int MAX_URL_CHARS = 2048;
    static final int MAX_CHOICE_OPTIONS = 255;
    static final int MAX_SCORE_LEVELS = 10;
    static final int MAX_GRID_VALUES = 4096;
    static final int MAX_DECIMALS = 6;
    private static final BigDecimal TWO_53 = BigDecimal.valueOf(9007199254740992L);

    private LocalChecks() {}

    // ------------------------------------------------------------------ images

    /** Every image of a request: the count, ids, URLs, and local bytes. */
    static void images(List<?> images) {
        if (images.size() > MAX_IMAGES) {
            throw usage("Too many images. Must have at most " + MAX_IMAGES + " images.",
                    "a request takes up to " + MAX_IMAGES + " images, got " + images.size());
        }
        Set<String> ids = new HashSet<>();
        for (Object o : images) {
            String id = o instanceof Image ? ((Image) o).id() : Responses.str(mapOrEmpty(o).get("id"));
            if (id != null && !ids.add(id)) {
                throw usage("Duplicate image id: " + id, null);
            }
            if (o instanceof Image) {
                image((Image) o, false);
            }
        }
    }

    /**
     * One image. A session frame takes no URL ({@code frame} true): its image is bytes or an
     * upload.
     */
    static void image(Image img, boolean frame) {
        String id = img.id();
        if (img.url() != null) {
            if (frame) {
                throw usage("A frame's image takes bytes or an upload, not a url.", null);
            }
            String url = img.url();
            if (!url.regionMatches(true, 0, "https://", 0, 8) || url.length() > MAX_URL_CHARS) {
                throw new BadRequestError(info("image_url_refused",
                        "Image '" + id + "': only https URLs of at most " + MAX_URL_CHARS + " characters are accepted.",
                        null, true));
            }
            return;
        }
        if (!img.isLocal()) {
            return; // an upload id, sent as given
        }
        if (img.size() > Image.MAX_BYTES) {
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

    // ------------------------------------------------------------------ questions

    /**
     * Every question, in the API's order: the 422 faults of all questions first (a number grid
     * that cannot be built, a score without levels), then the first 400 fault.
     */
    static void questions(Map<String, ?> questions) {
        List<Map<String, Object>> entries = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        DecisionNodeError deferred = null;
        for (Map.Entry<String, ?> e : questions.entrySet()) {
            Map<String, Object> q = wire(e.getValue());
            if (q == null) {
                continue;
            }
            DecisionNodeError fault = question(e.getKey(), q, entries, reasons);
            if (deferred == null) {
                deferred = fault;
            }
        }
        if (!entries.isEmpty()) {
            String reason = reasons.get(0) + (entries.size() > 1 ? " and " + (entries.size() - 1) + " more" : "");
            Map<String, Object> body = Map.of("detail", entries);
            throw new ValidationError(new ErrorInfo(null, null, "", null, reason, body, Map.of()), List.copyOf(entries));
        }
        if (deferred != null) {
            throw deferred;
        }
    }

    /** The wire form of a question given as a {@link Question} or a map; null for anything else. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> wire(Object q) {
        if (q instanceof Question) {
            return ((Question) q).toMap();
        }
        return q instanceof Map ? (Map<String, Object>) q : null;
    }

    private static DecisionNodeError question(String qid, Map<String, Object> q, List<Map<String, Object>> entries, List<String> reasons) {
        Object type = q.get("type");
        Object criteria = q.get("criteria");
        List<Object> loc = List.of("body", "questions", qid, String.valueOf(type));
        if ("choice".equals(type) && criteria instanceof Map) {
            int n = ((Map<?, ?>) criteria).size();
            if (n == 0) {
                return plain("Choice question must have at least one choice: " + qid,
                        "question '" + qid + "': a choice needs at least one option");
            }
            if (n > MAX_CHOICE_OPTIONS) {
                return plain("Too many choices. Must have at most " + MAX_CHOICE_OPTIONS + " choices.",
                        "question '" + qid + "': a choice takes up to " + MAX_CHOICE_OPTIONS + " options, got " + n);
            }
        } else if ("score".equals(type) && (criteria instanceof Collection || criteria instanceof Map)) {
            int n = criteria instanceof Map ? ((Map<?, ?>) criteria).size() : ((Collection<?>) criteria).size();
            if (n == 0) {
                entries.add(entry("too_short", append(loc, "criteria"), "List should have at least 1 item after validation, not 0", criteria, null));
                reasons.add("question '" + qid + "': a score needs at least one level");
                return null;
            }
            if (n > MAX_SCORE_LEVELS) {
                return plain("Too many score levels. Must have at most " + MAX_SCORE_LEVELS + " levels.",
                        "question '" + qid + "': a score takes up to " + MAX_SCORE_LEVELS + " levels, got " + n);
            }
        } else if ("truth".equals(type)) {
            Map<String, Object> crit = mapOrEmpty(criteria);
            if (q.get("instructions") == null && crit.get("true") == null && crit.get("false") == null) {
                return plain("Truth question must have criteria or instructions: " + qid,
                        "question '" + qid + "': a truth question needs instructions or criteria");
            }
        } else if ("number".equals(type)) {
            return number(qid, q, loc, entries, reasons);
        }
        return null;
    }

    /** A number grid, checked as the API checks it, in exact decimals. */
    private static DecisionNodeError number(String qid, Map<String, Object> q, List<Object> loc,
            List<Map<String, Object>> entries, List<String> reasons) {
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
                String kind = sent instanceof String ? "a string" : sent instanceof Boolean ? "a boolean"
                        : sent instanceof Map ? "an object" : "an array";
                entries.add(entry("float_type", floc, "Input should be a valid number", sent, null));
                reasons.add("question '" + qid + "': " + name + " must be a number, got " + kind);
                ok = false;
                continue;
            }
            BigDecimal dec = decimal((Number) sent);
            if (dec == null) {
                entries.add(entry("finite_number", floc, "Input should be a finite number", sent, null));
                reasons.add("question '" + qid + "': " + name + " must be a finite number");
                ok = false;
                continue;
            }
            if (i < 2 && dec.compareTo(TWO_53.negate()) < 0) {
                entries.add(entry("greater_than_equal", floc, "Input should be greater than or equal to -9007199254740992",
                        sent, Map.of("ge", -9007199254740992L)));
                reasons.add("question '" + qid + "': " + name + " must be within plus or minus 2^53");
                ok = false;
                continue;
            }
            if (i < 2 && dec.compareTo(TWO_53) > 0) {
                entries.add(entry("less_than_equal", floc, "Input should be less than or equal to 9007199254740992",
                        sent, Map.of("le", 9007199254740992L)));
                reasons.add("question '" + qid + "': " + name + " must be within plus or minus 2^53");
                ok = false;
                continue;
            }
            if (i == 2 && dec.signum() <= 0) {
                entries.add(entry("greater_than", floc, "Input should be greater than 0", sent, Map.of("gt", 0)));
                reasons.add("question '" + qid + "': step must be above 0, got " + text(dec));
                ok = false;
                continue;
            }
            if (i != 1 && places(dec) > MAX_DECIMALS) {
                entries.add(entry("decimal_max_places", floc, "Decimal input should have no more than " + MAX_DECIMALS + " decimal places",
                        sent, Map.of("decimal_places", MAX_DECIMALS)));
                reasons.add("question '" + qid + "': " + name + " has " + places(dec) + " decimals, at most " + MAX_DECIMALS);
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
                String why = "question '" + qid + "': " + names[i] + " must be within plus or minus " + b + " on a grid with "
                        + d + " decimals, so every value fits 15 significant digits";
                if (v.compareTo(bound) > 0) {
                    entries.add(entry("less_than_equal", append(loc, names[i]), "Input should be less than or equal to " + b,
                            q.get(names[i]), Map.of("le", jsonNumber(bound))));
                    reasons.add(why);
                    return null;
                }
                if (v.compareTo(bound.negate()) < 0) {
                    entries.add(entry("greater_than_equal", append(loc, names[i]), "Input should be greater than or equal to -" + b,
                            q.get(names[i]), Map.of("ge", jsonNumber(bound.negate()))));
                    reasons.add(why);
                    return null;
                }
            }
        }
        if (lo.compareTo(hi) > 0) {
            Object maxSent = q.get("max") != null ? q.get("max") : 255;
            Object minSent = q.get("min") != null ? q.get("min") : 0;
            String maxText = text(decimal((Number) maxSent));
            entries.add(entry("less_than_equal", append(loc, "min"), "Input should be less than or equal to " + maxText,
                    minSent, Map.of("le", maxSent)));
            reasons.add("question '" + qid + "': min " + text(decimal((Number) minSent)) + " is above max " + maxText);
            return null;
        }
        BigInteger m = lo.movePointRight(d).toBigIntegerExact();
        BigInteger s = step.movePointRight(d).toBigIntegerExact();
        BigInteger top = hi.movePointRight(d).setScale(0, RoundingMode.FLOOR).toBigIntegerExact();
        BigInteger n = top.subtract(m).divide(s).add(BigInteger.ONE);
        if (n.compareTo(BigInteger.valueOf(MAX_GRID_VALUES)) > 0) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("field_type", "Grid");
            ctx.put("max_length", MAX_GRID_VALUES);
            ctx.put("actual_length", n.longValue());
            entries.add(entry("too_long", loc, "Grid should have at most " + MAX_GRID_VALUES + " values, not " + n, q, ctx));
            reasons.add("question '" + qid + "': the grid holds " + n + " values, the limit is " + MAX_GRID_VALUES
                    + "; narrow the range or widen the step");
            return null;
        }
        Object instructions = q.get("instructions");
        if (instructions == null || "".equals(instructions)) {
            return plain("Number question must have instructions: " + qid, "question '" + qid + "': a number question needs instructions");
        }
        return null;
    }

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
            // The shortest text that reads back as this double: what the JSON writer sends.
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

    // ------------------------------------------------------------------ errors

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

    /** A 400 whose body is a plain string, as the API answers a question it cannot use. */
    private static BadRequestError plain(String message, String reason) {
        return new BadRequestError(new ErrorInfo(null, null, message, null, reason, Map.of("detail", message), Map.of()));
    }

    /** A 400 {@code api_usage_error}, the API's answer to an image it cannot use. */
    static BadRequestError usage(String message, String reason) {
        return new BadRequestError(info("api_usage_error", message, reason, true));
    }

    private static ErrorInfo info(String errorType, String message, String reason, boolean typed) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("error_type", errorType);
        detail.put("message", message);
        return new ErrorInfo(null, errorType, message, null, reason == null ? message : reason,
                typed ? Map.of("detail", detail) : null, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrEmpty(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }
}
