package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.drop.Drop;
import dev.vexelray.gui.core.drop.Payload;
import dev.vexelray.gui.core.drop.PayloadType;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.model.NodeKind;
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
    private final InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run);
    private final List<String> applied = new ArrayList<>();
    private final History history = new History();
    private RetainedNode root;
    private RetainedNode body;

    private static RetainedNode node(long id, float x, float y, float w, float h) {
        RetainedNode n = new RetainedNode(id, NodeKind.BOX);
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
        dispatcher.dropHistory(history);
    }

    private Change change(String what) {
        return new Change() {
            @Override
            public Change apply() {
                applied.add(what);
                return change("un:" + what);
            }
        };
    }

    private void press(int x, int y) {
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, x, y, 0));
    }

    private void move(int x, int y, int dx, int dy) {
        bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(x, y, dx, dy, 0));
    }

    private void release(int x, int y) {
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, x, y, 0));
    }

    private void escape() {
        bus.publish(InputTopics.INPUT, new InputEvent.KeyPressed(Key.ESCAPE, 0));
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
    @DisplayName("a drag released over a target commits its change, once, undoably")
    void aDropCommits() {
        tree();
        press(20, 20);
        move(20, 60, 0, 40);
        frame();

        release(20, 60);
        frame();

        assertEquals(List.of("row@20->60"), applied);
        assertNull(dispatcher.dragSession(), "the session is over");
        assertTrue(history.canUndo());

        history.undo();
        assertEquals(List.of("row@20->60", "un:row@20->60"), applied);
    }

    /**
     * The protection the requirement asked for by name. Press, twitch, release inside one drain: the recogniser
     * reports it as a drag because the distance was met, but no frame ever rendered it, so nothing commits.
     */
    @Test
    @DisplayName("a click too fast to see does not reorganise anything")
    void aFlickCommitsNothing() {
        tree();

        press(20, 20);
        move(20, 60, 0, 40);
        release(20, 60);
        frame();

        assertEquals(List.of(), applied, "the user never saw a drag, so there was no drop");
        assertFalse(history.canUndo(), "and nothing to undo, which is the point");
        assertNull(dispatcher.dragSession());
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
        assertFalse(history.canUndo());
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
        dispatcher.dropHistory(history);

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
    @DisplayName("with no history set, a drop resolves and draws but changes nothing")
    void noHistoryMeansNoSilentMutation() {
        root = node(0, 0, 0, 400, 400);
        body = node(7, 0, 0, 200, 200);
        body.parent = root;
        root.children.add(body);
        dispatcher.onDragSource(7, (x, y) -> Payload.of(ROW, "r"));
        dispatcher.onDrop(7, (p, x, y) -> Drop.move(new Rect(0f, y, 200f, 2f), change("moved")));

        press(20, 20);
        move(20, 60, 0, 40);
        frame();
        assertTrue(dispatcher.dragSession().drop().accepts(), "it still resolves, so the indicator still draws");

        release(20, 60);
        frame();

        assertEquals(List.of(), applied, "a mutation with no way back must not happen silently");
    }

    @Test
    @DisplayName("a drop moves focus, so the next Ctrl+Z reaches the history the drop went to")
    void dropMovesFocus() {
        tree();
        dispatcher.onKey(7, e -> { });   // registering a key handler makes the node focusable

        press(20, 20);
        move(20, 60, 0, 40);
        frame();
        release(20, 60);
        frame();

        assertEquals(7L, dispatcher.focusedId(),
                "a drag has no focus relationship of its own, so the drop has to establish one");
    }
}
