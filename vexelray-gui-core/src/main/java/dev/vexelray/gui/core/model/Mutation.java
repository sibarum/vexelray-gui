package dev.vexelray.gui.core.model;

import java.util.List;
import java.util.Map;

/**
 * A single edit to the retained tree, produced by {@code Node} setters on any thread and applied by the
 * {@link Reconciler} on the GUI thread. Construction and updates share this one vocabulary — a {@code Create} at
 * frame 0 is just the first mutation (architecture.md §3).
 *
 * <p><b>Open, and applied through a {@link Sink}.</b> Each mutation says what it means by calling one method on
 * the sink; {@link Reconciler} is the sink, and remains the only thing that writes a {@link RetainedNode} field.
 * That is the point of the inversion rather than a side effect of it: the obvious alternative — letting each
 * mutation apply itself to the tree — would have moved write logic out to eight records and forced the list of
 * permitted writers in {@code ModelWriterGuardTest} to admit them all. The set of <em>operations</em> is closed,
 * the set of <em>mutations</em> is open, and neither side switches. Same shape as {@code Picture.Mark} with
 * {@code Picture.Sink}.
 */
public interface Mutation {

    /** The node this mutation targets (for coalescing / routing); {@code Batch} returns {@code 0}. */
    long targetId();

    /**
     * Say what this edit is, by calling the one method on {@code sink} that means it.
     *
     * <p>Replaced a switch in {@link Reconciler} that named every mutation kind, which meant adding a mutation
     * was editing two files that had to agree, and the vocabulary was closed because the switch was.
     */
    void emitTo(Sink sink);

    /**
     * The closed set of things that can be done to the retained tree — one method per kind of edit, no defaults,
     * so a new operation cannot be added without every sink being made to answer for it.
     *
     * <p>Closed on purpose, and the opposite choice from {@link Mutation} itself. What a tree can have done to it
     * is a small fixed vocabulary the model writer has to implement completely; who asks for it is not.
     */
    interface Sink {

        /** A new node, with the properties it is born holding. */
        void create(long id, Map<PropKey, Object> initial);

        /** Put {@code child} under {@code parent}, at {@code index} or appended when it is {@link Insert#END}. */
        void insert(long parent, long child, int index);

        /** Drop the node and everything under it. */
        void remove(long id);

        /**
         * Make {@code children} the parent's children, in that order: each moved there from wherever it is, and every
         * other child removed. See {@link Arrange}.
         */
        void arrange(long parent, long[] children);

        /** Write one property. */
        void setProp(long id, PropKey key, Object value);

        /** Write the text property, which is {@link PropKey#TEXT} like any other. */
        void setText(long id, String text);

        /** Re-attach a scroll-locked container to its locked edge. */
        void scrollToEdge(long id);

        /** Put a scrolling container's view at an offset, in layout px, clamped to what it can scroll. */
        void scrollTo(long id, float x, float y);

        /** Record that a node should be brought into view once the layout knows where it is. */
        void reveal(long id);

        /** Draw the whole tree in {@code theme}: every paint and finish resolves against it from now on. */
        void retheme(dev.vexelray.gui.core.style.Theme theme);
    }

    /**
     * The <b>cell</b> this mutation writes, for the folding mailbox the mutation channel is drained through
     * ({@code sibarum.atchung.Fold}), or {@code null} if it is an edge that supersedes nothing.
     *
     * <p>Setting a label text twice before the next frame is not two edits — it is two writes to one cell,
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
     * question in its own declaration rather than be forgotten in a switch somewhere else.
     */
    default Object cell() {
        return null;
    }

    /**
     * One writable cell of the tree: a node single property. The unit a {@link SetProp} or {@link SetText}
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

        @Override
        public void emitTo(Sink sink) {
            sink.create(id, initial);
        }
    }

    /** {@code END} = append; a non-negative index inserts before that child. */
    record Insert(long parent, long child, int index) implements Mutation {
        public static final int END = -1;

        @Override
        public long targetId() {
            return parent;
        }

