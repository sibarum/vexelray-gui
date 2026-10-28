package dev.vexelray.gui.widget.spi;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Rail;
import dev.vexelray.gui.widget.TitleBar;

import java.util.Objects;

/**
 * The widgets' forms that run code, for the <b>host</b> and nobody else (vexelray-framework's {@code docs/threading.md},
 * T7.2): a close prompt's proceed, the framework's About opening from a title bar's mark. An application's form of
 * each is a message ({@code Modal.button(label, sender, message)}, {@code TitleBar.onMark(tip, sender, message)},
 * {@code Rail.action(icon, sender, message)}), on the widget itself.
 *
 * <p><b>Why a type of its own</b>, as {@code dev.vexelray.gui.core.spi.Seams} is: a widget a view is handed would
 * otherwise carry a door a view could run code through, in the frame. Nothing an application is handed reaches this,
 * and the framework's processor refuses a {@code @View} that imports an {@code spi} package.
 */
public abstract class WidgetSeams {

    private static volatile WidgetSeams installed;

    /** For the widget library's own implementation; there is no other. */
    protected WidgetSeams() {
    }

    /** Installed once, by the widget library as its classes initialise. A second install is refused. */
    public static void install(WidgetSeams seams) {
        Objects.requireNonNull(seams, "seams");
        synchronized (WidgetSeams.class) {
            if (installed != null) {
                throw new IllegalStateException("the widget seams are installed once, by the widget library");
            }
            installed = seams;
        }
    }

    /** The widget seams, installing them first if no widget that carries one has been built yet. */
    public static WidgetSeams get() {
        if (installed == null) {
            // Building a dialog spec initialises its class, whose static block installs the seams: no reflection.
            Modal.of(null, null);
        }
        return installed;
    }

    /** Add an ordinary button to {@code modal} that runs {@code action} when chosen, after the dialog has closed. */
    public abstract Modal button(Modal modal, String label, Runnable action);

    /** Add the button Enter presses, running {@code action}. */
    public abstract Modal defaultButton(Modal modal, String label, Runnable action);

    /** Add the button Escape and the close box press, running {@code action}. */
    public abstract Modal cancelButton(Modal modal, String label, Runnable action);

    /**
     * Choose {@code button} as a presenter does: send its message, or run the host's code it carries. For a host that
     * presents a dialog itself, or drives one in a test, rather than through {@code Modals}.
     */
    public abstract void press(Modal.Button button);

    /** Have a click on {@code bar}'s mark run {@code action}, saying {@code tooltip} under the pointer. */
    public abstract TitleBar onMark(TitleBar bar, String tooltip, Runnable action);

    /** Add a divider, and below it an icon on {@code rail} that runs {@code action}. */
    public abstract Rail action(Rail rail, Node icon, Runnable action);
}
