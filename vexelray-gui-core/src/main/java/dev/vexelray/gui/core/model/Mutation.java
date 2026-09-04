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
                Mutation.ScrollToEdge, Mutation.Reveal, Mutation.Batch {

    /** The node this mutation targets (for coalescing / routing); {@code Batch} returns {@code 0}. */
    long targetId();

    /**
     * The <b>cell</b> this mutation writes, for the folding mailbox the mutation channel is drained through
     * ({@code sibarum.atchung.Fold}), or {@code null} if it is an edge that supersedes nothing.
     *
     * <p>Setting a label's text twice before the next frame is not two edits — it is two writes to one cell,
     * and only the second is ever observable, because nothing reads the retained tree between a publish and the
     * drain that applies it. So a property write names the cell it lands in and the queue keeps one entry for
     * it, and the mailbox then grows with how much of the tree changed rather than with how busy the thread
     * that changed it was. A worker looping on {@code node.text(...)} occupies one slot.
     *
     * <p><b>Structure is not a cell.</b> {@link Create}, {@link Insert} and {@link Remove} are occurrences in an
     * order that means something — the same node inserted twice is two moves — and a {@link Batch} is a group
     * that was already coalesced by whoever grouped it. All of them fold with nothing.
     *
     * <p>Declared here, per mutation, rather than read off a switch somewhere else: whether an edit supersedes
     * an earlier one is a fact about that kind of edit, and the next kind of edit should have to answer the
     * question in its own declaration rather than be forgotten in someone else's.
     */
    default Object cell() {
        return null;
    }

    /**
     * One writable cell of the tree: a node's single property. The unit a {@link SetProp} or {@link SetText}
     * supersedes another over — and they supersede each other, because {@code SetText} writes
     * {@link PropKey#TEXT} like any other property write.
     */
    record Cell(long id, PropKey key) {
    }

    record Create(long id, Map<PropKey, Object> initial) implements Mutation {
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

        @Override
        public Object cell() {
            return new Cell(id, key);
        }
    }

    record SetText(long id, String text) implements Mutation {
        @Override
        public long targetId() {
            return id;
        }

        /** The cell an ordinary write to {@link PropKey#TEXT} lands in, because that is what this is. */
        @Override
        public Object cell() {
            return new Cell(id, PropKey.TEXT);
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

        /**
         * Itself: this record's identity <em>is</em> the request, so two asks for the same container in one
         * frame are one ask. Which they are — the second cannot mean anything the first did not, since neither
         * carries an offset and both mean "be at the edge when this frame is drawn".
         */
        @Override
        public Object cell() {
            return this;
        }
    }

    /**
     * Bring a node inside every scrolling ancestor's viewport — the other edit that changes where a scroller is
     * looking rather than what the tree contains, and the twin of {@link ScrollToEdge}: that one goes to an edge
     * the layout can find on its own, this one goes to a node whose position only the layout knows.
     *
     * <p>A request, not an offset, because the answer does not exist yet when it is asked. The caller is a
     * keyboard handler or a search on a worker thread; the row it wants seen may be one that this frame's
     * mutations are still about to reveal. So it queues behind them, and is answered in the same frame — after
     * the layout that places the row, before the publish that reports it.
     *
     * <p>One-shot on purpose. A standing "keep this in view" would fight the user the moment they scrolled away
     * from it, which is the difference between following a selection and taking the scrollbar away.
     */
    record Reveal(long id) implements Mutation {
        @Override
        public long targetId() {
            return id;
        }

        /** Itself, for the reason {@link ScrollToEdge#cell()} gives: asking twice is asking once. */
        @Override
        public Object cell() {
            return this;
        }
    }

    record Batch(List<Mutation> ops) implements Mutation {
        @Override
        public long targetId() {
            return 0L;
        }
    }
}
