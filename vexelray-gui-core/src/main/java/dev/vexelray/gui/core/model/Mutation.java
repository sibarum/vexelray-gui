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
                Mutation.ScrollToEdge, Mutation.Batch {

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

    /**
     * Re-attach a scroll-locked container to its locked edge (§8.5) — the one edit here that carries no state,
     * because what it changes is not a property of the tree but where a scroller is looking.
     *
     * <p>It has to be a mutation and not a setter on the model, for the same reason every other edit is: the
     * caller is on whatever thread it is on, and the retained tree has one writer. So "jump back to the tail"
     * queues behind the appends that made the tail move, and lands in the same frame they do.
     */
    record ScrollToEdge(long id) implements Mutation {
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
