package dev.vexelray.gui.core;

import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.edit.Outcome;
import dev.vexelray.gui.core.edit.Target;
import dev.vexelray.gui.core.edit.UndoEvent;
import dev.vexelray.gui.core.input.InputDispatcher;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.nav.Address;
import dev.vexelray.gui.core.nav.Navigator;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * The two-press undo: take the user to what an entry edits before letting it apply (docs/plans/undo.md §2).
 *
 * <p>One press, against the entry on top of the history:
 * <ol>
 *   <li>No target — apply it where the user is, as every history did before targets existed.</li>
 *   <li>A target no longer in the tree, or one nothing can reveal — {@link Outcome#UNREACHABLE}.</li>
 *   <li>A disabled target — reveal and scroll to it, give it no focus, and answer {@link Outcome#REFUSED}, so the
 *       presenter can show which control is in the way.</li>
 *   <li>A target that holds focus, or holds the node that does — bring it into view and apply.</li>
 *   <li>Otherwise — reveal, scroll and focus it, apply nothing, and answer {@link Outcome#REVEALING} once it has
 *       arrived. The next press finds it focused and applies.</li>
 * </ol>
 *
 * <p><b>The decision is the GUI thread's; the applying is not.</b> Whether a node is in the tree, disabled or
 * focused is a read of the model, which only the GUI thread may make, so requests queue from any thread and are
 * judged once a frame. The entry is then applied on a handler thread, exactly where a claimed Ctrl+Z applied it
 * before this existed — an application whose changes write a file (MainFrame's workspaces) keeps that write off
 * the frame. Every {@link UndoEvent} is delivered on a handler thread too, so a presenter sees one thread kind.
 *
 * <p>A reveal is never recorded anywhere: it moves focus and scroll, and leaves both stacks alone, which is what
 * lets redo survive an undo that only revealed.
 */
final class Undoing {

    private record Request(History history, boolean redo) {
    }

    private final Queue<Request> requests = new ConcurrentLinkedQueue<>();
    private volatile Consumer<UndoEvent> sink = e -> { };

    private final Navigator navigator;
    private final InputDispatcher input;
    private final LongFunction<RetainedNode> nodes;
    private final Supplier<RetainedNode> root;
    private final LongConsumer scrollIntoView;
    private final Executor handlers;
    private final Consumer<String> wake;
    /** A handle on a node by id, for the event: a presenter plays its cue on a {@link Node}, not on an id. */
    private final LongFunction<Node> handles;

    Undoing(Navigator navigator, InputDispatcher input, LongFunction<RetainedNode> nodes,
            Supplier<RetainedNode> root, LongConsumer scrollIntoView, Executor handlers, Consumer<String> wake,
            LongFunction<Node> handles) {
        this.navigator = navigator;
        this.input = input;
        this.nodes = nodes;
        this.root = root;
        this.scrollIntoView = scrollIntoView;
        this.handlers = handlers;
        this.wake = wake;
        this.handles = handles;
    }

    private UndoEvent event(History history, boolean redo, Outcome outcome, long id) {
        return new UndoEvent(history, redo, outcome, id == Target.NO_NODE ? null : handles.apply(id));
    }

    /** Ask for one undo or redo of {@code history}, from any thread. Judged in the next frame. */
    void request(History history, boolean redo) {
        requests.add(new Request(history, redo));
        wake.accept(redo ? "redo requested" : "undo requested");
    }

    /** Where every outcome goes; see {@link UndoEvent}. */
    void sink(Consumer<UndoEvent> sink) {
        this.sink = sink == null ? e -> { } : sink;
    }

    /** Judge every request made since the last frame, in the order they were made. GUI thread, after the drain. */
    void step() {
        for (Request r = requests.poll(); r != null; r = requests.poll()) {
            attempt(r.history(), r.redo());
        }
    }

    private void attempt(History history, boolean redo) {
        Target target = redo ? history.redoTarget() : history.undoTarget();
        if (target == null) {
            apply(history, redo, Target.NO_NODE);
            return;
        }
        long id = target.isNode() ? target.node() : resolve(target.address());
        RetainedNode node = id == Target.NO_NODE ? null : nodes.apply(id);
        if (node == null || !attached(node)) {
            if (node == null && target.isNode()) {
                // Gone for good: a node id is never reused, so this entry can never apply. It fails once, and is
                // then cleared out of the way rather than blocking every other undo in the history behind it.
                // A landmark, or a node merely detached, may come back, so those stay and keep failing.
                history.discard(target, redo);
            }
            report(history, redo, Outcome.UNREACHABLE, Target.NO_NODE);
            return;
        }
        if (input.disabled(id)) {
            // Shown, not focused: the keyboard would only be taken away again, and the user needs to see which
            // control is closed — the presenter's cue says the rest once it is in view.
            navigator.navigate(id, false).arrival().whenComplete((arrived, failed) ->
                    report(history, redo, failed == null ? Outcome.REFUSED : Outcome.UNREACHABLE, id));
            return;
        }
        if (focusWithin(node)) {
            scrollIntoView.accept(id);   // focused but scrolled away still deserves to be seen changing
            apply(history, redo, id);
            return;
        }
        navigator.navigate(id, true).arrival().whenComplete((arrived, failed) ->
                report(history, redo, failed == null ? Outcome.REVEALING : Outcome.UNREACHABLE, id));
    }

    /**
     * The node a landmark names in this window, or none. An address naming another window is unreachable from
     * here: a history belongs to one window, and an entry that edits another window's control would change
     * something in a window the user is not looking at.
     */
    private long resolve(Address address) {
        if (address.windowNamed() && !address.window().equals(navigator.windowKey())) {
            return Target.NO_NODE;
        }
        return navigator.landmarkId(address.landmark());
    }

    /** Whether {@code node} is in the tree being drawn rather than built and not attached, or since detached. */
    private boolean attached(RetainedNode node) {
        RetainedNode top = node;
        while (top.parent != null) {
            top = top.parent;
        }
        return top == root.get();
    }

    /** Whether focus is on {@code node} or inside it — a field's text node counts for the field that holds it. */
    private boolean focusWithin(RetainedNode node) {
        long focused = input.focused();
        for (RetainedNode n = focused == -1 ? null : nodes.apply(focused); n != null; n = n.parent) {
            if (n == node) {
                return true;
            }
        }
        return false;
    }

    private void apply(History history, boolean redo, long id) {
        handlers.execute(() -> {
            Outcome outcome = redo ? history.attemptRedo() : history.attemptUndo();
            sink.accept(event(history, redo, outcome, id));
        });
    }

    private void report(History history, boolean redo, Outcome outcome, long id) {
        handlers.execute(() -> sink.accept(event(history, redo, outcome, id)));
    }
}
