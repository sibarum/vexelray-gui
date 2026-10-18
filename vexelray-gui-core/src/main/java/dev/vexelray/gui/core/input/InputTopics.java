package dev.vexelray.gui.core.input;

import sibarum.atchung.Topic;
import sibarum.tactroller.api.GestureEvent;
import sibarum.tactroller.api.InputEvent;

/**
 * The input topic contract between the framework and whatever publishes input onto the bus. The framework core
 * knows only this: a {@code Topic<InputEvent>} named {@value #INPUT_NAME}. Tactroller's {@code InputPublisher}
 * publishes on the same name by default, so wiring {@code tactroller-atchung} onto the GUI's bus (at the
 * application edge) is all it takes to feed dispatch — the core never depends on a concrete input source
 * (architecture.md §8, §11).
 */
public final class InputTopics {

    /** The channel name tactroller-atchung publishes device input on, and the one dispatch subscribes to. */
    public static final String INPUT_NAME = "tactroller.input";

    /** Discrete device-input edges (key/button/scroll/motion/focus). */
    public static final Topic<InputEvent> INPUT = Topic.of(INPUT_NAME, InputEvent.class);

    /**
     * The touchpad's gestures, as the dispatcher recognized them from the contacts on {@link #INPUT}: pans and
     * pinches to manipulate something directly, and taps. One recognizer per window, the same one whose swipes and
     * taps reach claims ({@link Gesture}), so a widget reading this and a command bound to a swipe agree on what the
     * fingers did. Published while the dispatcher handles input, on the GUI thread.
     */
    public static final Topic<GestureEvent> GESTURE = Topic.of("vexelray.gesture", GestureEvent.class);

    private InputTopics() {
    }
}
