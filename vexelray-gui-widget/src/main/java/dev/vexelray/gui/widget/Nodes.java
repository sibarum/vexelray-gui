package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.IconSource;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.icons.IconView;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * What a {@link Face} draws with: boxes, text, icons, the theme, and a button whose press is a message. The narrow
 * half of a {@code Gui}, so that drawing an item cannot do anything but draw it (vexelray-framework's threading.md,
 * T7.2): there is no bus here, no mailbox, no clipboard and no seam, and nothing that leads to them.
 *
 * <p>Handed to a face by the widget that asks it, per item; a face keeps nothing of it.
 */
public final class Nodes {

    private final Gui gui;
    /** What a button's press becomes for the item being drawn, or null where the widget takes no presses. */
    private final Consumer<String> presses;
    private final String widget;

    /** Nodes for a widget whose items take no presses: {@link #button} is refused, saying which widget. */
    Nodes(Gui gui, String widget) {
        this(gui, widget, null);
    }

    /** Nodes for one item of a widget that turns a button's press into a message: {@code presses} is given its id. */
    Nodes(Gui gui, String widget, Consumer<String> presses) {
        this.gui = gui;
        this.widget = widget;
        this.presses = presses;
    }

    /** A generic box (defaults to a row). */
    public Node box() {
        return gui.box();
    }

    /** A horizontal box. */
    public Node row() {
        return gui.row();
    }

    /** A vertical box. */
    public Node column() {
        return gui.column();
    }

    /** A line of text. */
    public Node text(String s) {
        return gui.text(s);
    }

    /** The look the window resolves its roles through, for a colour a role cannot name. */
    public Theme theme() {
        return gui.theme();
    }

    /** An icon from the set, to size, tint and place. */
    public IconView icon(IconSource icon) {
        return IconView.of(gui, icon);
    }

    /**
     * A small icon button on the item: a press is announced as {@link ListView.Chosen} with {@code action} and the
     * item, on the widget's own topic ({@link ListView#CHOSEN}), and the view does the work. Whether it is offered at
     * all, its icon and whether it can be pressed are the face's to decide per item, like everything else it draws.
     *
     * @param icon    what the button shows
     * @param label   its accessible name, and its tip
     * @param action  what {@link ListView.Chosen#action()} carries
     * @param enabled whether it can be pressed; a greyed button still says the command lives here
     * @throws IllegalStateException in a widget whose items take no presses yet
     */
    public Node button(IconSource icon, String label, String action, boolean enabled) {
        Objects.requireNonNull(action, "action");
        if (presses == null) {
            throw new IllegalStateException("a " + widget + "'s items take no presses: draw the button elsewhere, or "
                    + "ask for it here");
        }
        Consumer<String> to = presses;
        return new IconButton(gui, icon, label)
                .tip(label)
                .enabled(enabled)
                .onPress(() -> to.accept(action))
                .node();
    }
}
