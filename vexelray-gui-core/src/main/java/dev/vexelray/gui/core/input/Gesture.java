package dev.vexelray.gui.core.input;

import java.util.Locale;

/**
 * A touchpad gesture that is a command: some number of fingers swiping one way, tapping, or tapping twice. Claimed
 * and bound exactly as a chord is ({@link Trigger}); recognized from the touchpad by {@link GestureTriggers}.
 *
 * <p>Spelled as words run together, the way {@code PageDown} is: {@code ThreeFingerSwipeLeft},
 * {@code TwoFingerDoubleTap}. One finger is the pointer, so a gesture has two to five.
 */
public record Gesture(int fingers, Motion motion) implements Trigger {

    /** The most fingers a gesture is spelled for: a hand. */
    public static final int MAX_FINGERS = 5;

    private static final String[] COUNTS = {"", "", "Two", "Three", "Four", "Five"};

    /** What the fingers did. A swipe's direction is the way the fingers went, as seen on the screen. */
    public enum Motion {
        SWIPE_LEFT("SwipeLeft"),
        SWIPE_RIGHT("SwipeRight"),
        SWIPE_UP("SwipeUp"),
        SWIPE_DOWN("SwipeDown"),
        TAP("Tap"),
        DOUBLE_TAP("DoubleTap");

        private final String word;

        Motion(String word) {
            this.word = word;
        }
    }

    public Gesture {
        if (fingers < 2 || fingers > MAX_FINGERS) {
            throw new IllegalArgumentException("a gesture has 2 to " + MAX_FINGERS + " fingers: " + fingers);
        }
        if (motion == null) {
            throw new IllegalArgumentException("a gesture needs a motion");
        }
    }

    public static Gesture swipe(int fingers, Motion direction) {
        return new Gesture(fingers, direction);
    }

    public static Gesture tap(int fingers) {
        return new Gesture(fingers, Motion.TAP);
    }

    public static Gesture doubleTap(int fingers) {
        return new Gesture(fingers, Motion.DOUBLE_TAP);
    }

    /** {@code ThreeFingerSwipeLeft}. */
    @Override
    public String text() {
        return COUNTS[fingers] + "Finger" + motion.word;
    }

    @Override
    public String toString() {
        return text();
    }

    /**
     * Read what {@link #text()} wrote. Lenient where it costs nothing: any case, and the count as a digit
     * ({@code 3FingerSwipeLeft}).
     *
     * @throws IllegalArgumentException if it is not a gesture
     */
    public static Gesture parse(String text) {
        Gesture g = tryParse(text);
        if (g == null) {
            throw new IllegalArgumentException("'" + text + "' is not a gesture");
        }
        return g;
    }

    /** {@link #parse}, or null rather than throwing: how {@link Trigger#parse} tells a gesture from a chord. */
    static Gesture tryParse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip().toLowerCase(Locale.ROOT);
        int at = t.indexOf("finger");
        if (at <= 0) {
            return null;
        }
        String count = t.substring(0, at);
        String rest = t.substring(at + "finger".length());
        int fingers = -1;
        for (int n = 2; n <= MAX_FINGERS; n++) {
            if (count.equals(COUNTS[n].toLowerCase(Locale.ROOT)) || count.equals(Integer.toString(n))) {
                fingers = n;
            }
        }
        if (fingers < 0) {
            return null;
        }
        for (Motion m : Motion.values()) {
            if (rest.equals(m.word.toLowerCase(Locale.ROOT))) {
                return new Gesture(fingers, m);
            }
        }
        return null;
    }
}
