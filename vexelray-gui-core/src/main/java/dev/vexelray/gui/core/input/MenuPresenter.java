package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.Gui;

import java.util.List;

/**
 * Whatever puts a context menu on screen. Dispatch decides <b>that</b> a menu opens, whose it is and what is on
 * it; this decides what it looks like.
 *
 * <p>The same split as {@link CursorShape} and {@code TextClipboard}: the framework resolves the fact from input
 * and hands it to an installed implementation, because drawing a panel of rows is a widget's job and core builds no
 * UI. {@code vexelray-gui-widget}'s {@code ContextMenu} is the one the framework ships. It is installed the first time
 * anything gives a node a menu ({@link Gui#onContextMenu}), found through the {@link Default} it registers, so a
 * right click opens something without the application wiring anything, whether the menu is a text field's own or
 * only the application's. Installing your own instead (before building the UI) replaces it wholesale — a menu that
 * looks nothing like this one is a presenter, not a fork of the dispatch rules.
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

    /**
     * Makes the presenter a tree gets when it is given its first menu and has none. Core builds no UI, so it cannot
     * name one; a module that can registers this as a {@link java.util.ServiceLoader} service
     * ({@code META-INF/services/dev.vexelray.gui.core.input.MenuPresenter$Default}). With none on the class path a
     * menu is collected and never drawn, which is said once, when a right click finds it.
     */
    interface Default {

        /** A presenter for {@code gui}. It may install itself; {@code Gui} installs what is returned either way. */
        MenuPresenter create(Gui gui);
    }
}
