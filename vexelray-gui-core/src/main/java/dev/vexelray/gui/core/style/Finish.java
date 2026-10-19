package dev.vexelray.gui.core.style;

/**
 * A yes-or-no about how a surface or its text is finished — lit, letterpressed — as a node holds it: not the answer,
 * but how to get it from whichever {@link Theme} the tree is drawn in. {@link Paint}'s counterpart for the two
 * decisions a theme makes that are not colours, and resolved at the same moment, for the same reason: a dark theme's
 * edge light washes a light page out, so a node lit by a dark theme has to stop being lit when the theme changes.
 *
 * <p>Use the constants: {@link #LIT} and {@link #LETTERPRESS} for "whatever the look says", {@link #ON} and
 * {@link #OFF} for a decision the look does not get to make. A method reference made at every write is not equal to
 * the last one and costs a frame per write, which is why these are fields.
 */
@FunctionalInterface
public interface Finish {

    /** Whether this finish applies in {@code theme}. */
    boolean in(Theme theme);

    /** Always. */
    Finish ON = theme -> true;

    /** Never. */
    Finish OFF = theme -> false;

    /** Whether the theme lights its surfaces ({@link Theme#lit()}). */
    Finish LIT = Theme::lit;

    /** Whether the theme letterpresses labels on a fill ({@link Theme#letterpress()}). */
    Finish LETTERPRESS = Theme::letterpress;

    /** {@link #ON} or {@link #OFF}. */
    static Finish of(boolean on) {
        return on ? ON : OFF;
    }
}
