package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A list whose retained tree holds <b>viewport-many rows however long the list is</b>. The invariant, and the
 * reason this is a component rather than a column an application fills: a hundred thousand items must cost a
 * screenful of nodes, and everything that addresses a row — selection, the keyboard, revealing one — has to keep
 * working for the rows that consequently do not exist.
 *
 * <h2>How the window is kept</h2>
 * Three children, always in this order: a top spacer, the realized rows, a bottom spacer. The spacers stand in for
 * the rows above and below the window, so the scroller's content height is the full list's from the first frame —
 * the scrollbar is the right size and the right place before anything has been built, and scrolling to the end
 * does not walk the list into existence.
 *
 * <p>The window itself is recomputed from the layout read-model ({@code Gui.layout()}), which publishes this
 * node's own scroll offset and viewport height each frame. Rows that stay in the window are <b>kept</b>, not
 * rebuilt: a scroll of one row builds one row. That is what keeps a drag of the scrollbar from being a rebuild
 * per frame.
 *
 * <h2>Row height is uniform, and stated in rem</h2>
 * Uniform, because mapping a scroll offset to an index without measuring is what makes the window O(1) rather
 * than a walk of the items. In rem, because the spacers need <i>n</i> times a row and nothing in this framework
 * multiplies a {@link Length} — the same reason {@link Relief} is a ladder of rungs. A row therefore scales with
 * the root em, zoom and density like everything else, and the arithmetic stays on the ratio.
 *
 * <h2>Selection</h2>
 * A {@link SelectionModel} — the same one a tree or a table uses, which is the point of it being a type. The list
 * translates gestures into its three operations and nothing more: a click is {@code at}, Ctrl-click is
 * {@code toggle}, Shift-click and Shift+Arrow are {@code extendTo}. Nothing here decides what a range <i>is</i>.
 *
 * {@snippet :
 * ListView<String> list = new ListView<>(gui, 2.0f, (g, item) -> g.text(item));
 * list.items(names);
 * list.selection().onChange(sel -> status.text(sel.size() + " selected"));
 * panel.append(list.node());
 * }
 *
 * @param <T> the item type
 */
public final class ListView<T> implements AutoCloseable {

    /** Builds the contents of one row. The row's box, its height and its selected look are the list's business. */
    @FunctionalInterface
    public interface RowBuilder<T> {
        Node build(Gui gui, T item);
    }

    /** Rows kept beyond each edge of the viewport, so a scroll of a few pixels does not build anything. */
    private static final int OVERSCAN = 2;

    private static final Shortcut SELECT_ALL = Shortcut.of(Key.A, Modifier.CONTROL);

    private final Gui gui;
    private final RowBuilder<T> builder;
    private final float rowRem;
    private final SelectionModel<T> selection;

    private final Node root;
    private final Node spacerTop;
    private final Node spacerBottom;
    private final List<Subscription> subs = new ArrayList<>();

    private final List<T> items = new ArrayList<>();

    /** The realized window: {@code rows.get(i)} shows {@code items.get(windowStart + i)}. */
    private final List<Node> rows = new ArrayList<>();
    private int windowStart;

    /** The row box currently showing an item, for restyling on selection change without rebuilding. */
    private final Map<T, Node> shown = new HashMap<>();

    private volatile Consumer<T> onActivate = t -> { };

    /** What this list and its rows call themselves in the semantic read-model. See {@link #roles}. */
    private String containerRole = "list";
    private String itemRole = "listitem";

    public ListView(Gui gui, float rowRem, RowBuilder<T> builder) {
        this(gui, rowRem, builder, SelectionModel.range());
    }

    public ListView(Gui gui, float rowRem, RowBuilder<T> builder, SelectionModel<T> selection) {
        this.gui = Objects.requireNonNull(gui, "gui");
        this.builder = Objects.requireNonNull(builder, "builder");
        this.selection = Objects.requireNonNull(selection, "selection");
        if (rowRem <= 0f) {
            throw new IllegalArgumentException("row height must be positive: " + rowRem);
        }
        this.rowRem = rowRem;

        this.root = gui.column().role(containerRole).width(Length.FILL).height(Length.FILL).scroll(false, true)
                .background(gui.theme().color(Role.WELL));
        this.spacerTop = gui.box().width(Length.FILL).height(Length.ZERO);
        this.spacerBottom = gui.box().width(Length.FILL).height(Length.ZERO);
        root.children(spacerTop, spacerBottom);

        // The window follows the scroll offset, which the read-model republishes on every changed frame. This is
        // the whole of the virtualisation trigger: no polling, and no frame callback of our own.
        subs.add(gui.layout().onCommit(snapshot -> update()));
        selection.onChange(sel -> restyleAll());

        // One tab stop for the whole list, as a tree is: registering the key stage is what makes the root
        // focusable, and rows are pointer targets rather than tab stops.
        gui.onKeyUi(root, this::onKey);
        gui.claim(root, SELECT_ALL, ClaimScope.FOCUSED, () -> selection.all(order()));
    }

