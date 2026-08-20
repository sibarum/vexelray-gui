package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The composition an editor actually runs: a {@link Tabs} panel whose pages each hold a focused multiline
 * {@link TextField}, with the app's "close tab" chord bound globally on the {@code Gui}.
 *
 * <p>A focused text field consumes plenty of Ctrl chords of its own (select-all, copy, cut, paste, undo,
 * redo), so the question these pin down is whether an app chord the field does <em>not</em> claim still
 * reaches the app, and whether the tab it targets actually goes away.
 */
class TabCloseShortcutTest {

    /** Build {@code n} tabs, each a page holding a multiline field; returns the fields for cleanup. */
    private static List<TextField> fill(HeadlessGui h, Tabs tabs, int n) {
        List<TextField> fields = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TextField f = new TextField(h.gui, "document " + i).multiline(true);
            f.node().width(Length.FILL).height(Length.FILL);
            Node page = h.gui.column().width(Length.FILL).height(Length.FILL).children(f.node());
            tabs.add("tab" + i, page);
            fields.add(f);
        }
        return fields;
    }

    @Test
    void ctrlWClosesTheActiveTabWhileItsFieldHasFocus() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tabs tabs = new Tabs(h.gui);
            tabs.node().width(Length.FILL).height(Length.FILL);
            h.gui.root().children(tabs.node());
            List<TextField> fields = fill(h, tabs, 3);

            // The app's close action, as an editor would wire it: bound on the Gui, acting on the widget.
            h.gui.shortcut(Key.W, () -> tabs.remove(tabs.selected()), Modifier.CONTROL);

            tabs.select(1);
            h.frame();
            h.focus(fields.get(1).node());   // the page's editor holds keyboard focus, as in the real app
            h.frame();

            h.chord(Key.W, Key.LEFT_CONTROL);
            h.frame();

            assertEquals(2, tabs.count(), "Ctrl+W must close the active tab even with its editor focused");

            h.chord(Key.W, Key.LEFT_CONTROL);
            h.frame();
            assertEquals(1, tabs.count(), "a second Ctrl+W closes another tab");

            fields.forEach(TextField::close);
        }
    }

    /** The field must keep the Ctrl chords it does own: Ctrl+A selects all rather than reaching the app. */
    @Test
    void theFieldStillOwnsItsOwnCtrlChords() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tabs tabs = new Tabs(h.gui);
            tabs.node().width(Length.FILL).height(Length.FILL);
            h.gui.root().children(tabs.node());
            List<TextField> fields = fill(h, tabs, 1);
            TextField f = fields.get(0);

            h.frame();
            h.focus(f.node());
            h.frame();

            h.chord(Key.A, Key.LEFT_CONTROL);
            h.frame();

            assertEquals("document 0", f.document().value().selectedText(),
                    "Ctrl+A belongs to the focused field, not the app");

            f.close();
        }
    }
}
