package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.drop.Drop;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cards on a board: picked up, carried, put down — and the one property all of it serves, that nothing on screen
 * ever jumps. Each card here is a 40px face in a 50px carrier (5px of spacing above and below), so card {@code i}
 * of a deck rests at {@code y = 50 i}.
 */
class BoardTest {

    private static final float CARD = 50f;

    /** A ramp the test steps by hand, so a motion can be looked at in the middle. */
    private static final class Hand implements Ramp {
        private final List<DoubleConsumer> steps = new ArrayList<>();
        private final List<Runnable> dones = new ArrayList<>();

        @Override
        public void run(DoubleConsumer progress, Runnable done) {
            progress.accept(0d);
            steps.add(progress);
            dones.add(done);
        }

        /** Every running motion to {@code p}. */
        void at(double p) {
            for (DoubleConsumer s : new ArrayList<>(steps)) {
                s.accept(p);
            }
        }

        /** Finish everything, including whatever finishing starts. */
        void finish() {
            for (int guard = 0; guard < 100 && !steps.isEmpty(); guard++) {
                List<DoubleConsumer> s = new ArrayList<>(steps);
                List<Runnable> d = new ArrayList<>(dones);
                steps.clear();
                dones.clear();
                for (DoubleConsumer step : s) {
                    step.accept(1d);
                }
                for (Runnable done : d) {
                    done.run();
                }
            }
        }

        boolean idle() {
            return steps.isEmpty();
        }
    }

    /** The application: named columns of strings, changed only through undoable changes. */
    private static final class Model {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        final Map<String, Deck<String>> decks = new LinkedHashMap<>();
        final List<String> asked = new ArrayList<>();

        void show() {
            for (Map.Entry<String, Deck<String>> e : decks.entrySet()) {
                e.getValue().items(columns.get(e.getKey()));
            }
        }

        Change set(Map<String, List<String>> next) {
            return () -> {
                Map<String, List<String>> prev = columns;
                columns = next;
                show();
                return set(prev);
            };
        }

