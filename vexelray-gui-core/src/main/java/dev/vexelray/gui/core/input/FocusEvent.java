package dev.vexelray.gui.core.input;

/**
 * A change of keyboard focus, published on the GUI's focus topic. One {@code gained=false} for the node losing
 * focus and one {@code gained=true} for the node receiving it. The framework draws the focus ring itself (see
 * {@code FocusRing}); subscribe to react to focus, not to draw it. The change is purely visual, never geometric
 * (the pointer-target UX rule).
 *
 * @param nodeId the node whose focus changed
 * @param gained true if it gained focus, false if it lost focus
 * @param cause  how the focus move that produced this event was asked for — the same on both halves of a move
 */
public record FocusEvent(long nodeId, boolean gained, FocusCause cause) {
}
