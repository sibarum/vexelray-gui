package dev.vexelray.gui.core.input;

/**
 * The pointer-interaction state of a node, computed by {@link InputDispatcher} from hit-testing and left-button
 * state. A node registered via {@code Gui.onState} is told whenever its state changes, so it can restyle
 * (hover highlight, pressed depression, etc.).
 *
 * <p>Priority is {@link #PRESSED} &gt; {@link #HOVER} &gt; {@link #NORMAL}. A node is {@code PRESSED} while the
 * left button is held and both the press origin and the current pointer are within it (so dragging off a pressed
 * button releases the pressed look, and dragging back restores it — standard button behavior).
 */
public enum InteractionState {

    /** The pointer is elsewhere. */
    NORMAL,

    /** The pointer is over this node (or a descendant) and no button is pressing it. */
    HOVER,

    /** The left button went down on this node (or a descendant) and is still held with the pointer over it. */
    PRESSED
}
