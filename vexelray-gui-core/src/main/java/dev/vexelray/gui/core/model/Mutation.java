package dev.vexelray.gui.core.model;

import java.util.List;
import java.util.Map;

/**
 * A single edit to the retained tree, produced by {@code Node} setters on any thread and applied by the
 * {@link Reconciler} on the GUI thread. Construction and updates share this one vocabulary — a {@code Create} at
 * frame 0 is just the first mutation (architecture.md §3).
 */
public sealed interface Mutation
        permits Mutation.Create, Mutation.Insert, Mutation.Remove, Mutation.SetProp, Mutation.SetText,
                Mutation.Batch {

    /** The node this mutation targets (for coalescing / routing); {@code Batch} returns {@code 0}. */
    long targetId();

    record Create(long id, NodeKind kind, Map<PropKey, Object> initial) implements Mutation {
        @Override
        public long targetId() {
            return id;
        }
    }

    /** {@code END} = append; a non-negative index inserts before that child. */
    record Insert(long parent, long child, int index) implements Mutation {
        public static final int END = -1;

        @Override
        public long targetId() {
            return parent;
        }
    }

    record Remove(long id) implements Mutation {
        @Override
        public long targetId() {
            return id;
        }
    }

    record SetProp(long id, PropKey key, Object value) implements Mutation {
        @Override
        public long targetId() {
            return id;
        }
    }

    record SetText(long id, String text) implements Mutation {
        @Override
        public long targetId() {
            return id;
        }
    }

    record Batch(List<Mutation> ops) implements Mutation {
        @Override
        public long targetId() {
            return 0L;
        }
    }
}
