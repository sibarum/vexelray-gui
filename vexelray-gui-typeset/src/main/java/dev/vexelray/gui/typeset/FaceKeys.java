package dev.vexelray.gui.typeset;

import dev.vexelray.gui.core.text.Styling;
import dev.vexelray.text.FontSet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The binding from a profile's face <b>keys</b> to the font a node actually renders with — a family, a weight and a
 * slope — supplied by the application at construction, because only the application knows what it baked.
 *
 * <p>Profiles name faces by key ({@code "text"}, {@code "math"}, {@code "mathItalic"}) and never by font. A key is
 * a role in the notation; which family serves it, and whether it is that family's italic, is the application's
 * choice, made once here. It also means a profile can be written, shared and unit-tested with no fonts present at
 * all.
 *
 * <p>An unknown key resolves to the set's first family, regular and upright, rather than failing. A missing face is
 * a degraded render — the text still appears, in the primary face — and that is strictly better than a blank block
 * or an exception on the GUI thread. Callers that care can check with {@link #knows(String)} first.
 */
public final class FaceKeys {

    /** The key every profile is expected to define: the face used when a run names none. */
    public static final String TEXT = "text";

    private final Map<String, Styling> faces;

    private FaceKeys(Map<String, Styling> faces) {
        this.faces = Map.copyOf(faces);
    }

    /** An empty binding — every key is the first family, regular. Useful for a single-face app, and for tests. */
    public static FaceKeys single() {
        return new FaceKeys(Map.of());
    }

    /** Start building a binding. */
    public static Builder builder() {
        return new Builder();
    }

    /** The font {@code key} is drawn in — family, weight and slope, with no spans — or the default for an unbound key. */
    public Styling styleOf(String key) {
        Styling s = key == null ? null : faces.get(key);
        return s == null ? Styling.DEFAULT : s;
    }

    /** Whether {@code key} is bound — so a caller can choose a fallback instead of silently taking the default. */
    public boolean knows(String key) {
        return key != null && faces.containsKey(key);
    }

    public static final class Builder {

        private final Map<String, Styling> faces = new LinkedHashMap<>();

        private Builder() {
        }

        /** Bind {@code key} to a font family by its key in the application's {@code FontSet}, regular and upright. */
        public Builder bind(String key, String family) {
            return bind(key, family, 400, FontSet.Slope.NORMAL);
        }

        /** Bind {@code key} to a family at a weight and slope — {@code "mathItalic"} to sans, italic. */
        public Builder bind(String key, String family, int weight, FontSet.Slope slope) {
            faces.put(key, new Styling(family, weight, slope, List.of()));
            return this;
        }

        /** Bind {@code key} to a font family by its index in the application's {@code FontSet}, as {@code Node.font(int)}. */
        public Builder bind(String key, int familyIndex) {
            faces.put(key, new Styling(Math.max(0, familyIndex), 400, FontSet.Slope.NORMAL, List.of()));
            return this;
        }

        public FaceKeys build() {
            return new FaceKeys(faces);
        }
    }
}
