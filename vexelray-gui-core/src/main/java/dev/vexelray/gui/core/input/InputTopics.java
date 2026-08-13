package dev.vexelray.gui.core.input;

import sibarum.atchung.Topic;
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

    private InputTopics() {
    }
}
