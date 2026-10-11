package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.drop.DragState;
import dev.vexelray.gui.core.drop.DragWatch;
import dev.vexelray.gui.core.drop.Drop;
import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.drop.Payload;
import dev.vexelray.gui.core.drop.PayloadType;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a source carrying something hears about its drag: every frame, in that frame, and how it ended. The ending
 * is the half nothing else could say — a published {@code DragState} goes to NONE whether the user dropped or gave
 * up, and those are opposite animations.
 */
class DragWatchTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.clicks", ClickEvent.class);
    private static final PayloadType<String> ROW = PayloadType.of("row");

    private final Atchung bus = Atchung.create();
    private final InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run);
    private final History history = new History();
    private final List<String> heard = new ArrayList<>();
    private RetainedNode root;

    private static RetainedNode node(long id, float x, float y, float w, float h) {
        RetainedNode n = new RetainedNode(id);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        return n;
    }

    /** A body at (0,0,200,200) that offers a row and takes one anywhere, and a watch that writes down what it hears. */
    private void tree() {
        root = node(0, 0, 0, 400, 400);
        RetainedNode body = node(7, 0, 0, 200, 200);
        body.parent = root;
        root.children.add(body);
        dispatcher.onDragSource(7, (x, y) -> Payload.of(ROW, "r"));
        dispatcher.onDrop(7, (payload, x, y) -> Drop.move(new Rect(0f, y, 200f, 2f), nothing()));
        dispatcher.dropHistory(history);
        dispatcher.watchDrag(7, recorder("w"));
    }

    private DragWatch recorder(String name) {
        return new DragWatch() {
            @Override
            public void moved(DragState now) {
                heard.add(name + ":moved " + Math.round(now.y()) + " " + now.effect());
            }

            @Override
            public void ended(DragState last, DropEffect performed) {
                heard.add(name + ":ended " + Math.round(last.y()) + " " + last.effect() + " -> " + performed);
            }
        };
    }

    private static Change nothing() {
        return new Change() {
            @Override
            public Change apply() {
                return this;
            }
        };
    }

    private void press(int x, int y) {
        lastX = x;
        lastY = y;
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, x, y, 0));
    }

    private int lastX = 20;
    private int lastY = 20;

    private void move(int x, int y) {
        bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(x, y, x - lastX, y - lastY, 0));
        lastX = x;
        lastY = y;
    }

    private void release(int x, int y) {
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, x, y, 0));
    }

    private void frame() {
        dispatcher.dispatch(root);
    }

    @Test
    @DisplayName("a watch hears the drag on the frame it begins, already resolved")
    void heardOnTheFirstFrame() {
        tree();

        press(20, 20);
        move(20, 60);
        frame();

        assertEquals(List.of("w:moved 60 MOVE"), heard,
                "a card lifted a frame after the hand moved has already been left behind once");
    }

    @Test
    @DisplayName("a watch hears every frame of a drag, including the ones where nothing moved")
    void heardEveryFrame() {
        tree();
        press(20, 20);
        move(20, 60);
        frame();
        frame();   // the pointer is still; the tree under it might not be
        move(20, 80);
        frame();

        assertEquals(List.of("w:moved 60 MOVE", "w:moved 60 MOVE", "w:moved 80 MOVE"), heard);
    }

    @Test
    @DisplayName("a drop and a cancel end differently, which is the whole of what the published state cannot say")
    void aDropAndACancelAreDistinguishable() {
        tree();
        press(20, 20);
        move(20, 60);
        frame();
        release(20, 60);
        frame();
        move(20, 70);
        press(20, 70);   // the click that puts it down
        release(20, 70);
        frame();

        assertEquals("w:ended 70 MOVE -> MOVE", heard.getLast(),
                "where it was put down, what was resolved there, and what was actually done");

        heard.clear();
        press(20, 20);
        move(20, 60);
        frame();
        bus.publish(InputTopics.INPUT, new InputEvent.KeyPressed(Key.ESCAPE, 0));
        frame();

        assertEquals("w:ended 60 MOVE -> NONE", heard.getLast(),
                "what would have happened, kept so a return can be drawn from it — and that it did not");
    }

    @Test
    @DisplayName("a drop the user never saw ends as nothing performed")
    void anUnseenDropPerformedNothing() {
        tree();

        // Picked up, let go and clicked down, all inside one drain: no frame ever drew it.
        press(20, 20);
        move(20, 60);
        release(20, 60);
        press(20, 60);
        release(20, 60);
        frame();

        assertEquals("w:ended 60 MOVE -> NONE", heard.getLast(),
                "no frame drew it, so there is nothing to settle — but the source still has to put it back");
        assertFalse(history.canUndo());
    }

    @Test
    @DisplayName("letting go of the button keeps the drag: the hand is free, and the next click puts it down")
    void releasingLatches() {
        tree();
        List<DragState> seen = new ArrayList<>();
        dispatcher.watchDrag(7, new DragWatch() {
            @Override
            public void moved(DragState now) {
                seen.add(now);
            }

            @Override
            public void ended(DragState last, DropEffect performed) {
            }
        });

        press(20, 20);
        move(20, 60);
        frame();
        assertFalse(seen.getLast().latched(), "held while the button is down");
        release(20, 60);
        frame();
        assertTrue(seen.getLast().latched(), "and still carried, latched, once it is up");
        assertFalse(history.canUndo(), "letting go is not dropping");

        move(20, 90);
        frame();
        assertEquals(90f, seen.getLast().y(), "the pointer still carries it, with no button down");
        press(20, 90);
        release(20, 90);
        frame();
        assertTrue(history.canUndo(), "the click did");
        assertEquals("w:ended 90 MOVE -> MOVE", heard.getLast());
    }

    @Test
    @DisplayName("while latched, a right click takes it home and opens no menu")
    void aRightClickCancels() {
        tree();
        List<String> menus = new ArrayList<>();
        dispatcher.onContextClick(7, e -> menus.add("menu"));
        press(20, 20);
        move(20, 60);
        frame();
        release(20, 60);
        frame();

        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.RIGHT, 20, 60, 0));
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.RIGHT, 20, 60, 0));
        frame();

        assertEquals("w:ended 60 MOVE -> NONE", heard.getLast());
        assertEquals(List.of(), menus, "the click that cancelled is used up by cancelling");
        assertFalse(history.canUndo());
    }

    @Test
    @DisplayName("a press on something draggable is armed until it comes loose or comes up")
    void armedThenLooseOrClick() {
        tree();
        List<String> arming = new ArrayList<>();
        dispatcher.watchDrag(7, new DragWatch() {
            @Override
            public void armed(float pressX, float pressY, float x, float y) {
                arming.add("armed " + Math.round(y));
            }

            @Override
            public void disarmed() {
                arming.add("disarmed");
            }

            @Override
            public void moved(DragState now) {
                arming.add("moved");
            }

            @Override
            public void ended(DragState last, DropEffect performed) {
            }
        });

        press(20, 20);
        move(21, 23);   // inside the deadzone
        frame();
        release(21, 23);
        frame();
        assertEquals(List.of("armed 23", "disarmed"), arming, "held, then a click: it springs back");

        arming.clear();
        press(20, 20);
        move(20, 22);
        frame();
        move(20, 60);
        frame();
        assertEquals(List.of("armed 22", "moved"), arming, "held, then loose: the first moved is the transition");
    }

    @Test
    @DisplayName("every watch is shown the same state in a frame, so one owner can do its work once")
    void oneStatePerFrame() {
        tree();
        List<DragState> seen = new ArrayList<>();
        DragWatch keep = new DragWatch() {
            @Override
            public void moved(DragState now) {
                seen.add(now);
            }

            @Override
            public void ended(DragState last, DropEffect performed) {
            }
        };
        dispatcher.watchDrag(7, keep);
        dispatcher.watchDrag(0, keep);

        press(20, 20);
        move(20, 60);
        frame();

        assertEquals(2, seen.size());
        assertSame(seen.get(0), seen.get(1));
    }

    @Test
    @DisplayName("a watch goes with its node")
    void droppedWithItsNode() {
        tree();
        dispatcher.clearHandlers(7);
        dispatcher.onDragSource(0, (x, y) -> Payload.of(ROW, "r"));

        press(20, 20);
        move(20, 60);
        frame();
        release(20, 60);
        frame();

        assertEquals(List.of(), heard);
    }

    @Test
    @DisplayName("the hand closes for the length of a drag, whatever it passes over")
    void theHandIsClosedWhileCarrying() {
        tree();
        List<CursorShape> shapes = new ArrayList<>();
        dispatcher.cursorSink(shapes::add);

        press(20, 20);
        move(20, 60);
        frame();
        assertTrue(shapes.contains(CursorShape.GRABBING), "carrying is grabbing: " + shapes);

        release(20, 60);
        frame();
        assertEquals(CursorShape.GRAB, shapes.getLast(), "let go and still carrying: the hand opens, the thing stays");

        press(20, 60);
        release(20, 60);
        frame();
        assertEquals(CursorShape.DEFAULT, shapes.getLast(), "and it is an ordinary pointer once it is put down");
    }
}
