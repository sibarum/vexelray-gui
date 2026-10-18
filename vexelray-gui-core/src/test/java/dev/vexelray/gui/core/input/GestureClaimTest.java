package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.GestureEvent;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.TouchpadContact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fingers on the touchpad, through the dispatcher: a swipe reaches the claim on it exactly as a chord would, and
 * the pan it was made of is published for whatever manipulates things directly.
 */
class GestureClaimTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.gesture.clicks", ClickEvent.class);

    private static RetainedNode tree() {
        RetainedNode root = new RetainedNode(0);
        RetainedNode c = new RetainedNode(1);
        c.w = 10;
        c.h = 10;
        c.parent = root;
        root.children.add(c);
        root.w = 100;
        root.h = 100;
        return root;
    }

    /** Three fingers in a row at {@code x} mm, at {@code atMs}. */
    private static void three(Atchung bus, double x, long atMs) {
        bus.publish(InputTopics.INPUT, new InputEvent.TouchpadChanged(List.of(
                new TouchpadContact(1, x, 30, 0, 0), new TouchpadContact(2, x + 15, 30, 0, 0),
                new TouchpadContact(3, x + 30, 30, 0, 0)), atMs * 1_000_000L));
    }

    private static void lift(Atchung bus, long atMs) {
        bus.publish(InputTopics.INPUT, new InputEvent.TouchpadChanged(List.of(), atMs * 1_000_000L));
    }

    @Test
    void aThreeFingerSwipeRunsItsClaimAndThePanIsPublished() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode root = tree();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        List<GestureEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(InputTopics.GESTURE, seen::add);
        d.claim(-1L, Gesture.swipe(3, Gesture.Motion.SWIPE_LEFT), ClaimScope.GLOBAL, () -> log.add("previous"));
        d.claim(-1L, Gesture.swipe(3, Gesture.Motion.SWIPE_RIGHT), ClaimScope.GLOBAL, () -> log.add("next"));

        for (int i = 0; i <= 10; i++) {
            three(bus, 40 - 2 * i, 10L * i);   // 20mm to the left over 100ms
            d.dispatch(root);
        }
        lift(bus, 120);
        d.dispatch(root);

        assertEquals(List.of("previous"), log);
        assertTrue(seen.stream().anyMatch(g -> g instanceof GestureEvent.GestureStarted s
                && s.kind() == GestureEvent.Kind.PAN && s.fingers() == 3), "the pan is published: " + seen);
        assertTrue(seen.getLast() instanceof GestureEvent.GestureEnded, "and its end: " + seen);
    }

    @Test
    void aGestureClaimRanksAsAChordDoes() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode root = tree();
        d.setFocusable(1, true);
        d.focus(1);
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Gesture left = Gesture.swipe(3, Gesture.Motion.SWIPE_LEFT);
        d.claim(-1L, left, ClaimScope.GLOBAL, () -> log.add("global"));
        d.claim(1, left, ClaimScope.FOCUSED, () -> log.add("focused"));

        for (int i = 0; i <= 10; i++) {
            three(bus, 40 - 2 * i, 10L * i);
            d.dispatch(root);
        }
        lift(bus, 120);
        d.dispatch(root);

        assertEquals(List.of("focused"), log, "the narrower place wins, as for a key");
    }
}
