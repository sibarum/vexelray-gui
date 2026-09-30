package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;

import java.util.HashMap;
import java.util.Map;

/**
 * A strip of small facts along the bottom of a window: {@code 16 items - 9 selected - 6.5 GB}.
 *
 * <h2>Slots are declared, and a slot's place never changes</h2>
 * Each fact has a key and a side, and exists from the moment it is declared whether or not it has anything to
 * say. Changing what a slot says is {@link #text}; it never adds, removes or reorders a node, so nothing on the
 * bar jumps when a count changes. That is the reason to have a widget rather than a text node with a string
 * built by the caller: joined into one string, the second fact moves every time the first one gains a digit.
 * {@link #minWidth} reserves room for a slot that is known to grow.
 *
 * <p>Like {@link Breadcrumb} this is a projection of state that already exists, and holds none of its own: the
 * application writes a whole snapshot's worth of slots when the model changes.
 *
 * <p>{@link #text} may be called from any thread — a prop written off the GUI thread is queued and applied by
 * the next drain.
 */
public final class StatusBar {

    /** Which end of the bar a slot sits at. */
    public enum Side { LEFT, RIGHT }

    private final Gui gui;
    private final Node bar;
    private final Node left;
    private final Node right;
    private final Map<String, Node> slots = new HashMap<>();
    private volatile int face = 0;

    /** An empty bar. Declare slots with {@link #slot}. */
    public StatusBar(Gui gui) {
        this.gui = gui;
        this.left = gui.row().alignItems(AlignItems.CENTER).gap(Length.rem(1f)).scroll(false, false);
        this.right = gui.row().alignItems(AlignItems.CENTER).gap(Length.rem(1f)).scroll(false, false);
        Node spacer = gui.box().width(Length.grow(1f)).height(Length.FILL);
        this.bar = gui.row().role("status")
                .alignItems(AlignItems.CENTER)
                .padding(Length.ZERO, Length.rem(1.25f))
                .scroll(false, false)
                .children(left, spacer, right);
    }

    /** The node to place in a layout. Give it a height. */
    public Node node() {
        return bar;
    }

    /** The font face for every slot declared after this call. */
    public StatusBar font(int face) {
        this.face = face;
        return this;
    }

    /** Declare a slot. Slots on a side read in the order they were declared. */
    public synchronized StatusBar slot(String key, Side side, String initial) {
        if (slots.containsKey(key)) {
            throw new IllegalArgumentException("slot already declared: " + key);
        }
        Node text = gui.text(initial).font(face).role("statusitem")
                .textSize(Length.rem(0.75f))
                .textColor(gui.theme().color(Role.DIM))
                .wordWrap(false);
        slots.put(key, text);
        (side == Side.LEFT ? left : right).append(text);
        return this;
    }

    /** The node behind a slot, or null — for a landmark, a test, or an agent to address. */
    public synchronized Node slot(String key) {
        return slots.get(key);
    }

    /** Reserve at least {@code width} for a slot that will grow, so the slots after it do not move. */
    public synchronized StatusBar minWidth(String key, Length width) {
        Node slot = slots.get(key);
        if (slot != null) {
            slot.width(width);
        }
        return this;
    }

    /** Say something in a slot. An undeclared key is ignored: a status line is never worth an exception. */
    public synchronized StatusBar text(String key, String value) {
        Node slot = slots.get(key);
        if (slot != null) {
            slot.text(value);
        }
        return this;
    }
}
