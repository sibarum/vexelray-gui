package dev.vexelray.gui.core.input;

/**
 * Where keyboard focus is this frame, as a value: what the renderer needs to draw the focus ring without reaching
 * into the dispatcher, and what the semantic read-model publishes beside {@code focused}.
 *
 * @param nodeId       the focused node, or {@code -1} for none
 * @param cause        how focus got there; meaningless when {@code nodeId} is {@code -1}
 * @param windowActive whether the window holds OS focus. A node keeps keyboard focus while the window is in the
 *                     background, so Alt+Tab back returns the caret where it was; the ring dims meanwhile
 * @param shown        how far the focused node's ring has faded in, 0 to 1 — 1 always, unless the application
 *                     asked for a fade ({@code Gui.focusFade})
 * @param leavingId    the node focus last left, whose ring is still fading out, or {@code -1} for none
 * @param leavingCause how focus had got to {@code leavingId}, which is what its ring still answers to
 * @param leavingShown how much of {@code leavingId}'s ring is left, 0 to 1
 */
public record FocusState(long nodeId, FocusCause cause, boolean windowActive, float shown, long leavingId,
                         FocusCause leavingCause, float leavingShown) {

    /** Nothing focused. */
    public static final FocusState NONE = new FocusState(-1L, FocusCause.PROGRAM, true);

    /** Focus with nothing fading: the ring in full, and no ring on its way out. */
    public FocusState(long nodeId, FocusCause cause, boolean windowActive) {
        this(nodeId, cause, windowActive, 1f, -1L, FocusCause.PROGRAM, 0f);
    }
}
