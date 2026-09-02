package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.model.SemanticNode;

import java.util.Map;

/**
 * An immutable, versioned snapshot of what the tree <b>means</b> — role, name, structure and interaction state
 * for every node — published beside {@link LayoutSnapshot} and carrying the same {@link #version}, so the two
 * join by node id and by frame (docs/automation.md §3).
 *
 * <p>Same contract as its geometric twin and for the same reasons: pure data keyed by node id, so a local
 * widget, a test, an automation agent or a remote thin client all read the same state through
 * {@link #node(long)}, and {@link #version} tells a consumer whether its mutation is reflected yet.
 *
 * <p>Deliberately holds no query surface. "Find the enabled button named Save" is a consumer's question, and
 * the consumers differ; putting one consumer's search here would make this the place every later consumer
 * has to extend.
 */
public record SemanticSnapshot(long version, long rootId, Map<Long, SemanticNode> nodes) {

    /** The empty snapshot before the first layout. */
    public static final SemanticSnapshot EMPTY = new SemanticSnapshot(0L, -1L, Map.of());

    public SemanticSnapshot {
        nodes = Map.copyOf(nodes);
    }

    /** What {@code nodeId} is, or {@link SemanticNode#ABSENT} if this snapshot does not describe it. */
    public SemanticNode node(long nodeId) {
        return nodes.getOrDefault(nodeId, SemanticNode.ABSENT);
    }

    /** The root, or {@link SemanticNode#ABSENT} before the first layout. */
    public SemanticNode root() {
        return node(rootId);
    }
}