        Reorder<String> into(String column) {
            return (moved, where, effect) -> {
                asked.add(moved + " " + where.relation() + " " + where.reference() + " in " + column);
                Map<String, List<String>> next = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> e : columns.entrySet()) {
                    List<String> l = new ArrayList<>(e.getValue());
                    l.remove(moved);
                    next.put(e.getKey(), l);
                }
                List<String> to = next.get(column);
                int at = to.size();
                if (where.reference() != null) {
                    int ref = to.indexOf(where.reference());
                    at = where.relation() == Placement.Relation.BEFORE ? ref : ref + 1;
                }
                to.add(at, moved);
                return next.equals(columns) ? null : set(next);
            };
        }
    }

    private final Hand hand = new Hand();
    private final History history = new History();
    private final Model model = new Model();

    /** One column, or several side by side, each holding the listed cards. */
    private Board<String> board(HeadlessGui h, Map<String, List<String>> columns) {
        h.gui.dropHistory(history);
        Board<String> board = new Board<String>(h.gui,
                s -> h.gui.box().width(Length.FILL).height(Length.dp(40)))
                .spacing(Length.dp(5)).motion(hand);
        Node row = h.gui.row().width(Length.FILL).height(Length.FILL);
        model.columns = new LinkedHashMap<>(columns);
        for (String name : columns.keySet()) {
            Deck<String> deck = board.column().reorderable(model.into(name));
            deck.node().width(Length.grow(1));
            model.decks.put(name, deck);
            row.append(deck.node());
        }
        h.gui.root().children(row);
        h.frame();
        model.show();
        h.frame();
        return board;
    }

    private static Rect rectOf(Board<String> board, String item) {
        Node face = board.cardOf(item);
        assertNotNull(face, item + " is on the board");
        return face.layout().rect();
    }

    private int lastX;
    private int lastY;

    private void press(HeadlessGui h, float x, float y) {
        lastX = (int) x;
        lastY = (int) y;
        h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, (int) x, (int) y, 0));
    }

    private void move(HeadlessGui h, float x, float y) {
        h.bus.publish(InputTopics.INPUT,
                new InputEvent.PointerMoved((int) x, (int) y, (int) x - lastX, (int) y - lastY, 0));
        lastX = (int) x;
        lastY = (int) y;
    }

    private void release(HeadlessGui h, float x, float y) {
        h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, (int) x, (int) y, 0));
    }

    /**
     * The click that puts a carried card down, where the pointer is. Letting go of the button kept it, latched;
     * this is the drop.
     */
    private void putDown(HeadlessGui h) {
        h.frame();
        h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, lastX, lastY, 0));
        h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, lastX, lastY, 0));
    }

    /** Finish every motion and let the frames that follow lay the result out. */
    private void rest(HeadlessGui h) {
        hand.finish();
        h.frame();
        hand.finish();
        h.frame();
    }

    @Test
    @DisplayName("a deck's first list is its starting state: shown in order, at rest, with nothing animating in")
    void theFirstListIsNotAChange() {
        try (HeadlessGui h = new HeadlessGui()) {
            Board<String> board = board(h, Map.of("todo", List.of("a", "b", "c")));

            assertTrue(board.settled(), "nothing grows on the first frame a user sees");
            assertEquals(5f, rectOf(board, "a").y(), 0.01f);
            assertEquals(55f, rectOf(board, "b").y(), 0.01f);
            assertEquals(105f, rectOf(board, "c").y(), 0.01f);
        }
    }

    @Test
    @DisplayName("lifting a card is invisible: in the frame it leaves the deck, nothing is drawn anywhere new")
    void liftingIsInvisible() {
        try (HeadlessGui h = new HeadlessGui()) {
            Board<String> board = board(h, Map.of("todo", List.of("a", "b", "c")));
            Rect a = rectOf(board, "a");
            Rect b = rectOf(board, "b");

            press(h, 100, 20);
            move(h, 100, 30);
            h.frame();

            assertFalse(board.settled(), "the drag began");
            assertEquals(a, rectOf(board, "a"), "the card is where it was, though it is now floating over the window");
            assertEquals(b, rectOf(board, "b"), "and the gap it left holds the one below exactly where it was");
        }
    }

    @Test
    @DisplayName("a carried card catches up with the hand, then follows it exactly")
    void theCardFollowsTheHand() {
        try (HeadlessGui h = new HeadlessGui()) {
            Board<String> board = board(h, Map.of("todo", List.of("a", "b", "c")));

            press(h, 100, 20);   // 15px into a's face
            move(h, 100, 30);
            h.frame();
            hand.at(0.5);
            h.frame();
            float halfway = rectOf(board, "a").y();
            assertTrue(halfway > 5f && halfway < 15f,
                    "the 10px it fell behind while the drag was being recognised is made up, not skipped: " + halfway);

            hand.finish();
            move(h, 100, 37);
            h.frame();
            assertEquals(37f - 15f, rectOf(board, "a").y(), 0.01f, "held where it was grabbed");
        }
    }

    @Test
    @DisplayName("a dropped card lands in the gap it opened; the move is the application's, and is undoable")
    void aDropLands() {
        try (HeadlessGui h = new HeadlessGui()) {
            Board<String> board = board(h, Map.of("todo", List.of("a", "b", "c")));
            Node faceA = board.cardOf("a");

            press(h, 100, 20);
            move(h, 100, 30);
            h.frame();
            hand.finish();
            move(h, 100, 70);    // a's top at 55: past b's middle (25 in the deck without a), short of c's (75)
            h.frame();
            hand.finish();
            h.frame();
            assertEquals(5f, rectOf(board, "b").y(), 0.01f, "b has closed up into the place a left");
            assertEquals(105f, rectOf(board, "c").y(), 0.01f, "and c has stood aside for a's gap");

            release(h, 100, 70);
            putDown(h);
            h.frame();
            rest(h);

            assertTrue(board.settled());
            assertEquals(List.of("b", "a", "c"), model.columns.get("todo"));
            assertEquals(List.of("a BEFORE c in todo"), model.asked.subList(model.asked.size() - 1, model.asked.size()));
            assertEquals(55f, rectOf(board, "a").y(), 0.01f);
            assertSame(faceA, board.cardOf("a"), "the same node travelled; the application's state on it survived");

            history.undo();
            rest(h);
            assertEquals(List.of("a", "b", "c"), model.columns.get("todo"));
            assertEquals(5f, rectOf(board, "a").y(), 0.01f, "undo is the application handing back its old list");
            assertSame(faceA, board.cardOf("a"));
        }
    }

    @Test
    @DisplayName("a landing is continuous: every frame of it is between where the card was let go and its slot")
    void aLandingIsContinuous() {
        try (HeadlessGui h = new HeadlessGui()) {
            Board<String> board = board(h, Map.of("todo", List.of("a", "b", "c")));
            press(h, 100, 20);
            move(h, 100, 30);
            h.frame();
            hand.finish();
            move(h, 100, 80);
            h.frame();
            hand.finish();
            h.frame();
            float letGo = rectOf(board, "a").y();
            release(h, 100, 80);
            putDown(h);
            h.frame();
            assertEquals(letGo, rectOf(board, "a").y(), 0.01f, "the release itself moves nothing");

            float previous = letGo;
            for (double p : new double[] {0.25, 0.5, 0.75, 1.0}) {
                hand.at(p);
                h.frame();
                float y = rectOf(board, "a").y();
                assertTrue(y <= previous + 0.01f && y >= 55f - 0.01f,
                        "heading from " + letGo + " to 55, never past it: " + y + " at " + p);
                previous = y;
            }
            rest(h);
            assertEquals(55f, rectOf(board, "a").y(), 0.01f, "and settling into the slot moved it by nothing");
        }
    }

    @Test
    @DisplayName("Escape takes the card home, changes nothing, and records nothing")
    void escapeGoesHome() {
        try (HeadlessGui h = new HeadlessGui()) {
            Board<String> board = board(h, Map.of("todo", List.of("a", "b", "c")));
            press(h, 100, 20);
            move(h, 100, 30);
            h.frame();
            move(h, 100, 140);
            h.frame();
            hand.finish();
            h.frame();

            h.bus.publish(InputTopics.INPUT, new InputEvent.KeyPressed(Key.ESCAPE, 0));
            h.frame();
            rest(h);

            assertTrue(board.settled());
            assertEquals(List.of("a", "b", "c"), model.columns.get("todo"));
            assertFalse(history.canUndo(), "a cancel leaves no trace, not an edit and its undo");
            assertEquals(5f, rectOf(board, "a").y(), 0.01f);
            assertEquals(55f, rectOf(board, "b").y(), 0.01f);
        }
    }

    @Test
    @DisplayName("the slot a card came from answers nothing: putting it back is not an edit")
    void homeIsNotAnEdit() {
        try (HeadlessGui h = new HeadlessGui()) {
            board(h, Map.of("todo", List.of("a", "b", "c")));
            press(h, 100, 70);   // b
            move(h, 100, 80);
            h.frame();

            assertFalse(h.gui.dragSession().drop().accepts(), "over its own gap, a card would only go home");
        }
    }

    @Test
    @DisplayName("every point of a deck answers, and a pixel of movement changes the answer by one slot at most")
    void resolutionIsTotalAndContinuous() {
        try (HeadlessGui h = new HeadlessGui()) {
            board(h, Map.of("todo", List.of("a", "b", "c", "d")));
            press(h, 100, 20);
            move(h, 100, 30);
            h.frame();
            hand.finish();

            float lastSlot = -1f;
            for (int y = 15; y < 590; y++) {
                move(h, 100, y);
                h.frame();
                Drop drop = h.gui.dragSession().drop();
                float slot = drop.accepts() ? drop.indicator().y() : 0f;   // home is the top slot here
                assertTrue(slot >= lastSlot, "slots only advance as the card goes down: " + y);
                assertTrue(slot - Math.max(lastSlot, 0f) <= CARD + 0.01f, "and one at a time: " + y);
                lastSlot = slot;
            }
            assertEquals(3 * CARD, lastSlot, 0.01f, "below the last card is the end of the deck");
        }
    }

    @Test
    @DisplayName("a card carried to another deck flies there, and is the same node when it lands")
    void betweenDecks() {
        try (HeadlessGui h = new HeadlessGui()) {
            Map<String, List<String>> columns = new LinkedHashMap<>();
            columns.put("todo", List.of("a", "b"));
            columns.put("done", List.of("x"));
            Board<String> board = board(h, columns);
            Node faceA = board.cardOf("a");
            Rect x = rectOf(board, "x");

            press(h, 100, 20);
            move(h, 100, 30);
            h.frame();
            hand.finish();
            move(h, 600, 20);   // over done, above x
            h.frame();
            hand.finish();
            h.frame();
            assertEquals(x.y() + CARD, rectOf(board, "x").y(), 0.01f, "x stands aside for the card above it");

            release(h, 600, 20);
            putDown(h);
            h.frame();
            rest(h);

            assertEquals(List.of("b"), model.columns.get("todo"));
            assertEquals(List.of("a", "x"), model.columns.get("done"));
            assertSame(faceA, board.cardOf("a"));
            assertEquals(x.x(), rectOf(board, "a").x(), 0.01f, "in done's column now");
            assertEquals(5f, rectOf(board, "b").y(), 0.01f, "and todo has closed the gap");
            assertTrue(board.settled());

            history.undo();
            rest(h);
            assertEquals(List.of("a", "b"), model.columns.get("todo"));
            assertSame(faceA, board.cardOf("a"), "undo flew the same card back, it did not build another");
        }
    }

    @Test
    @DisplayName("a card arriving grows its slot, and one leaving fades while its slot closes")
    void arrivingAndLeavingAreGradual() {
        try (HeadlessGui h = new HeadlessGui()) {
            Board<String> board = board(h, Map.of("todo", List.of("a", "b")));

            model.decks.get("todo").items(List.of("a", "n", "b"));
            h.frame();
            hand.at(0.5);
            h.frame();
            h.frame();
            hand.at(0.5);
            h.frame();
            float b = rectOf(board, "b").y();
            assertTrue(b > 55f && b < 105f, "b makes room for n gradually: " + b);
            rest(h);
            assertEquals(105f, rectOf(board, "b").y(), 0.01f);

            model.decks.get("todo").items(List.of("a", "b"));
            h.frame();
            hand.at(0.5);
            h.frame();
            b = rectOf(board, "b").y();
            assertTrue(b > 55f && b < 105f, "and closes up gradually as it leaves: " + b);
            rest(h);
            assertEquals(55f, rectOf(board, "b").y(), 0.01f);
            assertEquals(null, board.cardOf("n"), "a card that has left the board is forgotten");
        }
    }

    @Test
    @DisplayName("Alt+Down moves the focused card the way a drop would, through the same history")
    void theKeyboardMovesCards() {
        try (HeadlessGui h = new HeadlessGui()) {
            Board<String> board = board(h, Map.of("todo", List.of("a", "b", "c")));
            h.click(100, 20);   // focuses a's carrier

            h.chord(Key.DOWN, Key.LEFT_ALT);
            rest(h);

            assertEquals(List.of("b", "a", "c"), model.columns.get("todo"));
            assertEquals(55f, rectOf(board, "a").y(), 0.01f);
            assertTrue(history.canUndo());
        }
    }

    @Test
    @DisplayName("a card taken by something that is not a deck fades where it was let go, and its slot closes")
    void takenElsewhere() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.dropHistory(history);
            Board<String> board = new Board<String>(h.gui,
                    s -> h.gui.box().width(Length.FILL).height(Length.dp(40)))
                    .spacing(Length.dp(5)).motion(hand);
            model.columns = new LinkedHashMap<>(Map.of("todo", List.of("a", "b")));
            Deck<String> deck = board.column().reorderable(model.into("todo"));
            deck.node().width(Length.rem(20));
            model.decks.put("todo", deck);
            List<String> binned = new ArrayList<>();
            Node bin = h.gui.box().width(Length.grow(1)).height(Length.FILL);
            h.gui.onDrop(bin, (payload, x, y) -> payload.as(board.itemType())
                    .map(item -> Drop.move(bin.layout().rect(), () -> {
                        binned.add(item);
                        Map<String, List<String>> next = new LinkedHashMap<>(model.columns);
                        List<String> l = new ArrayList<>(next.get("todo"));
                        l.remove(item);
                        next.put("todo", l);
                        return model.set(next).apply();
                    }))
                    .orElse(Drop.NONE));
            h.gui.root().children(h.gui.row().width(Length.FILL).height(Length.FILL).children(deck.node(), bin));
            h.frame();
            model.show();
            h.frame();

            press(h, 100, 20);
            move(h, 100, 30);
            h.frame();
            hand.finish();
            move(h, 600, 300);
            h.frame();
            hand.finish();
            h.frame();
            assertEquals(5f, rectOf(board, "b").y(), 0.01f, "over the bin, the card would leave, so its gap closes");

            release(h, 600, 300);
            putDown(h);
            h.frame();
            hand.at(0.5);
            h.frame();
            assertNotNull(board.cardOf("a"), "still there, fading");
            rest(h);

            assertEquals(List.of("a"), binned);
            assertEquals(List.of("b"), deck.items());
            assertEquals(null, board.cardOf("a"), "gone from the board once it has faded");
            assertTrue(board.settled());
        }
    }

    @Test
    @DisplayName("a pointer that wanders off a slot and back reopens the same gap, from however far it had closed")
    void wanderingReopensTheSameGap() {
        try (HeadlessGui h = new HeadlessGui()) {
            Board<String> board = board(h, Map.of("todo", List.of("a", "b", "c")));
            press(h, 100, 20);
            move(h, 100, 30);
            h.frame();
            hand.finish();
            move(h, 100, 70);    // between b and c
            h.frame();
            hand.finish();
            h.frame();
            move(h, 100, 20);    // home: the gap it left reopens, and the one between b and c starts to close
            h.frame();
            hand.at(0.5);
            h.frame();
            int slots = model.decks.get("todo").slots.size();
            move(h, 100, 70);    // and straight back, before anything has finished closing
            h.frame();
            hand.at(0.5);
            h.frame();

            assertEquals(slots, model.decks.get("todo").slots.size(), "going back reopens the closing gap rather than opening another beside it");
            float c = rectOf(board, "c").y();
            assertTrue(c > 55f && c <= 105f, "c is pushed back down from where it had got to, not snapped: " + c);
        }
    }

    @Test
    @DisplayName("a click is a click: the card sinks while held, then springs back, and nothing was carried")
    void aClickLiftsNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            Board<String> board = board(h, Map.of("todo", List.of("a", "b")));
            Rect a = rectOf(board, "a");

            h.click(100, 20);
            assertFalse(hand.idle(), "held, it showed: the press is a state of its own");
            rest(h);

            assertTrue(board.settled());
            assertEquals(a, rectOf(board, "a"), "and it is back exactly where it was");
            assertEquals(List.of("a", "b"), model.columns.get("todo"));
        }
    }

    @Test
    @DisplayName("held before the threshold, the card follows the hand part of the way, then comes loose to it")
    void heldThenLoose() {
        try (HeadlessGui h = new HeadlessGui()) {
            Board<String> board = board(h, Map.of("todo", List.of("a", "b", "c")));

            press(h, 100, 20);
            move(h, 100, 23);   // inside the threshold: held, not loose
            h.frame();
            hand.finish();
            h.frame();
            assertEquals(5f, rectOf(board, "a").y(), 0.01f,
                    "a press moves nothing in the layout: the lane does not reflow under what may be a click");
            assertFalse(board.settled(), "but the card is held, which is a state");

            move(h, 100, 60);   // loose
            h.frame();
            float loose = rectOf(board, "a").y();
            assertTrue(loose >= 5f && loose < 45f, "it comes away from where the pull had it: " + loose);
            hand.finish();
            h.frame();
            assertEquals(60f - 15f, rectOf(board, "a").y(), 0.01f, "and then follows exactly");
        }
    }

    @Test
    @DisplayName("let go, the card stays with the pointer; a click puts it down where it is aimed")
    void letGoAndClick() {
        try (HeadlessGui h = new HeadlessGui()) {
            Board<String> board = board(h, Map.of("todo", List.of("a", "b", "c")));
            press(h, 100, 20);
            move(h, 100, 30);
            h.frame();
            release(h, 100, 30);
            h.frame();
            hand.finish();

            move(h, 100, 70);
            h.frame();
            hand.finish();
            h.frame();
            assertEquals(70f - 15f, rectOf(board, "a").y(), 0.01f, "carried with no button down");
            assertEquals(List.of("a", "b", "c"), model.columns.get("todo"), "and nothing done yet");

            putDown(h);
            h.frame();
            rest(h);
            assertEquals(List.of("b", "a", "c"), model.columns.get("todo"));
        }
    }

    @Test
    @DisplayName("a card carried and held still owes no frames: a still hand is a still loop")
    void aStillCarryParks() {
        try (HeadlessGui h = new HeadlessGui()) {
            board(h, Map.of("todo", List.of("a", "b", "c")));
            press(h, 100, 20);
            move(h, 100, 30);
            h.frame();
            release(h, 100, 30);
            h.frame();
            rest(h);
            h.frame();
            h.frame();

            assertFalse(h.gui.frameOwed(), "nothing changed, so nothing was written, so nothing is owed");
        }
    }

    @Test
    @DisplayName("a press held still owes no frames either")
    void aStillPressParks() {
        try (HeadlessGui h = new HeadlessGui()) {
            board(h, Map.of("todo", List.of("a", "b", "c")));
            press(h, 100, 20);
            move(h, 100, 22);
            h.frame();
            rest(h);
            h.frame();
            h.frame();

            assertFalse(h.gui.frameOwed());
        }
    }

    @Test
    @DisplayName("Escape after the lane was scrolled puts the lane back as well as the card")
    void cancellingScrollsBack() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> many = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                many.add("c" + i);
            }
            Board<String> board = board(h, Map.of("todo", many));
            Deck<String> deck = model.decks.get("todo");
            press(h, 100, 20);
            move(h, 100, 30);
            h.frame();
            release(h, 100, 30);
            h.frame();
            hand.finish();
            h.wheel(0, -5, 100, 300);   // the lane scrolls under the carried card
            float scrolled = deck.node().layout().scrollY();
            assertTrue(scrolled > 0f, "the wheel works while carrying");

            h.bus.publish(InputTopics.INPUT, new InputEvent.KeyPressed(Key.ESCAPE, 0));
            h.frame();
            hand.at(0.5);
            h.frame();
            float midway = deck.node().layout().scrollY();
            assertTrue(midway > 0f && midway < scrolled, "the lane glides back with the card: " + midway);
            rest(h);

            assertEquals(0f, deck.node().layout().scrollY(), 0.01f, "where it was looking when the card came loose");
            assertEquals(5f, rectOf(board, "c0").y(), 0.01f, "and the card is home");
            assertTrue(board.settled());
        }
    }
}
