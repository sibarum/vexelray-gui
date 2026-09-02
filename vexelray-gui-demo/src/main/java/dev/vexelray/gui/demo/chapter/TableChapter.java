package dev.vexelray.gui.demo.chapter;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.widget.Table;
import dev.vexelray.text.TextLayout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * A hundred thousand rows that cost a screenful of nodes, and everything that addresses a row still working for
 * the rows that therefore do not exist.
 *
 * <p>The counter under the table is the whole argument: it says how many rows the retained tree is holding, and
 * it does not move as you scroll a hundred thousand of them. Selecting every row does not move it either — the
 * selection is over <em>items</em>, and the rows are a window onto them.
 *
 * <p>Three components are on this page and only one is visible. The body is a {@code ListView}; what is selected
 * is a {@code SelectionModel}, the same type a tree or a list would use; and the {@code Table} itself is the part
 * that decides how wide a column is — which is a constraint between cells in different rows, and so the one thing
 * the layout engine deliberately will not do for it.
 */
public final class TableChapter implements Chapter {

    /** One row of the table. Synthetic, but shaped like the thing a real table holds: an id, text, a number. */
    private record Item(int id, String name, long bytes, String kind) { }

    private static final int ROWS = 100_000;

    /** What the counter last said, so a line that has not changed is not written again. See {@link #build}. */
    private String lastCount = "";

    @Override
    public String title() {
        return "Tables";
    }

    @Override
    public String blurb() {
        return "A hundred thousand rows in a screenful of nodes: columns negotiated once a frame, a selection "
                + "that is three operations rather than four gestures, and a row you can reach before it exists.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Console console = stage.console();
        List<Item> items = items();

        // A column's width is a Length and nothing new: a fixed one resolves against the table, grow(n) takes a
        // share of what is left, and AUTO sizes to its content. The engine cannot relate two cells in different
        // rows, so the table solves this itself once a frame -- and writes nothing on the frames where the
        // answer did not move, because a width write invalidates the layout that produced it.
        Table<Item> table = new Table<>(gui, 2f, List.of(
                Table.Column.of("#", Length.rem(4.5f), (g, it) -> cell(g, String.valueOf(it.id()), true),
                        Comparator.comparingInt(Item::id)),
                Table.Column.of("Name", Length.grow(3), (g, it) -> cell(g, it.name(), false),
                        Comparator.comparing(Item::name)),
                // AUTO, and the interesting one: a virtualised body has no nodes for the rows off screen, so
                // "as wide as its content" is measured from the content that exists and then held. A column
                // that grew as you scrolled would be the one movement the framework's rules forbid outright.
                Table.Column.of("Kind", Length.AUTO, (g, it) -> cell(g, it.kind(), false),
                        Comparator.comparing(Item::kind)),
                Table.Column.of("Size", Length.rem(7), (g, it) -> cell(g, size(it.bytes()), true),
                        Comparator.comparingLong(Item::bytes))));
        table.items(items);
        table.node().width(Length.FILL).height(Length.FILL);

        Node counter = gui.text("").width(Length.FILL).height(Length.rem(1.5f))
                .textSize(Length.rem(0.9375f)).textColor(gui.theme().color(Role.DIM))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);

        // Read on every published layout, written only when it changed. The guard is not an optimisation: a
        // text write invalidates the layout, so a counter that wrote unconditionally would relayout for ever --
        // the same discipline the table's own column solve is under, which is why it is worth seeing twice.
        gui.layout().onCommit(snapshot -> {
            String now = ROWS + " rows  ·  " + table.rows().realizedRows() + " of them exist  ·  "
                    + table.selection().size() + " selected";
            if (!now.equals(lastCount)) {
                lastCount = now;
                counter.text(now);
            }
        });

        table.selection().onChange(selected -> {
            Item one = table.selection().one();
            console.note(selected.size() == 1 && one != null
                    ? "selected " + one.name()
                    : selected.size() + " rows selected");
        });

        Node tools = Ui.strip(gui,
                Ui.controls(gui,
                        Ui.label(gui, "Try:", Length.rem(3)),
                        Ui.button(gui, "Reveal row 75,000", () -> {
                            Item far = items.get(75_000);
                            // Nothing to scroll to: that row has no node. The list builds it, puts it where its
                            // index says, and only then scrolls -- which is the half an application gets wrong.
                            table.rows().reveal(far);
                            table.selection().at(far);
                            console.good("revealed and selected " + far.name());
                        }),
                        Ui.button(gui, "Select every row", () -> {
                            table.selection().all(table.rows().order());
                            console.good("selected all " + ROWS + " items — the row count did not move");
                        }),
                        Ui.button(gui, "Clear the selection", () -> {
                            table.selection().clear();
                            console.note("cleared");
                        }),
                        Ui.button(gui, "Sort by size", () -> {
                            table.sortBy(3, Table.Sort.DESCENDING);
                            console.note("sorted by size, largest first — the selection came with it");
                        })),
                counter,
                Ui.prose(gui, "Click a row to select it, Ctrl-click to add one, Shift-click to take everything "
                        + "between — and Shift+Arrow does exactly what Shift-click does, because they are the "
                        + "same operation and not two that have to be kept in agreement. Drag the divider at "
                        + "the right of a header to resize a column; click a header to sort by it, twice to "
                        + "reverse, three times to get the order the rows arrived in. The header does not "
                        + "scroll away because it is not inside the thing that scrolls."));

        return gui.column().width(Length.FILL).height(Length.FILL).gap(Ui.GAP)
                .children(Ui.card(gui, Ui.heading(gui, "A hundred thousand rows"), table.node()), tools);
    }

    /** One cell: a plain text node, because a cell's box, width and clipping are the table's business. */
    private static Node cell(Gui gui, String text, boolean numeric) {
        return gui.text(text).width(Length.FILL).height(Length.FILL)
                .textSize(Length.rem(0.9375f))
                .textColor(gui.theme().color(numeric ? Role.DIM : Role.INK))
                .align(numeric ? TextLayout.HAlign.RIGHT : TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
    }

    /** Rows enough to make the point, generated from a fixed seed so the page is the same page every run. */
    private static List<Item> items() {
        String[] first = {"alder", "birch", "cedar", "dogwood", "elm", "fir", "gum", "hazel", "ironwood",
                "juniper", "katsura", "larch", "maple", "nutmeg", "oak", "pine", "quince", "rowan", "spruce",
                "teak", "umbrella", "viburnum", "willow", "yew"};
        String[] kinds = {"document", "image", "archive", "spreadsheet", "audio"};
        Random random = new Random(20260902L);
        List<Item> out = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            String name = first[random.nextInt(first.length)] + "-" + first[random.nextInt(first.length)]
                    + "-" + (1000 + random.nextInt(9000));
            out.add(new Item(i + 1, name, (long) (random.nextDouble() * 900_000_000L),
                    kinds[random.nextInt(kinds.length)]));
        }
        return out;
    }

    /** A byte count a person can read, which is what a size column is for. */
    private static String size(long bytes) {
        String[] units = {"B", "KB", "MB", "GB"};
        double v = bytes;
        int unit = 0;
        while (v >= 1024 && unit < units.length - 1) {
            v /= 1024;
            unit++;
        }
        return unit == 0 ? (long) v + " B" : String.format("%.1f %s", v, units[unit]);
    }
}
