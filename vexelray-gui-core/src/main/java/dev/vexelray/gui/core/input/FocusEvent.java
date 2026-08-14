package dev.vexelray.gui.core.input;

/**
 * A change of keyboard focus, published on the GUI's focus topic. One {@code gained=false} for the node losing
 * focus and one {@code gained=true} for the node receiving it. Widgets subscribe to draw a focus ring; the change
 * is purely visual, never geometric (the pointer-target UX rule).
 *
 * @param nodeId the node whose focus changed
 * @param gained true if it gained focus, false if it lost focus
 */
public record FocusEvent(long nodeId, boolean gained) {
}
