package dev.vexelray.gui.core.input;

import java.util.List;

/**
 * Whatever puts a context menu on screen. Dispatch decides <b>that</b> a menu opens, whose it is and what is on
 * it; this decides what it looks like.
 *
 * <p>The same split as {@link CursorShape} and {@code TextClipboard}: the framework resolves the fact from input
 * and hands it to an installed implementation, because drawing a panel of rows is a widget's job and core builds no
 * UI. {@code vexelray-gui-widget}'s {@code ContextMenu} is the one the framework ships, and it installs itself the
 * first time a widget with a default menu is built, so a right click on a text field opens something without the
 * application wiring anything. Installing your own instead (before building the UI) replaces it wholesale — a menu
 * that looks nothing like this one is a presenter, not a fork of the dispatch rules.
 *
 * <p>Called on the handler executor, like every other context handler, so an implementation may mutate the tree
 * through {@code Node} handles.
 */
public interface MenuPresenter extends AutoCloseable {

    /**
     * Show {@code items} for the right click {@code where}. Never called with an empty list: a node whose sources
     * contribute nothing has no menu, and no menu is opened rather than an empty panel flashed.
     */
    void present(ClickEvent where, List<MenuItem> items);

    /** Release whatever the presenter holds. Called when the {@code Gui} it is installed on closes. */
    @Override
    default void close() {
    }
}
