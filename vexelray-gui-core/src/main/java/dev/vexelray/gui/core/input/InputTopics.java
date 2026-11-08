package dev.vexelray.gui.core.input;

import sibarum.atchung.Topic;
import sibarum.tactroller.api.GestureEvent;
import sibarum.tactroller.api.InputEvent;

/**
 * The input topic contract between the framework and whatever publishes input onto the bus: one pair of topics per
 * tree, named by the tree ({@code Gui.name()}), so two windows sharing a bus each hear their own device input. The
 * core never depends on a concrete input source (architecture.md §8, §11): wiring {@code tactroller-atchung} onto a
 * window is an {@code InputPublisher} on that window's bus, named {@code Gui.input().name()}.
 */
public final class InputTopics {

    /** Discrete device-input edges (key/button/scroll/motion/focus) for the tree called {@code tree}. */
    public static Topic<InputEvent> input(String tree) {
        return Topic.of("vexelray.gui." + tree + ".input", InputEvent.class);
    }

    /**
     * The touchpad's gestures for the tree called {@code tree}, as its dispatcher recognized them from the contacts
     * on {@link #input}: pans and pinches to manipulate something directly, and taps. One recognizer per window, the
     * same one whose swipes and taps reach claims ({@link Gesture}), so a widget reading this and a command bound to
     * a swipe agree on what the fingers did. Published while the dispatcher handles input, on the GUI thread.
     */
    public static Topic<GestureEvent> gestures(String tree) {
        return Topic.of("vexelray.gui." + tree + ".gesture", GestureEvent.class);
    }

    private InputTopics() {
    }
}
