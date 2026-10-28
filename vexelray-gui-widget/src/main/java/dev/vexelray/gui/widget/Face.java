package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;

/**
 * How an item is drawn: a list's row ({@link ListView}), a table's cell ({@link Table.Column}), a board's card
 * ({@link Board}). Asked on the GUI thread whenever the widget needs the item on screen, which for a virtualised list
 * is whenever its row scrolls in.
 *
 * <p><b>The item and a node factory, never the {@code Gui}</b> (vexelray-framework's threading.md, T7.2). A face is
 * presentation, so it may build nodes; it may not publish, subscribe, read the clipboard or reach the seams, because
 * it runs in the frame, as often as the widget likes. {@link Nodes} is what it can do, and a press on a row's control
 * leaves as a message the view hears ({@link Nodes#button}).
 */
@FunctionalInterface
public interface Face<T> {

    /** Draw {@code item} with {@code nodes}, returning the node that stands for it. */
    Node build(Nodes nodes, T item);
}
