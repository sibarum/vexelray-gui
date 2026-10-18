package dev.vexelray.gui.core.input;

/**
 * What a claim is made on: a keyboard {@link Shortcut}, or a touchpad {@link Gesture} that is over the moment it
 * happens — a swipe, a tap. Both are commands in the same sense: a person does one, and one thing happens. That is
 * why they share claims ({@link ClaimScope}), precedence and the key map, and why "Next picture" can be → and a
 * three-finger swipe at once, rebound in one place.
 *
 * <p>A gesture that goes on — a pan, a pinch — is not a trigger. It is direct manipulation, read off
 * {@link InputTopics#GESTURE}, and there is nothing to rebind about it.
 *
 * <p><b>One spelling.</b> {@link #text()} is what is shown and what a settings file stores, and {@link #parse}
 * reads it back. No spelling has a space in it, so a list of triggers is words separated by spaces.
 */
public sealed interface Trigger permits Shortcut, Gesture {

    /** As a person reads it, and as a file stores it: {@code Ctrl+Shift+K}, {@code ThreeFingerSwipeLeft}. */
    String text();

    /**
     * Read what {@link #text()} wrote: a gesture's name, or otherwise a chord ({@link Shortcut#parse}).
     *
     * @throws IllegalArgumentException if it is neither
     */
    static Trigger parse(String text) {
        Gesture g = Gesture.tryParse(text);
        return g != null ? g : Shortcut.parse(text);
    }
}
