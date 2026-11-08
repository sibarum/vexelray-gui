package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.widget.spi.WidgetSeams;

/**
 * The widget seams' implementation: each method is the widget's own package-private form, reached through
 * {@link WidgetSeams} by the host. Installed by the first widget class that carries one, as it initialises.
 */
final class Hosting extends WidgetSeams {

    private static final Hosting INSTANCE = new Hosting();

    static {
        WidgetSeams.install(INSTANCE);
    }

    private Hosting() {
    }

    /** Make sure the seams are installed: the class initialising is what does it. */
    static void install() {
        // Touching INSTANCE initialises the class, and the static block above runs once.
    }

    @Override
    public Modal button(Modal modal, String label, Runnable action) {
        return modal.button(label, action);
    }

    @Override
    public Modal defaultButton(Modal modal, String label, Runnable action) {
        return modal.defaultButton(label, action);
    }

    @Override
    public Modal cancelButton(Modal modal, String label, Runnable action) {
        return modal.cancelButton(label, action);
    }

    @Override
    public void press(Modal.Button button) {
        button.press();
    }

    @Override
    public TitleBar onMark(TitleBar bar, String tooltip, Runnable action) {
        return bar.onMark(tooltip, action);
    }

    @Override
    public Rail action(Rail rail, Node icon, Runnable action) {
        return rail.action(icon, action);
    }
}
