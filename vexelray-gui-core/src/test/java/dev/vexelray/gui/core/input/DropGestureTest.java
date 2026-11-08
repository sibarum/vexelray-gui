package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.drop.Drop;
import dev.vexelray.gui.core.drop.Payload;
import dev.vexelray.gui.core.drop.PayloadType;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pointer as one drag source: gesture recognition, the session it opens, and the two ways it ends.
 *
 * <p>Timestamps on the published events are all zero, so the hold threshold is never met by an event's own clock
 * and is met by the per-frame tick instead — which is the real sequence too, since a user holding still after
 * crossing the distance produces no further events.
 */
class DropGestureTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.clicks", ClickEvent.class);
    private static final PayloadType<String> ROW = PayloadType.of("row");

    private final Atchung bus = Atchung.create();
    private final InputDispatcher dispatcher = new InputDispatcher(bus, "test", CLICKS);
    private final List<String> applied = new ArrayList<>();
    private RetainedNode root;
    private RetainedNode body;

    private static RetainedNode node(long id, float x, float y, float w, float h) {
        RetainedNode n = new RetainedNode(id);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        return n;
    }

    /** A tree body at (0,0,200,200) inside a root, with a source that offers whatever row was pressed. */
    private void tree() {
        root = node(0, 0, 0, 400, 400);
        body = node(7, 0, 0, 200, 200);
        body.parent = root;
        root.children.add(body);
        dispatcher.onDragSource(7, (x, y) -> Payload.of(ROW, "row@" + Math.round(y)));
        dispatcher.onDrop(7, (payload, x, y) -> payload.as(ROW)
                .map(row -> Drop.move(new Rect(0f, y, 200f, 2f), change(row + "->" + Math.round(y))))
                .orElse(Drop.NONE));
    }

    private Runnable change(String what) {
        return () -> applied.add(what);
    }

    private void press(int x, int y) {
        bus.publish(InputTopics.input("test"), new InputEvent.ButtonPressed(MouseButton.LEFT, x, y, 0));
    }

    private void move(int x, int y, int dx, int dy) {
        bus.publish(InputTopics.input("test"), new InputEvent.PointerMoved(x, y, dx, dy, 0));
    }

    private void release(int x, int y) {
        bus.publish(InputTopics.input("test"), new InputEvent.ButtonReleased(MouseButton.LEFT, x, y, 0));
    }

    private void escape() {
        bus.publish(InputTopics.input("test"), new InputEvent.KeyPressed(Key.ESCAPE, 0));
    }

    private void frame() {
        dispatcher.dispatch(root);
    }

    @Test
    @DisplayName("a press that travels far enough opens a session and resolves against the tree")
    void aDragOpensASession() {
        tree();

        press(20, 20);
        move(20, 60, 0, 40);
        frame();

        assertNotNull(dispatcher.dragSession(), "past the distance, and the frame tick supplied the hold");
        assertEquals("row@20", dispatcher.dragSession().payload().as(ROW).orElseThrow(),
                "the payload comes from where the press landed, not from where the pointer has got to");
        assertTrue(dispatcher.dragSession().drop().accepts());
    }

    @Test
    @DisplayName("a drag put down over a target commits its change, once, undoably")
    void aDropCommits() {
        tree();
        press(20, 20);
        move(20, 60, 0, 40);
        frame();

        release(20, 60);
        frame();
        assertEquals(List.of(), applied, "letting go of the button is not putting it down");
        press(20, 60);
        release(20, 60);
        frame();

        assertEquals(List.of("row@20->60"), applied);
        assertNull(dispatcher.dragSession(), "the session is over");
        assertEquals(1, applied.size(), "landed once");
    }

    /**
     * The protection the requirement asked for by name. Press, twitch, release, click inside one drain: the
     * recogniser reports a drag because the distance was met, and the click puts it down, but no frame ever
     * rendered it, so nothing commits.
     */
    @Test
    @DisplayName("a click too fast to see does not reorganise anything")
    void aFlickCommitsNothing() {
        tree();

        press(20, 20);
        move(20, 60, 0, 40);
        release(20, 60);
        press(20, 60);
        release(20, 60);
        frame();

        assertEquals(List.of(), applied, "the user never saw a drag, so there was no drop");
        assertNull(dispatcher.dragSession());
    }

    /**
     * The one test here on the real clock, because the hold is what it is about. A driver's path is shorter than the
     * hold and then stops: no further event comes to carry the drag over its threshold, so only a frame's tick can,
     * and a loop that parks gets no frame unless the tree says one is owed.
     */
    @Test
    @DisplayName("a press gone far enough is pending until its hold passes, and then the tick starts the drag")
    void aHoldStillAheadIsPendingUntilATickStartsTheDrag() throws InterruptedException {
        tree();
        long pressed = System.nanoTime();
        bus.publish(InputTopics.input("test"), new InputEvent.ButtonPressed(MouseButton.LEFT, 20, 20, pressed));
        bus.publish(InputTopics.input("test"), new InputEvent.PointerMoved(20, 60, 0, 40, pressed));
        frame();
        org.junit.jupiter.api.Assumptions.assumeTrue(System.nanoTime() - pressed < 60_000_000L,
                "the frame came after most of the hold had passed; nothing to observe on a machine this busy");

        assertNull(dispatcher.dragSession(), "travelled, but not yet held long enough");
        assertTrue(dispatcher.dragHoldPending(), "so the loop owes the frame whose tick will start it");

        Thread.sleep(100);
        frame();

        assertNotNull(dispatcher.dragSession(), "the tick started it, with no event to carry it");
        assertFalse(dispatcher.dragHoldPending(), "and nothing is owed for it any more");
    }

    @Test
    @DisplayName("a press held still, or one that has not travelled far enough, owes no frames")
    void aPressThatIsStillAClickIsNotPending() {
        tree();
        long pressed = System.nanoTime();
        bus.publish(InputTopics.input("test"), new InputEvent.ButtonPressed(MouseButton.LEFT, 20, 20, pressed));
        frame();
        assertFalse(dispatcher.dragHoldPending(), "held still: a click so far");

        bus.publish(InputTopics.input("test"), new InputEvent.PointerMoved(23, 22, 3, 2, pressed));
        frame();
        assertFalse(dispatcher.dragHoldPending(), "inside the deadzone: still a click");

        bus.publish(InputTopics.input("test"), new InputEvent.ButtonReleased(MouseButton.LEFT, 23, 22, pressed));
        frame();
        assertFalse(dispatcher.dragHoldPending());
    }

    @Test
    @DisplayName("Escape cancels the drag and commits nothing")
    void escapeCancels() {
        tree();
        press(20, 20);
        move(20, 60, 0, 40);
        frame();
        assertNotNull(dispatcher.dragSession());

        escape();
        frame();

        assertNull(dispatcher.dragSession());
        assertEquals(List.of(), applied);
    }

    /**
     * Escape is consumed by the drag rather than observed alongside it. Were it not, the same key would cancel
     * the drag <em>and</em> fire whatever claimed Escape — dismissing a modal or closing a context menu at the
     * same time, which is the layering ContextMenuTest exists to protect.
     */
    @Test
    @DisplayName("Escape during a drag does not also reach whatever claimed it")
    void escapeDuringADragIsNotAlsoAClaim() {
        tree();
        List<String> claimed = new ArrayList<>();
        dispatcher.claim(0, Shortcut.of(Key.ESCAPE), ClaimScope.GLOBAL, () -> claimed.add("modal-dismissed"));

        press(20, 20);
        move(20, 60, 0, 40);
        frame();
        escape();
        frame();

        assertEquals(List.of(), claimed, "one key, one action");

        // ...and once the drag is over, the same claim works as it always did.
        escape();
        frame();
        assertEquals(List.of("modal-dismissed"), claimed);
    }

    @Test
    @DisplayName("a release after Escape does not complete the cancelled gesture")
    void theReleaseAfterACancelDoesNothing() {
        tree();
        press(20, 20);
        move(20, 60, 0, 40);
        frame();
        escape();
        frame();

        release(20, 60);
        frame();

        assertEquals(List.of(), applied, "the button coming up must not resurrect what Escape ended");
    }

    @Test
    @DisplayName("a source that offers nothing leaves the gesture as an ordinary click")
    void aDecliningSourceOpensNothing() {
        root = node(0, 0, 0, 400, 400);
        body = node(7, 0, 0, 200, 200);
        body.parent = root;
        root.children.add(body);
        dispatcher.onDragSource(7, (x, y) -> null);   // nothing here is draggable

        press(20, 20);
        move(20, 60, 0, 40);
        frame();

        assertNull(dispatcher.dragSession());
    }

    @Test
    @DisplayName("a press that never travels is a click, not a drag")
    void aStillPressIsNotADrag() {
        tree();

        press(20, 20);
        move(21, 20, 1, 0);   // inside the deadzone
        frame();

        assertNull(dispatcher.dragSession(), "an ordinary click's hand movement must not start a reorder");
    }

    @Test
    @DisplayName("a drop moves focus, so the next Ctrl+Z reaches what was dropped on")
    void dropMovesFocus() {
        tree();
        dispatcher.onKey(7, e -> { });   // registering a key handler makes the node focusable

        press(20, 20);
        move(20, 60, 0, 40);
        frame();
        release(20, 60);
        press(20, 60);
        release(20, 60);
        frame();

        assertEquals(7L, dispatcher.focusedId(),
                "a drag has no focus relationship of its own, so the drop has to establish one");
    }

    @Test
    @DisplayName("with DROP, the button is held for the carry and letting go puts it down")
    void theConventionDropsOnRelease() {
        tree();
        dispatcher.dragRelease(dev.vexelray.gui.core.drop.DragRelease.DROP);

        press(20, 20);
        move(20, 60, 0, 40);
        frame();
        assertFalse(dispatcher.dragSession().latched());
        release(20, 60);
        frame();

        assertEquals(List.of("row@20->60"), applied, "the release is the drop");
        assertNull(dispatcher.dragSession(), "nothing stays with the pointer");
    }

    @Test
    @DisplayName("with DROP, a drag let go before anyone saw it is still not a drop")
    void theConventionStillRefusesAnUnseenDrop() {
        tree();
        dispatcher.dragRelease(dev.vexelray.gui.core.drop.DragRelease.DROP);

        press(20, 20);
        move(20, 60, 0, 40);
        release(20, 60);
        frame();

        assertEquals(List.of(), applied);
        assertNull(dispatcher.dragSession());
    }

    @Test
    @DisplayName("changing the preference mid-carry does not drop what is already latched")
    void aLatchedDragOutlivesTheSettingChanging() {
        tree();
        press(20, 20);
        move(20, 60, 0, 40);
        frame();
        release(20, 60);
        frame();
        dispatcher.dragRelease(dev.vexelray.gui.core.drop.DragRelease.DROP);
        frame();

        assertTrue(dispatcher.dragSession() != null && dispatcher.dragSession().latched(),
                "the user is mid-gesture; it ends the way it began");
        press(20, 60);
        release(20, 60);
        frame();
        assertEquals(List.of("row@20->60"), applied);
    }

    @Test
    @DisplayName("a node that drags for itself keeps its press from picking up the source it sits in")
    void aNodeThatDragsForItselfOwnsItsPress() {
        tree();
        // A text field on a card: its drag is a selection, and the card is picked up anywhere else on it.
        RetainedNode field = node(8, 0, 0, 200, 40);
        field.parent = body;
        body.children.add(field);
        List<DragEvent.Phase> selecting = new ArrayList<>();
        dispatcher.onDrag(8, e -> selecting.add(e.phase()));

        press(20, 20);
        move(20, 60, 0, 40);
        frame();
        assertNull(dispatcher.dragSession(), "the field's press is the field's");
        assertFalse(selecting.isEmpty(), "and the field had it");
        release(20, 60);
        frame();

        press(20, 100);
        move(20, 140, 0, 40);
        frame();
        assertNotNull(dispatcher.dragSession(), "off the field, the card still comes up");
    }
}
