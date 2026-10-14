package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.edit.Target;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Undo for a control that holds one value — a switch, a slider, a number, a choice (docs/plans/undo.md §2, step 6).
 * The control calls {@link #edited} on its user path, the one that tells the application; this records the way
 * back into the history the control's tree binds nearest above it (the window's, in a framework application), and
 * nothing at all where none is bound.
 *
 * <p><b>Undo replays as the user would.</b> {@code replay} is the control's own way of being edited — it shows the
 * value <em>and</em> fires the application's handler — so the application's model follows an undo with no code of
 * its own, which is what lets this work in applications that never heard of undo. It must not record: the history
 * records the reverse itself.
 *
 * <p><b>An entry knows what it left behind.</b> If the control no longer shows that value — a sync from the model
 * ({@code show}) put something else there — the entry is a conflict and changes nothing, rather than overwriting a
 * newer value with an older one.
 *
 * <p><b>Runs</b>, for a control edited a step at a time (arrow keys on a slider, a number stepped up and up), fold
 * successive edits of the same control into one entry, until something else is recorded or {@link History#barrier}
 * is called. A switch does not run: two flips are two things the user did, and folding them would leave a Ctrl+Z
 * that changes nothing.
 *
 * @param <T> the value; compared with {@link Objects#equals}
 */
final class ValueUndo<T> {

    private final Gui gui;
    private final Node node;
    private final Supplier<T> read;
    private final Consumer<T> replay;
    private final boolean runs;
    private volatile boolean enabled = true;

    /**
     * @param node   the control's node: what the entry targets, and where the history is found from
     * @param read   the value the control shows now
     * @param replay set the value as the user would — show it and tell the application — without recording
     * @param runs   whether successive edits of this control fold into one entry
     */
    ValueUndo(Gui gui, Node node, Supplier<T> read, Consumer<T> replay, boolean runs) {
        this.gui = gui;
        this.node = node;
        this.read = read;
        this.replay = replay;
        this.runs = runs;
    }

    /** Whether edits are recorded; on unless the application said otherwise. */
    void enabled(boolean on) {
        this.enabled = on;
    }

    boolean enabled() {
        return enabled;
    }

    /** The user moved the value from {@code before} to {@code after}. Record the way back, if there is a history. */
    void edited(T before, T after) {
        edited(before, after, false);
    }

    /**
     * As {@link #edited(Object, Object)}; {@code alone} makes it a step of its own, folded into no run and starting
     * none — a whole drag, which the user made as one gesture and expects back as one.
     */
    void edited(T before, T after, boolean alone) {
        if (!enabled || Objects.equals(before, after)) {
            return;
        }
        History history = gui.historyFor(node);
        if (history != null) {
            if (alone) {
                history.barrier();
            }
            history.record(new Set(before, after));
            if (alone) {
                history.barrier();
            }
        }
    }

    /** One entry: put {@code to} back, which is right only while the control still shows {@code expect}. */
    private final class Set implements Change {

        private final T to;
        private final T expect;

        Set(T to, T expect) {
            this.to = to;
            this.expect = expect;
        }

        private ValueUndo<T> owner() {
            return ValueUndo.this;
        }

        @Override
        public Change apply() {
            replay.accept(to);
            return new Set(expect, to);
        }

        @Override
        public Target target() {
            return Target.node(node.id());
        }

        @Override
        public Check check() {
            return Objects.equals(read.get(), expect) ? Check.OK : Check.CONFLICT;
        }

        @Override
        public Change coalesce(Change following) {
            // Both are ways back: this one to the older value, the following one to the value in between. The run
            // goes back to the older value, from wherever the last edit left it.
            if (runs && following instanceof ValueUndo<?>.Set next && next.owner() == ValueUndo.this) {
                @SuppressWarnings("unchecked") T latest = (T) next.expect;
                return new Set(to, latest);
            }
            return null;
        }
    }
}
