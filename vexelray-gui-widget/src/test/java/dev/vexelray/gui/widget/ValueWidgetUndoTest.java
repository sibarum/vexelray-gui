package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.edit.Outcome;
import dev.vexelray.gui.core.edit.UndoEvent;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every control that holds a value undoes by default (docs/plans/undo.md, step 6): the user's edit is a step in the
 * window's history, Ctrl+Z replays the earlier value the way the user would have set it — handler and all, so the
 * application's model follows — and a sync ({@code show}) is never a step.
 */
class ValueWidgetUndoTest {

    /** A headless window with a history bound on its root, as the framework binds one on every window. */
    private static final class Window implements AutoCloseable {

        final HeadlessGui h = new HeadlessGui();
        final History history = new History();
        final List<UndoEvent> events = new CopyOnWriteArrayList<>();

        Window() {
            h.gui.history(history);
            h.gui.onUndo(events::add);
        }

        Window mount(dev.vexelray.gui.core.Node... nodes) {
            h.gui.root().children(h.gui.column().width(Length.dp(400)).children(nodes));
            h.frame().frame();
            return this;
        }

        Window undo() {
            h.chord(Key.Z, Key.LEFT_CONTROL);
            h.frame().frame().frame();
            return this;
        }

        Window redo() {
            h.chord(Key.Y, Key.LEFT_CONTROL);
            h.frame().frame().frame();
            return this;
        }

        void click(dev.vexelray.gui.core.Node n) {
            Rect r = n.layout().rect();
            h.click(r.x() + r.w() / 2f, r.y() + r.h() / 2f);
        }

        @Override
        public void close() {
            h.close();
        }
    }

    @Test
    void aFlipUndoesAndTheApplicationHearsIt() {
        try (Window w = new Window()) {
            List<Boolean> heard = new CopyOnWriteArrayList<>();
            Toggle t = new Toggle(w.h.gui, false).onChange(heard::add);
            w.mount(t.node());

            w.click(t.node());
            assertTrue(t.on());
            assertEquals(1, w.history.undoDepth());

            w.undo();
            assertFalse(t.on(), "Ctrl+Z flips it back");
            assertEquals(List.of(true, false), heard, "the way the user would have: the application's model follows");

            w.redo();
            assertTrue(t.on());
        }
    }

    @Test
    void twoFlipsAreTwoSteps() {
        try (Window w = new Window()) {
            Toggle t = new Toggle(w.h.gui, false);
            w.mount(t.node());
            w.click(t.node());
            w.h.pause();
            w.click(t.node());
            assertEquals(2, w.history.undoDepth(), "folding them would leave a Ctrl+Z that changes nothing");
        }
    }

    @Test
    void aSyncIsNeverAStep() {
        try (Window w = new Window()) {
            Toggle t = new Toggle(w.h.gui, false);
            Slider s = new Slider(w.h.gui, 0f);
            NumberField n = new NumberField(w.h.gui, 1, 1);
            Segment<String> g = new Segment<String>(w.h.gui).option("A", "a").option("B", "b");
            w.mount(t.node(), s.node(), n.node(), g.node());

            t.show(true);
            s.show(0.5f);
            n.show(7);
            g.show("b");
            w.h.frame();
            assertEquals(0, w.history.undoDepth(), "the model changed under the controls; nobody edited anything");
        }
    }

    @Test
    void aValueWrittenUnderneathIsAConflictAndNothingMoves() {
        try (Window w = new Window()) {
            Toggle t = new Toggle(w.h.gui, false);
            w.mount(t.node());
            w.click(t.node());
            t.show(false);   // another window, or the model, said otherwise since

            w.undo();
            assertFalse(t.on(), "an older value is never written over a newer one");
            assertEquals(1, w.history.undoDepth());
            assertTrue(w.events.stream().anyMatch(e -> e.outcome() == Outcome.CONFLICT), w.events.toString());
        }
    }

    @Test
    void undoableFalseRecordsNothing() {
        try (Window w = new Window()) {
            Toggle t = new Toggle(w.h.gui, false).undoable(false);
            w.mount(t.node());
            w.click(t.node());
            assertTrue(t.on());
            assertEquals(0, w.history.undoDepth(), "a switch that is not an edit stays out of the history");
        }
    }

