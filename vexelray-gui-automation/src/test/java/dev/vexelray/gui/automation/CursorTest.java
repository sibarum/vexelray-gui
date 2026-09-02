package dev.vexelray.gui.automation;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pointer travels, and things it passes over find out.
 *
 * <p>The first test here is the one the module exists for. Everything else — the module, the protocol, the log —
 * is in service of being able to reproduce a bug whose only evidence was a hover fired while crossing an
 * unrelated widget. A driver that teleports passes a test asserting the destination and produces a clean run
 * that says nothing happened; this asserts the <em>middle</em> of the journey, which is the part that has to be
 * real.
 *
 * <p>Deterministic by construction: a same-thread handler executor, so an event published here is dispatched and
 * fully handled inside {@code frame()} on this thread.
 */
class CursorTest {

    private static final float W = 800f;
    private static final float H = 600f;

    /** A GUI whose handlers run on the calling thread, so a published edge is handled inside the next frame. */
    private static Gui deterministic() {
        return new Gui(sibarum.atchung.Atchung.create(), Runnable::run);
    }

    /** Text metrics are irrelevant here; nothing in these trees has text. */
    private static final TextMeasurer NO_TEXT = new TextMeasurer() {
        @Override
        public float intrinsic(RetainedNode node, Axis axis, float px) {
            return 0f;
        }

        @Override
        public int offsetAt(String text, float localX, float px) {
            return 0;
        }

        @Override
        public float[] caretAdvances(String text, float px) {
            int n = text == null ? 0 : text.length();
            return new float[n + 1];    // zero-width glyphs, but the right number of caret boundaries
        }
    };

    /** Three 100px boxes in a row at y 0..100: left at x 0..100, middle at 100..200, right at 200..300. */
    private record Strip(Gui gui, Node left, Node middle, Node right) { }

    private static Strip strip(Gui gui) {
        Node left = box(gui);
        Node middle = box(gui);
        Node right = box(gui);
        gui.root().children(gui.row().width(Length.FILL).height(Length.dp(100))
                .scroll(false, false)
                .children(left, middle, right));
        return new Strip(gui, left, middle, right);
    }

    private static Node box(Gui gui) {
        return gui.box().size(Length.dp(100), Length.dp(100));
    }

    @Test
    void aPathProvokesHoverOnWhatItCrosses() {
        try (Gui gui = deterministic()) {
            Strip s = strip(gui);
            List<String> middleStates = new ArrayList<>();
            gui.onState(s.middle(), state -> middleStates.add(state.name()));
            gui.frame(W, H, NO_TEXT);

            // Start left of everything, finish on the right box. The middle box is not the start, not the
            // target, and is never named — it is simply in the way, which is the whole point.
            Cursor cursor = new Cursor(gui.bus(), 10, 50);
            cursor.moveTo(250, 50);
            gui.frame(W, H, NO_TEXT);

            assertTrue(middleStates.contains("HOVER"),
                    "the box between start and target must have been hovered in passing, got " + middleStates);
            assertTrue(middleStates.indexOf("HOVER") < middleStates.lastIndexOf("NORMAL"),
                    "and left again as the pointer went on, got " + middleStates);
        }
    }

    @Test
    void aTeleportWouldHaveMissedIt() {
        try (Gui gui = deterministic()) {
            Strip s = strip(gui);
            List<String> middleStates = new ArrayList<>();
            gui.onState(s.middle(), state -> middleStates.add(state.name()));
            gui.frame(W, H, NO_TEXT);

            // The control for the test above: the same start and target, published as one edge the way a
            // teleporting driver would. If this ever starts reporting a hover, the test above has stopped
            // proving anything and something else is generating the transitions.
            gui.bus().publish(dev.vexelray.gui.core.input.InputTopics.INPUT,
                    new sibarum.tactroller.api.InputEvent.PointerMoved(250, 50, 240, 0, 0));
            gui.frame(W, H, NO_TEXT);

            assertTrue(middleStates.stream().noneMatch("HOVER"::equals),
                    "one jump cannot hover what it flew over, got " + middleStates);
        }
    }

    @Test
    void theCursorKnowsWhereItIsAndArrivesExactly() {
        try (Gui gui = deterministic()) {
            Cursor cursor = new Cursor(gui.bus(), 10, 20);
            assertEquals(10, cursor.x());
            assertEquals(20, cursor.y());

            cursor.moveTo(413, 89);
            // Interpolated from the origin rather than accumulated, so the last step lands on the target rather
            // than near it. Rounding drift here would put every later click a few pixels off, intermittently.
            assertEquals(413, cursor.x(), "a path ends where it was aimed");
            assertEquals(89, cursor.y());

            cursor.moveTo(0, 0);
            assertEquals(0, cursor.x(), "and the next path starts from there, not from nowhere");
        }
    }

    @Test
    void theSamePathTwiceIsTheSameEvents() {
        try (Gui gui = deterministic()) {
            List<String> first = record(gui, () -> new Cursor(gui.bus(), 5, 5).moveTo(300, 200));
            List<String> second = record(gui, () -> new Cursor(gui.bus(), 5, 5).moveTo(300, 200));

            // An instrument that is not reproducible cannot be used to bisect anything, which is why there is
            // no jitter here however human it might look.
            assertEquals(first, second, "same start, same target, same events");
            assertTrue(first.size() > 4, "and it is a path, not a jump: " + first.size() + " steps");
        }
    }

    @Test
    void aClickIsAJourneyThatEndsInAPressAndRelease() {
        try (Gui gui = deterministic()) {
            List<String> events = record(gui, () -> new Cursor(gui.bus(), 0, 0).click(120, 60));

            assertEquals("ButtonReleased", kind(events.get(events.size() - 1)));
            assertEquals("ButtonPressed", kind(events.get(events.size() - 2)));
            assertTrue(events.size() > 3, "the press is preceded by the travel, not by nothing");
            assertTrue(events.subList(0, events.size() - 2).stream()
                            .allMatch(e -> kind(e).equals("PointerMoved")),
                    "and everything before it is movement: " + events);
        }
    }

    @Test
    void aDragHoldsTheButtonForTheWholePath() {
        try (Gui gui = deterministic()) {
            Cursor cursor = new Cursor(gui.bus(), 40, 40);
            List<String> events = record(gui, () -> cursor.drag(300, 40, MouseButton.LEFT));

            assertEquals("ButtonPressed", kind(events.get(0)), "press first, where the pointer already was");
            assertEquals("ButtonReleased", kind(events.get(events.size() - 1)), "release where it ended up");
            long moves = events.stream().filter(e -> kind(e).equals("PointerMoved")).count();
            // The framework's own drag detection watches for a distance threshold and a hold. A press followed
            // by one jump satisfies neither, which is why a synthesized drag has to actually be dragged.
            assertTrue(moves > 4, "a drag is a path with the button down, got " + moves + " moves");
        }
    }

    /** Collect the input events {@code action} publishes, in order, as their simple class names plus position. */
    private static List<String> record(Gui gui, Runnable action) {
        List<String> seen = new ArrayList<>();
        try (var sub = gui.bus().subscribe(dev.vexelray.gui.core.input.InputTopics.INPUT,
                e -> seen.add(e.getClass().getSimpleName() + " " + e))) {
            action.run();
        }
        assertNotEquals(0, seen.size(), "the cursor must publish on the ordinary input topic");
        return seen;
    }

    private static String kind(String recorded) {
        return recorded.substring(0, recorded.indexOf(' '));
    }
}
