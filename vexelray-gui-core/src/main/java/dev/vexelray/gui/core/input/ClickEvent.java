package dev.vexelray.gui.core.input;

import sibarum.tactroller.api.MouseButton;

/**
 * A framework-level click — a press and release of the same button that landed on the same node. Published on the
 * GUI's click topic (see {@code Gui.clicks()}) so workers, on any thread or process, can react without coupling to
 * the tree; the raw device edges that produced it stay on the input topic.
 *
 * <p>{@code button} says which click this was. Left clicks additionally drive focus, interaction state and drag
 * capture; a right click is routed to {@code Gui.onContextClick} handlers and published here, and changes nothing
 * else — what a context action <em>means</em> (select the row, open a menu) is the widget's decision.
 *
 * @param nodeId the id of the node the click resolved to (the topmost node under the pointer)
 * @param button the mouse button that clicked
 * @param x      client-space x of the release, pixels
 * @param y      client-space y of the release, pixels
 */
public record ClickEvent(long nodeId, MouseButton button, float x, float y) {
}
