package dev.vexelray.gui.core.input;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link MenuSink} dispatch hands to a node's menu sources, and the menu it builds out of what they say.
 *
 * <p>All it owns beyond appending is the <b>separator rule</b>: a rule is dropped where it would open the menu,
 * close it, or double another. That is what makes several independent contributors composable — each opens its
 * group with {@link #separator()} unconditionally, and whichever of them turns out to be first does not show a
 * stray line above its first item.
 *
 * <p>Not thread-safe, and does not need to be: one collector is built, filled and finished inside one handler
 * task, and never escapes it.
 */
final class MenuCollector implements MenuSink {

    private final ClickEvent event;
    private final List<MenuItem> items = new ArrayList<>();

    MenuCollector(ClickEvent event) {
        this.event = event;
    }

    @Override
    public ClickEvent event() {
        return event;
    }

    @Override
    public MenuSink item(String icon, String label, boolean enabled, Runnable action) {
        items.add(enabled ? MenuItem.of(icon, label, action) : MenuItem.disabled(icon, label));
        return this;
    }

    @Override
    public MenuSink separator() {
        if (!items.isEmpty() && !items.getLast().separator()) {
            items.add(MenuItem.SEPARATOR);
        }
        return this;
    }

    /** The finished menu: what the sources said, with a trailing rule (if any) dropped. */
    List<MenuItem> build() {
        while (!items.isEmpty() && items.getLast().separator()) {
            items.removeLast();
        }
        return List.copyOf(items);
    }
}