    /** The node to place in a layout. */
    public Node node() {
        return root;
    }

    /**
     * What this list and its rows are called in the semantic read-model — {@code list} and {@code listitem}
     * unless something composing it says otherwise.
     *
     * <p>This exists because a {@link Table}'s body <em>is</em> one of these and is not a list: its rows are
     * rows, and an agent told they are list items has been told the wrong thing about the one structure it
     * came here to address. The alternative was for the composing widget to declare a second role over the
     * top, which is two nodes claiming to be the same row — so the list takes the words it uses as an
     * argument instead, and there is still exactly one declaration per node.
     *
     * <p>Set it before the items: rows already realized keep the role they were built with, and this rebuilds
     * the window rather than pretending otherwise.
     */
    public ListView<T> roles(String container, String item) {
        this.containerRole = Objects.requireNonNull(container, "container");
        this.itemRole = Objects.requireNonNull(item, "item");
        root.role(container);
        synchronized (this) {
            clearWindow();
        }
        update();
        return this;
    }

    /** What is selected — wire handlers to it, or hand it a mode at construction. */
    public SelectionModel<T> selection() {
        return selection;
    }

    /** The items, in order. Replaces what was there; the selection keeps whatever survives. */
    public ListView<T> items(List<T> items) {
        Objects.requireNonNull(items, "items");
        synchronized (this) {
            this.items.clear();
            this.items.addAll(items);
            // Rows are rebuilt against the new items rather than reused: a row's position no longer implies its
            // item, and reusing by position is precisely how a list comes to show the wrong row's contents.
            clearWindow();
        }
        selection.retainAll(items);
        update();
        return this;
    }

    /** How many items the list holds — not how many rows exist, which is the point of the type. */
    public synchronized int count() {
        return items.size();
    }

    /** The rows that currently exist. For tests and tools: an application has no reason to care. */
    public synchronized int realizedRows() {
        return rows.size();
    }

    /**
     * The row box showing {@code item}, or null when it has none — which is the ordinary case for an item outside
     * the window, and the thing every other API here has to keep working in spite of.
     */
    public synchronized Node rowNode(T item) {
        return shown.get(item);
    }

    /**
     * The items that currently have a row, as a snapshot. For anything that hangs state off a realized row and
     * therefore has to drop it when the row goes — a table keeping its cells, say. Prune against this rather than
     * asking to be notified: the set is a window's worth, so re-deriving is cheaper than a subscription and cannot
     * drift from what actually exists.
     */
    public synchronized Set<T> realizedItems() {
        return Set.copyOf(shown.keySet());
    }

    /** React to a row being activated (double-click equivalent: Enter on the selection). */
    public ListView<T> onActivate(Consumer<T> handler) {
        this.onActivate = handler == null ? t -> { } : handler;
        return this;
    }

    /**
     * Scroll {@code item} into view, building its row if it does not have one.
     *
     * <p><b>This is the half an application gets wrong,</b> and the reason it cannot be left to one: an item
     * outside the window has no node, so there is nothing to scroll to and nothing to select. The list realizes
     * the window around the item first — which is geometrically consistent whatever the scroll offset is, because
     * the spacers put the row exactly where its index says — and only then asks the row to scroll itself in. The
     * next frame's read-model recomputes the window from where the scroller actually landed.
     *
     * <p>A landmark <em>inside</em> an unrealized row is a different matter and deliberately not solved here: it
     * does not exist, so {@code Gui.navigate} cannot resolve it and no {@code Reveal} on this node would ever be
     * consulted (see {@code Reveal}'s own scope — a container whose hidden children have no nodes at all declares
     * nothing). Reaching one means asking the list for the item first.
     */
    public ListView<T> reveal(T item) {
        Node row;
        synchronized (this) {
            int index = items.indexOf(item);
            if (index < 0) {
                return this;
            }
            realize(Math.max(0, index - OVERSCAN), Math.min(items.size() - 1, index + OVERSCAN));
            row = rows.get(index - windowStart);
        }
        row.scrollIntoView();
        return this;
    }

