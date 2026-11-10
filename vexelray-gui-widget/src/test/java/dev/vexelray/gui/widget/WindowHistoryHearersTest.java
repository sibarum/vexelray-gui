package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A window's history is heard by every part of the window that shows it — a menu item's enablement, a title bar's dirty
 * dot, a page's readout — and none of them owns it. So it is heard by key, which admits any number of hearers, and not
 * on a widget's route, which admits one: the gallery built two pages that each heard the window's history and was
 * refused at startup with "two mailboxes answer to 'tree1.history'".
 */
class WindowHistoryHearersTest {

    private static Change set(List<String> model, String to) {
        return () -> {
            String was = model.get(0);
            model.set(0, to);
            return set(model, was);
        };
    }

    @Test
    void twoPartsOfOneWindowBothHearItsHistory() {
        try (HeadlessGui h = new HeadlessGui()) {
            History window = new History();
            h.gui.history(window);
            Set<String> key = Set.of(h.gui.address(History.ADDRESS));
            List<History.Status> menu = new ArrayList<>();
            List<History.Status> readout = new ArrayList<>();
            h.gui.mailbox(History.STATUS, c -> menu.add(c.status()), 1, key);
            h.gui.mailbox(History.STATUS, c -> readout.add(c.status()), 1, key);   // refused when it was a route
            h.frame();

            window.perform(set(new ArrayList<>(List.of("a")), "b"));
            h.frame();

            History.Status now = window.status();
            assertTrue(now.canUndo());
            assertEquals(now, menu.get(menu.size() - 1), "the first hearer is told");
            assertEquals(now, readout.get(readout.size() - 1), "and so is the second, in the same frame");
        }
    }

    @Test
    void anotherWindowsHistoryIsNotHeard() {
        try (HeadlessGui a = new HeadlessGui(); HeadlessGui b = new HeadlessGui()) {
            History mine = new History();
            History theirs = new History();
            a.gui.history(mine);
            b.gui.history(theirs);
            List<History.Status> heard = new ArrayList<>();
            a.gui.mailbox(History.STATUS, c -> heard.add(c.status()), 1, Set.of(a.gui.address(History.ADDRESS)));
            a.frame();
            heard.clear();

            theirs.perform(set(new ArrayList<>(List.of("a")), "b"));
            a.frame();

            assertTrue(heard.isEmpty(), "the key names the window, so another window's history is not this one's");
        }
    }
}
