package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A mark is a fill that is not a choice. These pin the two halves of that: a marked row looks different from a plain
 * one without being selected, and being selected still outranks being marked.
 */
class ListViewMarksTest {

    private static ListView<String> mount(HeadlessGui h) {
        ListView<String> list = new ListView<>(h.gui, 2f, (g, item) -> g.text(item));
        list.node().height(Length.rem(12));
        h.gui.root().children(list.node());
        list.items(List.of("a", "b", "c", "d"));
        h.frame().frame();
        return list;
    }

    private static Color fill(HeadlessGui h, ListView<String> list, String item) {
        return h.retained(list.rowNode(item)).background();
    }

    @Test
    void aMarkedRowWearsTheMarkedLookAndDoesNotBecomeSelected() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mount(h);
            Color plain = fill(h, list, "a");

            list.marked(Set.of("b", "c")::contains);
            h.frame();

            assertNotEquals(plain, fill(h, list, "b"), "the mark shows");
            assertEquals(plain, fill(h, list, "d"), "an unmarked row is untouched");
            assertEquals(Set.of(), list.selection().selection(), "and nothing was chosen");
        }
    }

    @Test
    void selectedOutranksMarked() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mount(h);
            list.looks(Role.SELECTION, Role.ACCENT);
            list.marked("b"::equals);
            h.frame();
            Color marked = fill(h, list, "b");

            list.selection().at("b");
            h.frame();

            assertNotEquals(marked, fill(h, list, "b"), "chosen is the stronger fact");
        }
    }

    @Test
    void clearingTheMarksRestoresTheRows() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mount(h);
            Color plain = fill(h, list, "a");
            list.marked("a"::equals);
            h.frame();
            list.marked(null);
            h.frame();
            assertEquals(plain, fill(h, list, "a"));
        }
    }
}
