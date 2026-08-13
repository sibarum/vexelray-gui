package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.FlexLayout;
import dev.vexelray.gui.core.layout.LayoutContext;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.Mutation;
import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.Reconciler;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.InputDispatcher;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.model.RetainedNode;
import sibarum.atchung.Atchung;
import sibarum.atchung.Backpressure;
import sibarum.atchung.Committer;
import sibarum.atchung.Pump;
import sibarum.atchung.State;
import sibarum.atchung.Subscription;
import sibarum.atchung.Topic;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The framework facade and the boundary between worker threads and the GUI thread. Workers build UI and mutate it
 * through {@link Node} handles minted here (each publishes a {@code Create}); the GUI thread calls {@link #frame}
 * once per frame to drain the mutation channel, reconcile the retained tree (single writer), and lay it out.
 *
 * <p>The mutation channel is an Atchung {@code Topic<Mutation>} (architecture.md §4-5): {@link Node} setters
 * publish to it from any thread, and a {@link Pump} owned here drains it on the GUI thread. Losslessness is
 * required — a dropped tree edit corrupts the model — so the pumped mailbox uses {@link Backpressure#BLOCK} with a
 * generous capacity, which throttles a runaway producer instead of shedding edits. The GUI owns the model and the
 * reconciler; Atchung owns the transport. The same bus carries application events and input, so a subscriber on
 * another thread, process, or machine is indistinguishable from a local one.
 *
 * <p>Tree construction is just the first batch of mutations: node factories return handles immediately with no
 * round-trip to the GUI thread, so a worker can assemble a whole subtree off-thread.
 */
public final class Gui implements AutoCloseable {

    /**
     * The internal tree-mutation channel. A private topic name so it never collides with application traffic on a
     * shared bus; losslessly drained on the GUI thread each frame.
     */
    private static final Topic<Mutation> MUTATIONS = Topic.of("vexelray.gui.mutations", Mutation.class);

    /** Mailbox bound for the mutation pump. BLOCK makes this a throttle, not a drop threshold (see class doc). */
    private static final int MUTATION_MAILBOX = 1 << 16;

    /** Framework click events, resolved from raw input by the dispatcher; workers subscribe here. */
    private static final Topic<ClickEvent> CLICKS = Topic.of("vexelray.gui.clicks", ClickEvent.class);

    private final AtomicLong ids = new AtomicLong(1);
    private final Atchung bus;
    private final MutationSink sink;
    private final Pump pump;
    private final Subscription mutationSub;
    private final Reconciler reconciler;
    private final InputDispatcher input;
    private final Node root;
    private final State<Viewport> viewport;
    private final Committer<Viewport, Viewport> setViewport;
    private float lastViewportW = -1f;
    private float lastViewportH = -1f;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "vexelray-gui-worker");
        t.setDaemon(true);
        return t;
    });

    /** Create a GUI on its own private Atchung bus. */
    public Gui() {
        this(Atchung.create());
    }

    /**
     * Create a GUI on a shared Atchung bus — hand in the same bus the application uses so input publishers
     * ({@code tactroller-atchung}), widgets, and workers all meet the framework on one fabric.
     */
    public Gui(Atchung bus) {
        this.bus = bus;
        this.pump = bus.pump();
        // Publisher seam for Node handles: every setter publishes a Mutation onto the bus from any thread.
        this.sink = m -> bus.publish(MUTATIONS, m);

        long rootId = ids.getAndIncrement();
        this.reconciler = new Reconciler(rootId);
        // The GUI thread drains this pump each frame; the subscriber runs on that (drain) thread, so applying to
        // the single-writer reconciler here is the GUI-thread write the model requires.
        this.mutationSub = pump.subscribe(MUTATIONS, reconciler::apply, MUTATION_MAILBOX, Backpressure.BLOCK);
        // Framework input dispatch on the same bus; click handlers run on the worker executor (off the GUI thread).
        this.input = new InputDispatcher(bus, CLICKS, workers);

        // Window size as a coalesced, latest-wins State on the bus — the framework relays out from it and workers
        // can observe resizes without coupling to the window.
        State.Builder<Viewport> vb = State.of(new Viewport(0, 0));
        this.setViewport = vb.mutation("set", (current, next) -> next);
        this.viewport = vb.build();

        Map<PropKey, Object> init = new EnumMap<>(PropKey.class);
        init.put(PropKey.DIRECTION, Direction.COLUMN);
        init.put(PropKey.WIDTH, Length.FILL);
        init.put(PropKey.HEIGHT, Length.FILL);
        sink.post(new Mutation.Create(rootId, NodeKind.BOX, init));
        this.root = new Node(rootId, sink);
    }

    /** The Atchung bus this GUI publishes mutations, events, and (via a bridge) input on. */
    public Atchung bus() {
        return bus;
    }

    /** The live window size as a bus {@code State} — subscribe with {@code gui.viewport().onCommit(...)}. */
    public State<Viewport> viewport() {
        return viewport;
    }

    /**
     * Register a click handler for {@code node} — fired when a left press and release land on it (bubbling to the
     * nearest ancestor with a handler). Runs on a worker thread, so it may freely mutate the tree via handles.
     */
    public Gui onClick(Node node, Runnable handler) {
        input.onClick(node.id(), handler);
        return this;
    }

    /** The click topic: {@code gui.bus().subscribe(gui.clicks(), ...)} to react to clicks anywhere. */
    public Topic<ClickEvent> clicks() {
        return CLICKS;
    }

    /**
     * React to {@code node}'s pointer-interaction state (NORMAL/HOVER/PRESSED) — e.g. to restyle a button on hover
     * and press. The handler is invoked on a worker thread on each state change, so it may mutate the tree via
     * handles. Hovering a descendant (a button's label) counts as hovering {@code node}.
     */
    public Gui onState(Node node, java.util.function.Consumer<InteractionState> handler) {
        input.onState(node.id(), handler);
        return this;
    }

    /**
     * Drag {@code node}: a left press on it (or a descendant) captures the pointer and delivers START/MOVE/END
     * {@link DragEvent}s — MOVE continues while held even when the pointer leaves the node. The handler runs on a
     * worker thread; use {@link DragEvent#fractionX()}/{@code fractionY()} to map the pointer onto the node.
     */
    public Gui onDrag(Node node, java.util.function.Consumer<DragEvent> handler) {
        input.onDrag(node.id(), handler);
        return this;
    }

    /** The root node (fills the viewport). Append the UI to it. */
    public Node root() {
        return root;
    }

    /** A generic box (defaults to a row). */
    public Node box() {
        return create(NodeKind.BOX, null);
    }

    /** A horizontal box. */
    public Node row() {
        return create(NodeKind.BOX, Direction.ROW);
    }

    /** A vertical box. */
    public Node column() {
        return create(NodeKind.BOX, Direction.COLUMN);
    }

    /** A text node carrying {@code s}. */
    public Node text(String s) {
        long id = ids.getAndIncrement();
        Map<PropKey, Object> init = new EnumMap<>(PropKey.class);
        init.put(PropKey.TEXT, s);
        sink.post(new Mutation.Create(id, NodeKind.TEXT, init));
        return new Node(id, sink);
    }

    private Node create(NodeKind kind, Direction dir) {
        long id = ids.getAndIncrement();
        Map<PropKey, Object> init = new EnumMap<>(PropKey.class);
        if (dir != null) {
            init.put(PropKey.DIRECTION, dir);
        }
        sink.post(new Mutation.Create(id, kind, init));
        return new Node(id, sink);
    }

    /** Run {@code work} on a worker thread (app logic stays off the GUI thread). */
    public void async(Runnable work) {
        workers.submit(work);
    }

    /** Buffer a group of edits and post them as one atomic {@link Mutation.Batch}. */
    public void batch(Runnable edits) {
        // For now edits post individually (already atomic per-frame within a drain); a true buffering Batch is a
        // small follow-up. Kept as the API seam so call sites are stable.
        edits.run();
    }

    /**
     * Drain + reconcile + (re)layout for one frame; returns the retained root to render (may be null on the very
     * first call before the root's Create is drained — it won't be, since we drain first). {@code tm} supplies
     * text intrinsic sizes.
     */
    public RetainedNode frame(float viewportW, float viewportH, TextMeasurer tm) {
        // Dispatch this frame's input first, against the previous frame's laid-out tree (§8, §10): a click may
        // register a mutation, which the drain below then applies in the same frame.
        input.dispatch(reconciler.root());
        // Drain the mutation pump on the GUI thread: the subscriber applies each Mutation to the reconciler in
        // FIFO order (single writer). The tree is up to date afterward.
        pump.drain();
        RetainedNode r = reconciler.root();
        boolean viewportChanged = viewportW != lastViewportW || viewportH != lastViewportH;
        if (viewportChanged) {
            // Publish the new size on the bus (coalesced State) before relaying out, so observers and the layout
            // see the same value this frame.
            viewport.commit(setViewport, new Viewport(Math.round(viewportW), Math.round(viewportH)));
        }
        if (r != null && (reconciler.layoutDirty() || viewportChanged)) {
            FlexLayout.layout(r, viewportW, viewportH, LayoutContext.of(viewportW, viewportH), tm);
            reconciler.clearDirty();
            lastViewportW = viewportW;
            lastViewportH = viewportH;
        }
        return r;
    }

    @Override
    public void close() {
        input.close();
        mutationSub.close();
        workers.shutdownNow();
    }
}
