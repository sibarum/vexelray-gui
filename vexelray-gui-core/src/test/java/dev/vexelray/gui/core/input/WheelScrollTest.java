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
        assertEquals(48f, node.scrollY, 0.01f, "one notch = WHEEL_STEP px");
        assertTrue(relayouts.get() >= 1, "scrolling requests a relayout");

        // Several notches down clamp to maxScroll (contentH - viewH = 100), never beyond.
        bus.publish(InputTopics.INPUT, new InputEvent.Scrolled(0, -10, 50, 50, 0));
        dispatcher.dispatch(node);
        assertEquals(100f, node.scrollY, 0.01f, "scroll clamps to content - viewport");
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
