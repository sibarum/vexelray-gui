package dev.vexelray.gui.core.layout;

import java.util.Map;

/**
 * An immutable, versioned snapshot of the whole tree's computed layout — the framework's read-model
 * (docs/layout-read-model.md). Core publishes a new snapshot after each layout pass as a coalesced bus
 * {@code State<LayoutSnapshot>} (mirroring {@code Gui.viewport()}); widgets and observers read it lock-free.
 *
 * <p>Pure data keyed by node id, so it is transport-serializable: a local widget, a test, or a remote thin
 * client all read the same computed state through {@link #node(long)}. {@link #version} increases per published
 * frame, giving consumers a basis for "is my mutation reflected yet?" and, later, delta encoding.
 */
public record LayoutSnapshot(long version, Map<Long, NodeLayout> nodes) {

    /** The empty snapshot before the first layout. */
    public static final LayoutSnapshot EMPTY = new LayoutSnapshot(0L, Map.of());

    public LayoutSnapshot {
        nodes = Map.copyOf(nodes);
    }

    /** The computed layout for {@code nodeId}, or {@link NodeLayout#ABSENT} if it is not in this snapshot. */
    public NodeLayout node(long nodeId) {
        return nodes.getOrDefault(nodeId, NodeLayout.ABSENT);
    }
}
