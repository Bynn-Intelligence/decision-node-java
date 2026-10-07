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
}
