package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.FlexLayout;
import dev.vexelray.gui.core.layout.LayoutContext;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutSnapshot;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.Mutation;
import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.Reconciler;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.KeyRouted;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.FocusEvent;
import dev.vexelray.gui.core.input.InputDispatcher;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.text.TextMetrics;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import sibarum.atchung.Atchung;
import sibarum.atchung.Backpressure;
import sibarum.atchung.Committer;
import sibarum.atchung.Pump;
import sibarum.atchung.State;
import sibarum.atchung.Subscription;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
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

    /** Keyboard focus changes (gained/lost per node). */
    private static final Topic<FocusEvent> FOCUS = Topic.of("vexelray.gui.focus", FocusEvent.class);

    /** Every routed key press, whatever took it — the observation channel (see {@link KeyRouted}). */
    private static final Topic<KeyRouted> KEY_ROUTES = Topic.of("vexelray.gui.keys", KeyRouted.class);

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
    // Computed-layout read-model (docs/layout-read-model.md): the latest snapshot workers read via Node.layout(),
    // and the coalesced State observers subscribe to. Published after each layout pass.
    private final State<LayoutSnapshot> layoutState;
    private final Committer<LayoutSnapshot, LayoutSnapshot> setLayout;
    private volatile LayoutSnapshot latestLayout = LayoutSnapshot.EMPTY;
    private long layoutVersion;
    private final LayoutReader layoutReader = () -> latestLayout;
    private float lastViewportW = -1f;
    private float lastViewportH = -1f;
    private volatile TextClipboard clipboard = new TextClipboard.InMemory();
    private final Executor handlers;
    /**
     * Mutations buffered by an in-progress {@link #batch} on this thread, or null when not batching. Thread-local
     * because a batch is one thread's group of edits: two workers batching at once must not braid their ops into
     * each other's group, and per-producer FIFO already keeps each thread's own order.
     */
    private final ThreadLocal<List<Mutation>> batching = new ThreadLocal<>();
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
        this(bus, null);
    }

    /**
     * Create a GUI on a shared bus with an explicit executor for input handlers (clicks, keys, chars, drags,
     * state). Pass a same-thread executor ({@code Runnable::run}) for a <b>deterministic, headless</b> GUI —
     * input published on the bus is handled synchronously inside {@link #frame}, so a test (or any embedder) has
     * exact control over what fires and when, with no worker-thread races. {@code null} uses the default worker
     * pool (handlers run off the GUI thread), which is what a live application wants.
     */
    public Gui(Atchung bus, java.util.concurrent.Executor handlerExecutor) {
        this.bus = bus;
        this.pump = bus.pump();
        // Publisher seam for Node handles: every setter publishes a Mutation onto the bus from any thread — unless
        // this thread is inside a batch, in which case the op joins the group and the whole group publishes once.
        this.sink = m -> {
            List<Mutation> group = batching.get();
            if (group != null) {
                group.add(m);
            } else {
                bus.publish(MUTATIONS, m);
            }
        };

        long rootId = ids.getAndIncrement();
        // A node leaving the tree releases everything keyed by its id. Without this, a removed node kept its
        // handlers, its claims and — if it had it — focus, so a deleted focused editor went on preempting the
        // chords it claimed forever.
        this.reconciler = new Reconciler(rootId, this::releaseNodeId);
        // The GUI thread drains this pump each frame; the subscriber runs on that (drain) thread, so applying to
        // the single-writer reconciler here is the GUI-thread write the model requires.
        this.mutationSub = pump.subscribe(MUTATIONS, reconciler::apply, MUTATION_MAILBOX, Backpressure.BLOCK);
        // Framework input dispatch on the same bus; click handlers run on the worker executor (off the GUI thread).
        // Wheel scrolling mutates scroll offsets on the GUI thread and asks for a relayout next frame.
        this.handlers = handlerExecutor != null ? handlerExecutor : workers;
        this.input = new InputDispatcher(bus, CLICKS, handlers, reconciler::markLayoutDirty);
        this.input.focusTopic(FOCUS);
        this.input.keyRoutedTopic(KEY_ROUTES);

        // Window size as a coalesced, latest-wins State on the bus — the framework relays out from it and workers
        // can observe resizes without coupling to the window.
        State.Builder<Viewport> vb = State.of(new Viewport(0, 0));
        this.setViewport = vb.mutation("set", (current, next) -> next);
        this.viewport = vb.build();

        // Computed-layout read-model as a coalesced State (mirrors the viewport State): published on change.
        State.Builder<LayoutSnapshot> lb = State.of(LayoutSnapshot.EMPTY);
        this.setLayout = lb.mutation("set", (current, next) -> next);
        this.layoutState = lb.build();

        Map<PropKey, Object> init = new EnumMap<>(PropKey.class);
        init.put(PropKey.DIRECTION, Direction.COLUMN);
        init.put(PropKey.WIDTH, Length.FILL);
        init.put(PropKey.HEIGHT, Length.FILL);
        sink.post(new Mutation.Create(rootId, NodeKind.BOX, init));
        this.root = new Node(rootId, sink, layoutReader);
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
     * The computed-layout read-model as a coalesced {@code State} (docs/layout-read-model.md): every node's
     * position/size/scroll after layout, republished per changed frame. Subscribe to react to layout changes, or
     * read a node's own via {@link Node#layout()}. The value is one frame stale (the framework's input latency).
     */
    public State<LayoutSnapshot> layout() {
        return layoutState;
    }

    /** The latest computed-layout snapshot (never null). Backs {@link Node#layout()}; also useful to tests/tools. */
    public LayoutSnapshot layoutSnapshot() {
        return latestLayout;
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

    /**
     * Register a key handler for {@code node} (which becomes focusable). Fires with each key press while the node
     * holds focus, after shortcuts and Tab traversal have had first refusal. Runs on a worker thread.
     */
    public Gui onKey(Node node, java.util.function.Consumer<KeyEvent> handler) {
        input.onKey(node.id(), handler);
        return this;
    }

    /**
     * Register a typed-text handler for {@code node} (which becomes focusable and, by convention, editable).
     * Fires once per Unicode code point typed while the node holds focus — the text channel, delivered from
     * {@code CharTyped}, kept separate from {@link #onKey} (caret motion, backspace, shortcuts). Runs on a
     * worker thread.
     */
    public Gui onChar(Node node, java.util.function.IntConsumer handler) {
        input.onChar(node.id(), handler);
        return this;
    }

    // --- ordered stages (§5): the GUI-thread variants of the seams above ---

    /**
     * Register {@code node}'s typed-text <b>stage</b>: like {@link #onChar}, but run on the GUI thread during the
     * frame's input drain, <b>in arrival order</b>.
     *
     * <p>Use this — not {@link #onChar} — when the handler performs a state transition that depends on the order
     * events arrived in. The worker executor orders the invocations but not their effects: a burst of typed
     * characters handed to a pool one task at a time can apply scrambled, and because each individual edit is
     * coherent, the result reads as a dropped keystroke rather than a race. Everything without an ordering
     * requirement — application logic, notifications, anything slow — belongs on {@link #onChar}, where it still
     * cannot stall rendering.
     *
     * <p>A stage runs inline on the frame loop, so it must be bounded model work (splice a document, move a
     * caret) and never application logic or I/O. That is the same contract the reconciler runs under.
     */
    public Gui onCharUi(Node node, java.util.function.IntConsumer stage) {
        input.onCharUi(node.id(), stage);
        return this;
    }

    /** Register {@code node}'s key-command stage — the ordered counterpart of {@link #onKey}. */
    public Gui onKeyUi(Node node, java.util.function.Consumer<KeyEvent> stage) {
        input.onKeyUi(node.id(), stage);
        return this;
    }

    /** Register {@code node}'s drag stage — the ordered counterpart of {@link #onDrag}. */
    public Gui onDragUi(Node node, java.util.function.Consumer<DragEvent> stage) {
        input.onDragUi(node.id(), stage);
        return this;
    }

    /**
     * Claim {@code chord} for {@code node}, running the command on the GUI thread in arrival order — the ordered
     * counterpart of {@link #claim}, for a claim whose command edits state that typing also edits.
     */
    public Gui claimUi(Node node, Shortcut chord, ClaimScope scope, Runnable command) {
        input.claimUi(node.id(), chord, scope, command);
        return this;
    }

    /**
     * Drop every input registration {@code node} holds — handlers, ordered stages, claims, focusability — and
     * clear focus if it held it. Removing a node does this automatically; this is for releasing a node's
     * registrations while keeping the node.
     */
    public Gui releaseNode(Node node) {
        releaseNodeId(node.id());
        return this;
    }

    /** The reconciler's removal seam: everything keyed by a node id is dropped when the node leaves the tree. */
    private void releaseNodeId(long id) {
        input.clearHandlers(id);
    }

    /** Make {@code node} focusable (reachable by click and Tab) without a key handler — e.g. a button. */
    public Gui focusable(Node node, boolean canFocus) {
        input.setFocusable(node.id(), canFocus);
        return this;
    }

    /** Move keyboard focus to {@code node}. */
    public Gui focus(Node node) {
        input.focus(node.id());
        return this;
    }

    /** Register a global keyboard shortcut. The command runs on a worker thread. */
    public Gui shortcut(Shortcut shortcut, Runnable command) {
        input.registerShortcut(shortcut, command);
        return this;
    }

    /** Convenience: {@code gui.shortcut(Key.S, save, Modifier.CONTROL)}. */
    public Gui shortcut(Key key, Runnable command, Modifier... mods) {
        return shortcut(Shortcut.of(key, mods), command);
    }

    /**
     * Claim {@code chord} for {@code node} at {@code scope} — preemption declared in advance. While the claim
     * applies, that chord runs {@code command} and reaches nothing else: not the focused node's key handler, not
     * another element's claim at a broader scope, not the framework's own defaults (which are themselves
     * {@link ClaimScope#GLOBAL} claims, so a focused element outranks them).
     *
     * <p>This is what the framework offers in place of {@code preventDefault}, which it cannot: cancelling
     * requires a handler to answer synchronously, and handlers here run on worker threads. Declaring the
     * preemption up front lets the dispatcher decide on the GUI thread while the command runs wherever.
     */
    public Gui claim(Node node, Shortcut chord, ClaimScope scope, Runnable command) {
        input.claim(node.id(), chord, scope, command);
        return this;
    }

    /** Drop every claim {@code node} holds on {@code chord}. */
    public Gui releaseClaim(Node node, Shortcut chord) {
        input.releaseClaim(node.id(), chord);
        return this;
    }

    /**
     * Every key press and what became of it — which node had focus, and whether a claim preempted delivery.
     * Fires for <b>all</b> keys including ones the framework or a claim handled, so an extension can see keys it
     * would otherwise never be told about. Observation only: subscribing cannot cancel or redirect anything.
     */
    public Topic<KeyRouted> keyRoutes() {
        return KEY_ROUTES;
    }

    /** The focus-change topic: {@code gui.bus().subscribe(gui.focusEvents(), ...)}. */
    public Topic<FocusEvent> focusEvents() {
        return FOCUS;
    }

    /**
     * Install the sink notified when the desired pointer cursor changes (§8.3) — e.g. the I-beam over editable
     * text. The application maps {@link CursorShape} onto its window's cursor. Called on the GUI thread.
     */
    public Gui onCursorChange(java.util.function.Consumer<CursorShape> sink) {
        input.cursorSink(sink);
        return this;
    }

    /** Install the clipboard implementation text widgets use (default: an in-memory, process-local one). */
    public Gui clipboard(TextClipboard clipboard) {
        this.clipboard = clipboard == null ? new TextClipboard.InMemory() : clipboard;
        return this;
    }

    /** The clipboard text widgets read/write (cut/copy/paste). Never null. */
    public TextClipboard clipboard() {
        return clipboard;
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
        return new Node(id, sink, layoutReader);
    }

    private Node create(NodeKind kind, Direction dir) {
        long id = ids.getAndIncrement();
        Map<PropKey, Object> init = new EnumMap<>(PropKey.class);
        if (dir != null) {
            init.put(PropKey.DIRECTION, dir);
        }
        sink.post(new Mutation.Create(id, kind, init));
        return new Node(id, sink, layoutReader);
    }

    /** Run {@code work} on a worker thread (app logic stays off the GUI thread). */
    public void async(Runnable work) {
        workers.submit(work);
    }

    /**
     * The executor application handlers run on — the same lane clicks, keys and drags are delivered through, so a
     * widget dispatching its own app-facing callbacks here gives them identical semantics (and identical
     * determinism under a test harness that injected a same-thread executor).
     */
    public Executor handlers() {
        return handlers;
    }

    /**
     * Buffer a group of edits and post them as one atomic {@link Mutation.Batch} — one publish, so no frame
     * boundary can split the group and no drain can observe it half-applied (§4).
     *
     * <p>Posting the ops individually is <em>not</em> equivalent, which is what this used to do. A drain applies
     * everything queued, so a single producer's edits usually land together by luck; but the drain runs
     * concurrently with the producer, and a frame that fires midway through the group renders the half of it that
     * had been published. Batching removes the luck.
     *
     * <p>Nested calls join the enclosing batch rather than publishing early, so a helper that batches internally
     * composes into a caller's larger group.
     */
    public void batch(Runnable edits) {
        if (batching.get() != null) {
            edits.run();   // nested: the ops join the outer group, which publishes them
            return;
        }
        List<Mutation> group = new ArrayList<>();
        batching.set(group);
        try {
            edits.run();
        } finally {
            batching.remove();
        }
        if (!group.isEmpty()) {
            bus.publish(MUTATIONS, new Mutation.Batch(List.copyOf(group)));
        }
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
        if (r != null) {
            boolean layoutRan = reconciler.layoutDirty() || viewportChanged;
            if (layoutRan) {
                FlexLayout.layout(r, viewportW, viewportH, LayoutContext.of(viewportW, viewportH), tm);
                lastViewportW = viewportW;
                lastViewportH = viewportH;
            }
            // The compute phase (docs/layout-read-model.md §2.1): resolve everything that is a pure function of the
            // laid-out tree — caret-follow scroll, text metrics — then publish. It runs whenever the geometry could
            // have moved, which includes a caret move that reflows nothing, and it runs in *every* host: this is
            // what makes a field behave identically headless, on screen, and over the wire. Static frames do
            // neither, so the coalesced State still commits only on change.
            if (layoutRan || reconciler.geometryDirty()) {
                resolveGeometry(r, tm);
                publishLayout(r);
            }
            reconciler.clearDirty();
        }
        return r;
    }

    /**
     * The compute phase: walk the laid-out tree and write each node's derived geometry onto it. Runs on the GUI
     * thread, after layout and before publish, and is the <b>only</b> stage allowed to compute it — publish copies,
     * renderers and widgets read (docs/layout-read-model.md §2.1).
     */
    private static void resolveGeometry(RetainedNode n, TextMeasurer tm) {
        if (n.kind == NodeKind.TEXT) {
            resolveTextGeometry(n, tm);
        }
        for (RetainedNode c : n.children) {
            resolveGeometry(c, tm);
        }
    }

    /**
     * Resolve one text node's scroll and caret geometry. Scroll is narrowed first — an editable field keeps its
     * caret in view, then clamps to the content — and the caret x positions are baked afterwards <em>with that
     * scroll applied</em>, so the metrics a widget reads describe exactly what the renderer draws. Getting that
     * order wrong is what made click-to-caret miss in a scrolled field (CaretScrollTest).
     */
    private static void resolveTextGeometry(RetainedNode n, TextMeasurer tm) {
        n.textMetrics = null;
        String s = n.textString();
        if (s == null || s.isEmpty()) {
            n.scrollX = 0f;   // a field emptied after scrolling must snap back to its origin
            n.scrollY = 0f;
            return;
        }
        float px = n.textSizePx;
        float[] adv = tm.caretAdvances(s, px);
        if (adv == null) {
            return;           // a measurer with no glyph metrics (an atlas-less stub) — nothing to resolve
        }
        // The text-area viewport the layout resolved for this node (FlexLayout.layoutTextLeaf). It is already
        // inset by the padding and already excludes whatever the scrollbars reserved, so scroll offsets, thumb
        // geometry and caret metrics are all expressed against the one rectangle.
        float viewW = n.viewW > 0f ? n.viewW : TextMetrics.contentWidth(n);
        float viewH = Math.max(1f, n.viewH > 0f ? n.viewH : n.h - 2f * TextMetrics.padY(n));
        float lineH = tm.intrinsic(n, Axis.VERTICAL, px);
        boolean multiline = n.multiline();
        boolean wraps = n.wrapsText();
        // The breaks the layout already computed at the width it settled on — read, never recomputed, so the line
        // count the box was sized for and the lines drawn into it are the same object. The fallback covers a node
        // the layout has not reached yet.
        List<dev.vexelray.text.TextLayout.LineSpan> spans =
                n.lineSpans != null ? n.lineSpans : tm.lineSpans(s, wraps ? viewW : 0f, px);

        // Where the caret sits, in line-relative terms: everything below is expressed against this.
        int caret = n.caret();
        int caretLine = caret < 0 ? 0 : lineIndexOf(spans, caret);

        if (n.editable()) {
            resolveTextScroll(n, adv, spans, caret, caretLine, lineH, viewW, viewH, wraps, multiline);
        }

        // Bake absolute geometry. A multiline node tops out (a growing document grows downward); everything else
        // centres its text *block* in the box — the whole block, not one line, or a label the layout sized for
        // three wrapped lines would draw them starting a line down and spill out the bottom.
        float viewX = n.viewW > 0f ? n.viewX : n.x + n.textPadXPx;
        float viewY = n.viewW > 0f ? n.viewY : n.y + TextMetrics.padY(n);
        float contentLeft = viewX - n.scrollX;
        // Vertical placement of the whole block. A multiline node tops out and scrolls (a growing document grows
        // downward); everything else honours the node's vAlign against the *block*, not one line — a label the
        // layout sized for three wrapped rows must not place them as though there were one.
        float blockH = spans.size() * lineH;
        float contentTop = multiline ? viewY - n.scrollY : viewY + switch (n.vAlign()) {
            case TOP -> 0f;
            case MIDDLE -> Math.max(0f, (viewH - blockH) * 0.5f);
            case BOTTOM -> Math.max(0f, viewH - blockH);
        };
        List<TextMetrics.VisualLine> lines = new ArrayList<>(spans.size());
        // Hard-line numbering: a visual line gets a number only when it *begins* a hard line — the first one, or
        // one whose predecessor ended at a '\n'. Wrapped continuations get 0 and draw no number.
        int hardLine = 1;
        for (int i = 0; i < spans.size(); i++) {
            var span = spans.get(i);
            // Horizontal placement, per line: a centred or right-aligned node indents each row by its own slack.
            // Baking it here is what lets a label be *read* correctly — before this, the published xs described a
            // left-aligned line while the renderer drew a centred one, so nothing but the renderer could trust
            // the geometry. A line wider than the view has no slack, so it pins to the left and scrolls.
            float slack = Math.max(0f, viewW - (adv[span.end()] - adv[span.start()]));
            float indent = switch (n.hAlign()) {
                case LEFT -> 0f;
                case CENTER -> slack * 0.5f;
                case RIGHT -> slack;
                // Real justification stretches the gaps *within* a line, so it cannot be an indent — it would
                // have to be baked into xs per word. Not modelled, so it starts at the left rather than
                // pretending: better a known-left line than geometry that lies about where the glyphs are.
                case JUSTIFY -> 0f;
            };
            float[] xs = new float[span.end() - span.start() + 1];
            for (int j = 0; j < xs.length; j++) {
                xs[j] = contentLeft + indent + (adv[span.start() + j] - adv[span.start()]);
            }
            boolean startsHardLine = i == 0 || spans.get(i - 1).hardBreak();
            int number = startsHardLine ? hardLine++ : 0;
            lines.add(new TextMetrics.VisualLine(span.start(), span.end(), contentTop + i * lineH, lineH, xs,
                    number));
        }
        n.textMetrics = new TextMetrics(lines);
    }

    /**
     * Narrow an editable node's scroll so the caret stays in view (docs/layout-read-model.md §2.2, §11.3 step 4).
     * A wrapped node never scrolls horizontally — there is nothing to the right to reach — and a single-line node
     * never scrolls vertically.
     */
    private static void resolveTextScroll(RetainedNode n, float[] adv,
                                          List<dev.vexelray.text.TextLayout.LineSpan> spans, int caret,
                                          int caretLine, float lineH, float viewW, float viewH,
                                          boolean wraps, boolean multiline) {
        // Follow the caret only when it has *moved*. A field that reports overflow is wheel- and drag-scrollable
        // like any other scroller, so following every frame would drag the view back to the caret the instant the
        // user scrolled away from it. Clamping still runs unconditionally.
        boolean caretMoved = caret != n.caretFollowed;
        n.caretFollowed = caret;

        if (wraps) {
            n.scrollX = 0f;
        } else {
            if (caret >= 0 && caretMoved) {
                var line = spans.get(caretLine);
                float caretRel = adv[clamp(caret, line.start(), line.end())] - adv[line.start()];
                if (caretRel - n.scrollX > viewW) {
                    n.scrollX = caretRel - viewW;
                }
                if (caretRel - n.scrollX < 0f) {
                    n.scrollX = caretRel;
                }
            }
            float widest = 0f;
            for (var line : spans) {
                widest = Math.max(widest, adv[line.end()] - adv[line.start()]);
            }
            n.scrollX = Math.max(0f, Math.min(n.scrollX, Math.max(0f, widest - viewW)));
        }

        if (!multiline) {
            n.scrollY = 0f;
            return;
        }
        if (caret >= 0 && caretMoved) {
            float caretTop = caretLine * lineH;
            if (caretTop + lineH - n.scrollY > viewH) {
                n.scrollY = caretTop + lineH - viewH;
            }
            if (caretTop - n.scrollY < 0f) {
                n.scrollY = caretTop;
            }
        }
        n.scrollY = Math.max(0f, Math.min(n.scrollY, Math.max(0f, spans.size() * lineH - viewH)));
    }

    /** The visual line containing {@code offset}: the last span whose start is at or before it. */
    private static int lineIndexOf(List<dev.vexelray.text.TextLayout.LineSpan> spans, int offset) {
        int found = 0;
        for (int i = 0; i < spans.size(); i++) {
            if (spans.get(i).start() <= offset) {
                found = i;
            } else {
                break;
            }
        }
        return found;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** Copy the resolved tree into an immutable {@link LayoutSnapshot} and publish it. A pure copy — no arithmetic. */
    private void publishLayout(RetainedNode root) {
        Map<Long, NodeLayout> nodes = new HashMap<>();
        collectLayout(root, nodes);
        LayoutSnapshot snap = new LayoutSnapshot(++layoutVersion, nodes);
        latestLayout = snap;             // volatile: Node.layout() reads this lock-free from any thread
        layoutState.commit(setLayout, snap);
    }

    private static void collectLayout(RetainedNode n, Map<Long, NodeLayout> out) {
        out.put(n.id, new NodeLayout(true,
                new Rect(n.x, n.y, n.w, n.h),
                new Rect(n.viewX, n.viewY, n.viewW, n.viewH),
                n.scrollX, n.scrollY, n.contentW, n.contentH, n.overflowX, n.overflowY, n.textSizePx,
                n.textMetrics));
        for (RetainedNode c : n.children) {
            collectLayout(c, out);
        }
    }

    @Override
    public void close() {
        input.close();
        mutationSub.close();
        workers.shutdownNow();
    }
}
