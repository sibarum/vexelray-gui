package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.edit.Outcome;
import dev.vexelray.gui.core.edit.UndoEvent;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typing is undone with everything else in the window (docs/plans/undo.md §5): fields record into the history
 * their tree binds, Ctrl+Z walks back across fields — taking the user to each before changing it — and a field
 * that is a document keeps a past of its own.
 */
class TextFieldWindowHistoryTest {

    private static final class Two implements AutoCloseable {

        final HeadlessGui h = new HeadlessGui();
        final History window = new History();
        final TextField a = new TextField(h.gui, "");
        final TextField b = new TextField(h.gui, "");
        final List<UndoEvent> events = new CopyOnWriteArrayList<>();

        Two() {
            h.gui.history(window);
            h.gui.onUndo(events::add);
            h.gui.root().children(h.gui.column().width(Length.FILL).children(a.node(), b.node()));
            h.frame();
        }

        Two typeIn(TextField f, String s) {
            h.focus(f.node());
            h.type(s);
            return this;
        }

        Two undo() {
            h.chord(Key.Z, Key.LEFT_CONTROL);
            h.frame().frame().frame();   // a reveal walks over frames
            return this;
        }

        long focused() {
            return h.gui.focusState().nodeId();
        }

        @Override
        public void close() {
            h.close();
        }
    }

    @Test
    void ctrlZWalksBackAcrossFieldsTakingTheUserToEach() {
        try (Two t = new Two()) {
            t.typeIn(t.a, "abc").typeIn(t.b, "xyz");
            assertSame(t.window, t.a.history(), "a field records where its tree says");
            assertEquals(2, t.window.undoDepth(), "one run per field, in the window's one history");

            t.undo();
            assertEquals("", t.b.text(), "the field with focus undoes at once");
            assertEquals("abc", t.a.text());

            t.undo();
            assertEquals("abc", t.a.text(), "the next entry is the other field's: the first press only goes there");
            assertEquals(t.a.node().id(), t.focused());

            t.undo();
            assertEquals("", t.a.text(), "and the second undoes it, in front of the user");
            assertEquals(0, t.window.undoDepth());
        }
    }

    @Test
    void leavingAFieldEndsItsRun() {
        try (Two t = new Two()) {
            t.typeIn(t.a, "ab");
            t.h.focus(t.b.node());
            t.typeIn(t.a, "c");
            assertEquals(2, t.window.undoDepth(), "typed, left, came back and typed: two steps");
        }
    }

    @Test
    void settingContentForgetsOnlyThatFieldsPast() {
        try (Two t = new Two()) {
            t.typeIn(t.a, "abc").typeIn(t.b, "xyz");
            t.a.text("loaded");
            assertEquals(1, t.window.undoDepth(), "the field's old typing has nothing to return to");
            t.undo();
            assertEquals("", t.b.text(), "the other field's typing still undoes");
            assertEquals("loaded", t.a.text());
        }
    }

    @Test
    void aDocumentKeepsAPastOfItsOwn() {
        try (Two t = new Two()) {
            History doc = new History();
            t.a.history(doc);
            t.typeIn(t.b, "xyz").typeIn(t.a, "abc");
            assertEquals(1, doc.undoDepth());
            assertEquals(1, t.window.undoDepth(), "the document's typing never enters the window's history");

            t.undo();
            assertEquals("", t.a.text(), "Ctrl+Z in the document undoes the document");
            assertEquals("xyz", t.b.text(), "and does not reach into the window");
        }
    }

    @Test
    void aReadOnlyFieldRefusesToBeUndoneAndSaysSo() {
        try (Two t = new Two()) {
            t.typeIn(t.a, "abc");
            t.a.readOnly(true);
            t.undo();
            assertEquals("abc", t.a.text());
            assertTrue(t.events.stream().anyMatch(e -> e.outcome() == Outcome.REFUSED),
                    "refused, so the window can show it: " + t.events);
        }
    }
}
