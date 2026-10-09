package com.bynn.decisionnode;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A booster named with its settings: a specialist model that reads the request's images before
 * the decision. Its reading is added to what the model sees as evidence and comes back in
 * {@link Decision#evidence()}.
 *
 * <pre>{@code
 * DecideRequest req = DecideRequest.builder()
 *     .image(Image.fromUrl("selfie", "https://files.example.com/selfie.jpg"))
 *     .image(Image.fromUrl("upload", "https://files.example.com/upload.jpg"))
 *     .booster("age")                                                        // every image
 *     .booster(Booster.of("ai_generated").images("upload").require(true))   // one image, required
 *     .question("adult", truth("Is the person in `selfie` an adult?"))
 *     .build();
 * }</pre>
 *
 * <p>A plain name is enough when the booster needs no settings: {@code .booster("age")}. A booster
 * in its JSON wire form, a map, is accepted wherever a {@code Booster} is. Names are not checked
 * here: an unknown name, a booster turned off for the workspace or a malformed list is the API's
 * to answer, before any booster runs and before any charge.
 */
public final class Booster {
    private final String name;
    private final List<String> images;
    private final Boolean require;
    private final Map<String, Object> options;

    private Booster(String name, List<String> images, Boolean require, Map<String, Object> options) {
        this.name = name;
        this.images = images;
        this.require = require;
        this.options = options;
    }

    /** The booster of this name, for example {@code age} or {@code ai_generated}, with no settings yet. */
    public static Booster of(String name) {
        return new Booster(Objects.requireNonNull(name, "name"), null, null, null);
    }

    /** The ids of the request's images it reads. Not given: every image of the request. */
    public Booster images(String... ids) {
        return images(Arrays.asList(ids));
    }

    /** The ids of the request's images it reads. Not given: every image of the request. */
    public Booster images(List<String> ids) {
        Objects.requireNonNull(ids, "ids");
        return new Booster(name, Collections.unmodifiableList(new ArrayList<>(ids)), require, options);
    }

    /**
     * True: a booster that fails or does not answer in time fails the request with the 529
     * {@code booster_unavailable} ({@link OverloadedError}, retried). False or not given: the
     * decision runs without the reading, and its evidence says why.
     */
    public Booster require(boolean require) {
        return new Booster(name, images, require, options);
    }

    /** The booster's settings, sent as given. */
    public Booster options(Map<String, ?> options) {
        Objects.requireNonNull(options, "options");
        return new Booster(name, images, require, Collections.unmodifiableMap(new LinkedHashMap<>(options)));
    }

    public String name() {
        return name;
    }

    /** The image ids it reads, or null when not given. */
    public List<String> images() {
        return images;
    }

    /** Whether it is required, or null when not given. */
    public Boolean require() {
        return require;
    }

    /** Its settings, or null when not given. */
    public Map<String, Object> options() {
        return options;
    }

    /** The wire form: {@code {"booster", "images", "require", "options"}}, each setting only when given. */
    @JsonValue
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("booster", name);
        if (images != null) {
            out.put("images", images);
        }
        if (require != null) {
            out.put("require", require);
        }
        if (options != null) {
            out.put("options", options);
        }
        return out;
    }

    /** The wire form of the entries of a {@code boosters} list: a name as itself, a map as given. */
    static List<Object> wires(List<Object> boosters) {
        List<Object> out = new ArrayList<>(boosters.size());
        for (int i = 0; i < boosters.size(); i++) {
            Object b = boosters.get(i);
            if (b == null) {
                throw new IllegalArgumentException("boosters[" + i + "] is null");
            }
            out.add(b instanceof Booster ? ((Booster) b).toMap() : b);
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Booster && ((Booster) o).toMap().equals(toMap());
    }

    @Override
    public int hashCode() {
        return toMap().hashCode();
    }

    @Override
    public String toString() {
        return "Booster" + toMap();
    }
}
