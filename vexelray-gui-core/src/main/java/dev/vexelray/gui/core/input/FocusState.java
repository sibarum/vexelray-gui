package dev.vexelray.gui.core.input;

/**
 * Where keyboard focus is this frame, as a value: what the renderer needs to draw the focus ring without reaching
 * into the dispatcher, and what the semantic read-model publishes beside {@code focused}.
 *
 * @param nodeId       the focused node, or {@code -1} for none
 * @param cause        how focus got there; meaningless when {@code nodeId} is {@code -1}
 * @param windowActive whether the window holds OS focus. A node keeps keyboard focus while the window is in the
 *                     background, so Alt+Tab back returns the caret where it was; the ring dims meanwhile
 */
public record FocusState(long nodeId, FocusCause cause, boolean windowActive) {

    /** Nothing focused. */
    public static final FocusState NONE = new FocusState(-1L, FocusCause.PROGRAM, true);
}
