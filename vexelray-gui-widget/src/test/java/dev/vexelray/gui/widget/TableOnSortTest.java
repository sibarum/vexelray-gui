package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The order changing is announced, however it changed, so a status line can say what the rows are sorted by. */
class TableOnSortTest {

    @Test
    void sortingByCodeIsAnnouncedWithTheColumnAndDirection() {
        try (HeadlessGui h = new HeadlessGui()) {
            Table<String> table = new Table<>(h.gui, 2f, List.of(
                    Table.Column.of("Name", Length.grow(1), (g, s) -> g.text(s), Comparator.<String>naturalOrder())));
            h.gui.root().children(table.node());
            List<String> seen = new ArrayList<>();
            table.onSort((column, direction) -> seen.add(column + ":" + direction));
            table.items(List.of("b", "a"));

            table.sortBy(0, Table.Sort.DESCENDING);
            table.sortBy(0, Table.Sort.NONE);

            assertEquals(List.of("0:DESCENDING", "-1:NONE"), seen);
        }
    }
}
