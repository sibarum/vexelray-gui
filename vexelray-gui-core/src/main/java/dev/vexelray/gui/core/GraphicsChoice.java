package dev.vexelray.gui.core;

/**
 * One of the graphics choices a user makes about how a tree is drawn — {@link Gui#flatShading} and
 * {@link Gui#reducedMotion} — and who gets the last word on it.
 *
 * <p>Five voices, in rising order: what the operating system says the user asked for there (for reduced motion; the
 * OS has no word on shading); what the user chose for every VexelRay application ({@code ~/.vexelray}, the suite's
 * store); what the code or this application's settings asked for; the run's preset,
 * {@code -Dvexelray.simplifiedGraphics}, which speaks for every choice at once; and the run's own property for this
 * choice, {@code -Dvexelray.<key>}, which speaks for this one only. A run's word is final, so it is read once, when
 * the tree is made, and the code cannot talk over it. The OS's word is only a default: anything asked says more.
 */
final class GraphicsChoice {

    /** The preset every choice answers to: {@code simplifiedGraphics} is all of them at once. */
    static final String PRESET = "simplifiedGraphics";

    private final String key;
    private final Boolean run;
    /** What the code or the user's settings asked for; null until either does. */
    private volatile Boolean asked;
    /** What the user chose for every VexelRay application; null where they have not. */
    private volatile Boolean suite;
    /** What the OS says the user asked for; null where it has not been told, or cannot say. */
    private volatile Boolean system;

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

    /** What the user chose for every VexelRay application, over the OS's word and under anything asked here. */
    void suite(Boolean on) {
        suite = on;
    }

    /** What the OS says, the default until something asks; null for "cannot say". */
    void system(Boolean on) {
        system = on;
    }

    /** Whether the choice is on: the first voice that spoke, from the run's down to the OS's; off if none did. */
    boolean on() {
        if (run != null) {
            return run;
        }
        Boolean a = asked;
        if (a != null) {
            return a;
        }
        Boolean p = suite;
        if (p != null) {
            return p;
        }
        Boolean s = system;
        return s != null && s;
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
