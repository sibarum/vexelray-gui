package dev.vexelray.gui.demo.chapter;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.drop.Drop;
import dev.vexelray.gui.core.drop.DragState;
import dev.vexelray.gui.core.drop.Payload;
import dev.vexelray.gui.core.drop.PayloadType;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.widget.Placement;
import dev.vexelray.gui.widget.TreeView;
import dev.vexelray.text.TextLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drag and drop: a tree that says where a row would land, an application that says what landing there means, and
 * two ordinary boxes that accept the same payload.
 *
 * <p>The registration is two calls — {@code onDragSource} and {@code onDrop} — and everything that makes a drag
 * a drag comes from the framework: the travel-and-hold threshold that keeps an ordinary click an ordinary click,
 * the flick too quick to have been drawn committing nothing, Escape cancelling, the undo record, and the focus
 * move that keeps Ctrl+Z pointed at the history the drop wrote into.
 *
 * <p><b>The split is the whole design.</b> A tree reads its hierarchy through a {@code Source}, so it can say
 * where a drop would land <em>in the tree's own terms</em> — before this row, into that branch, out to the root
 * — but it cannot say what putting it there does, because that is a mutation of a model it can only read. So the
 * tree resolves a {@link Placement} and the application returns a {@link Change}, and neither learns the other's
 * job. <b>Refusal is the application's too</b>, by returning null: a folder into itself, a placement that would
 * leave the item exactly where it already is. Answering there is what makes the indicator disappear <em>before</em>
 * the user releases, rather than the drop being swallowed afterwards.
 */
public final class DragChapter implements Chapter {

    /** A node in the small in-memory hierarchy this chapter drags around. */
    private static final class Item {

        private final String name;
        private final List<Item> kids = new ArrayList<>();
        private Item parent;

        Item(String name, Item... children) {
            this.name = name;
            for (Item kid : children) {
                kid.parent = this;
                kids.add(kid);
            }
        }

