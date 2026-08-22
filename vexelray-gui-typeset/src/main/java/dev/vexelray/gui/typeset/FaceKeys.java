package dev.vexelray.gui.typeset;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The binding from a profile's face <b>keys</b> to the atlas face indices a node actually renders with — supplied
 * by the application at construction, because only the application knows what it baked.
 *
 * <p>Profiles name faces by key ({@code "text"}, {@code "math"}, {@code "mathItalic"}) and never by index. The
 * reason is that an index is not a portable fact: {@code Node.font(int)} indexes a face array whose contents are
 * decided by whichever atlas the app registered, and {@code AtlasData.face(i)} returns faces over one shared
 * image, so face 2 means one thing in one app and something else in the next. A key survives that; an index does
 * not. It also means a profile can be written, shared and unit-tested with no atlas present at all.
 *
 * <p>An unknown key resolves to face 0 rather than failing. A missing face is a degraded render — the text still
 * appears, in the primary face — and that is strictly better than a blank block or an exception on the GUI
 * thread. Callers that care can check with {@link #knows(String)} first.
 */
public final class FaceKeys {

    /** The key every profile is expected to define: the face used when a run names none. */
    public static final String TEXT = "text";

    private final Map<String, Integer> indices;

    private FaceKeys(Map<String, Integer> indices) {
        this.indices = Map.copyOf(indices);
    }

    /** An empty binding — every key resolves to face 0. Useful for a single-face app, and for tests. */
    public static FaceKeys single() {
        return new FaceKeys(Map.of());
    }

    /** Start building a binding. */
    public static Builder builder() {
        return new Builder();
    }

    /** The atlas face index for {@code key}, or {@code 0} when the key is unbound. */
    public int indexOf(String key) {
        if (key == null) {
            return 0;
        }
        Integer i = indices.get(key);
        return i == null ? 0 : i;
    }

    /** Whether {@code key} is bound — so a caller can choose a fallback instead of silently taking face 0. */
    public boolean knows(String key) {
        return key != null && indices.containsKey(key);
    }

    public static final class Builder {

        private final Map<String, Integer> indices = new LinkedHashMap<>();

        private Builder() {
        }

        /** Bind {@code key} to an atlas face index. */
        public Builder bind(String key, int faceIndex) {
            indices.put(key, Math.max(0, faceIndex));
            return this;
        }

        public FaceKeys build() {
            return new FaceKeys(indices);
        }
    }
}
