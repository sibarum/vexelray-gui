package dev.vexelray.gui.core.input;

/**
 * A framework-level click — a left-button press and release that landed on the same node. Published on the GUI's
 * click topic (see {@code Gui.clicks()}) so workers, on any thread or process, can react without coupling to the
 * tree; the raw device edges that produced it stay on the input topic.
 *
 * @param nodeId the id of the node the click resolved to (the topmost node under the pointer)
 * @param x      client-space x of the release, pixels
 * @param y      client-space y of the release, pixels
 */
public record ClickEvent(long nodeId, float x, float y) {
}
