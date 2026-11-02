package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.IconSource;
import sibarum.atchung.Sender;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A context menu <b>declared</b> on a node ({@code Gui.menu}): what is on it, and the message each item sends when it
 * is chosen. A value, set before the right click rather than worked out during it.
 *
 * {@snippet :
 * gui.menu(row, Menu.builder()
 *         .item("Open", open, new Open(file))
 *         .item("Rename", !file.readOnly(), rename, new Rename(file))   // shown, greyed when it cannot apply
 *         .separator()
 *         .item("Delete", delete, new Delete(file))
 *         .build());
 * }
 *
 * <p><b>Why data and not a function</b> (vexelray-framework's {@code docs/threading.md}, T7.5): the menu has to open in
 * the frame of the click, and a question asked of the application as a message would be answered a tick later, so the
 * answer is given ahead of time. What the menu holds is state like a row's label: when the facts behind it change,
 * declare it again. Choosing an item sends its message, and that may land a tick later, because nothing on screen
 * waits for it.
 *
 * <p><b>Built from messages only.</b> There is no form of {@link #builder()}'s {@code item} that takes code, so a
 * declared menu cannot run anything but a send. The widget library's own menus (a text field's Copy and Paste) are
 * the GUI's, and answer in code through {@code Gui.onContextMenu}; a declared menu is added after them, so a field's
 * defaults come first and the application's items follow.
 */
public final class Menu {

    /** No menu: declaring it takes a node's menu away. */
    public static final Menu NONE = new Menu(List.of());

    private final List<MenuItem> items;

    private Menu(List<MenuItem> items) {
        this.items = items;
    }

    /** A menu to fill in, item by item, in the order shown. */
    public static Builder builder() {
        return new Builder();
    }

    /** What the presenter is handed: the items, in order, separators included. */
    public List<MenuItem> items() {
        return items;
    }

    /** True for a menu with nothing on it, which opens nothing. */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    @Override
    public String toString() {
        return "Menu" + items.stream().map(i -> i.separator() ? "-" : i.label()).toList();
    }

    /** Fills a {@link Menu}. Separators at either end or two in a row are dropped, as a {@link MenuSink}'s are. */
    public static final class Builder {

        private final List<MenuItem> items = new ArrayList<>();

        private Builder() {
        }

        /** An item that sends {@code message} through {@code to} when chosen. */
        public <T> Builder item(String label, Sender<T> to, T message) {
            return item(label, true, to, message);
        }

        /** As {@link #item(String, Sender, Object)}, choosable only when {@code enabled}, and shown greyed when not. */
        public <T> Builder item(String label, boolean enabled, Sender<T> to, T message) {
            items.add(new MenuItem(null, label, send(to, message), enabled, null));
            return this;
        }

        /** An item with an icon from the icon set, that sends {@code message} through {@code to} when chosen. */
        public <T> Builder item(IconSource icon, String label, Sender<T> to, T message) {
            return item(icon, label, true, to, message);
        }

        /** As {@link #item(IconSource, String, Sender, Object)}, choosable only when {@code enabled}. */
        public <T> Builder item(IconSource icon, String label, boolean enabled, Sender<T> to, T message) {
            items.add(new MenuItem(null, label, send(to, message), enabled, icon));
            return this;
        }

        /** A line between groups of items. */
        public Builder separator() {
            if (!items.isEmpty() && !items.getLast().separator()) {
                items.add(MenuItem.SEPARATOR);
            }
            return this;
        }

        /** A heading naming the group that follows ({@link MenuItem#heading(String)}). */
        public Builder heading(String label) {
            items.add(MenuItem.heading(label));
            return this;
        }

        /** A submenu holding {@code menu}'s items, greyed when none of them can be chosen. */
        public Builder submenu(String label, Menu menu) {
            items.add(MenuItem.submenu(label, true, menu.items()));
            return this;
        }

        /** The menu as declared so far. */
        public Menu build() {
            List<MenuItem> kept = new ArrayList<>(items);
            while (!kept.isEmpty() && kept.getLast().separator()) {
                kept.removeLast();
            }
            return kept.isEmpty() ? NONE : new Menu(List.copyOf(kept));
        }

        private static <T> Runnable send(Sender<T> to, T message) {
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(message, "message");
            return () -> to.send(message);
        }
    }
}
