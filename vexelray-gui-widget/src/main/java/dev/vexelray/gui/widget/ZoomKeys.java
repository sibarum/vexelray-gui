package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

/**
 * Ctrl+= / Ctrl+- / Ctrl+0, and the numpad's three, for a window the <em>framework</em> opens — the key bindings
 * editor, the automation switches, anything a title-bar instrument shows.
 *
 * <p>Which chord zooms an application's own windows is the application's decision, made in its own keymap. A window
 * the framework opens on the application's behalf has no keymap of its own to make it in, and is exactly as much in
 * need of being readable: a person who zoomed the main window to read it zoomed it for a reason that does not stop
 * at the window edge. So these windows get the chords every desktop uses, and {@link #follow} starts them at the
 * zoom of the window they were opened from.
 *
 * <p>Each is an ordinary global claim, so a focused element that wants the chord outranks it — the key bindings
 * editor recording Ctrl+= records it rather than zooming.
 */
public final class ZoomKeys {

    private ZoomKeys() {
    }

    /** Bind the six zoom chords on {@code gui}. Returns {@code gui}. */
    public static Gui bind(Gui gui) {
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_ADD, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_SUBTRACT, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_0, gui::resetZoom, Modifier.CONTROL);
        return gui;
    }

    /**
     * Bind the chords on {@code gui} and start it at {@code opener}'s zoom; the factor to scale the new window's
     * requested size by, so it opens the size it would have grown to had it been open when the opener was zoomed.
     */
    public static float follow(Gui gui, Gui opener) {
        bind(gui);
        float zoom = opener == null ? 1f : opener.zoom().value();
        gui.zoom(zoom);
        return gui.zoom().value();
    }
}
