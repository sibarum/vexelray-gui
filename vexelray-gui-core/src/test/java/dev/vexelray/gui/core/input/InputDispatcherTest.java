package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the click vertical end to end with no window: publish raw {@link InputEvent} edges onto the input topic
 * (exactly what {@code tactroller-atchung} publishes), drain through the dispatcher, and observe both the routed
 * handler firing and the framework {@link ClickEvent} landing on the bus.
 */
class InputDispatcherTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.clicks", ClickEvent.class);

    private static RetainedNode node(long id, float x, float y, float w, float h) {
        RetainedNode n = new RetainedNode(id, NodeKind.BOX);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        return n;
    }

    private static void press(Atchung bus, int x, int y) {
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, x, y, 0));
    }

    private static void release(Atchung bus, int x, int y) {
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, x, y, 0));
    }

    @Test
    void pressThenReleaseOnSameNodeFiresHandlerAndPublishesClick() {
        Atchung bus = Atchung.create();
        List<ClickEvent> published = new ArrayList<>();
        bus.subscribe(CLICKS, published::add);

        InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run); // synchronous handlers
        AtomicInteger fired = new AtomicInteger();
        dispatcher.onClick(7, fired::incrementAndGet);

        RetainedNode root = node(0, 0, 0, 200, 200);
        RetainedNode btn = node(7, 10, 10, 80, 30);
        btn.parent = root;
        root.children.add(btn);

        press(bus, 20, 20);
        release(bus, 20, 20);
        dispatcher.dispatch(root);

        assertEquals(1, fired.get(), "handler fires once on a press+release within the node");
        assertEquals(1, published.size());
        assertEquals(7, published.get(0).nodeId());
    }

    @Test
    void releaseOutsidePressTargetIsNotAClick() {
        Atchung bus = Atchung.create();
        InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run);
        AtomicInteger fired = new AtomicInteger();
        dispatcher.onClick(7, fired::incrementAndGet);

        RetainedNode root = node(0, 0, 0, 200, 200);
        RetainedNode btn = node(7, 10, 10, 80, 30);
        btn.parent = root;
        root.children.add(btn);

        press(bus, 20, 20);      // inside the button
        release(bus, 150, 150);  // outside it
        dispatcher.dispatch(root);

        assertEquals(0, fired.get(), "a drag off the node is not a click");
    }

    @Test
    void clickBubblesToNearestAncestorHandler() {
        Atchung bus = Atchung.create();
        InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run);
        AtomicInteger parentFired = new AtomicInteger();
        dispatcher.onClick(0, parentFired::incrementAndGet); // handler on the parent only

        RetainedNode root = node(0, 0, 0, 200, 200);
        RetainedNode child = node(9, 10, 10, 40, 40); // no handler of its own
        child.parent = root;
        root.children.add(child);

        press(bus, 20, 20);
        release(bus, 20, 20);
        dispatcher.dispatch(root);

        assertTrue(parentFired.get() == 1, "a click on a handler-less child bubbles to the parent's handler");
    }
}
