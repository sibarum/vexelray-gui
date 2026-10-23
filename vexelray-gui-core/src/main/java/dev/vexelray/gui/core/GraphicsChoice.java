package dev.vexelray.gui.core;

/**
 * One of the graphics choices a user makes about how a tree is drawn — {@link Gui#flatShading} and
 * {@link Gui#reducedMotion} — and who gets the last word on it.
 *
 * <p>Three voices, in rising order: what the code or the user's settings asked for; the run's preset,
 * {@code -Dvexelray.simplifiedGraphics}, which speaks for every choice at once; and the run's own property for this
 * choice, {@code -Dvexelray.<key>}, which speaks for this one only. A run's word is final, so it is read once, when
 * the tree is made, and the code cannot talk over it.
 */
final class GraphicsChoice {

    /** The preset every choice answers to: {@code simplifiedGraphics} is all of them at once. */
    static final String PRESET = "simplifiedGraphics";

    private final String key;
    private final Boolean run;
    private volatile boolean asked;

    /** The choice saved under {@code key}, decided for this run by {@code vexelray.<key>} or the preset's property. */
    GraphicsChoice(String key) {
        this.key = key;
        Boolean own = runProperty(key);
        this.run = own != null ? own : runProperty(PRESET);
    }

    String key() {
        return key;
    }

    /** What the code or the user's settings asked for, which the run may overrule. */
    void ask(boolean on) {
        asked = on;
    }

    boolean asked() {
        return asked;
    }

    /** Whether the choice is on: the run's word if it gave one, else what was asked. */
    boolean on() {
        return run != null ? run : asked;
    }

    /** The system property a run sets for {@code key}. */
    static String property(String key) {
        return "vexelray." + key;
    }

    /** {@code vexelray.<key>} as this run was launched with it; null when it was not set. */
    private static Boolean runProperty(String key) {
        String v = System.getProperty(property(key));
        return v == null || v.isBlank() ? null : Boolean.valueOf(v.trim());
    }
}