    @Test
    void aSliderDragIsOneStepAndOneCommit() {
        try (Window w = new Window()) {
            List<Double> commits = new CopyOnWriteArrayList<>();
            Slider s = new Slider(w.h.gui, 0f).lattice(Lattice.intervals(10)).onCommit(commits::add);
            w.mount(s.node());
            Rect r = s.node().layout().rect();
            int y = (int) (r.y() + r.h() / 2f);
            float travel = r.w() - r.h();
            w.h.bus.publish(InputTopics.INPUT,
                    new InputEvent.ButtonPressed(MouseButton.LEFT, (int) (r.x() + r.h() / 2f), y, 0));
            w.h.frame();
            for (int k = 1; k <= 8; k++) {
                int x = (int) (r.x() + r.h() / 2f + k * 0.1f * travel);
                w.h.bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(x, y, 1, 0, 0));
                w.h.frame();
            }
            w.h.bus.publish(InputTopics.INPUT,
                    new InputEvent.ButtonReleased(MouseButton.LEFT, (int) (r.x() + r.h() / 2f + 0.8f * travel), y, 0));
            w.h.frame().frame();
            assertEquals(0.8f, s.value(), 1e-5f);
            assertEquals(1, commits.size(), "the drag commits once, at its end");
            assertEquals(1, w.history.undoDepth(), "and is one step, however many points it crossed");

            w.undo();
            assertEquals(0f, s.value(), 1e-5f, "all the way back to where the drag began");
            assertEquals(0.0, commits.get(commits.size() - 1), 1e-5, "and the application is told, as a commit");
        }
    }

    @Test
    void arrowKeysOnASliderAreOneRun() {
        try (Window w = new Window()) {
            Slider s = new Slider(w.h.gui, 0f).lattice(Lattice.intervals(10));
            w.mount(s.node());
            w.h.focus(s.node());
            w.h.tap(Key.RIGHT).tap(Key.RIGHT).tap(Key.RIGHT);
            assertEquals(0.3f, s.value(), 1e-5f);
            assertEquals(1, w.history.undoDepth(), "a run of steps is one Ctrl+Z");
            w.undo();
            assertEquals(0f, s.value(), 1e-5f);
        }
    }

    @Test
    void aNumberUndoesToTheLastAgreedNumberNotTheLastKeystroke() {
        try (Window w = new Window()) {
            List<Double> heard = new CopyOnWriteArrayList<>();
            NumberField n = new NumberField(w.h.gui, 1, 1).onChange(heard::add);
            w.mount(n.node());
            w.h.focus(n.node());
            w.h.tap(Key.UP).tap(Key.UP);
            assertEquals(3.0, n.value());
            assertEquals(1, w.history.undoDepth(), "the steps fold into one run");

            w.undo();
            assertEquals(1.0, n.value());
            assertEquals("1", n.field().text(), "and the field shows it");
            assertEquals(1.0, heard.get(heard.size() - 1), "and the application hears it as an agreed number");
        }
    }

    @Test
    void aSegmentChoiceUndoes() {
        try (Window w = new Window()) {
            Segment<String> g = new Segment<String>(w.h.gui).option("A", "a").option("B", "b");
            w.mount(g.node());
            g.select("b");
            assertEquals(1, w.history.undoDepth());
            w.h.gui.undo(w.history);   // from a button, not the keyboard: the segment is not focused yet
            w.h.frame().frame().frame();
            assertEquals("b", g.selected(), "the first press only goes there");
            w.h.gui.undo(w.history);
            w.h.frame().frame().frame();
            assertEquals("a", g.selected());
        }
    }

    /** A command as a change: the press does it, Ctrl+Z undoes it, and the change is built at press time. */
    @Test
    void aButtonThatPerformsAChangeIsUndone() {
        try (Window w = new Window()) {
            List<String> items = new CopyOnWriteArrayList<>(List.of("a"));
            java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
            Button add = new Button(w.h.gui, "Add").perform(() -> {
                asked.incrementAndGet();
                String item = "n" + items.size();
                return new dev.vexelray.gui.core.edit.Change() {
                    @Override
                    public dev.vexelray.gui.core.edit.Change apply() {
                        items.add(item);
                        dev.vexelray.gui.core.edit.Change self = this;
                        return () -> {
                            items.remove(item);
                            return self;
                        };
                    }
                };
            });
            w.mount(add.node());

            w.click(add.node());
            w.h.pause();
            w.click(add.node());
            assertEquals(List.of("a", "n1", "n2"), items);
            assertEquals(2, asked.get(), "the change is asked for on each press, against what is there then");
            assertEquals(2, w.history.undoDepth());

            w.undo();
            assertEquals(List.of("a", "n1"), items, "Ctrl+Z takes the last command back");
            w.redo();
            assertEquals(List.of("a", "n1", "n2"), items);
        }
    }

    @Test
    void aCommandWithNothingToDoRecordsNothing() {
        try (Window w = new Window()) {
            Button b = new Button(w.h.gui, "Nothing").perform(() -> null);
            w.mount(b.node());
            w.click(b.node());
            assertEquals(0, w.history.undoDepth());
        }
    }

    @Test
    void aChipUndoesAsASwitchDoes() {
        try (Window w = new Window()) {
            List<Boolean> heard = new CopyOnWriteArrayList<>();
            Button chip = new Button(w.h.gui, "Bold").toggle(true).onToggle(heard::add);
            w.mount(chip.node());
            w.click(chip.node());
            assertTrue(chip.pressed());
            w.undo();
            assertFalse(chip.pressed());
            assertEquals(List.of(true, false), heard);
        }
    }

    @Test
    void aSelectChoiceUndoesButBrowsingTheListDoesNot() {
        try (Window w = new Window()) {
            List<Set<String>> commits = new CopyOnWriteArrayList<>();
            Select<String> s = new Select<String>(w.h.gui).options(List.of("x", "y", "z")).onCommit(commits::add);
            w.mount(s.node());
            s.show("x");
            w.h.focus(s.node());
            s.open();
            s.selection().set(List.of("y"));
            s.selection().set(List.of("z"));
            s.commit();
            w.h.frame();
            assertEquals(1, w.history.undoDepth(), "moving through the options is not choosing");

            w.undo();
            assertEquals("x", s.value());
            assertEquals(Set.of("x"), commits.get(commits.size() - 1));
        }
    }
}
