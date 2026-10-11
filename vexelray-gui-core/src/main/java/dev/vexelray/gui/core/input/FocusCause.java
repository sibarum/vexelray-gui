package dev.vexelray.gui.core.input;

/**
 * How keyboard focus arrived at a node. Data, not a mode: nothing switches on it. A focus ring's
 * {@code Visibility} compares it with {@code ==} to decide whether this arrival is one that should show.
 *
 * <p>The dispatcher knows the cause at every place it moves focus, so the value is stamped there rather than
 * guessed afterwards from which device spoke last.
 */
public enum FocusCause {
    /** A press landed on the node, or on something inside it (and a drop that landed there). */
    POINTER,
    /** Tab or Shift+Tab walked onto it. */
    KEYBOARD,
    /**
     * {@code Gui.focus} put it there. Counts as keyboard for showing a ring: the usual programmatic focus (a find
     * bar opening on Ctrl+F, a tab bar's arrow keys) answers a keystroke.
     */
    PROGRAM
}
