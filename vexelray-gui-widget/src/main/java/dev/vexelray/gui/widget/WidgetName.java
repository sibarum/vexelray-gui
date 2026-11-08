package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;

/**
 * What a widget is called on the bus: a name unique in its tree, chosen by whoever builds the view ("path", "save"),
 * and the {@link Gui#address address} that qualifies it by the tree, which every message the widget publishes
 * carries. Until it is given one a widget is called {@code <kind>-<node id>}, which is unique in its tree and means
 * nothing to anyone, so a view that hears a widget names it.
 *
 * <p>The vexelray-framework's {@code docs/threading.md}, T3.8: what a widget does is published as a message, addressed
 * by the widget.
 */
final class WidgetName {

    private final Gui gui;
    private volatile String name;

    WidgetName(Gui gui, String kind, Node node) {
        this.gui = gui;
        this.name = kind + "-" + node.id();
    }

    /** The name in the tree. */
    String name() {
        return name;
    }

    /** Call the widget {@code name}. */
    void name(String name) {
        java.util.Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a widget's name is not empty");
        }
        this.name = name;
    }

    /** {@code "<tree>.<name>"}: what the widget's messages carry. */
    String address() {
        return gui.address(name);
    }
}