    /**
     * The order a range runs along: the items as they stand. Read live, so a range is never computed on stale.
     *
     * <p>Public because {@link SelectionModel}'s range operations take one, so anything wiring a gesture of its
     * own — a select-all button, a command that extends the selection — needs <em>this</em> order rather than a
     * list of its own that a sort may since have reordered.
     */
    public SelectionModel.Order<T> order() {
        return SelectionModel.Order.of(items);
    }

    // ------------------------------------------------------------------ the window

    /** The height of one row in pixels, at the current basis — what turns a scroll offset into an index. */
    private float rowPx() {
        return Length.rem(rowRem).scalarPx(
                new dev.vexelray.gui.core.layout.LayoutContext(
                        gui.rootEmPx(), gui.zoom().value(), gui.dpi().value(),
                        gui.viewport().value().width(), gui.viewport().value().height()),
                0f);
    }

    /**
     * Recompute the window from where the scroller is, and realize it. Called on every published layout, so it
     * must be cheap when nothing moved — which it is: an unchanged window realizes nothing.
     */
    private void update() {
        int first;
        int last;
        synchronized (this) {
            if (items.isEmpty()) {
                clearWindow();
                sizeSpacers();
                return;
            }
            float rowPx = rowPx();
            NodeLayout l = root.layout();
            // Before the first layout the list's own viewport is unknown, and the window it will need cannot be
            // larger than the one the whole application is drawn in. Bounding by that fills the first frame with
            // rows rather than with nothing, and the real viewport replaces it a frame later.
            float viewH = l.present() && l.viewH() > 0f ? l.viewH() : gui.viewport().value().height();
            float scrollY = l.present() ? l.scrollY() : 0f;

            int visible = (int) Math.ceil(viewH / rowPx) + 1;
            first = Math.max(0, (int) Math.floor(scrollY / rowPx) - OVERSCAN);
            last = Math.min(items.size() - 1, first + visible + 2 * OVERSCAN);
            realize(first, last);
        }
    }

    /**
     * Make the window exactly {@code [first, last]}, keeping every row already in it. The overlap is why a scroll
     * builds a row rather than a screenful; when there is none, this degenerates to a rebuild, which is correct
     * for a jump.
     */
    private void realize(int first, int last) {
        if (!rows.isEmpty() && windowStart == first && windowStart + rows.size() - 1 == last) {
            return;
        }
        int oldFirst = windowStart;
        int oldLast = windowStart + rows.size() - 1;
        if (rows.isEmpty() || last < oldFirst || first > oldLast) {
            clearWindow();                       // no overlap: a jump, not a scroll
        } else {
            for (int i = oldFirst; i < first; i++) {
                dropRow(0);                      // scrolled down: shed the rows above
            }
            for (int i = oldLast; i > last; i--) {
                dropRow(rows.size() - 1);        // scrolled up: shed the rows below
            }
            windowStart = Math.max(oldFirst, first);
        }
        if (rows.isEmpty()) {
            windowStart = first;
        }
        for (int i = windowStart - 1; i >= first; i--) {
            rows.add(0, buildRow(i, 1));         // after the top spacer
            windowStart = i;
        }
        for (int i = windowStart + rows.size(); i <= last; i++) {
            rows.add(buildRow(i, 1 + rows.size()));
        }
        sizeSpacers();
    }

    /** Build the row for {@code index} and put it at {@code at} among the root's children. */
    private Node buildRow(int index, int at) {
        T item = items.get(index);
        // Not a scroller: overflow scrolling is on by default, and a row that scrolled would draw a scrollbar
        // over its own contents the moment they were too wide for it.
        Node row = gui.box().width(Length.FILL).height(Length.rem(rowRem)).scroll(false, false)
                .background(fill(item));
        // The role goes on what the application built, not on the box around it. Both are "the row" — this one
        // carries the click and the selected look, that one carries the contents — and only one of them can be
        // declared without the other becoming a second, anonymous row in the tree. The contents win, because a
        // node's accessible name is derived from the text below it and the wrapper is one level too far up:
        // declared here, every row in a table reads as `row "3 item-00023 binary"` instead of `row ""`.
        row.append(builder.build(gui, item).role(itemRole));
        gui.onClick(row, e -> onRowClick(item, e));
        gui.onState(row, state -> row.background(hovered(item, state)));
        root.insert(row, at);
        shown.put(item, row);
        return row;
    }

