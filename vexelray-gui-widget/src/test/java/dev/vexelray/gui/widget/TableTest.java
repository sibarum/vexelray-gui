package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The table's claims are all about the one thing the layout engine will not do for it: two cells in the same
 * column live in different rows, so their agreeing is a constraint across the tree, and the table has to collect,
 * solve and place it itself — every frame, without writing on the frames where the answer did not move.
 */
class TableTest {

    private record Person(String name, int age) { }

    private static final Comparator<Person> BY_NAME = Comparator.comparing(Person::name);
    private static final Comparator<Person> BY_AGE = Comparator.comparingInt(Person::age);

    /** The harness lays out at 800px wide; a table filling it is the width every solve below shares out. */
    private static final float TABLE_W = 800f;

    // ------------------------------------------------------------------ the solve

    /**
     * The point of the whole exercise: a cell in one row and a cell in another end up exactly as wide as each
     * other, and as wide as their header, though nothing in the layout engine relates them.
     */
    @Test
    void everyCellOfAColumnComesOutTheSameWidth() {
        try (HeadlessGui h = new HeadlessGui()) {
            Table<Person> table = mount(h, 20);

            float first = cellWidth(table, "aa00", 0);
            assertEquals(first, cellWidth(table, "aa01", 0), 0.5f, "two rows agree");
            assertEquals(first, cellWidth(table, "aa02", 0), 0.5f, "and so does a third");
            assertEquals(first, table.columnWidth(0), 0.5f, "at the width the solve says");
        }
    }

    /** A fixed column takes its length, a growing one takes the share of the remainder its weight asks for. */
    @Test
    void fixedColumnsTakeTheirLengthAndTheRestIsSharedByWeight() {
        try (HeadlessGui h = new HeadlessGui()) {
            Table<Person> table = mount(h, 20);   // grow(2), rem(5) fixed, grow(1)

            float fixed = Length.rem(5).scalarPx(dev.vexelray.gui.core.layout.LayoutContext.of(800, 600), 0f);
            assertEquals(fixed, table.columnWidth(1), 0.5f, "the fixed column is its declared length");

            float remainder = TABLE_W - fixed;
            assertEquals(remainder * 2f / 3f, table.columnWidth(0), 1f, "two thirds of what is left");
            assertEquals(remainder * 1f / 3f, table.columnWidth(2), 1f, "and one third");
            assertEquals(TABLE_W, table.columnWidth(0) + table.columnWidth(1) + table.columnWidth(2), 1f,
                    "and together they are the table");
        }
    }

    /**
     * The solve runs on every published layout, so it must write nothing when the answer has not moved — a write
     * invalidates the layout that produced it, and a solve that always wrote would relayout for ever.
     */
    @Test
    void aSolveThatChangesNothingWritesNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            Table<Person> table = mount(h, 20);
            float settled = table.columnWidth(0);

