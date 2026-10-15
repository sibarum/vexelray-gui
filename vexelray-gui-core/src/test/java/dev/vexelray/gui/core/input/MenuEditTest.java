package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A menu item whose command is an edit (docs/plans/undo.md, step 7): choosing it performs the change through the
 * history of the node that was right-clicked, so it undoes; with no history it is still done.
 */
class MenuEditTest {

    private static final ClickEvent CLICK = new ClickEvent(7L, MouseButton.RIGHT, 1f, 1f, 1, Set.of());

    /** Add {@code word} to {@code list}, and hand back the way home. */
    private static Change adding(List<String> list, String word) {
        return () -> {
            list.add(word);
            return () -> {
                list.remove(word);
                return adding(list, word);
            };
        };
    }

    private static void choose(MenuCollector menu, String label) {
        for (MenuItem item : menu.build()) {
            if (label.equals(item.label())) {
                item.action().run();
                return;
            }
        }
        throw new AssertionError("no item " + label);
    }

    @Test
    void anEditItemIsPerformedThroughTheHistoryAndUndoes() {
        History history = new History();
        List<String> doc = new ArrayList<>();
        MenuCollector menu = new MenuCollector(CLICK, history);
        menu.edit("Add word", () -> adding(doc, "word"));

        choose(menu, "Add word");
        assertEquals(List.of("word"), doc);
        assertEquals(1, history.undoDepth());
        assertTrue(history.undo());
        assertTrue(doc.isEmpty(), "Ctrl+Z takes the menu's command back");
    }

    @Test
    void withNoHistoryItIsStillDone() {
        List<String> doc = new ArrayList<>();
        MenuCollector menu = new MenuCollector(CLICK, null);
        menu.edit("Add word", () -> adding(doc, "word"));
        choose(menu, "Add word");
        assertEquals(List.of("word"), doc, "a tree that binds no history still gets the command, only not its undo");
    }

    @Test
    void aDisabledEditCarriesNoCommand() {
        MenuCollector menu = new MenuCollector(CLICK, new History());
        menu.edit("Add word", false, () -> {
            throw new AssertionError("never asked");
        });
        assertEquals(null, menu.build().get(0).action());
    }
}
