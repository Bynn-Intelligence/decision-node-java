package com.bynn.decisionnode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The question builders. Import them statically:
 *
 * <pre>{@code
 * import static com.bynn.decisionnode.Questions.*;
 *
 * Question route = choice("Where should this go?", options("billing", "money", "bug", "broken"));
 * Question urgency = score("How urgent is this?", "routine", "today", "urgent", "critical");
 * Question escalate = truth("Escalate to a human now?");
 * Question cars = number("How many cars are visible?", 0, 50);
 * }</pre>
 *
 * <p>Instructions, descriptions and levels take a string or any value that serialises to JSON.
 */
public final class Questions {
    private Questions() {}

    /** A yes-or-no question, answered as the probability that it is true. */
    public static Question truth(Object instructions) {
        return truth(instructions, null, null);
    }

    /**
     * A yes-or-no question with what counts as true and what counts as false. The criteria are
     * left out when both are null; either side may be null on its own.
     */
    public static Question truth(Object instructions, Object ifTrue, Object ifFalse) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "truth");
        if (instructions != null) {
            q.put("instructions", instructions);
        }
        if (ifTrue != null || ifFalse != null) {
            Map<String, Object> criteria = new LinkedHashMap<>();
            if (ifTrue != null) {
                criteria.put("true", ifTrue);
            }
            if (ifFalse != null) {
                criteria.put("false", ifFalse);
            }
            q.put("criteria", criteria);
        }
        return new Question(q);
    }

    /**
     * One option of a set. {@code options} maps each option name to its description (which may be
     * null). Names are sent exactly as given, in the map's iteration order: use
     * {@link #options} or a {@link LinkedHashMap} to keep the order you wrote.
     */
    public static Question choice(Object instructions, Map<String, ?> options) {
        return choiceOf(instructions, new LinkedHashMap<>(options));
    }

    /** One option of a set, given by name only: each description is null. */
    public static Question choice(Object instructions, Collection<String> names) {
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (String name : names) {
            criteria.put(name, null);
        }
        return choiceOf(instructions, criteria);
    }

    /** One option of a set, given by name only: each description is null. */
    public static Question choice(Object instructions, String... names) {
        return choice(instructions, Arrays.asList(names));
    }

    /**
     * An ordered map of option names to descriptions, for {@link #choice(Object, Map)}:
     * {@code options("billing", "money", "bug", "broken")}.
     *
     * @throws IllegalArgumentException when an option name is not a string or a description is missing
     */
    public static Map<String, Object> options(Object... namesAndDescriptions) {
        if (namesAndDescriptions.length % 2 != 0) {
            throw new IllegalArgumentException("options takes name, description pairs");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < namesAndDescriptions.length; i += 2) {
            if (!(namesAndDescriptions[i] instanceof String)) {
                throw new IllegalArgumentException("option names are strings, got " + namesAndDescriptions[i]);
            }
            out.put((String) namesAndDescriptions[i], namesAndDescriptions[i + 1]);
        }
        return out;
    }

    private static Question choiceOf(Object instructions, Map<String, Object> criteria) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "choice");
        if (instructions != null) {
            q.put("instructions", instructions);
        }
        q.put("criteria", criteria);
        return new Question(q);
    }

    /** A place on an ordered scale. {@code levels} run from low to high. */
    public static Question score(Object instructions, List<?> levels) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "score");
        if (instructions != null) {
            q.put("instructions", instructions);
        }
        q.put("criteria", Collections.unmodifiableList(new ArrayList<>(levels)));
        return new Question(q);
    }

    /** A place on an ordered scale. {@code levels} run from low to high. */
    public static Question score(Object instructions, Object... levels) {
        return score(instructions, Arrays.asList(levels));
    }

    /**
     * A value on a grid of numbers: 0 to 255 in whole numbers, the API's defaults. Answered as a
     * {@link NumberAnswer}.
     */
    public static Question number(Object instructions) {
        return number(instructions, null, null, null);
    }

    /**
     * A value on the grid from {@code min} to {@code max} in whole numbers. A null bound is left
     * out of the request, so the API's default applies (0 and 255).
     */
    public static Question number(Object instructions, Number min, Number max) {
        return number(instructions, min, max, null);
    }

    /**
     * A value on the grid {@code min}, {@code min + step}, ... up to {@code max}. Each argument
     * left null is left out of the request, so the API's default applies ({@code min} 0,
     * {@code max} 255, {@code step} 1). The grid holds at most 4,096 values; a grid the API would
     * refuse is refused before anything is sent, with the API's own {@link ValidationError}.
     * Numbers are sent as given: pass a {@link java.math.BigDecimal} to keep an exact decimal.
     */
    public static Question number(Object instructions, Number min, Number max, Number step) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "number");
        if (instructions != null) {
            q.put("instructions", instructions);
        }
        if (min != null) {
            q.put("min", min);
        }
        if (max != null) {
            q.put("max", max);
        }
        if (step != null) {
            q.put("step", step);
        }
        return new Question(q);
    }

    /**
     * One spot in an image: answered as a {@link PointAnswer} with {@code x} and {@code y}, the
     * probability that the thing is there at all ({@code present}) and that the spot lies on it
     * ({@code confidence}). The request needs an image; with two or more, name the one to look in
     * with {@link #point(Object, String, String)}.
     */
    public static Question point(Object instructions) {
        return point(instructions, null, null);
    }

    /**
     * One spot in the image with the id {@code image} (a string, required when the request has two
     * or more images; a reference by number, {@code "Picture 2"}, goes in the instructions), in
     * {@code units}: {@code pixels} (the API's default) or {@code normalized} (fractions of the
     * width and the height). A null argument is left out of the request.
     */
    public static Question point(Object instructions, String image, String units) {
        return point(instructions, image, units, null);
    }

    /**
     * Up to {@code max} spots in the image (an integer from 2 to 16): answered as a
     * {@link PointsAnswer} with how many there are and each spot, left to right. The other
     * arguments are those of {@link #point(Object, String, String)}; a null argument is left out
     * of the request.
     */
    public static Question point(Object instructions, String image, String units, Integer max) {
        return place("point", instructions, image, units, max);
    }

    /**
     * A rectangle in an image: answered as a {@link BoxAnswer} with its top-left ({@code x1},
     * {@code y1}) and bottom-right ({@code x2}, {@code y2}) corners, {@code present} and
     * {@code confidence}. The request needs an image; with two or more, name the one to look in
     * with {@link #box(Object, String, String)}.
     */
    public static Question box(Object instructions) {
        return box(instructions, null, null);
    }

    /** A rectangle in the image with the id {@code image}, in {@code units}, like {@link #point(Object, String, String)}. */
    public static Question box(Object instructions, String image, String units) {
        return box(instructions, image, units, null);
    }

    /**
     * Up to {@code max} rectangles in the image (an integer from 2 to 16): answered as a
     * {@link BoxesAnswer}, like {@link #point(Object, String, String, Integer)}.
     */
    public static Question box(Object instructions, String image, String units, Integer max) {
        return place("box", instructions, image, units, max);
    }

    private static Question place(String type, Object instructions, String image, String units, Integer max) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", type);
        if (instructions != null) {
            q.put("instructions", instructions);
        }
        if (image != null) {
            q.put("image", image);
        }
        if (units != null) {
            q.put("units", units);
        }
        if (max != null) {
            q.put("max", max);
        }
        return new Question(q);
    }
}
