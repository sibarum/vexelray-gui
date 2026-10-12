package dev.vexelray.gui.core.drop;

/**
 * What letting go of the button means during a drag — a preference, and the user's: set it with
 * {@code Gui.dragRelease}, ideally from a setting they can reach.
 *
 * <p>Two answers, and both are right for somebody. {@link #KEEP} treats picking up as a gesture: the hand lets go and
 * the thing stays with it, the wheel is free to scroll underneath, and a click puts it down where it is aimed
 * ({@code docs/reference/transfer.md} §13). {@link #DROP} is the convention almost every other program follows: the
 * button is held for the length of the carry, and coming up puts the thing down where the pointer is. A user who has
 * the second in their hands from twenty years of other software should not have to unlearn it to use this one.
 *
 * <p>Everything else about a drag is the same either way — the threshold, the armed state, Escape, the drop target's
 * answer, the history it records into. Only the ending differs, so this is one fact rather than two protocols.
 */
public enum DragRelease {

    /** Letting go keeps it: the next left click puts it down, and Escape or a right click takes it home. */
    KEEP(true),

    /** Letting go puts it down, where the pointer is: the button is held for the whole carry. */
    DROP(false);

    private final boolean keeps;

    DragRelease(boolean keeps) {
        this.keeps = keeps;
    }

    /** Whether a drag outlives its button: true for {@link #KEEP}. */
    public boolean keeps() {
        return keeps;
    }
}