            for (int i = 0; i < 5; i++) {
                h.frame();
            }
            assertEquals(settled, table.columnWidth(0), 0.01f, "the widths are a fixed point, not a drift");
        }
    }

    /** An auto column is as wide as its content, measured from the header and the rows that exist. */
    @Test
    void anAutoColumnSizesToItsContent() {
        try (HeadlessGui h = new HeadlessGui()) {
            Table<Person> table = new Table<>(h.gui, 2f, List.of(
                    Table.Column.of("Name", Length.AUTO, (g, p) -> g.text(p.name())),
                    Table.Column.of("Age", Length.FILL, (g, p) -> g.text(String.valueOf(p.age())))));
            mountNode(h, table, people(10));

            // Ten characters of the harness's monospace stub, plus the cell's padding, and nothing like the
            // half of the table an even split would have given it.
            assertTrue(table.columnWidth(0) < TABLE_W / 3f,
                    "an auto column takes what it needs: " + table.columnWidth(0));
            assertTrue(table.columnWidth(0) > 4 * HeadlessGui.CELL,
                    "and it really did measure something: " + table.columnWidth(0));
            table.close();
        }
    }

    /**
     * The latch. An auto column must not resize while the user scrolls — the rows coming into the window are
     * content it did not size against, and a column that grew as you scrolled would be exactly the movement the
     * standing rule forbids.
     */
    @Test
    void anAutoColumnDoesNotResizeWhileScrolling() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Person> wideLater = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                // The rows far down the list are much wider than the ones the column was sized against.
                wideLater.add(new Person(i < 50 ? "short" + i : "an extremely long name indeed " + i, i));
            }
            Table<Person> table = new Table<>(h.gui, 2f, List.of(
                    Table.Column.of("Name", Length.AUTO, (g, p) -> g.text(p.name())),
                    Table.Column.of("Age", Length.FILL, (g, p) -> g.text(String.valueOf(p.age())))));
            mountNode(h, table, wideLater);
            float sized = table.columnWidth(0);

            Rect box = table.node().layout().rect();
            for (int i = 0; i < 120; i++) {
                h.wheel(0, -1, box.x() + box.w() / 2f, box.y() + box.h() / 2f);
            }
            h.frame();

            assertTrue(table.rows().node().layout().scrollY() > 0f, "the body really scrolled");
            assertEquals(sized, table.columnWidth(0), 0.5f, "and the column stayed exactly where it was");
            table.close();
        }
    }

    // ------------------------------------------------------------------ resizing

    /**
     * Dragging the grip at a header cell's trailing edge widens the column by exactly the pointer's displacement,
     * and every cell of it follows — which is the same solve, reached from the other end.
     */
    @Test
    void draggingTheGripResizesTheColumnAndEveryCellOfIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            Table<Person> table = mount(h, 20);
            float before = table.columnWidth(0);

            dragGrip(h, table, 0, 40f);

            assertEquals(before + 40f, table.columnWidth(0), 1.5f, "as wide as the pointer moved it");
            assertEquals(table.columnWidth(0), cellWidth(table, "aa01", 0), 0.5f, "and the rows went with it");
            assertEquals(TABLE_W, table.columnWidth(0) + table.columnWidth(1) + table.columnWidth(2), 1.5f,
                    "the growing columns absorbed it, so the table is still the table");
        }
    }

    /** A dragged column stops growing: it is the width the user set, and later solves leave it there. */
    @Test
    void aDraggedColumnKeepsItsWidthAcrossLaterSolves() {
        try (HeadlessGui h = new HeadlessGui()) {
            Table<Person> table = mount(h, 20);
            dragGrip(h, table, 0, -60f);
            float set = table.columnWidth(0);

            for (int i = 0; i < 5; i++) {
                h.frame();
            }
            assertEquals(set, table.columnWidth(0), 0.5f, "the user's width is not a suggestion");
        }
    }

    /** There is a floor: a column dragged to nothing could never be got hold of again. */
    @Test
    void aColumnCannotBeDraggedAwayEntirely() {
        try (HeadlessGui h = new HeadlessGui()) {
            Table<Person> table = mount(h, 20);
            dragGrip(h, table, 0, -5000f);
            assertTrue(table.columnWidth(0) > 0f, "still there: " + table.columnWidth(0));
        }
    }

    // ------------------------------------------------------------------ sorting

    @Test
    void clickingAHeaderCyclesAscendingDescendingAndBack() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Person> unordered = List.of(
                    new Person("carol", 30), new Person("alice", 41), new Person("bob", 25));
            Table<Person> table = new Table<>(h.gui, 2f, columns());
            mountNode(h, table, unordered);

            table.sortBy(0, Table.Sort.ASCENDING);
            h.frame().frame();
            assertEquals(List.of("alice", "bob", "carol"), shownNames(table, unordered));

            table.sortBy(0, Table.Sort.DESCENDING);
            h.frame().frame();
            assertEquals(List.of("carol", "bob", "alice"), shownNames(table, unordered));

            table.sortBy(0, Table.Sort.NONE);
            h.frame().frame();
            assertEquals(List.of("carol", "alice", "bob"), shownNames(table, unordered),
                    "and back to the order the application gave them, which is why the source is kept");
        }
    }

    @Test
    void sortingIsByTheColumnClickedAndNotTheOneBefore() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Person> unordered = List.of(
                    new Person("carol", 30), new Person("alice", 41), new Person("bob", 25));
            Table<Person> table = new Table<>(h.gui, 2f, columns());
            mountNode(h, table, unordered);

            table.sortBy(1, Table.Sort.ASCENDING);
            h.frame().frame();
            assertEquals(List.of("bob", "carol", "alice"), shownNames(table, unordered), "by age");
            assertEquals(1, table.sortedColumn());
            assertEquals(Table.Sort.ASCENDING, table.sortDirection());
        }
    }

    /** A column with no comparator is not sortable, and asking is a no-op rather than an exception. */
    @Test
    void anUnsortableColumnStaysUnsorted() {
        try (HeadlessGui h = new HeadlessGui()) {
            Table<Person> table = mount(h, 5);
            table.sortBy(2, Table.Sort.ASCENDING);       // the third column has no comparator
            h.frame();
            assertEquals(-1, table.sortedColumn());
            assertEquals(Table.Sort.NONE, table.sortDirection());
        }
    }

    /** Sorting reorders rows; it does not deselect. The selection is over items, and the items are the same. */
    @Test
    void sortingKeepsTheSelection() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Person> unordered = List.of(
                    new Person("carol", 30), new Person("alice", 41), new Person("bob", 25));
            Table<Person> table = new Table<>(h.gui, 2f, columns());
            mountNode(h, table, unordered);

            table.selection().at(unordered.get(0));
            table.sortBy(0, Table.Sort.ASCENDING);
            h.frame().frame();

            assertEquals(java.util.Set.of(unordered.get(0)), table.selection().selection(),
                    "the same person is selected, wherever the sort put them");
        }
    }

    /**
     * The indicator is drawn rather than typed, because the atlas carries no arrow glyphs — so it is a
     * {@link dev.vexelray.gui.draw.Picture} on a slot that exists whether or not the column is sorted. Asserted
     * because a mark authored into a box of no height is invisible while looking entirely correct in the code.
     */
    @Test
    void theSortedColumnIsMarkedAndTheOthersAreNot() {
        try (HeadlessGui h = new HeadlessGui()) {
            Table<Person> table = mount(h, 20);
            assertNull(markOf(h, table, 0), "nothing is sorted yet, so nothing is marked");

            table.sortBy(0, Table.Sort.ASCENDING);
            h.frame().frame();
            assertNotNull(markOf(h, table, 0), "the sorted column carries a mark");
            assertNull(markOf(h, table, 1), "and the others do not");

            table.sortBy(1, Table.Sort.DESCENDING);
            h.frame().frame();
            assertNull(markOf(h, table, 0), "the mark moves with the sort");
            assertNotNull(markOf(h, table, 1));

            table.sortBy(1, Table.Sort.NONE);
            h.frame().frame();
            assertNull(markOf(h, table, 1), "and goes when the sort does");
        }
    }

    /** The slot is reserved whether or not it is drawn in, so sorting never moves the header's text. */
    @Test
    void sortingMovesNothingInTheHeader() {
        try (HeadlessGui h = new HeadlessGui()) {
            Table<Person> table = mount(h, 20);
            Rect before = boxOf(h.retained(table.headerCell(0)).children.get(0));

            table.sortBy(0, Table.Sort.ASCENDING);
            h.frame().frame();

            assertEquals(before, boxOf(h.retained(table.headerCell(0)).children.get(0)),
                    "the label is exactly where it was");
        }
    }

    // ------------------------------------------------------------------ the body is a list

    @Test
    void aTableOfAHundredThousandRowsCostsAScreenful() {
        try (HeadlessGui h = new HeadlessGui()) {
            Table<Person> table = mount(h, 100_000);
            assertEquals(100_000, table.rows().count());
            assertTrue(table.rows().realizedRows() < 25,
                    "the body is a ListView, and holds a window: " + table.rows().realizedRows());
        }
    }

    /** The header is a sibling above the body rather than something floating in it, so it cannot scroll away. */
    @Test
    void theHeaderStaysWhileTheBodyScrolls() {
        try (HeadlessGui h = new HeadlessGui()) {
            Table<Person> table = mount(h, 500);
            Rect before = table.node().layout().rect();
            float headerY = headerCellRect(table).y();

            Rect box = table.node().layout().rect();
            for (int i = 0; i < 20; i++) {
                h.wheel(0, -1, box.x() + box.w() / 2f, box.y() + box.h() / 2f);
            }
            h.frame();

            assertTrue(table.rows().node().layout().scrollY() > 0f, "the body scrolled");
            assertEquals(headerY, headerCellRect(table).y(), 0.5f, "and the header did not move");
            assertEquals(before.y(), table.node().layout().rect().y(), 0.5f);
        }
    }

    // ------------------------------------------------------------------ harness

    private static List<Table.Column<Person>> columns() {
        return List.of(
                Table.Column.of("Name", Length.grow(2), (g, p) -> g.text(p.name()), BY_NAME),
                Table.Column.of("Age", Length.rem(5), (g, p) -> g.text(String.valueOf(p.age())), BY_AGE),
                Table.Column.<Person>of("Note", Length.grow(1), (g, p) -> g.text("-")));
    }

    private static List<Person> people(int count) {
        List<Person> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new Person(String.format("aa%02d", i), 20 + i % 50));
        }
        return out;
    }

    private static Table<Person> mount(HeadlessGui h, int count) {
        Table<Person> table = new Table<>(h.gui, 2f, columns());
        mountNode(h, table, people(count));
        return table;
    }

    private static void mountNode(HeadlessGui h, Table<Person> table, List<Person> items) {
        table.node().height(Length.rem(20));
        h.gui.root().children(table.node());
        table.items(items);
        h.frame().frame().frame();
    }

    /** The width of one realized cell, read from the layout rather than from what the table says it wrote. */
    private static float cellWidth(Table<Person> table, String name, int column) {
        Node cell = table.cell(personNamed(table, name), column);
        assertNotNull(cell, "the cell for " + name + " is realized");
        return cell.layout().rect().w();
    }

    private static Person personNamed(Table<Person> table, String name) {
        for (Person p : table.rows().realizedItems()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    private static List<String> shownNames(Table<Person> table, List<Person> all) {
        List<Person> realized = new ArrayList<>(table.rows().realizedItems());
        realized.sort(Comparator.comparingDouble((Person p) -> table.rows().rowNode(p).layout().rect().y()));
        List<String> names = new ArrayList<>();
        for (Person p : realized) {
            names.add(p.name());
        }
        return names;
    }

    /**
     * Drag the resize grip of {@code column} by {@code dx} pixels, as a pointer would: press on the grip, move,
     * release. The grip is found by geometry — it is the strip at the header cell's trailing edge — rather than
     * through an accessor, so the test exercises where the grip actually is.
     */
    private static void dragGrip(HeadlessGui h, Table<Person> table, int column, float dx) {
        Rect cell = table.headerCell(column).layout().rect();
        float x = cell.x() + cell.w() - 2f;
        float y = cell.y() + cell.h() / 2f;

        h.bus.publish(dev.vexelray.gui.core.input.InputTopics.INPUT,
                new sibarum.tactroller.api.InputEvent.ButtonPressed(
                        sibarum.tactroller.api.MouseButton.LEFT, (int) x, (int) y, 0));
        h.frame();
        h.bus.publish(dev.vexelray.gui.core.input.InputTopics.INPUT,
                new sibarum.tactroller.api.InputEvent.PointerMoved(
                        (int) (x + dx), (int) y, (int) dx, 0, 0));
        h.frame();
        h.bus.publish(dev.vexelray.gui.core.input.InputTopics.INPUT,
                new sibarum.tactroller.api.InputEvent.ButtonReleased(
                        sibarum.tactroller.api.MouseButton.LEFT, (int) (x + dx), (int) y, 0));
        h.frame().frame();
    }

    /** The sort indicator on a column, or null when it carries none. It is the header cell's reserved slot. */
    private static dev.vexelray.gui.draw.Picture markOf(HeadlessGui h, Table<Person> table, int column) {
        return h.retained(table.headerCell(column)).children.get(1).picture();
    }

    private static Rect boxOf(dev.vexelray.gui.core.model.RetainedNode n) {
        return new Rect(n.x, n.y, n.w, n.h);
    }

    private static Rect headerCellRect(Table<Person> table) {
        return table.headerCell(0).layout().rect();
    }
}
