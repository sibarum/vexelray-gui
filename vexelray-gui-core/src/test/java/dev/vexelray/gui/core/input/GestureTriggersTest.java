package dev.vexelray.gui.core.input;

import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.GestureEvent;
import sibarum.tactroller.api.GestureEvent.Kind;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Which touchpad gestures are commands: swipes as they happen, flicks as they leave, taps and double taps. */
class GestureTriggersTest {

    private final GestureTriggers t = new GestureTriggers();
    private final List<Gesture> out = new ArrayList<>();

    private static long ms(long millis) {
        return millis * 1_000_000L;
    }

    private void start(Kind kind, int fingers, long atMs) {
        out.addAll(t.feed(new GestureEvent.GestureStarted(kind, fingers, 0, 0, ms(atMs))));
    }

    private void move(Kind kind, int fingers, double dx, double dy, long atMs) {
        out.addAll(t.feed(new GestureEvent.GestureUpdated(kind, fingers, 0, 0, dx, dy, 1, 0, ms(atMs))));
    }

    private void end(Kind kind, int fingers, double vx, double vy, long atMs) {
        out.addAll(t.feed(new GestureEvent.GestureEnded(kind, fingers, 0, 0, vx, vy, ms(atMs))));
    }

    private void tap(int fingers, long atMs) {
        out.addAll(t.feed(new GestureEvent.Tapped(fingers, 0, 0, ms(atMs))));
    }

    @Test
    void aSwipeFiresOnceItHasGoneFarEnoughAndOnlyOnce() {
        start(Kind.PAN, 3, 0);
        move(Kind.PAN, 3, -5, 0.5, 10);
        assertEquals(List.of(), out, "5mm is not a swipe yet");
        move(Kind.PAN, 3, -8, 0.5, 20);
        assertEquals(List.of(Gesture.swipe(3, Gesture.Motion.SWIPE_LEFT)), out, "13mm left is, while moving");
        move(Kind.PAN, 3, -30, 0, 40);
        end(Kind.PAN, 3, -400, 0, 50);
        assertEquals(1, out.size(), "however far it goes, and however fast it leaves, one swipe");
    }

    @Test
    void aDiagonalIsNoSwipe() {
        start(Kind.PAN, 3, 0);
        move(Kind.PAN, 3, 15, 10, 10);
        end(Kind.PAN, 3, 300, 200, 20);
        assertEquals(List.of(), out, "a swipe goes mostly one way");
    }

    @Test
    void aShortFastFlickIsASwipeWhenItLeaves() {
        start(Kind.PAN, 3, 0);
        move(Kind.PAN, 3, 0, 6, 10);
        end(Kind.PAN, 3, 0, 200, 20);
        assertEquals(List.of(Gesture.swipe(3, Gesture.Motion.SWIPE_DOWN)), out);
    }

    @Test
    void aShortSlowPanIsNothing() {
        start(Kind.PAN, 3, 0);
        move(Kind.PAN, 3, 6, 0, 100);
        end(Kind.PAN, 3, 0, 0, 400);
        assertEquals(List.of(), out);
    }

    @Test
    void aPinchNeverSwipes() {
        start(Kind.PINCH, 2, 0);
        move(Kind.PINCH, 2, 20, 0, 10);
        end(Kind.PINCH, 2, 500, 0, 20);
        assertEquals(List.of(), out);
    }

    @Test
    void twoTapsCloseTogetherAreATapEachAndADoubleTap() {
        tap(2, 0);
        tap(2, 200);
        tap(2, 300);
        assertEquals(List.of(Gesture.tap(2), Gesture.tap(2), Gesture.doubleTap(2), Gesture.tap(2)), out,
                "a third tap starts another pair rather than making a second double");
    }

    @Test
    void tapsTooFarApartOrWithOtherFingersAreNoDouble() {
        tap(2, 0);
        tap(2, 500);
        tap(3, 600);
        assertEquals(List.of(Gesture.tap(2), Gesture.tap(2), Gesture.tap(3)), out);
    }

    @Test
    void aGestureIsSpelledAsWordsAndReadBackLeniently() {
        assertEquals("ThreeFingerSwipeLeft", Gesture.swipe(3, Gesture.Motion.SWIPE_LEFT).text());
        assertEquals("TwoFingerDoubleTap", Gesture.doubleTap(2).text());
        for (int n = 2; n <= Gesture.MAX_FINGERS; n++) {
            for (Gesture.Motion m : Gesture.Motion.values()) {
                Gesture g = new Gesture(n, m);
                assertEquals(g, Trigger.parse(g.text()));
            }
        }
        assertEquals(Gesture.tap(4), Trigger.parse("4fingertap"));
        assertEquals(Shortcut.parse("Ctrl+S"), Trigger.parse("Ctrl+S"), "anything else is a chord");
        assertThrows(IllegalArgumentException.class, () -> Gesture.parse("OneFingerTap"));
        assertThrows(IllegalArgumentException.class, () -> Trigger.parse("ThreeFingerWave"));
    }
}
