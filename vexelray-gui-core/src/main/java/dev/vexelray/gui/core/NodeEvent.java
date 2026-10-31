package dev.vexelray.gui.core;

import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.InputDispatcher;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.input.PointerEvent;
import dev.vexelray.gui.core.input.WheelEvent;
import dev.vexelray.gui.core.layout.NodeLayout;

import java.util.function.Consumer;

/**
 * Something that happens to one node, which a view hears as a message ({@link Gui#hear}): a click, the pointer's
 * hover and press, where the pointer is over it, a drag, the wheel, a key, typed text, a new size.
 *
 * <p><b>Two kinds, as every topic has</b> (vexelray-framework's {@code docs/threading.md}, T1.6). Most are
 * <i>edges</i>, each delivered in order: two clicks in a frame are two clicks. {@link #STATE}, {@link #POINTER} and
 * {@link #RESIZE} are
 * <i>state</i>, kept per node with the last write winning: a pointer that crossed a row and left within one frame
 * reaches the view as where it ended, not as two restyles that were never drawn.
 *
 * <p>A kind is a value rather than a method per kind on {@link Gui}, so that what a view hears is a list a processor
 * can read, and a new kind is a constant here rather than a method everywhere.
 *
 * @param <E> what the view is handed
 */
public abstract class NodeEvent<E> {

    /** A left press and release on the node, or bubbled to it from a descendant with no click of its own. */
    public static final NodeEvent<ClickEvent> CLICK = new NodeEvent<>("click", false) {
        @Override
        boolean taken(InputDispatcher input, Gui gui, long id) {
            return input.hasClick(id);
        }

        @Override
        void listen(InputDispatcher input, Gui gui, long id, Consumer<ClickEvent> publish) {
            input.onClick(id, publish);
        }
    };

    /** The second of two quick clicks in one place; the clicks still arrive as {@link #CLICK}s too. */
    public static final NodeEvent<ClickEvent> DOUBLE_CLICK = new NodeEvent<>("double-click", false) {
        @Override
        boolean taken(InputDispatcher input, Gui gui, long id) {
            return input.hasDoubleClick(id);
        }

        @Override
        void listen(InputDispatcher input, Gui gui, long id, Consumer<ClickEvent> publish) {
            input.onDoubleClick(id, publish);
        }
    };

    /**
     * A right press and release on the node. For a menu, declare one instead ({@link Gui#menu}): it opens in the frame
     * of the click, and this arrives in it too, but only after the menu has been decided.
     */
    public static final NodeEvent<ClickEvent> CONTEXT_CLICK = new NodeEvent<>("context-click", false) {
        @Override
        boolean taken(InputDispatcher input, Gui gui, long id) {
            return input.hasContextClick(id);
        }

        @Override
        void listen(InputDispatcher input, Gui gui, long id, Consumer<ClickEvent> publish) {
            input.onContextClick(id, publish);
        }
    };

    /** The pointer's relation to the node (normal, hover, pressed): state, so only where it ended in a frame. */
    public static final NodeEvent<InteractionState> STATE = new NodeEvent<>("state", true) {
        @Override
        boolean taken(InputDispatcher input, Gui gui, long id) {
            return false;   // observers accumulate: a widget restyling itself and a view watching do not collide
        }

        @Override
        void listen(InputDispatcher input, Gui gui, long id, Consumer<InteractionState> publish) {
            input.onState(id, publish);
        }
    };

    /**
     * Where the pointer is over the node, and that it left: state, so only the newest in a frame. What {@link #STATE}
     * says as {@code HOVER}, with the position, for a node whose owner finds what is under the pointer itself (a
     * picture's marks, which are not nodes). Still hover: a colour change, never something that appears or moves.
     */
    public static final NodeEvent<PointerEvent> POINTER = new NodeEvent<>("pointer", true) {
        @Override
        boolean taken(InputDispatcher input, Gui gui, long id) {
            return false;   // observers accumulate, as for STATE
        }

        @Override
        void listen(InputDispatcher input, Gui gui, long id, Consumer<PointerEvent> publish) {
            input.onPointer(id, publish);
        }
    };

    /** A press on the node captured and moved: start, every move, end. */
    public static final NodeEvent<DragEvent> DRAG = new NodeEvent<>("drag", false) {
        @Override
        boolean taken(InputDispatcher input, Gui gui, long id) {
            return input.hasDrag(id);
        }

        @Override
        void listen(InputDispatcher input, Gui gui, long id, Consumer<DragEvent> publish) {
            input.onDrag(id, publish);
        }
    };

    /** The wheel over the node, taken from whatever it is inside, which no longer scrolls for it. */
    public static final NodeEvent<WheelEvent> WHEEL = new NodeEvent<>("wheel", false) {
        @Override
        boolean taken(InputDispatcher input, Gui gui, long id) {
            return input.hasWheel(id);
        }

        @Override
        void listen(InputDispatcher input, Gui gui, long id, Consumer<WheelEvent> publish) {
            input.onWheel(id, publish);
        }
    };

    /** A key pressed while the node has focus, after shortcuts and claims had first refusal. Makes it focusable. */
    public static final NodeEvent<KeyEvent> KEY = new NodeEvent<>("key", false) {
        @Override
        boolean taken(InputDispatcher input, Gui gui, long id) {
            return input.hasKey(id);
        }

        @Override
        void listen(InputDispatcher input, Gui gui, long id, Consumer<KeyEvent> publish) {
            input.onKey(id, publish);
        }
    };

    /** A code point typed while the node has focus. Makes it focusable. */
    public static final NodeEvent<Integer> CHAR = new NodeEvent<>("char", false) {
        @Override
        boolean taken(InputDispatcher input, Gui gui, long id) {
            return input.hasChar(id);
        }

        @Override
        void listen(InputDispatcher input, Gui gui, long id, Consumer<Integer> publish) {
            input.onChar(id, publish::accept);
        }
    };

    /**
     * The node's box, the first time it has one and whenever it changes: state, so only the last size in a frame.
     * Laid out after the widget tick has run, so it is heard in the next frame's; a node whose drawing has to follow
     * its size in the same frame takes it from layout (a {@code Length} or a widget that sizes itself), not from here.
     */
    public static final NodeEvent<NodeLayout> RESIZE = new NodeEvent<>("resize", true) {
        @Override
        boolean taken(InputDispatcher input, Gui gui, long id) {
            return gui.watchesResize(id);
        }

        @Override
        void listen(InputDispatcher input, Gui gui, long id, Consumer<NodeLayout> publish) {
            gui.watchResize(id, publish);
        }
    };

    private final String name;
    private final boolean state;

    private NodeEvent(String name, boolean state) {
        this.name = name;
        this.state = state;
    }

    /** What the kind is called, which its topic's name ends with. */
    public String name() {
        return name;
    }

    /** True for a kind where the last write per node wins within a tick; false for one where every event arrives. */
    public boolean isState() {
        return state;
    }

    /** True if {@code id} already has its one handler of this kind, which {@link Gui#hear} must not replace. */
    abstract boolean taken(InputDispatcher input, Gui gui, long id);

    /** Have the dispatcher hand this kind's events for {@code id} to {@code publish}. */
    abstract void listen(InputDispatcher input, Gui gui, long id, Consumer<E> publish);

    @Override
    public String toString() {
        return "NodeEvent[" + name + "]";
    }

    /**
     * One event, for one node: what crosses the tree's topic for {@code kind}, routed by the node's id. A view is
     * handed the event; the node it already knows, having heard it.
     */
    public record Message<E>(long node, E event) implements Addressed {
        @Override
        public Object address() {
            return node;
        }
    }
}
