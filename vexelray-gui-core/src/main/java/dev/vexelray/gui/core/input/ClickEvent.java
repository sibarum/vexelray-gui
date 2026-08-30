package dev.vexelray.gui.core.input;

import sibarum.tactroller.api.Modifier;
import sibarum.tactroller.api.MouseButton;

import java.util.Set;

/**
 * A framework-level click — a press and release of the same button that landed on the same node. Published on the
 * GUI's click topic (see {@code Gui.clicks()}) so workers, on any thread or process, can react without coupling to
 * the tree; the raw device edges that produced it stay on the input topic.
 *
 * <p>{@code button} says which click this was. Left clicks additionally drive focus, interaction state and drag
 * capture; a right click is routed to {@code Gui.onContextClick} handlers and published here, and changes nothing
 * else — what a context action <em>means</em> (select the row, open a menu) is the widget's decision.
 *
 * <p><b>The modifiers are part of the click.</b> Ctrl+click, Shift+click and Alt+click are different commands
 * from a click, not a click with something to look up elsewhere — and "elsewhere" would have to be a second
 * channel carrying keyboard state alongside a pointer event, which is exactly the side channel this framework
 * does not have. They travel here so that the handler which decides what the click <em>meant</em> is holding
 * everything that decided it.
 *
 * @param nodeId    the id of the node the click resolved to (the topmost node under the pointer)
 * @param button    the mouse button that clicked
 * @param x         client-space x of the release, pixels
 * @param y         client-space y of the release, pixels
 * @param modifiers the modifier keys held when the button was released
 */
public record ClickEvent(long nodeId, MouseButton button, float x, float y, Set<Modifier> modifiers) {

    public ClickEvent {
        modifiers = modifiers == null || modifiers.isEmpty() ? Set.of() : Set.copyOf(modifiers);
    }

    /** A click with nothing held — the plain kind. */
    public ClickEvent(long nodeId, MouseButton button, float x, float y) {
        this(nodeId, button, x, y, Set.of());
    }

    public boolean has(Modifier m) {
        return modifiers.contains(m);
    }
}
