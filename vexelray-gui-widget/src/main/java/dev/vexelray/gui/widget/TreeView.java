package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.FocusEvent;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A generic explorer for hierarchical data — a filesystem, an AST, a scene graph, anything recursive the
 * application can describe through a {@link Source}.
 *
 * <p><b>The tree nests; it does not flatten.</b> Each item is a column of two nodes: its row, and a children
 * container holding the item's subtree. Expand and collapse are one {@code visible()} flip on that container —
 * the same load-bearing choice {@link Tabs} makes, and for the same reason: registrations are keyed by node id
 * and released when a node leaves the tree, so a subtree rebuilt by remove/insert would come back drawn but
 * inert. Nesting also sidesteps the structural API on purpose: {@link Node#append} is append-only, and a
 * flattened row list would need mid-list insertion the model does not offer. A nested subtree only ever appends
 * into its own container.
 *
 * <p><b>Children are pulled, not pushed, and never on the frame loop.</b> {@link Source#children} runs on the
 * handler executor the first time an item expands — it may hit a disk or a network, and the ordered input stages
 * are for bounded model work only. Until the fetch lands the item shows as expanded with nothing under it;
 * materialised subtrees are kept (hidden) across collapse, so re-expanding is two prop flips and no I/O.
 *
 * <p><b>One tab stop, whole-tree keyboard model.</b> The tree is a single focusable node; rows are pointer
 * targets but never focus targets, so Tab crosses the tree in one step instead of one per row. While the tree
 * holds focus, Up/Down walk the visible rows, Right expands (then enters), Left collapses (then exits to the
 * parent), Home/End jump, PageUp/PageDown move by what the viewport holds, and Enter activates. All of it runs
 * as ordered stages on the GUI thread against a widget-side flattened list of visible rows — pure model work,
 * recomputed only when the set of visible rows actually changes.
 *
 * <p>Scrolling, clipping and scrollbars come from the container itself (overflow is a layout fact, not a widget
 * feature), so a tree taller than its box scrolls with no code here. Call {@link #close()} to release the
 * tree's subscription and registrations when removing it.
 */
public final class TreeView<T> implements AutoCloseable {

    /**
     * How the application describes its hierarchy. {@link #children} is called at most once per item, on the
     * handler executor, the first time that item expands — it may do I/O. {@link #hasChildren} must be cheap:
     * it decides whether a disclosure control is shown, and it is asked when the row is built, not when it opens
     * (a filesystem answers it from the directory bit without listing anything).
     */
    public interface Source<T> {
        /** The top-level items, in display order. */
        List<T> roots();

        /** The row text for {@code item}. */
        String label(T item);

        /** Whether {@code item} can expand at all. Cheap; asked once, when the row is built. */
        boolean hasChildren(T item);

        /** The children of {@code item}, in display order. Called once, lazily, on the handler executor. */
        List<T> children(T item);
    }

    private static final Color TREE_BG = Color.rgb(0x0e1220);
    private static final Color TREE_BORDER = Color.rgb(0x2b3346);
    private static final Color TREE_BORDER_FOCUS = Color.rgb(0x3aa0ff);
    private static final Color ROW_HOVER = Color.rgb(0x1b2130);
    private static final Color ROW_SELECTED = Color.rgb(0x2b3346);
    private static final Color INK = Color.rgb(0xeef2f8);
    private static final Color DIM = Color.rgb(0x93a0b4);
    private static final Color ACCENT = Color.rgb(0x3aa0ff);

    /** Indent per depth level, in em, so the stagger scales with the text it indents. */
    private static final float INDENT_EM = 1.2f;

    /** Row height in em — enough for the label plus breathing room, uniform so paging arithmetic is exact. */
    private static final float ROW_EM = 1.75f;

    // The atlas carries no triangle glyphs, so the disclosure affordance is the classic +/− pair. A leaf shows
    // a space: the control column keeps its width, so labels at one depth align whether or not they can open.
    private static final String GLYPH_COLLAPSED = "+";
    private static final String GLYPH_EXPANDED = "−";   // MINUS SIGN, full-width unlike hyphen
    private static final String GLYPH_LEAF = " ";

    /** One item's presence in the tree: its row, its (possibly unmaterialised) children container, its state. */
    private final class Row {
        final T item;
        final Row parent;
        final int depth;
        final boolean canExpand;
        final Node entry;        // column: [rowNode, kidsBox]
        final Node rowNode;      // the pointer target and the styled strip
        final Node disclosure;   // the +/− glyph
        final Node label;
        final Node kidsBox;      // hidden until expanded; children entries append here, never anywhere else
        boolean expanded;
        boolean materialized;    // whether Source.children has been asked
        final List<Row> children = new ArrayList<>();

        Row(T item, Row parent) {
            this.item = item;
            this.parent = parent;
            this.depth = parent == null ? 0 : parent.depth + 1;
            this.canExpand = source.hasChildren(item);

            Node spacer = gui.box().width(Length.em(depth * INDENT_EM)).scroll(false, false);
            this.disclosure = gui.text(canExpand ? GLYPH_COLLAPSED : GLYPH_LEAF)
                    .width(Length.em(1.2f))
                    .textSize(Length.rem(1))
                    .textColor(DIM)
                    .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE);
            // grow(1), not auto: the label takes the row's remaining width, so the whole strip past the glyph
            // belongs to the name — and a name longer than the row wraps at the row edge instead of widening it.
            this.label = gui.text(source.label(item))
                    .width(Length.grow(1))
                    .textSize(Length.rem(1))
                    .textColor(INK)
                    .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
            this.rowNode = gui.row()
                    .width(Length.FILL)
                    .height(Length.em(ROW_EM))
                    .corner(Length.rem(0.4f))
                    .padding(Length.ZERO, Length.dp(4))
                    .gap(Length.em(0.25f))
                    .scroll(false, false)
                    .children(spacer, disclosure, label);
            this.kidsBox = gui.column().width(Length.FILL).visible(false).scroll(false, false);
            this.entry = gui.column().width(Length.FILL).scroll(false, false).children(rowNode, kidsBox);

            // A row click selects; a click on the disclosure itself also toggles. Both arrive on the handler
            // executor and both funnel into synchronized transitions, so a click and a keystroke can interleave
            // but never tear the state.
            gui.onClick(rowNode, () -> select(this, true));
            // A context click selects first — the convention every explorer follows: the menu that opens is about
            // the row under the pointer, so that row must visibly become the subject — then defers to the app.
            gui.onContextClick(rowNode, e -> {
                select(this, true);
                java.util.function.BiConsumer<T, dev.vexelray.gui.core.input.ClickEvent> handler = onContext;
                handler.accept(item, e);
            });
            if (canExpand) {
                gui.onClick(disclosure, () -> {
                    select(this, true);
                    toggle(this);
                });
            }
            gui.onState(rowNode, state -> restyle(this, state));
        }
    }

    private final Gui gui;
    private final Source<T> source;
    private final Node root;
    private final Subscription focusSub;

    /** All state below is guarded by {@code this}. The GUI-thread stages and the handler executor both mutate
     *  it; the monitor makes their transitions atomic, and every callback leaves the monitor before dispatch. */
    private final List<Row> rootRows = new ArrayList<>();
    private final Map<T, Row> rowsByItem = new HashMap<>();
    private final List<Row> visible = new ArrayList<>();
    private Row selected;

    private volatile boolean focused;
    private volatile Consumer<T> onSelect = t -> { };
    private volatile Consumer<T> onActivate = t -> { };
    private volatile java.util.function.BiConsumer<T, dev.vexelray.gui.core.input.ClickEvent> onContext =
            (t, e) -> { };

    /** Build a tree over {@code source}; roots are listed immediately (on the calling thread), collapsed. */
    public TreeView(Gui gui, Source<T> source) {
        this.gui = gui;
        this.source = source;
        this.root = gui.column()
                .width(Length.FILL)
                .height(Length.FILL)
                .background(TREE_BG)
                .corner(Length.rem(0.5f))
                .border(Length.rem(0.1f), TREE_BORDER)
                .padding(Length.dp(4));

        // Navigation is an ordered stage: moving a cursor is order-dependent under key repeat, and it is pure
        // lookups on the visible list — exactly what the GUI-thread lane is for. Registering it also makes the
        // tree focusable, which is the single tab stop.
        gui.onKeyUi(this.root, this::onKey);
        this.focusSub = gui.bus().subscribe(gui.focusEvents(), this::onFocus);

        synchronized (this) {
            for (T item : source.roots()) {
                Row r = new Row(item, null);
                rootRows.add(r);
                rowsByItem.put(item, r);
                root.append(r.entry);
            }
            refreshVisible();
        }
    }

    /** The node to place in a layout (size it there — the tree fills whatever box it is given). */
    public Node node() {
        return root;
    }

    /** The selected item, or null when nothing is selected yet. */
    public synchronized T selected() {
        return selected == null ? null : selected.item;
    }

    /** React to the selection landing on an item (click or keyboard). Runs on the handler executor. */
    public TreeView<T> onSelect(Consumer<T> handler) {
        this.onSelect = handler == null ? t -> { } : handler;
        return this;
    }

    /** React to an item being activated (Enter). Runs on the handler executor. */
    public TreeView<T> onActivate(Consumer<T> handler) {
        this.onActivate = handler == null ? t -> { } : handler;
        return this;
    }

    /**
     * React to a context (right) click on an item's row. The row is selected first — the menu that opens is about
     * the row under the pointer — then the handler receives the item and the {@link dev.vexelray.gui.core.input.ClickEvent},
     * whose x/y anchor a {@link ContextMenu#show}. Runs on the handler executor.
     */
    public TreeView<T> onContext(java.util.function.BiConsumer<T, dev.vexelray.gui.core.input.ClickEvent> handler) {
        this.onContext = handler == null ? (t, e) -> { } : handler;
        return this;
    }

    /**
     * Expand {@code item} programmatically. Works top-down: an item whose ancestors have never been expanded has
     * no row yet (its parent's children were never asked for), and then this is a no-op — expand the path from
     * the root downward. The first expansion fetches children asynchronously, like any other.
     */
    public synchronized TreeView<T> expand(T item) {
        Row r = rowsByItem.get(item);
        if (r != null && r.canExpand && !r.expanded) {
            toggleLocked(r);
        }
        return this;
    }

    /** Collapse {@code item} if it is expanded. The subtree is hidden, not discarded. */
    public synchronized TreeView<T> collapse(T item) {
        Row r = rowsByItem.get(item);
        if (r != null && r.expanded) {
            toggleLocked(r);
        }
        return this;
    }

    /** Select {@code item} if it has a row and every ancestor is expanded; otherwise a no-op. */
    public TreeView<T> select(T item) {
        Row r;
        synchronized (this) {
            r = rowsByItem.get(item);
            if (r == null || !isVisible(r)) {
                return this;
            }
        }
        select(r, true);
        return this;
    }

    /** Move keyboard focus to the tree, so the arrow keys drive it. */
    public TreeView<T> focus() {
        gui.focus(root);
        return this;
    }

    /** Release the tree's subscription and registrations. Call when removing it from the layout. */
    @Override
    public void close() {
        focusSub.close();
        synchronized (this) {
            for (Row r : rowsByItem.values()) {
                gui.releaseNode(r.rowNode);
                gui.releaseNode(r.disclosure);
            }
        }
        gui.releaseNode(root);
    }

    // --- keyboard, as an ordered stage on the GUI thread ---

    private void onKey(KeyEvent e) {
        switch (e.key()) {
            case UP -> moveSelection(-1);
            case DOWN -> moveSelection(1);
            case PAGE_UP -> moveSelection(-pageRows());
            case PAGE_DOWN -> moveSelection(pageRows());
            case HOME -> selectAt(0);
            case END -> selectAt(Integer.MAX_VALUE);
            case RIGHT -> expandOrEnter();
            case LEFT -> collapseOrExit();
            case ENTER -> activate();
            default -> { }
        }
    }

    private void moveSelection(int by) {
        Row target;
        synchronized (this) {
            if (visible.isEmpty()) {
                return;
            }
            int at = selected == null ? -1 : visible.indexOf(selected);
            int next = at < 0 ? (by > 0 ? 0 : visible.size() - 1)
                    : Math.max(0, Math.min(visible.size() - 1, at + by));
            target = visible.get(next);
        }
        select(target, true);
    }

    private void selectAt(int index) {
        Row target;
        synchronized (this) {
            if (visible.isEmpty()) {
                return;
            }
            target = visible.get(Math.max(0, Math.min(visible.size() - 1, index)));
        }
        select(target, true);
    }

    /** Right: open a closed item; step into an open one; do nothing on a leaf. */
    private void expandOrEnter() {
        Row down = null;
        synchronized (this) {
            Row s = selected;
            if (s == null || !s.canExpand) {
                return;
            }
            if (!s.expanded) {
                toggleLocked(s);
                return;
            }
            if (!s.children.isEmpty()) {
                down = s.children.get(0);
            }
        }
        if (down != null) {
            select(down, true);
        }
    }

    /** Left: close an open item; step out to the parent of anything else. */
    private void collapseOrExit() {
        Row up = null;
        synchronized (this) {
            Row s = selected;
            if (s == null) {
                return;
            }
            if (s.expanded) {
                toggleLocked(s);
                return;
            }
            up = s.parent;
        }
        if (up != null) {
            select(up, true);
        }
    }

    private void activate() {
        T item;
        synchronized (this) {
            if (selected == null) {
                return;
            }
            item = selected.item;
        }
        Consumer<T> handler = onActivate;
        gui.handlers().execute(() -> handler.accept(item));
    }

    /** Rows the viewport holds, for PageUp/PageDown — rows are uniform height, so this is one division. */
    private int pageRows() {
        float viewH = root.layout().viewH();
        float rowH;
        synchronized (this) {
            rowH = visible.isEmpty() ? 0f : visible.get(0).rowNode.layout().rect().h();
        }
        return rowH > 0f && viewH > 0f ? Math.max(1, (int) Math.floor(viewH / rowH)) : 1;
    }

    // --- state transitions ---

    private void select(Row row, boolean notify) {
        T item;
        synchronized (this) {
            if (row == selected) {
                return;
            }
            Row previous = selected;
            selected = row;
            if (previous != null) {
                styleLocked(previous, InteractionState.NORMAL);
            }
            styleLocked(row, InteractionState.NORMAL);
            item = row.item;
        }
        if (notify) {
            Consumer<T> handler = onSelect;
            gui.handlers().execute(() -> handler.accept(item));
        }
    }

    private synchronized void toggle(Row row) {
        toggleLocked(row);
    }

    /**
     * Flip {@code row} between expanded and collapsed. The visual flip is immediate; the first expansion also
     * dispatches the children fetch to the handler executor, because {@link Source#children} may do I/O and this
     * method runs on the GUI thread when the flip came from a key. Collapsing with the selection inside the
     * subtree pulls the selection up onto the collapsed row, so it never rests on a hidden node.
     */
    private void toggleLocked(Row row) {
        if (!row.canExpand) {
            return;
        }
        row.expanded = !row.expanded;
        row.disclosure.text(row.expanded ? GLYPH_EXPANDED : GLYPH_COLLAPSED);
        if (row.expanded && !row.materialized) {
            row.materialized = true;
            gui.handlers().execute(() -> materialize(row));
            refreshVisible();
            return;
        }
        row.kidsBox.visible(row.expanded);
        refreshVisible();
        if (!row.expanded && selected != null && isUnder(selected, row)) {
            Row landed = row;
            gui.handlers().execute(() -> select(landed, true));
        }
    }

    /** Fetch and build {@code row}'s children — handler executor, so the source is free to touch a disk. */
    private void materialize(Row row) {
        List<T> kids = source.children(row.item);
        synchronized (this) {
            for (T item : kids) {
                Row r = new Row(item, row);
                row.children.add(r);
                rowsByItem.put(item, r);
                row.kidsBox.append(r.entry);
            }
            if (row.children.isEmpty()) {
                // The source promised children and delivered none (an emptied directory): demote to a leaf.
                row.expanded = false;
                row.disclosure.text(GLYPH_LEAF);
            }
            row.kidsBox.visible(row.expanded);
            refreshVisible();
        }
    }

    /** Recompute the flattened visible-row list — the thing the keyboard walks. Guarded by {@code this}. */
    private void refreshVisible() {
        visible.clear();
        for (Row r : rootRows) {
            appendVisible(r);
        }
    }

    private void appendVisible(Row row) {
        visible.add(row);
        if (row.expanded) {
            for (Row child : row.children) {
                appendVisible(child);
            }
        }
    }

    private boolean isVisible(Row row) {
        for (Row p = row.parent; p != null; p = p.parent) {
            if (!p.expanded) {
                return false;
            }
        }
        return true;
    }

    private boolean isUnder(Row row, Row ancestor) {
        for (Row p = row.parent; p != null; p = p.parent) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }

    // --- styling ---

    private void restyle(Row row, InteractionState state) {
        synchronized (this) {
            styleLocked(row, state);
        }
    }

    private void styleLocked(Row row, InteractionState state) {
        if (row == selected) {
            row.rowNode.background(ROW_SELECTED);
            row.label.textColor(ACCENT);
        } else {
            row.rowNode.background(state == InteractionState.NORMAL ? null : ROW_HOVER);
            row.label.textColor(INK);
        }
    }

    private void onFocus(FocusEvent e) {
        if (e.nodeId() != root.id()) {
            return;
        }
        focused = e.gained();
        root.border(Length.rem(0.1f), focused ? TREE_BORDER_FOCUS : TREE_BORDER);
    }
}
