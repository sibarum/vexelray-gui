package dev.vexelray.gui.core;

import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.edit.Outcome;
import dev.vexelray.gui.core.edit.Target;
import dev.vexelray.gui.core.edit.UndoEvent;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.nav.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two-press undo (docs/plans/undo.md §2), driven through a headless {@link Gui} with handlers run inline: the
 * first press takes the user to what an entry edits, the second applies it, and a target that is gone or disabled
 * is refused without anything moving.
 *
 * <p>The tree: a page (hidden until revealed, like an unselected tab) holding the field the entry edits, and a
 * second field elsewhere that has focus when the test starts.
 */
class UndoingTest {

    private final Gui gui = new Gui(Atchung.create(), Runnable::run);
    private final Node page = gui.box().width(Length.dp(200)).height(Length.dp(40)).visible(false);
    private final Node field = gui.box().width(Length.dp(100)).height(Length.dp(20));
    private final Node elsewhere = gui.box().width(Length.dp(100)).height(Length.dp(20));
    private final AtomicReference<String> value = new AtomicReference<>("before");
    private final List<UndoEvent> events = new CopyOnWriteArrayList<>();
    private final History history = new History();

    UndoingTest() {
        gui.root().children(page.children(field), elsewhere);
        gui.focusable(field, true);
        gui.focusable(elsewhere, true);
        gui.reveals(page, id -> {
            page.visible(true);
            return true;
        });
        gui.onUndo(events::add);
        frames(1);
        gui.focus(elsewhere);
        frames(1);
    }

    @AfterEach
    void close() {
        gui.close();
    }

    /** A value change on the field, as a widget would record it: absolute, aimed at the field, and checked. */
    private record SetValue(AtomicReference<String> value, String to, String expect, Target target) implements Change {

        @Override
        public Change apply() {
            String was = value.getAndSet(to);
            return new SetValue(value, was, to, target);
        }

        @Override
        public Check check() {
            return value.get().equals(expect) ? Check.OK : Check.CONFLICT;
        }
    }

    private void edit(Target target) {
        history.perform(new SetValue(value, "after", "before", target));
    }

    private void frames(int n) {
        for (int i = 0; i < n; i++) {
            gui.frame(400f, 300f, (RetainedNode node, dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) -> 0f);
        }
    }

    /** Press, then run frames until the press has been answered (a reveal takes a few). */
    private UndoEvent press(boolean redo) {
        int before = events.size();
        if (redo) {
            gui.redo(history);
        } else {
            gui.undo(history);
        }
        for (int i = 0; i < 20 && events.size() == before; i++) {
            frames(1);
        }
        assertEquals(before + 1, events.size(), "one press, one answer");
        return events.get(events.size() - 1);
    }

    private long focused() {
        return gui.focusState().nodeId();
    }

    @Test
    void theFirstPressRevealsAndTheSecondApplies() {
        edit(Target.node(field.id()));

        UndoEvent first = press(false);
        assertEquals(Outcome.REVEALING, first.outcome());
        assertEquals("after", value.get(), "nothing changes out of sight");
        assertEquals(field.id(), focused(), "the user is taken to the field");
        assertTrue(gui.semanticSnapshot().node(page.id()).visible(), "through the page that was hiding it");
        assertEquals(1, history.undoDepth());

        UndoEvent second = press(false);
        assertEquals(Outcome.APPLIED, second.outcome());
        assertEquals("before", value.get(), "now it is undone, where the user can see it");
        assertEquals(field.id(), second.node());
    }

    @Test
    void aFocusedTargetAppliesInOnePress() {
        edit(Target.node(elsewhere.id()));
        assertEquals(Outcome.APPLIED, press(false).outcome());
        assertEquals("before", value.get());
    }

    @Test
    void anEntryWithNoTargetAppliesWhereTheUserIs() {
        edit(null);
        assertEquals(Outcome.APPLIED, press(false).outcome());
        assertEquals(elsewhere.id(), focused(), "and takes them nowhere");
    }

    @Test
    void aTargetThatLeftTheTreeIsUnreachable() {
        edit(Target.node(field.id()));
        field.remove();
        frames(1);
        assertEquals(Outcome.UNREACHABLE, press(false).outcome());
        assertEquals("after", value.get());
        assertEquals(1, history.undoDepth(), "a failure moves nothing");
    }

    @Test
    void aDisabledTargetIsShownButNotFocusedAndRefuses() {
        edit(Target.node(field.id()));
        field.disabled(true);
        frames(1);

        UndoEvent e = press(false);
        assertEquals(Outcome.REFUSED, e.outcome());
        assertEquals(field.id(), e.node(), "the presenter is told which control is in the way");
        assertTrue(gui.semanticSnapshot().node(page.id()).visible(), "it is revealed, so the user can see it");
        assertNotEquals(field.id(), focused(), "but not focused: it would refuse the keyboard too");
        assertEquals("after", value.get());
        assertEquals(Outcome.REFUSED, press(false).outcome(), "and it stays in the way until it opens");
    }

    @Test
    void redoSurvivesARevealAndRevealsItself() {
        edit(Target.node(field.id()));
        press(false);
        press(false);
        assertEquals("before", value.get());

        gui.focus(elsewhere);
        frames(1);
        assertEquals(Outcome.REVEALING, press(true).outcome(), "redo takes the user there first, as undo does");
        assertEquals("before", value.get());
        assertEquals(1, history.redoDepth(), "the reveal cost nothing on either stack");
        assertEquals(Outcome.APPLIED, press(true).outcome());
        assertEquals("after", value.get());
    }

    @Test
    void aConflictIsReportedAndMovesNothing() {
        edit(Target.node(elsewhere.id()));
        value.set("written underneath");
        assertEquals(Outcome.CONFLICT, press(false).outcome());
        assertEquals("written underneath", value.get());
    }

    @Test
    void anAddressFindsWhateverNodeHoldsTheLandmark() {
        gui.landmark("settings.name", field);
        edit(Target.address(Address.of("settings.name")));
        assertEquals(Outcome.REVEALING, press(false).outcome());
        assertEquals(field.id(), focused());

        edit(Target.address(Address.of("nobody.has.this")));
        assertEquals(Outcome.UNREACHABLE, press(false).outcome());
        edit(Target.address(Address.of("another-window", "settings.name")));
        assertEquals(Outcome.UNREACHABLE, press(false).outcome(),
                "a history belongs to one window, and cannot edit another's controls");
    }
}
