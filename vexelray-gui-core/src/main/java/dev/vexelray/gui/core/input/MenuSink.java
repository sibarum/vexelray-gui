package dev.vexelray.gui.core.input;

/**
 * What a context-menu contributor writes into. One is handed to every source registered on the node that won the
 * right click ({@code Gui.onContextMenu}), in registration order, and what they leave behind is the menu.
 *
 * <p><b>Why a sink and not a returned list.</b> A menu's contents depend on the state of the application at the
 * instant it opens — what is selected, what is on the clipboard, which row is under the pointer — so the framework
 * cannot hold a menu and show it later; it has to ask. Asking through a sink is what lets several contributors
 * share one menu: a widget states its own defaults (a field's Copy/Cut/Paste), the application adds what only it
 * knows (Open, Copy path, Properties), and neither has to be aware of the other or agree on an order.
 *
 * <p>{@link #event()} is the context the sink carries: which node was hit and where, so a source can decide by
 * position. A widget that knows <em>more</em> than that — which row, which tab — passes it to its own callers as a
 * second argument rather than putting it here, because the framework has no way to name it.
 */
public interface MenuSink {

    /** The right click that opened this menu: the node hit, and where. */
    ClickEvent event();

    /** Add a choosable item. */
    MenuSink item(String label, Runnable action);

    /**
     * Add an item that is only choosable when {@code enabled} — the form to reach for when the action exists but
     * does not apply yet (see {@link MenuItem}: shown and greyed beats absent).
     */
    MenuSink item(String label, boolean enabled, Runnable action);

    /**
     * Open a group with a rule. Free to call unconditionally: a separator that would come out at either end of the
     * finished menu, or next to another one, is dropped.
     */
    MenuSink separator();
}
