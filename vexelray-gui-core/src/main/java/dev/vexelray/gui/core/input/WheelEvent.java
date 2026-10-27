package dev.vexelray.gui.core.input;

/**
 * The wheel, turned over a node that asked for it ({@code Gui.onWheel}): how far, in layout px, a view under the
 * pointer would move for it — positive {@code dy} toward the end of the content (down), positive {@code dx} toward
 * its right. Already a distance rather than a count of notches, at the step the framework scrolls by, so a handler
 * that moves something passes it straight on.
 *
 * <p>For a node that is not inside what it moves: a scrollbar beside its {@code Scroller}, a 3D view whose camera
 * is the thing scrolled.
 */
public record WheelEvent(float dx, float dy) {
}
