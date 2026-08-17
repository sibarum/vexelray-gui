package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mouse wheel scrolls the nearest overflowing container under the pointer, clamped to the content, and asks for
 * a relayout so the shift takes effect next frame.
 */
class WheelScrollTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.clicks", ClickEvent.class);

    private static RetainedNode scrollable(long id) {
        RetainedNode n = new RetainedNode(id, NodeKind.BOX);
        n.x = 0;
        n.y = 0;
        n.w = 100;
        n.h = 100;
        n.overflowY = true;
        n.viewH = 100;
        n.contentH = 200; // maxScroll = 100
        return n;
    }

    @Test
    void wheelScrollsClampedAndRequestsLayout() {
        Atchung bus = Atchung.create();
        AtomicInteger relayouts = new AtomicInteger();
        InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run, relayouts::incrementAndGet);

        RetainedNode node = scrollable(1);

        // Wheel "down" (negative yOffset by GLFW convention) scrolls content down: scrollY increases.
        bus.publish(InputTopics.INPUT, new InputEvent.Scrolled(0, -1, 50, 50, 0));
        dispatcher.dispatch(node);
        assertEquals(48f, node.scrollY, 0.01f, "one notch = 3em, which is 48px at the default 16px em");
        assertTrue(relayouts.get() >= 1, "scrolling requests a relayout");

        // Several notches down clamp to maxScroll (contentH - viewH = 100), never beyond.
        bus.publish(InputTopics.INPUT, new InputEvent.Scrolled(0, -10, 50, 50, 0));
        dispatcher.dispatch(node);
        assertEquals(100f, node.scrollY, 0.01f, "scroll clamps to content - viewport");
    }

    @Test
    void draggingTheThumbSetsScrollProportionally() {
        Atchung bus = Atchung.create();
        AtomicInteger relayouts = new AtomicInteger();
        InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run, relayouts::incrementAndGet);

        RetainedNode n = scrollable(1);
        n.viewX = 0;
        n.viewY = 0;
        n.viewW = 90;      // content area; scrollbar strip to the right
        n.scrollbarPx = 10;
        // viewH=100, contentH=200 -> thumb length = max(20, 100*100/200)=50, travel=50.

        // Grab the thumb near its top (thumb starts at y=0 when scrollY=0) and drag halfway down its travel.
        float[] thumb = n.vThumbRect();
        int grabY = Math.round(thumb[1] + 5);            // inside the thumb
        int grabX = Math.round(thumb[0] + 1);
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(sibarum.tactroller.api.MouseButton.LEFT,
                grabX, grabY, 0));
        bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(grabX, grabY + 25, 0, 25, 0)); // +25 of 50 travel
        dispatcher.dispatch(n);

        assertEquals(50f, n.scrollY, 0.5f, "half the thumb travel -> half of maxScroll (100)");
        assertTrue(relayouts.get() >= 1);

        // Release, then a move should no longer scroll.
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(sibarum.tactroller.api.MouseButton.LEFT,
                grabX, grabY + 25, 0));
        bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(grabX, grabY + 100, 0, 75, 0));
        dispatcher.dispatch(n);
        assertEquals(50f, n.scrollY, 0.5f, "no scroll after release");
    }

    /**
     * The reserved strip belongs to the scrollbar along its whole length, not just where the thumb happens to be.
     * A press on the track pages toward the click and is consumed; previously only the thumb was claimed, so a
     * press on the track fell through to whatever the clip had hidden underneath.
     */
    @Test
    void pressingTheTrackPagesAndConsumesThePress() {
        Atchung bus = Atchung.create();
        AtomicInteger relayouts = new AtomicInteger();
        InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run, relayouts::incrementAndGet);

        RetainedNode n = scrollable(1);
        n.viewX = 0;
        n.viewY = 0;
        n.viewW = 90;
        n.scrollbarPx = 10;
        // viewH=100, contentH=200 -> thumb is 50 long, sitting at the top while scrollY = 0.

        // Press the strip below the thumb: pages down by one viewport, clamped to maxScroll (100).
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(sibarum.tactroller.api.MouseButton.LEFT,
                95, 80, 0));
        dispatcher.dispatch(n);
        assertEquals(100f, n.scrollY, 0.5f, "a track press pages by one viewport (100), clamped to the content");
        assertTrue(relayouts.get() >= 1);

        // And back up again from the strip above the thumb, which now sits at the bottom.
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(sibarum.tactroller.api.MouseButton.LEFT,
                95, 80, 0));
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(sibarum.tactroller.api.MouseButton.LEFT,
                95, 5, 0));
        dispatcher.dispatch(n);
        assertEquals(0f, n.scrollY, 0.5f, "pressing above the thumb pages back");
    }

    /** A press in the strip is the scrollbar's, so it must not also register as a click on the content. */
    @Test
    void aTrackPressDoesNotClickThrough() {
        Atchung bus = Atchung.create();
        AtomicInteger clicks = new AtomicInteger();
        InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run, () -> { });
        dispatcher.onClick(1, clicks::incrementAndGet);

        RetainedNode n = scrollable(1);
        n.viewX = 0;
        n.viewY = 0;
        n.viewW = 90;
        n.scrollbarPx = 10;

        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(sibarum.tactroller.api.MouseButton.LEFT,
                95, 80, 0));
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(sibarum.tactroller.api.MouseButton.LEFT,
                95, 80, 0));
        dispatcher.dispatch(n);

        assertEquals(0, clicks.get(), "the press addressed the scrollbar, so no click reaches the content");
    }

    @Test
    void wheelIgnoredWhenNoOverflowingContainer() {
        Atchung bus = Atchung.create();
        AtomicInteger relayouts = new AtomicInteger();
        InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run, relayouts::incrementAndGet);

        RetainedNode node = new RetainedNode(1, NodeKind.BOX); // no overflow
        node.w = 100;
        node.h = 100;

        bus.publish(InputTopics.INPUT, new InputEvent.Scrolled(0, -1, 50, 50, 0));
        dispatcher.dispatch(node);

        assertEquals(0f, node.scrollY, 0.01f);
        assertEquals(0, relayouts.get(), "no relayout when nothing scrolls");
    }
}