        boolean isUnder(Item other) {
            for (Item p = this; p != null; p = p.parent) {
                if (p == other) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final Item root = new Item("Board",
            new Item("Inbox", new Item("Read the drop docs"), new Item("Try the bands")),
            new Item("Doing", new Item("Redesign the demo")),
            new Item("Done", new Item("Ship the indicator")));

    private final History history = new History();
    private final AtomicInteger collected = new AtomicInteger();

    @Override
    public String title() {
        return "Drag & drop";
    }

    @Override
    public String blurb() {
        return "Rows resolve to a placement, the application returns the change or refuses it, and any box can "
                + "be a drop target for the same payload.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Theme theme = stage.theme();
        Console console = stage.console();

        // Without a history, a drop resolves and draws but commits nothing — deliberately, because a drop that
        // mutated with no way back would be the one operation in the framework the user could not take back, and
        // silently so. Setting one is the application saying where those changes go.
        gui.dropHistory(history);

        TreeView<Item> board = new TreeView<>(gui, new TreeView.Source<Item>() {

            @Override
            public List<Item> roots() {
                return List.copyOf(root.kids);
            }

            @Override
            public String label(Item item) {
                return item.name;
            }

            @Override
            public boolean hasChildren(Item item) {
                return !item.kids.isEmpty();
            }

            @Override
            public List<Item> children(Item item) {
                return List.copyOf(item.kids);
            }
        });
        board.node().width(Length.FILL).height(Length.FILL);

        // What each placement means, in the model's own terms — and which ones mean nothing, which is the more
        // interesting half. Every point over the rows resolves to a placement, so this method is asked about
        // every one of them, once per frame, while the pointer moves. It decides what to draw, not what to do:
        // the change it returns is not applied until the user releases.
        board.reorderable((moved, where) -> {
            if (where.isRoot()) {
                return moveTo(moved, root, root.kids.size(), console);
            }
            Item reference = where.reference();
            if (reference.isUnder(moved)) {
                return null;   // into itself or its own descendant: not a failure, an answer
            }
            return switch (where.relation()) {
                case INTO -> reference.kids.contains(moved) ? null
                        : moveTo(moved, reference, reference.kids.size(), console);
                case BEFORE -> beside(moved, reference, 0, console);
                case AFTER -> beside(moved, reference, 1, console);
            };
        });

        // Two ordinary boxes, accepting the same payload the tree offers. Nothing about them is a widget: a
        // node, a drop target, and a subscription to the published drag so they can light up while one is over
        // them. The payload type is the tree's own, which is what stops two unrelated trees in one window from
        // silently accepting each other's rows.
        PayloadType<Item> itemType = board.itemType();
        Node basket = zone(gui, "Collect", "drop a card here to count it");
        Node bin = zone(gui, "Discard", "drop a card here to remove it");

        gui.onDrop(basket, (payload, x, y) -> payload.as(itemType)
                .map(item -> Drop.copy(rectOf(basket), counting(item, 1, console)))
                .orElse(Drop.NONE));

        gui.onDrop(bin, (payload, x, y) -> payload.as(itemType)
                .map(item -> item.parent == null ? Drop.NONE
                        : Drop.move(rectOf(bin), detaching(item, console)))
                .orElse(Drop.NONE));

        // Both zones read the *published* drag rather than the live session. The session is the framework's own
        // state, mutated on the GUI thread inside the frame, and a widget reading it would be reading a value
        // that changes underneath its own paint. The State commits once per frame and only on change, so a
        // pointer held still over one seam costs nothing at all.
        gui.drag().onCommit(v -> {
            DragState drag = v.value();
            light(basket, drag, theme);
            light(bin, drag, theme);
        });

        Node zones = gui.column().width(Length.rem(16)).height(Length.FILL).gap(Ui.GAP)
                .children(basket, bin);

        Node tools = Ui.strip(gui,
                Ui.controls(gui,
                        Ui.button(gui, "Undo the drop", () ->
                                console.note(history.undo() ? "undo: the drop is reversed" : "no drop to undo")),
                        Ui.button(gui, "Redo", () ->
                                console.note(history.redo() ? "redo" : "nothing to redo"))),
                Ui.prose(gui, "Press a row and drag: a branch divides into three bands — an outer quarter each "
                        + "side for before and after, the middle half for into — and a leaf divides in two, "
                        + "because a band that always refused would be a third of a row that looks live and is "
                        + "not. Below the last row is the root, which is the only way out of a branch."));

        return gui.column().width(Length.FILL).height(Length.FILL).gap(Ui.GAP)
                .children(
                        gui.row().width(Length.FILL).height(Length.FILL).gap(Ui.GAP)
                                .children(Ui.card(gui, Ui.heading(gui, "Board"), board.node()), zones),
                        tools);
    }

    /**
     * The change that puts {@code moved} at index {@code at} under {@code parent}, and the change that puts it
     * back.
     *
     * <p>Reversal is expressed the same way as the move — {@code apply} performs and hands back its own inverse —
     * so nothing anywhere records a "kind" of change or switches on one. Undo is a stack of reverses.
     */
    private Change moveTo(Item moved, Item parent, int at, Console console) {
        Item from = moved.parent;
        if (from == null) {
            return null;
        }
        int back = from.kids.indexOf(moved);
        if (from == parent && (back == at || back + 1 == at)) {
            return null;   // it would land exactly where it already is
        }
        return placing(moved, parent, at, console);
    }

    /**
     * The change itself, with no opinion about whether it is worth making — that was decided above, at the
     * moment the pointer was over the seam.
     *
     * <p>Where it came from is read when the change is <em>applied</em>, not when it is built. A drop is
     * resolved once per frame while the pointer moves and applied at most once, on release, so a change that
     * closed over the item's position at build time would be describing a hierarchy that had already moved on.
     */
    private Change placing(Item moved, Item parent, int at, Console console) {
        return () -> {
            Item from = moved.parent;
            int back = from == null ? -1 : from.kids.indexOf(moved);
            if (from != null) {
                from.kids.remove(moved);
            }
            parent.kids.add(Math.min(at, parent.kids.size()), moved);
            moved.parent = parent;
            console.say("moved " + moved.name + " into " + parent.name);
            return from == null ? detaching(moved, console) : placing(moved, from, back, console);
        };
    }

    /**
     * Counting an item in or out of the basket. A {@code COPY} drop leaves the source where it is, so the
     * change here is entirely the target's — and its inverse is the same change with the sign turned round,
     * which is what keeps redo working: an inverse that returned null would undo once and then be a dead end.
     */
    private Change counting(Item item, int delta, Console console) {
        return () -> {
            int now = collected.addAndGet(delta);
            console.good((delta > 0 ? "collected " : "un-collected ") + item.name + " (" + now + " held)");
            return counting(item, -delta, console);
        };
    }

    /** Before ({@code offset} 0) or after ({@code offset} 1) a sibling. */
    private Change beside(Item moved, Item reference, int offset, Console console) {
        Item parent = reference.parent == null ? root : reference.parent;
        return moveTo(moved, parent, parent.kids.indexOf(reference) + offset, console);
    }

    /** Taking an item out of the hierarchy, and putting it back exactly where it was. */
    private Change detaching(Item item, Console console) {
        return () -> {
            Item parent = item.parent;
            int at = parent.kids.indexOf(item);
            parent.kids.remove(item);
            item.parent = null;
            console.say("discarded " + item.name);
            return placing(item, parent, at, console);
        };
    }

    /** A labelled box that a drag can be dropped on. */
    private static Node zone(Gui gui, String title, String hint) {
        Theme theme = gui.theme();
        return gui.column().width(Length.FILL).height(Length.grow(1))
                .background(theme.color(Role.WELL)).corner(Length.rem(0.75f))
                .border(Length.rem(0.15f), theme.color(Role.LINE))
                .padding(Length.dp(14)).gap(Length.rem(0.25f))
                .children(
                        gui.text(title).width(Length.FILL).height(Length.rem(1.5f))
                                .textSize(Length.rem(1)).textColor(theme.color(Role.INK))
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE),
                        gui.text(hint).width(Length.FILL)
                                .textSize(Length.rem(0.875f)).textColor(theme.color(Role.FAINT))
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP));
    }

    /** Light a zone while a drag it would accept is over it, and put it back when the drag leaves. */
    private static void light(Node zone, DragState drag, Theme theme) {
        Rect box = zone.layout().rect();
        boolean over = drag.accepts() && box != null
                && drag.x() >= box.x() && drag.x() < box.x() + box.w()
                && drag.y() >= box.y() && drag.y() < box.y() + box.h();
        zone.background(over ? Color.withAlpha(theme.color(Role.ACCENT), 0.18f) : theme.color(Role.WELL))
                .border(Length.rem(0.15f), theme.color(over ? Role.ACCENT : Role.LINE));
    }

    /** A zone's own box, as the indicator for a drop onto it: the promise and the drop are one value. */
    private static Rect rectOf(Node node) {
        Rect box = node.layout().rect();
        return box == null ? Rect.ZERO : box;
    }
}