        @Override
        public void emitTo(Sink sink) {
            sink.insert(parent, child, index);
        }
    }

    record Remove(long id) implements Mutation {
        @Override
        public long targetId() {
            return id;
        }

        @Override
        public void emitTo(Sink sink) {
            sink.remove(id);
        }
    }

    /**
     * A parent's whole child list, by identity: the keyed reconcile's one edit. Structure, so not a cell — two
     * arranges of one parent are two orders, and the later one is applied after the earlier.
     */
    record Arrange(long parent, long[] children) implements Mutation {
        public Arrange {
            children = children.clone();
        }

        @Override
        public long targetId() {
            return parent;
        }

        @Override
        public void emitTo(Sink sink) {
            sink.arrange(parent, children.clone());
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

        @Override
        public void emitTo(Sink sink) {
            sink.setProp(id, key, value);
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

        @Override
        public void emitTo(Sink sink) {
            sink.setText(id, text);
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
         * Itself: this record identity <em>is</em> the request, so two asks for the same container in one
         * frame are one ask. Which they are — the second cannot mean anything the first did not, since neither
         * carries an offset and both mean "be at the edge when this frame is drawn".
         */
        @Override
        public Object cell() {
            return this;
        }

        @Override
        public void emitTo(Sink sink) {
            sink.scrollToEdge(id);
        }
    }

    /**
     * Put a scrolling container's view at an offset, in layout px — for something that is animating where a view
     * looks, a step per frame, alongside other things it is moving: a carried card flying home while the lane it
     * came from scrolls back to where it was when the card was picked up. Both are written in one batch, so the two
     * land on the same frame and stay in step.
     *
     * <p>A cell per container: two offsets written before the next frame are one write, and the later one is the
     * only one anybody could have seen.
     */
    record ScrollTo(long id, float x, float y) implements Mutation {

        /** What two writes to the same container's offset share. */
        private record Cell(long id) {
        }

        @Override
        public long targetId() {
            return id;
        }

        @Override
        public Object cell() {
            return new Cell(id);
        }

        @Override
        public void emitTo(Sink sink) {
            sink.scrollTo(id, x, y);
        }
    }

    /**
     * Bring a node inside every scrolling ancestor viewport — the other edit that changes where a scroller is
     * looking rather than what the tree contains, and the twin of {@link ScrollToEdge}: that one goes to an edge
     * the layout can find on its own, this one goes to a node whose position only the layout knows.
     *
     * <p>A request, not an offset, because the answer does not exist yet when it is asked. The caller is a
     * keyboard handler or a search on a worker thread; the row it wants seen may be one that the mutations of
     * this frame are still about to reveal. So it queues behind them, and is answered in the same frame — after
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

        @Override
        public void emitTo(Sink sink) {
            sink.reveal(id);
        }
    }

    /**
     * Draw the whole tree in {@code theme} from this frame on. An edit to the tree like any other, rather than a field
     * the renderer reads, so that it lands in order with the frame's other edits and the writer that reports colour
     * changes sees it: every node whose paint resolves differently in the new theme fades there like a hover would.
     *
     * <p>One cell for the tree: two swaps before a frame are one, and only the second could have been seen.
     */
    record Retheme(dev.vexelray.gui.core.style.Theme theme) implements Mutation {

        /** What every swap writes to. */
        private static final Object CELL = new Object();

        @Override
        public long targetId() {
            return 0L;
        }

        @Override
        public Object cell() {
            return CELL;
        }

        @Override
        public void emitTo(Sink sink) {
            sink.retheme(theme);
        }
    }

    record Batch(List<Mutation> ops) implements Mutation {
        @Override
        public long targetId() {
            return 0L;
        }

        /**
         * Each in turn, which is all a batch means. No {@code batch} method on the sink: a group is a fact about
         * how these edits were queued, not an operation the tree has done to it.
         */
        @Override
        public void emitTo(Sink sink) {
            for (Mutation op : ops) {
                op.emitTo(sink);
            }
        }
    }
}
