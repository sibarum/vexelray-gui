package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the pointer is over a node ({@link InputDispatcher#onPointer}), driven headlessly as
 * {@link InteractionStateTest} drives hover: every move over the node told with its position, and a leave told once.
 */
class PointerTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.clicks", ClickEvent.class);

    private static RetainedNode node(long id, float x, float y, float w, float h) {
        RetainedNode n = new RetainedNode(id);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        return n;
    }

    private static void move(Atchung bus, int x, int y) {
        bus.publish(InputTopics.input("test"), new InputEvent.PointerMoved(x, y, 0, 0, 0));
    }

    private static RetainedNode root(RetainedNode... children) {
        RetainedNode root = node(0, 0, 0, 400, 400);
        for (RetainedNode child : children) {
            child.parent = root;
            root.children.add(child);
        }
        return root;
    }

    @Test
    void everyMoveOverTheNodeIsToldWhereAndTheLeaveOnce() {
        Atchung bus = Atchung.create();
        InputDispatcher dispatcher = new InputDispatcher(bus, "test", CLICKS);
        RetainedNode chart = node(7, 100, 50, 200, 100);
        RetainedNode root = root(chart);
        List<PointerEvent> heard = new ArrayList<>();
        dispatcher.onPointer(7, heard::add);

        move(bus, 10, 10);     // not over it: nothing
        move(bus, 110, 60);
        move(bus, 250, 120);
        move(bus, 350, 120);   // off
        move(bus, 380, 120);   // still off: nothing more
        dispatcher.dispatch(root);

        assertEquals(3, heard.size(), heard.toString());
        assertTrue(heard.get(0).over());
        assertEquals(10f, heard.get(0).localX());
        assertEquals(10f, heard.get(0).localY());
        assertEquals(150f, heard.get(1).localX());
        assertEquals(70f, heard.get(1).localY());
        PointerEvent left = heard.get(2);
        assertFalse(left.over());
        assertEquals(350f, left.x(), "the pointer where it left");
        assertEquals(new PointerEvent(7, 350, 120, 100, 50, 200, 100, false), left, "with the box last told");
    }

    @Test
    void aDescendantUnderThePointerCountsAndTheNodeIsToldItsOwnBox() {
        Atchung bus = Atchung.create();
        InputDispatcher dispatcher = new InputDispatcher(bus, "test", CLICKS);
        RetainedNode lane = node(5, 0, 100, 400, 50);
        RetainedNode strip = node(6, 0, 100, 400, 50);   // the picture's node, on top
        strip.parent = lane;
        lane.children.add(strip);
        RetainedNode root = root(lane);
        List<PointerEvent> heard = new ArrayList<>();
        dispatcher.onPointer(5, heard::add);

        move(bus, 30, 120);
        dispatcher.dispatch(root);

        assertEquals(List.of(new PointerEvent(5, 30, 120, 0, 100, 400, 50, true)), heard);
    }

    @Test
    void aPressWithNoMoveIsNotToldAgainButOneLandingOnTheNodeIsAnArrival() {
        Atchung bus = Atchung.create();
        InputDispatcher dispatcher = new InputDispatcher(bus, "test", CLICKS);
        RetainedNode chart = node(7, 100, 50, 200, 100);
        RetainedNode root = root(chart);
        List<PointerEvent> heard = new ArrayList<>();
        dispatcher.onPointer(7, heard::add);

        // The first the dispatcher hears of the pointer is a press on the node: it has arrived there.
        bus.publish(InputTopics.input("test"), new InputEvent.ButtonPressed(MouseButton.LEFT, 120, 60, 0));
        bus.publish(InputTopics.input("test"), new InputEvent.ButtonReleased(MouseButton.LEFT, 120, 60, 0));
        dispatcher.dispatch(root);

        assertEquals(List.of(new PointerEvent(7, 120, 60, 100, 50, 200, 100, true)), heard,
                "told once, on arriving; the release moved nothing");
    }

    @Test
    void aNodeReleasedWhileThePointerIsOverItIsToldNothingMore() {
        Atchung bus = Atchung.create();
        InputDispatcher dispatcher = new InputDispatcher(bus, "test", CLICKS);
        RetainedNode chart = node(7, 100, 50, 200, 100);
        RetainedNode root = root(chart);
        List<PointerEvent> heard = new ArrayList<>();
        dispatcher.onPointer(7, heard::add);
        move(bus, 110, 60);
        dispatcher.dispatch(root);
        heard.clear();

        dispatcher.clearHandlers(7);
        root.children.clear();
        move(bus, 350, 120);
        dispatcher.dispatch(root);

        assertEquals(List.of(), heard);
    }

    @Test
    void nothingIsToldToANodeThatDoesNotFollowThePointer() {
        Atchung bus = Atchung.create();
        InputDispatcher dispatcher = new InputDispatcher(bus, "test", CLICKS);
        RetainedNode watched = node(7, 0, 0, 100, 100);
        RetainedNode other = node(8, 200, 0, 100, 100);
        RetainedNode root = root(watched, other);
        List<PointerEvent> heard = new ArrayList<>();
        dispatcher.onPointer(7, heard::add);

        move(bus, 250, 50);
        dispatcher.dispatch(root);

        assertEquals(List.of(), heard);
    }
}
