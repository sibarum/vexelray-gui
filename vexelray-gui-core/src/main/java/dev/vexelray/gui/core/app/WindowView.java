package dev.vexelray.gui.core.app;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.WindowControls;

/**
 * One open window as an outside observer sees it: what it is called, the tree in it, and the controls that
 * command it. What an instrument reaches a window through — {@code GuiApp.windows()} — without being handed the
 * loop's own bookkeeping, which lives on the main thread and is not safe to read from anywhere else.
 *
 * <p>A value, and a snapshot: the window it names may have closed since it was listed. An observer that holds one
 * across frames asks {@code windows()} again rather than trusting it.
 *
 * @param name     the key the window is registered under, or its title when it was never named. Not guaranteed
 *                 unique: two anonymous popups with one title share one
 * @param gui      the tree shown in it — each window has its own, and its own bus
 * @param controls the commands that act on this window, capture and resize included
 * @param main     whether this is the application's main window
 */
public record WindowView(String name, Gui gui, WindowControls controls, boolean main) {
}
