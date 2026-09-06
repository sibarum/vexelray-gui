package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutContext;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.text.TextLayout;
import dev.vexelray.gui.draw.Picture;
import sibarum.atchung.Subscription;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Rows of columns: a header that stays put, columns the user can resize and sort by, and a body that is a
 * {@link ListView}, so a table of a hundred thousand rows costs a screenful of nodes like a list of them does.
 *
 * <h2>A column width is a constraint the layout engine cannot express</h2>
 * Two cells in the same column live in different rows, so making them agree is a constraint <em>between
 * siblings' children</em> — the cross-tree solve the engine deliberately does not do (docs/todo.md §3). The table
 * therefore does it itself, in the shape that decision names: <b>collect, solve, place</b>, once per frame. It
 * collects from the layout read-model, solves the widths, and writes each cell one width — and it writes only
 * when the answer changed, or the write would invalidate the layout that produced it, every frame, for ever.
 *
 * <h2>The width vocabulary is {@link Length}, and nothing new</h2>
 * A column's width is a {@code Length}, read exactly as flex already reads one:
 * <ul>
 *   <li>a fixed length ({@code Length.rem(8)}, {@code Length.percent(20)}) — resolved against the table's width;</li>
 *   <li>{@link Length#FILL} or {@link Length#grow} — a share of what is left over, by weight;</li>
 *   <li>{@link Length#AUTO} — as wide as its content.</li>
 * </ul>
 * There is no {@code ColumnWidth} type because there does not need to be one: "resolves to a size", "takes a
 * share of the remainder" and "sizes to content" are the three things a {@code Length} already says, and the
 * solve reads them through the published contract ({@code resolve} answers negative for the keywords,
 * {@code growFactor} separates fill from auto) rather than by asking which case it is.
 *
 * <h2>What {@code AUTO} can honestly mean here</h2>
 * A virtualised body has no nodes for the rows off screen, and a widget cannot measure text that is not in the
 * tree. So "as wide as its content" can only be measured from the content that exists — and letting it follow
 * the window would mean columns that resize while the user scrolls, which is the one thing the standing rule
 * forbids. It is therefore measured once, from the header and the rows realized at the time, and <b>latched</b>:
 * a column sizes to what it was sized against and then holds until the items change or the user drags it. A
 * later row that is wider is clipped, exactly as it would be in a fixed column.
 *
 * <h2>What it calls itself</h2>
 * {@code table}, containing a {@code row} of {@code columnheader}s — each with a {@code columngrip} to drag —
 * and a {@code rowgroup} of {@code row}s. Declared because a table is the component whose whole content is
 * structure: without it a reader that is not the renderer (an agent, a screen reader, a thin client) sees a
 * hundred thousand anonymous boxes and has to guess which nesting level a row is. The rows are named after
 * their cells, so {@code row "3 item-00023 binary"} is a thing that can be found by what is in it.
 *
 * <h2>The header is sticky because it is not in the scroller</h2>
 * It is a sibling above the body, not something pinned inside it. There is nothing to keep in sync and nothing to
 * float, which is also why the body does not scroll horizontally: the columns are solved to the width there is.
 *
 * {@snippet :
 * Table<Person> table = new Table<>(gui, 2.0f, List.of(
 *         Table.Column.of("Name", Length.grow(2), (g, p) -> g.text(p.name()), byName),
 *         Table.Column.of("Age", Length.rem(5), (g, p) -> g.text(String.valueOf(p.age())), byAge)));
 * table.items(people);
 * }
 *
 * @param <T> the row type
 */
public final class Table<T> implements AutoCloseable {

    /**
     * One column: what its header says, how wide it is, what a cell of it contains, and how to sort by it.
     *
     * @param title the header label
     * @param width fixed, {@link Length#FILL}/{@link Length#grow}, or {@link Length#AUTO} — see the class doc
     * @param cell  builds the contents of one cell; the cell's box and width are the table's business
     * @param sort  how rows compare on this column, or null when it cannot be sorted by
     */
    public record Column<T>(String title, Length width, ListView.RowBuilder<T> cell, Comparator<T> sort) {

        public Column {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(width, "width");
            Objects.requireNonNull(cell, "cell");
        }

        /** A column that cannot be sorted by — the default, because most columns cannot be ordered meaningfully. */
        public static <T> Column<T> of(String title, Length width, ListView.RowBuilder<T> cell) {
            return new Column<>(title, width, cell, null);
        }

        /**
         * A sortable column, stating its order where it states everything else.
         *
         * <p>Preferred over {@code of(...).sortedBy(...)} for a reason that is Java's rather than ours: the
         * element type of a chained call is inferred from the factory alone, which makes it {@code Object} and
         * the comparator un-passable. Here the comparator is an argument, so it pins the type like the cell
         * builder does.
         */
        public static <T> Column<T> of(String title, Length width, ListView.RowBuilder<T> cell,
                                       Comparator<T> sort) {
            return new Column<>(title, width, cell, sort);
        }

        /** The same column, sortable by {@code order} — for where {@code T} is already fixed by the context. */
        public Column<T> sortedBy(Comparator<T> order) {
            return new Column<>(title, width, cell, order);
        }

        /** Whether clicking this column's header does anything. */
        public boolean sortable() {
            return sort != null;
        }
    }

    /** Which way a sorted column runs. Ascending, descending, and back to the order the items arrived in. */
    public enum Sort { NONE, ASCENDING, DESCENDING }

    /** The narrow strip at a header cell's trailing edge that a drag resizes the column by. */
    private static final Length GRIP_W = Length.rem(0.4f);

    /** The sort indicator's slot, always present so that sorting a column moves nothing. */
    private static final Length MARK_W = Length.rem(0.9f);

    private static final Length CELL_PAD = Length.dp(8);

    /** Nothing may be dragged narrower than this: a column with no width cannot be got hold of again. */
    private static final float MIN_COLUMN_REM = 1.5f;

    private final Gui gui;
    private final List<Column<T>> columns;
    private final ListView<T> body;

    private final Node root;
    private final Node header;
    private final List<Node> headerCells = new ArrayList<>();
    private final List<Node> sortMarks = new ArrayList<>();
    private final List<Subscription> subs = new ArrayList<>();

    /** The cells of every realized row, so a solved width can be written to them. Pruned against the body. */
    private final Map<T, List<Node>> cells = new HashMap<>();

    /** The items as the application gave them, kept so that un-sorting can restore that order exactly. */
    private final List<T> source = new ArrayList<>();

    /** Per column: the width the user dragged it to, or null while it is still whatever it was declared. */
    private final Length[] dragged;

    /** Per column: the content width latched for an {@link Length#AUTO} column, or -1 while unmeasured. */
    private final float[] latched;

    /** Per column: the width last written, so an unchanged solve writes nothing. */
    private final float[] solved;

    private int sortColumn = -1;
    private Sort sort = Sort.NONE;

    public Table(Gui gui, float rowRem, List<Column<T>> columns) {
        this.gui = Objects.requireNonNull(gui, "gui");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        if (this.columns.isEmpty()) {
            throw new IllegalArgumentException("a table needs at least one column");
        }
        this.dragged = new Length[this.columns.size()];
        this.latched = new float[this.columns.size()];
        this.solved = new float[this.columns.size()];
        java.util.Arrays.fill(latched, -1f);
        java.util.Arrays.fill(solved, -1f);

        // A list of rows, and it says so: the body is a ListView, but what it holds are this table's rows, and
        // an agent told they are list items has been told the wrong thing about the structure it is here for.
        this.body = new ListView<>(gui, rowRem, this::buildRow).roles("rowgroup", "row");
        this.root = gui.column().role("table").width(Length.FILL).height(Length.FILL).scroll(false, false);
        // Cells stretch to the header's height rather than centring: a grip or an indicator slot that sizes to
        // its own (empty) content is a box of no height, which is nothing to aim at and nothing to draw in.
        this.header = gui.row().role("row").width(Length.FILL).height(Length.rem(rowRem)).scroll(false, false)
                .background(gui.theme().color(Role.CHROME));
        buildHeader();
        root.children(header, body.node());

        // The solve rides the same read-model commit the body's window does: it is the frame's collect-and-place
        // step, and it needs the geometry that frame produced.
        subs.add(gui.layout().onCommit(snapshot -> solve()));
    }

    /** The node to place in a layout. */
    public Node node() {
        return root;
    }

    /** What is selected — the body's model, which is the same type a list or a tree uses. */
    public SelectionModel<T> selection() {
        return body.selection();
    }

    /** The rows, in the order the application means them to be in. Re-applies the current sort. */
    public Table<T> items(List<T> items) {
        Objects.requireNonNull(items, "items");
        synchronized (this) {
            source.clear();
            source.addAll(items);
            // The items changed, so a column that sizes to its content has a new content to size to.
            java.util.Arrays.fill(latched, -1f);
        }
        apply();
        return this;
    }

    /** Sort by {@code column}, or pass {@link Sort#NONE} to restore the order the items were given in. */
    public Table<T> sortBy(int column, Sort direction) {
        synchronized (this) {
            boolean sortable = column >= 0 && column < columns.size() && columns.get(column).sortable();
            this.sortColumn = sortable && direction != Sort.NONE ? column : -1;
            this.sort = sortColumn < 0 ? Sort.NONE : direction;
        }
        markSorted();
        apply();
        return this;
    }

    /** Which column the rows are ordered by, or -1. */
    public synchronized int sortedColumn() {
        return sortColumn;
    }

    public synchronized Sort sortDirection() {
        return sort;
    }

    /** The width a column came out at, in pixels — for tests and tools; an application states widths, not reads. */
    public synchronized float columnWidth(int column) {
        return solved[column];
    }

    /** The body, for the things a list already answers: reveal, activation, how many rows exist. */
    public ListView<T> rows() {
        return body;
    }

    /** A column's header cell. For tests and tools; an application states a column and reads nothing. */
    public Node headerCell(int column) {
        return headerCells.get(column);
    }

    /**
     * One cell of a realized row, or null when that row has none. The same shape of answer as
     * {@link ListView#rowNode}, and null for the same reason: most of a table's cells do not exist.
     */
    public synchronized Node cell(T item, int column) {
        List<Node> row = cells.get(item);
        return row == null ? null : row.get(column);
    }

    // ------------------------------------------------------------------ ordering

    /** Re-derive the row order from the source and the sort, and hand it to the body. */
    private void apply() {
        List<T> ordered;
        synchronized (this) {
            ordered = new ArrayList<>(source);
            if (sortColumn >= 0) {
                Comparator<T> order = columns.get(sortColumn).sort();
                ordered.sort(sort == Sort.DESCENDING ? order.reversed() : order);
            }
            cells.clear();   // every row is rebuilt against the new order
        }
        body.items(ordered);
    }

    /** A click on a sortable header cycles ascending, descending, and back to the order the items arrived in. */
    private void cycleSort(int column) {
        Sort next;
        synchronized (this) {
            if (sortColumn != column) {
                next = Sort.ASCENDING;
            } else {
                next = switch (sort) {
                    case ASCENDING -> Sort.DESCENDING;
                    case DESCENDING -> Sort.NONE;
                    case NONE -> Sort.ASCENDING;
                };
            }
        }
        sortBy(column, next);
    }

    // ------------------------------------------------------------------ the header

    private void buildHeader() {
        for (int i = 0; i < columns.size(); i++) {
            Column<T> column = columns.get(i);
            int index = i;

            // The padding is on the label rather than on the cell, so the grip sits flush against the boundary
            // it moves. A grip inset by the padding is a grip the user has to aim off-target for.
            Node cell = gui.row().role("columnheader")
                    .width(widthOf(index)).height(Length.FILL).scroll(false, false);
            Node label = gui.text(column.title()).width(Length.FILL).height(Length.FILL)
                    .padding(Length.ZERO, CELL_PAD)
                    .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE)
                    .textColor(gui.theme().color(Role.DIM));
            // The indicator's slot exists whether or not this column is the sorted one: sorting must not move the
            // header's text, and a slot that appears when needed is exactly the movement the rule forbids.
            Node mark = gui.box().width(MARK_W).height(Length.FILL);
            // Declared, because it is the only control here a pointer can use and text cannot name: an agent
            // that cannot address the grip has to compute where a boundary is and aim a few pixels off it.
            Node grip = gui.box().role("columngrip").width(GRIP_W).height(Length.FILL)
                    .background(gui.theme().color(Role.LINE));

            cell.children(label, mark, grip);
            if (column.sortable()) {
                gui.onClick(label, () -> cycleSort(index));
            }
            // GRAB rather than a resize cursor, which the shape vocabulary does not have: a divider is exactly
        // "something that can be grabbed and dragged", and a sixth shape is a native binding, not a widget.
        gui.cursor(grip, CursorShape.GRAB);
            gui.onDrag(grip, e -> resize(index, e));

            headerCells.add(cell);
            sortMarks.add(mark);
            header.append(cell);
        }
    }

    /**
     * Drag the boundary: the column becomes exactly as wide as the pointer has moved it, and stops being whatever
     * it was declared. Read as a <b>displacement</b> ({@code dx}) rather than from the pointer's position, per
     * {@link DragEvent} — the two agree until the pointer is locked, and differencing positions is the version
     * that silently stops working when it is.
     */
    private void resize(int column, DragEvent e) {
        if (e.phase() != DragEvent.Phase.MOVE) {
            return;
        }
        synchronized (this) {
            float dpi = Math.max(0.0001f, gui.dpi().value());
            float now = solved[column] > 0f ? solved[column] : 0f;
            float min = Length.rem(MIN_COLUMN_REM).scalarPx(context(), 0f);
            dragged[column] = Length.dp(Math.max(min, now + e.dx()) / dpi);
        }
        solve();
    }

    /** Author the sort indicator: a caret, drawn rather than typed, because the atlas has no arrow glyphs. */
    private void markSorted() {
        for (int i = 0; i < sortMarks.size(); i++) {
            Node mark = sortMarks.get(i);
            NodeLayout l = mark.layout();
            boolean active;
            Sort direction;
            synchronized (this) {
                active = i == sortColumn;
                direction = sort;
            }
            if (!active || direction == Sort.NONE || !l.present() || l.rect().w() <= 0f) {
                mark.picture(null);
                continue;
            }
            float w = l.rect().w();
            float h = l.rect().h();
            float arm = Math.min(w, h) * 0.28f;
            float cx = w / 2f;
            float cy = h / 2f;
            float tip = direction == Sort.ASCENDING ? cy - arm * 0.6f : cy + arm * 0.6f;
            float base = direction == Sort.ASCENDING ? cy + arm * 0.6f : cy - arm * 0.6f;
            Color ink = gui.theme().color(Role.ACCENT);
            mark.picture(Picture.of(
                    new Picture.Line(cx - arm, base, cx, tip, 1.5, ink, null),
                    new Picture.Line(cx + arm, base, cx, tip, 1.5, ink, null)));
        }
    }

    // ------------------------------------------------------------------ rows

    /** One row: a cell per column, recorded so the solve can write each one its width. */
    private Node buildRow(Gui g, T item) {
        Node row = g.row().width(Length.FILL).height(Length.FILL).scroll(false, false);
        List<Node> built = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            // A row, so that centring is the cross axis and the cell still takes its height from the row above.
            // Not a scroller: overflow scrolling is on by default, so a cell whose content is wider than its
            // column would draw a scrollbar across its own text. A cell truncates -- that is what clip is for.
            Node cell = g.row().width(widthOf(i)).height(Length.FILL)
                    .scroll(false, false)
                    .alignItems(AlignItems.CENTER)
                    .padding(Length.ZERO, CELL_PAD)
                    .clip(true);
            cell.append(columns.get(i).cell().build(g, item));
            built.add(cell);
            row.append(cell);
        }
        synchronized (this) {
            cells.put(item, built);
        }
        return row;
    }

    /**
     * The width a cell is built with, before the solve has an answer: a fixed column's own length, and
     * {@link Length#AUTO} for anything the solve still has to measure. Building an auto column's cell at AUTO is
     * what makes the measurement possible at all — the cell is laid out at its content's width for one frame, and
     * that frame is where the content width comes from.
     */
    private synchronized Length widthOf(int column) {
        if (solved[column] > 0f) {
            return Length.dp(solved[column] / Math.max(0.0001f, gui.dpi().value()));
        }
        Length declared = dragged[column] != null ? dragged[column] : columns.get(column).width();
        return toContent(declared) ? Length.AUTO : declared;
    }

    // ------------------------------------------------------------------ the solve

    /**
     * Collect, solve, place — once per published layout, and writing nothing when the answer has not moved.
     *
     * <p>The order is forced: a fixed column resolves against the table's width, an auto column against what its
     * cells measured, and only what is left over can be shared out by weight. Sharing first would make a fill
     * column's width depend on itself.
     */
    private void solve() {
        List<Runnable> writes = new ArrayList<>();
        synchronized (this) {
            // The BODY's viewport, not the table's: the body is a scroller, so it is narrower than the table by
            // its scrollbar, and solving against the table would push the last column under the bar and clip it.
            // The header is not in the scroller, so its cells stop short of the bar -- which is where they belong.
            NodeLayout viewport = body.node().layout();
            if (!viewport.present() || viewport.viewW() <= 0f) {
                return;
            }
            prune();
            float available = viewport.viewW();
            LayoutContext ctx = context();

            float[] basis = new float[columns.size()];
            float[] grow = new float[columns.size()];
            float fixedTotal = 0f;
            float growTotal = 0f;
            float min = Length.rem(MIN_COLUMN_REM).scalarPx(ctx, 0f);

            for (int i = 0; i < columns.size(); i++) {
                Length w = dragged[i] != null ? dragged[i] : columns.get(i).width();
                grow[i] = w.growFactor();
                growTotal += grow[i];
                if (grow[i] > 0f) {
                    // Basis zero, so the shares come out in the declared proportion: a grow(2) column is twice a
                    // grow(1) one. The minimum is a floor applied after the share, not a basis added before it --
                    // adding it first would make every ratio wrong by the same constant.
                    basis[i] = 0f;
                } else if (toContent(w)) {
                    basis[i] = Math.max(min, contentWidth(i));
                } else {
                    basis[i] = Math.max(min, w.scalarPx(ctx, available));
                }
                fixedTotal += basis[i];
            }

            float remainder = available - fixedTotal;
            for (int i = 0; i < columns.size(); i++) {
                float width = basis[i];
                if (grow[i] > 0f) {
                    width = Math.max(min, remainder > 0f ? remainder * grow[i] / growTotal : min);
                }
                if (Math.abs(width - solved[i]) < 0.5f) {
                    continue;                              // unchanged: writing it would invalidate this layout
                }
                solved[i] = width;
                int index = i;
                Length as = Length.dp(width / Math.max(0.0001f, gui.dpi().value()));
                writes.add(() -> place(index, as));
            }
        }
        for (Runnable write : writes) {
            write.run();
        }
        markSorted();
    }

    /** Write one column's width to its header cell and to every cell of it that exists. */
    private void place(int column, Length width) {
        headerCells.get(column).width(width);
        synchronized (this) {
            for (List<Node> row : cells.values()) {
                row.get(column).width(width);
            }
        }
    }

    /**
     * What an {@link Length#AUTO} column is as wide as: the widest of its header and the cells that exist, latched
     * the first time there is anything to measure. See the class doc for why it is latched rather than followed.
     */
    private float contentWidth(int column) {
        if (latched[column] > 0f) {
            return latched[column];
        }
        float widest = measured(headerCells.get(column));
        for (List<Node> row : cells.values()) {
            widest = Math.max(widest, measured(row.get(column)));
        }
        if (widest > 0f) {
            latched[column] = widest;
        }
        return widest;
    }

    /** A cell's laid-out width, which is its content's while it is still built at {@link Length#AUTO}. */
    private static float measured(Node cell) {
        NodeLayout l = cell.layout();
        return l.present() ? l.rect().w() : 0f;
    }

    /** Forget the cells of rows the body has released, so the map is a window's worth and not a history. */
    private void prune() {
        Set<T> live = body.realizedItems();
        cells.keySet().retainAll(live);
    }

    /**
     * Whether {@code width} means "as wide as its content", asked through {@link Length}'s published contract
     * rather than by testing which case it is: the flex keywords are the lengths that resolve to no size, and
     * {@code growFactor} is what separates the two that take a share from the one that does not.
     */
    private static boolean toContent(Length width) {
        return width.resolve(LayoutContext.of(1f, 1f), 0f) < 0f && width.growFactor() == 0f;
    }

    private LayoutContext context() {
        return new LayoutContext(gui.rootEmPx(), gui.zoom().value(), gui.dpi().value(),
                gui.viewport().value().width(), gui.viewport().value().height());
    }

    @Override
    public void close() {
        for (Subscription s : subs) {
            s.close();
        }
        subs.clear();
        body.close();
        synchronized (this) {
            cells.clear();
        }
        for (Node cell : headerCells) {
            gui.releaseNode(cell);
        }
        gui.releaseNode(root);
    }
}