    private void dropRow(int at) {
        Node row = rows.remove(at);
        shown.values().remove(row);
        gui.releaseNode(row);
        row.remove();
    }

    private void clearWindow() {
        while (!rows.isEmpty()) {
            dropRow(rows.size() - 1);
        }
        windowStart = 0;
    }

    /** The spacers stand in for the rows that do not exist, so the scrollbar never learns the window's size. */
    private void sizeSpacers() {
        int above = windowStart;
        int below = Math.max(0, items.size() - (windowStart + rows.size()));
        spacerTop.height(Length.rem(rowRem * above));
        spacerBottom.height(Length.rem(rowRem * below));
    }

    // ------------------------------------------------------------------ selection, as gestures

    /** A click is one of the three operations; which one is what the modifiers say. */
    private void onRowClick(T item, ClickEvent e) {
        if (e.has(Modifier.SHIFT)) {
            selection.extendTo(item, order());
        } else if (e.has(Modifier.CONTROL)) {
            selection.toggle(item);
        } else {
            selection.at(item);
        }
    }

    private void onKey(KeyEvent e) {
        boolean extend = e.modifiers().contains(Modifier.SHIFT);
        switch (e.key()) {
            case UP -> moveBy(-1, extend);
            case DOWN -> moveBy(1, extend);
            case PAGE_UP -> moveBy(-pageRows(), extend);
            case PAGE_DOWN -> moveBy(pageRows(), extend);
            case HOME -> moveTo(0, extend);
            case END -> moveTo(Integer.MAX_VALUE, extend);
            case ENTER -> activate();
            default -> { }
        }
    }

    /**
     * An arrow key is the same pair of operations a click is, one row along: Shift extends the range from the
     * anchor, and a bare arrow starts a new one. Stated once here rather than per key, which is what keeps
     * Shift+Down and Shift-click from drifting apart.
     */
    private void moveBy(int by, boolean extend) {
        T from = selection.lead();
        int at;
        synchronized (this) {
            at = from == null ? -1 : items.indexOf(from);
        }
        moveTo(at < 0 ? (by > 0 ? 0 : Integer.MAX_VALUE) : at + by, extend);
    }

    private void moveTo(int index, boolean extend) {
        T target;
        synchronized (this) {
            if (items.isEmpty()) {
                return;
            }
            target = items.get(Math.max(0, Math.min(items.size() - 1, index)));
        }
        if (extend) {
            selection.extendTo(target, order());
        } else {
            selection.at(target);
        }
        reveal(target);   // the keyboard never leaves the selection somewhere the user cannot see
    }

    /** How many rows a page key moves — what fits in the viewport, and at least one. */
    private int pageRows() {
        NodeLayout l = root.layout();
        float viewH = l.present() && l.viewH() > 0f ? l.viewH() : gui.viewport().value().height();
        return Math.max(1, (int) Math.floor(viewH / rowPx()));
    }

    private void activate() {
        T one = selection.one();
        if (one != null) {
            onActivate.accept(one);
        }
    }

    // ------------------------------------------------------------------ looks

    private void restyleAll() {
        synchronized (this) {
            for (Map.Entry<T, Node> e : shown.entrySet()) {
                e.getValue().background(fill(e.getKey()));
            }
        }
    }

    private dev.vexelray.canvas.Color fill(T item) {
        return gui.theme().color(selection.isSelected(item) ? Role.SELECTION : Role.NONE);
    }

    private dev.vexelray.canvas.Color hovered(T item, dev.vexelray.gui.core.input.InteractionState state) {
        return gui.theme().color(selection.isSelected(item) ? Role.SELECTION : Role.NONE, state);
    }

    @Override
    public void close() {
        for (Subscription s : subs) {
            s.close();
        }
        subs.clear();
        synchronized (this) {
            clearWindow();
        }
        gui.releaseNode(root);
    }
}
