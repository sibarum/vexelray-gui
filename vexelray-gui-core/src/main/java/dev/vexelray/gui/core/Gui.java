package dev.vexelray.gui.core;

import dev.vexelray.gui.core.drop.DragSession;
import dev.vexelray.gui.core.drop.DragSource;
import dev.vexelray.gui.core.drop.DragState;
import dev.vexelray.gui.core.drop.Transfer;
import dev.vexelray.gui.core.drop.DropTarget;
import dev.vexelray.gui.core.layout.Clip;
import dev.vexelray.gui.core.layout.Displacement;
import dev.vexelray.gui.core.layout.FlexLayout;
import dev.vexelray.gui.core.layout.LayoutContext;
import dev.vexelray.gui.core.layout.Metrics;
import dev.vexelray.gui.core.layout.ReadModels;
import dev.vexelray.gui.core.layout.LayoutMotion;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutSnapshot;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.SemanticSnapshot;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.Mutation;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.Reconciler;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.KeyRouted;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.FocusEvent;
import dev.vexelray.gui.core.input.InputDispatcher;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.input.MenuPresenter;
import dev.vexelray.gui.core.input.MenuSink;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.model.SemanticNode;
import dev.vexelray.gui.core.nav.Address;
import dev.vexelray.gui.core.nav.NavTopics;
import dev.vexelray.gui.core.nav.Navigation;
import dev.vexelray.gui.core.nav.Navigator;
import dev.vexelray.gui.core.nav.Reveal;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.core.text.TextMetrics;
import sibarum.probe.Lane;
import sibarum.probe.Probe;
import sibarum.probe.Zone;
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

    /**
     * Mailbox bound for the mutation pump, in <b>cells</b> rather than in edits — the channel folds, so this
     * counts how much of the tree has changed since the last frame and not how many times it was written.
     *
     * <p>{@code BLOCK} makes it a throttle rather than a drop threshold, and folding is what makes that safe.
     * A producer can no longer reach the bound by writing quickly, only by touching this many distinct
     * properties and structural edits between two frames — which in practice means building a very large tree
     * in one go, and a build that briefly outruns the frame loop <em>should</em> be slowed rather than failed.
     * (Wrapping such a build in {@link #batch} makes it one slot regardless.)
     */
    private static final int MUTATION_MAILBOX = 1 << 16;

    /** Framework click events, resolved from raw input by the dispatcher; workers subscribe here. */
    private static final Topic<ClickEvent> CLICKS = Topic.of("vexelray.gui.clicks", ClickEvent.class);

    /** Keyboard focus changes (gained/lost per node). */
    private static final Topic<FocusEvent> FOCUS = Topic.of("vexelray.gui.focus", FocusEvent.class);

    /**
     * How many frames a {@link #navigate} may spend before it gives up. Counted in frames rather than
     * milliseconds so a headless test driving frames by hand fails at the same point a running application does
     * — and generously, because every legitimate step (a tab selecting, a branch expanding, a transition
     * settling) is one frame, and no realistic destination is hundreds of containers deep.
     */
    private static final int MAX_NAV_FRAMES = 240;

    /** Every routed key press, whatever took it — the observation channel (see {@link KeyRouted}). */
    private static final Topic<KeyRouted> KEY_ROUTES = Topic.of("vexelray.gui.keys", KeyRouted.class);

    /** Where nodes come from: the id counter, the publisher seam, and the batch. See {@link Trees}. */
    private final Trees trees;
    private final Atchung bus;
    private final MutationSink sink;
    private final Pump pump;

    // Wakes are traced through Probe on the FRAME lane -- `-Dprobe=frame -Dprobe.trace=true`, or
    // `-Dprobe.format=csv` for the correlation log. There was a bespoke flag here writing to System.out, which
    // meant the two facts a stall is diagnosed from -- that the loop parked, and what woke it -- were being
    // written to two different streams with two different buffers. That is precisely the interleaving problem
    // one writer exists to avoid, so it now goes where everything else goes (docs/reference/automation.md §4).

    /** The unset {@link #workListener}. Held by identity so an unwired GUI is answerable. */
    private static final Runnable NO_WAKE = () -> { };
    /** Told when a mutation is published, so a parked host loop knows a frame is owed. */
    private volatile Runnable workListener = NO_WAKE;
    /**
     * Whether the loop has already been told a frame is owed, and has not yet started one.
     *
     * <p><b>The wake is a cell, not an event</b> — the same reason the mutation mailbox folds, one layer up. A
     * second wake before the frame arrives says nothing the first did not: the loop either draws next or it does
     * not, and it is already going to. Left undeduplicated it was one nudge of the OS message queue <em>per
     * property write</em>, which is a syscall against a fold that had just reduced the write itself to a map
     * put — the cheap half optimised and the expensive half left alone.
     *
     * <p>Cleared at the very top of {@link #frame}, before the drain rather than after it. A write that lands
     * mid-frame then re-arms the wake and costs one extra frame that finds nothing to do; clearing at the end
     * would instead swallow that write's wake and leave it sitting until something unrelated woke the loop. Over
     * -waking is a wasted frame, under-waking is a UI that stops updating.
     */
    private final java.util.concurrent.atomic.AtomicBoolean woken = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean warnedWakeFailed;
    private volatile boolean tracedUnwired;
    private final Subscription mutationSub;
    /** Navigation requests off the bus, drained on the GUI thread with everything else (see {@link #navigate}). */
    private final Subscription navSub;
    /**
     * Named places in this tree, and taking the user to one — landmarks, revealers, the walks in flight, and the
     * key this window is addressed by. See {@link Navigator}, which holds all of it and asks this class for only
     * the three things a walk needs of the tree.
     */
    private final Navigator navigator;
    private final Reconciler reconciler;
    private final InputDispatcher input;
    private final Node root;
    /**
     * Size and scale: the window, the zoom, the density, and the minimums and limits that shape them. Every
     * {@link Length} resolves against what this holds, and {@link #frame} asks it once per pass — see
     * {@link Metrics}, which owns the remembered values the dirty-check compares against.
     */
    private final Metrics metrics = new Metrics();
    /** Where nodes are drawn relative to where layout put them; NONE until a motion source is attached. */
    private volatile LayoutMotion motion = LayoutMotion.NONE;
    /** What is held for a paste. See {@link #transfer()}. */
    private volatile dev.vexelray.gui.core.drop.Transfer transfer = dev.vexelray.gui.core.drop.Transfer.NONE;
    /** Where drops and pastes record; the same stack, because they are the same change made two ways. */
    private volatile History transferHistory;
    /** The live drag, as a coalesced State -- the read-model half of DragSession (docs/reference/layout-read-model.md). */
    private final State<DragState> dragState;
    private final Committer<DragState, DragState> setDrag;
    private DragState lastDrag = DragState.NONE;
    /** The modifiers held right now (see {@link #modifiers}). */
    private final State<java.util.Set<Modifier>> modifierState;
    private final Committer<java.util.Set<Modifier>, java.util.Set<Modifier>> setModifiers;
    /**
     * Both published views of the tree — where each node is, and what it is — computed and published together at
     * one version from one walk. See {@link ReadModels} for why the two cannot be separated, and for the
     * geometry observers that are delivered from the same pass.
     */
    private final ReadModels readModels;
    private final LayoutReader layoutReader;
    private volatile TextClipboard clipboard = new TextClipboard.InMemory();
    // The look. Not a State on the bus like zoom is: a Length resolves against zoom every frame, whereas a colour
    // is resolved once and written into a prop, so there is nothing per-frame to coalesce (see Theme's class note
    // on what that costs and where it is heading). Volatile because widgets read it from whichever thread built
    // them and from every state handler.
    private volatile Theme theme = Theme.DARK;
    private final Executor handlers;
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
     *
     * <p><b>One {@code Gui} per bus.</b> Share it with anything that is not a {@code Gui}; never with a second
     * one. This class's topics are {@code static} — {@code vexelray.gui.mutations} is one name for every
     * instance — so two trees on one bus each receive the other's mutations, and the mailbox they arrive in is
     * bounded at {@value #MUTATION_MAILBOX} with {@link sibarum.atchung.Backpressure#BLOCK}. A tree that is not
     * currently being presented never drains it, so the second {@code Gui} fills up and then <b>blocks the
     * first one's node setters for good</b>: an application that freezes after some tens of thousands of
     * edits, with no exception and nothing in a log.
     *
     * <p>Two windows means two buses today, and the cost is that a message cannot travel between them without
     * something bridging it. Making one bus carry every tree is a real goal and a real change — the topics
     * would have to be per instance rather than per class — and it is not this constructor.
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
        // Where nodes come from, and the seam their handles write through. It resolves Node.layout() through a
        // reader that is consulted lazily, which is what lets it be built before the read-model it will read —
        // a handle minted here does not ask about geometry until something reads one.
        this.trees = new Trees(bus, MUTATIONS, this::wake, this::publishedLayout);
        this.sink = trees.sink();
        long rootId = trees.rootId();
        // A node leaving the tree releases everything keyed by its id. Without this, a removed node kept its
        // handlers, its claims and — if it had it — focus, so a deleted focused editor went on preempting the
        // chords it claimed forever.
        this.reconciler = new Reconciler(rootId, this::releaseNodeId);
        // The GUI thread drains this pump each frame; the subscriber runs on that (drain) thread, so applying to
        // the single-writer reconciler here is the GUI-thread write the model requires.
        // Folded by cell (Mutation.cell): a property write supersedes the queued write to the same property of
        // the same node instead of queueing beside it, so this mailbox holds what has changed since the last
        // frame rather than every time somebody said so. A worker looping on node.text(...) occupies one slot.
        //
        // Lossless, and not approximately: nothing reads the retained tree between a publish and the drain that
        // applies it — the reconciler is written only here, on this thread, and every reader goes through the
        // published LayoutSnapshot instead. So everything published between two frames is one transaction whose
        // only observable is the state at its end, and a superseded write is unobservable in principle. That
        // condition is the whole licence for this and it is worth re-checking before anything is allowed to read
        // the model directly.
        this.mutationSub = pump.subscribe(MUTATIONS, reconciler::apply, MUTATION_MAILBOX, Backpressure.BLOCK,
                Mutation::cell);
        // Navigation requests arrive on the same pump, so a link clicked in another window, a macro step and a
        // test all enter this tree at the same point in the frame the tree's own edits do — before the drain,
        // never in the middle of one.
        // What a walk needs of the tree, and nothing more: look a node up, and issue the two commands it issues
        // through the ordinary public API a click would have gone through.
        this.navigator = new Navigator(new Navigator.Tree() {
            @Override
            public RetainedNode node(long id) {
                return reconciler.node(id);
            }

            @Override
            public void scrollIntoView(long id) {
                new Node(id, sink, layoutReader).scrollIntoView();
            }

            @Override
            public void focus(long id) {
                Gui.this.focus(new Node(id, sink, layoutReader));
            }

            @Override
            public Node arrived(long id) {
                return new Node(id, sink, layoutReader);
            }
        }, bus, this::wake);
        this.navSub = pump.subscribe(NavTopics.GO, navigator::accept, MUTATION_MAILBOX, Backpressure.BLOCK);
        // Framework input dispatch on the same bus; click handlers run on the worker executor (off the GUI thread).
        // Wheel scrolling mutates scroll offsets on the GUI thread and asks for a relayout next frame.
        // Every handler is followed by a wake, whatever it did.
        //
        // Mutating a node wakes the loop already, because the publish does it. But a handler is ordinary
        // application code and its effect is very often *neither* a mutation nor a clock operation: it
        // drops a request on one of the application's own queues — a history to restore, a file to open,
        // a preview to render — each drained once per frame from the host's beforeFrame hook. Nothing
        // about that is visible from here, and to a loop that parks it does not exist: the handler runs,
        // the queue fills, no frame is asked for, and the request is executed on whatever frame some
        // unrelated event eventually causes. The tell is that the *effect* is what arrives late, so an
        // animation the handler queued starts from the beginning whenever the frame finally comes,
        // rather than being found already in progress.
        //
        // So the wake is hung on the one thing every such path has in common: a handler ran. Waking after
        // it finishes covers all of them, including the ones an application has not written yet, and
        // costs one frame per input event that finds nothing left to do.
        Executor base = handlerExecutor != null ? handlerExecutor : workers;
        this.handlers = task -> base.execute(() -> {
            try {
                task.run();
            } finally {
                wake("handler returned");
            }
        });
        // Dirtying layout is a change like any other, and it is the one that publishes no Mutation: a
        // scroll offset moves, the tree has to be laid out again, and nothing on the bus says so. In a
        // loop that redrew unconditionally the distinction never mattered; in one that parks, a
        // relayout nobody asked a frame for is a tree that stays exactly as it was last drawn.
        this.input = new InputDispatcher(bus, CLICKS, handlers, () -> {
            reconciler.markLayoutDirty();
            wake("layout requested");
        });
        this.input.focusTopic(FOCUS);
        this.input.keyRoutedTopic(KEY_ROUTES);

        // The window size, zoom and density States are Metrics' own — built there, so that component can be
        // constructed and exercised without any of this.

        // Both read-models, and the two States they publish on. Separate States rather than extra fields on
        // NodeLayout: geometry republishes whenever a box moves, which is most frames of an animation, while
        // what a node *is* changes far less often — and a consumer of one rarely wants the other at that rate.
        // Same version on both is what keeps them joinable despite that, which is why one component owns both.
        //
        // What the tree cannot say about itself is handed over as a value: focus comes from the dispatcher and
        // landmark names from the map beside it, and ReadModels holds neither of those objects.
        this.readModels = new ReadModels(new ReadModels.Meanings() {
            @Override
            public boolean focusable(long id) {
                return input.isFocusable(id);
            }

            @Override
            public long focusedId() {
                return input.focusedId();
            }

            @Override
            public String landmarkName(long id) {
                return navigator.landmarkName(id);
            }
        }, handlers);
        this.layoutReader = readModels.reader();

        // The live drag, published the same way. Latest-wins is exactly right for it: a subscriber that missed
        // an intermediate position has missed nothing it could still have drawn.
        State.Builder<DragState> gb = State.of(DragState.NONE);
        this.setDrag = gb.mutation("set", (current, next) -> next);
        this.dragState = gb.build();

        // The modifiers held right now, published the same way, and a State rather than a topic for the same
        // reason: this is a *condition*, not an event. Nobody wants the edges — they want to know what is held.
        State.Builder<java.util.Set<Modifier>> mb = State.of(java.util.Set.<Modifier>of());
        this.setModifiers = mb.mutation("set", (held, next) -> next);
        this.modifierState = mb.build();
        input.onModifiers(held -> modifierState.commit(setModifiers, held));

        // The root is created here rather than when Trees was built: its id was needed before the subscription
        // existed, and its Create must be published after it, or it goes onto the bus with nobody listening.
        this.root = trees.createRoot();
    }

    /** The Atchung bus this GUI publishes mutations, events, and (via a bridge) input on. */
    public Atchung bus() {
        return bus;
    }

    /**
     * The live drag as a bus {@code State} — subscribe with {@code gui.drag().onCommit(...)} to draw a ghost or a
     * drop indicator. {@link DragState#NONE} whenever nothing is being dragged.
     *
     * <p>Published once per frame, on change only, so a still pointer over a settled indicator costs nothing.
     */
    public State<DragState> drag() {
        return dragState;
    }

    /** The live window size as a bus {@code State} — subscribe with {@code gui.viewport().onCommit(...)}. */
    /**
     * The modifier keys held right now, as a bus {@code State} — subscribe with
     * {@code gui.modifiers().onCommit(...)} to restyle while one is held, or read {@code .value()} inside a
     * handler that needs to know what a click meant.
     *
     * <p><b>Why this exists at all.</b> A modifier press is not a command and is routed nowhere: it arms the
     * chord the next key makes, and until now that was the whole of its life. But a modifier is also a
     * <em>mode</em> the user can see — hold Ctrl and the links in a document underline themselves — and a mode
     * with no observable state is one every widget would have to reconstruct from key edges of its own, which
     * is precisely the side channel this framework does not permit. It comes from the same tactroller edges as
     * everything else; this is where the framework stops keeping it to itself.
     *
     * <p>Committed on change only, and cleared when the window loses focus — a key released over another window
     * is never seen here, and a modifier that stays held for ever is a UI stuck in a mode with no way out.
     */
    public State<java.util.Set<Modifier>> modifiers() {
        return modifierState;
    }

    public State<Viewport> viewport() {
        return metrics.viewport();
    }

    /**
     * The user zoom factor as a bus {@code State} (1.0 = unscaled). Read it with {@code gui.zoom().value()} or
     * subscribe to observe changes.
     *
     * <p>Zoom is not a property of any node: it is one of the ambient factors every {@link Length} resolves
     * against ({@code em = v · rootEmPx · zoom · dpi}, §6), so changing it rescales the entire UI — box sizes,
     * padding, border, corner radius, text size, the scrollbar thickness, the text insets and the wheel step —
     * in one step and in proportion. That is the whole point of §6's "no pixel unit", and zooming is the cheapest
     * way to see whether it actually holds: anything that fails to move with the rest is still pinned to device
     * pixels.
     */
    public State<Float> zoom() {
        return metrics.zoom();
    }

    /**
     * Set the zoom factor, clamped to the configured range ({@link #zoomRange}; by default
     * 0.5 to 3). Safe from any thread — it commits
     * to the {@code State}, and the next {@link #frame} notices the change and relays out, so nothing writes to
     * the reconciler off the GUI thread.
     */
    public Gui zoom(float factor) {
        metrics.zoom(factor);
        return this;
    }

    /**
     * Set the zoom limits and the step {@link #zoomIn}/{@link #zoomOut} move by. The step is a <b>factor</b>, not
     * an increment: zoom is perceived as a ratio, so a fixed increment is a huge jump at the bottom of the range
     * and an imperceptible one at the top. 1.25 means each press is 25% more.
     *
     * <p>Re-clamps the current factor immediately, so narrowing the range cannot leave the UI outside it.
     */
    public Gui zoomRange(float min, float max, float step) {
        metrics.zoomRange(min, max, step);
        return this;
    }

    /** Zoom in one step, clamped to the configured maximum. */
    public Gui zoomIn() {
        metrics.zoomIn();
        return this;
    }

    /** Zoom out one step, clamped to the configured minimum. */
    public Gui zoomOut() {
        metrics.zoomOut();
        return this;
    }

    /** Back to 1.0 — unscaled. */
    public Gui resetZoom() {
        metrics.resetZoom();
        return this;
    }

    /**
     * The smallest canvas the UI is laid out on. When the window is smaller than this, the layout still runs at
     * the minimum and the window shows part of it, rather than the UI being squeezed into a size it cannot
     * represent — where a flexible region absorbs the whole deficit and disappears.
     *
     * <p><b>In {@code em}, the minimum scales with zoom</b>, which is the point: a zoomed UI genuinely needs more
     * room, so the smallest canvas it can be laid out on grows with it. That is what makes one setting cover both
     * a small window and a high zoom factor.
     *
     * <p>{@code vw}/{@code vh} resolve against this clamped canvas rather than the window, since that is the area
     * actually laid out. {@link #viewport()} continues to report the real window size.
     *
     * <p><b>Not an OS window minimum</b>, and the two are worth having separately. This one decides what the UI
     * is laid out on when the window is smaller than the UI can represent, and shows part of it. Stopping the
     * <em>drag</em> is {@code WindowConfig.minSize}, which the window manager enforces — and a window can still
     * arrive under its minimum without any drag (a restore, a display-mode change, a programmatic resize), which
     * is exactly when this one is what saves the layout. The overflow here is cropped rather than scrollable,
     * which wants the root to become a scroll viewport.
     */
    public Gui minSize(Length width, Length height) {
        metrics.minSize(width, height);
        return this;
    }

    /**
     * How thick a resize grip the window manager gets inside each edge of the window, over the parts of it this
     * GUI declares nothing about — its outer margin and padding.
     *
     * <p><b>What it is for.</b> A window drawn with a generous gutter has a ring of pixels around its content
     * that exists to be looked at and nothing else. The system frame's own grip is two or three pixels, so the
     * user aims at a hairline while a whole margin of dead space sits right beside it. Declaring the gutter here
     * spends it: the pointer becomes a resize pointer as soon as it enters the margin, and the edge is as easy to
     * hit as the margin is wide. Set it to the padding the root actually has, in the same {@link Length} — the
     * two then scale together and cannot drift apart.
     *
     * <p><b>It only widens dead space.</b> Where the tree declares a {@link WindowRegion} — a title bar, a button
     * drawn on one — the platform's own thin band still applies, so a bar keeps its drag surface and a close
     * button keeps its corner. Nothing the GUI declares loses a pixel to this.
     *
     * <p><b>It does not know what you drew.</b> The framework cannot tell a margin from content that happens to
     * reach the window edge: this is a promise that the outer {@code thickness} of the window is yours to give
     * away. A root with no padding that declares one hands the window manager the first {@code thickness} of its
     * content, and clicks there stop arriving.
     *
     * <p>{@link Length#ZERO} (the default) leaves the platform's own metric everywhere, which is what a window
     * whose content runs to its edges wants.
     */
    public Gui resizeBorder(Length thickness) {
        metrics.resizeBorder(thickness);
        return this;
    }

    /**
     * {@link #resizeBorder} in pixels, as of the last {@link #frame} — resolved there so it answers for the same
     * zoom and density that frame was laid out at. {@code 0} means "the platform's own".
     */
    public int resizeBorderPx() {
        return metrics.resizeBorderPx();
    }

    /**
     * The display density as a bus {@code State} — the points-to-pixels ratio of the surface being drawn to
     * (1.0 on a conventional display, 2.0 on a Retina-class one).
     *
     * <p>Distinct from {@link #zoom} on purpose, even though both currently multiply into the same em. Density
     * keeps a UI the same <em>physical</em> size on a denser screen; zoom is the user asking for bigger. A length
     * can sensibly honour one and not the other, and that is only expressible while the two stay separate
     * factors.
     */
    public State<Float> dpi() {
        return metrics.dpi();
    }

    /**
     * The root em in pixels before zoom and DPI — the flat basis every {@link Length#em} resolves against
     * ({@code em = v · rootEmPx · zoom · dpi}, §6). A constant today, and exposed rather than the whole
     * {@link dev.vexelray.gui.core.layout.LayoutContext}, which is viewport-dependent and would couple a caller
     * to a layout type.
     *
     * <p>Not a {@code State}, unlike {@link #zoom()} and {@link #dpi()}, because nothing can change it. If it ever
     * becomes settable it becomes a {@code State} like the other two, and a caller that already subscribes to
     * those has somewhere obvious to add it.
     *
     * <p><b>Who needs it:</b> anything that must solve in pixels before handing coordinates back as
     * {@code Length}s. {@code vexelray-gui-typeset} is the case that asked for it — its tone map has a physical
     * legibility floor, so it cannot work in em and defer (docs/reference/typeset.md §4.3).
     */
    public float rootEmPx() {
        return metrics.rootEmPx();
    }

    /**
     * Set the display density (see {@link #dpi()}), clamped to the range {@link Metrics} declares.
     *
     * <p><b>Feed this from the real surface scale, and lay out in the same space input arrives in.</b> The
     * framework lays out in whatever viewport {@link #frame} is handed and hit-tests input against those rects,
     * so the two must be the same coordinate space. On a Retina-class display, points and pixels differ by this
     * factor: lay out in framebuffer pixels and deliver input in {@code CLIENT} (point) space and every hit test
     * is out by it. On a 1:1 display the two spaces coincide, which is exactly why the mismatch survives
     * development on such a machine and surfaces on the first dense one.
     */
    public Gui dpi(float scale) {
        metrics.dpi(scale);
        return this;
    }

    /**
     * The computed-layout read-model as a coalesced {@code State} (docs/reference/layout-read-model.md): every node's
     * position/size/scroll after layout, republished per changed frame. Subscribe to react to layout changes, or
     * read a node's own via {@link Node#layout()}. The value is one frame stale (the framework's input latency).
     */
    public State<LayoutSnapshot> layout() {
        return readModels.layout();
    }

    /**
     * The semantic read-model as a coalesced {@code State} (docs/reference/automation.md §3): what every node <b>is</b> —
     * role, name, structure, focus — published on the same frames and at the same {@code version} as
     * {@link #layout()}, so the two join by node id and by frame.
     *
     * <p>Exists because {@link dev.vexelray.gui.core.model.NodeKind} is {@code BOX} or {@code TEXT}: enough for
     * the renderer, and an anonymous tree to everyone else. A consumer that must address a node by meaning — an
     * automation agent, a thin client — reads this.
     */
    public State<SemanticSnapshot> semantics() {
        return readModels.semantics();
    }

    /** The latest semantic snapshot (never null; {@link SemanticSnapshot#EMPTY} before the first layout). */
    public SemanticSnapshot semanticSnapshot() {
        return readModels.semanticSnapshot();
    }

    /** The latest computed-layout snapshot (never null). Backs {@link Node#layout()}; also useful to tests/tools. */
    public LayoutSnapshot layoutSnapshot() {
        return readModels.layoutSnapshot();
    }

    /**
     * Register a click handler for {@code node} — fired when a left press and release land on it (bubbling to the
     * nearest ancestor with a handler). Runs on a worker thread, so it may freely mutate the tree via handles.
     */
    public Gui onClick(Node node, Runnable handler) {
        input.onClick(node.id(), handler);
        return this;
    }

    /**
     * Register a click handler that is told <b>what the click meant</b>: the {@link ClickEvent} carries the
     * modifiers held when the button was released, so Ctrl-click and Shift-click can be told apart from a click.
     *
     * <p>Without this a widget driving a selection would have to read keyboard state from somewhere else at the
     * moment a pointer handler runs — a second channel alongside the event, and one that is already wrong if the
     * key came up while the handler was queued. The modifiers were always part of the click (see
     * {@link ClickEvent}); this hands them to the left-button handler as {@link #onContextClick} already does to
     * the right-button one. Runs on a worker thread, like the plain form.
     */
    public Gui onClick(Node node, java.util.function.Consumer<ClickEvent> handler) {
        input.onClick(node.id(), handler);
        return this;
    }

    /**
     * Register a context-click handler for {@code node} — fired when a <em>right</em> press and release land on it
     * (bubbling to the nearest ancestor with a handler). The handler receives the {@link ClickEvent} because a
     * context action almost always needs the pointer position, to anchor a menu at it. A right click changes
     * nothing else — no focus move, no PRESSED state, no drag capture; what it means is the widget's decision.
     * Runs on a worker thread, so it may freely mutate the tree via handles.
     */
    public Gui onContextClick(Node node, java.util.function.Consumer<ClickEvent> handler) {
        input.onContextClick(node.id(), handler);
        return this;
    }

    /**
     * Give {@code node} a context menu: {@code source} is asked what should be on it at the moment of each right
     * click, and the answer is shown by the installed {@link MenuPresenter}.
     *
     * {@snippet :
     * gui.onContextMenu(row, menu -> menu
     *         .item("Open", () -> open(path))
     *         .item("Paste", clipboard.hasText(), () -> paste(path)));   // shown, greyed when it cannot apply
     * }
     *
     * <p><b>The nearest node with a menu owns the click</b> — leaf→root, the same rule a click handler follows. So
     * a row inside a panel inside a page may each declare one and the innermost thing the user pointed at answers,
     * with none of them knowing the others exist.
     *
     * <p><b>Sources accumulate</b>, like {@link #onState} observers: a widget declares its own defaults (a text
     * field's Copy/Cut/Paste) and the application adds what only it knows, in registration order, into one menu.
     * That is why the menu is built per click rather than held: its contents are a fact about the current state,
     * and only the source knows what that is. A source that adds nothing means no menu opens, so "sometimes there
     * is nothing to offer here" needs no special case.
     *
     * <p>Sources run on a worker thread, so they may read application state freely.
     */
    public Gui onContextMenu(Node node, java.util.function.Consumer<MenuSink> source) {
        input.onContextMenu(node.id(), source);
        return this;
    }

    /**
     * Install what draws context menus. {@code vexelray-gui-widget}'s {@code ContextMenu} installs itself the
     * first time a widget with a default menu is built, so this is only for replacing it with something else —
     * before the UI is built, since the widgets check for a presenter rather than replacing one.
     */
    public Gui menus(MenuPresenter presenter) {
        input.menuPresenter(presenter);
        return this;
    }

    /** The installed context-menu presenter, or {@code null} if nothing shows menus on this tree yet. */
    public MenuPresenter menus() {
        return input.menuPresenter();
    }

    /** The click topic: {@code gui.bus().subscribe(gui.clicks(), ...)} to react to clicks anywhere (any button). */
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
     * Say what would happen if a drag were released on {@code node} — see {@link DropTarget}.
     *
     * <p>Not the same thing as {@link #onDrag}, and a node may have both without them interacting. That one is a
     * manipulation the pointer steers: captured on press, no threshold, ends on release, and always about a
     * gesture that started on the node itself. This one answers about a drag the node did not start and may know
     * nothing about — one begun elsewhere in this window, in another window of this process, or in another
     * process entirely, since a file dragged from the desktop arrives with no press for anything to have
     * captured.
     *
     * <p>The target is asked once per frame while a drag is over it, on the GUI thread, and returns a
     * description. Nothing it describes happens until the user releases, and what happens then is exactly the
     * {@link dev.vexelray.gui.core.drop.Drop} that was last shown.
     */
    public Gui onDrop(Node node, DropTarget target) {
        input.onDrop(node.id(), java.util.Objects.requireNonNull(target, "target"));
        return this;
    }

    /** The drop targets under a point, innermost first — the {@link DragSession.Lookup} for this tree, for a
     * drag source driving a session against it. */
    public java.util.List<DropTarget> dropTargetsAt(float x, float y) {
        return input.dropTargetsAt(x, y);
    }

    /**
     * Make {@code node} something a drag can begin on — see {@link DragSource}.
     *
     * <p>The gesture is recognised for you: a press here becomes a drag only once the pointer has travelled far
     * enough and held long enough, so an ordinary click on the node stays an ordinary click. The source is asked
     * for a payload at that moment, not at the press, and answering null leaves the gesture as a click.
     */
    public Gui onDragSource(Node node, DragSource source) {
        input.onDragSource(node.id(), java.util.Objects.requireNonNull(source, "source"));
        return this;
    }

    /**
     * Where a completed drop records its change, so it can be undone.
     *
     * <p>Until one is set, drops resolve and draw but commit nothing — deliberately, because a drop that mutated
     * with no way back would be the one operation in the framework the user could not undo, and silently so.
     *
     * <p>A drop also moves focus to what a click at the drop point would focus, which is what keeps Ctrl+Z
     * pointed at the right history: {@link #history(Node, History, ClaimScope)} resolves by focus, and a drag is
     * a pointer gesture with no focus relationship of its own.
     */
    public Gui dropHistory(History history) {
        input.dropHistory(java.util.Objects.requireNonNull(history, "history"));
        this.transferHistory = history;
        return this;
    }

    /**
     * Where a transfer records its change — the history {@link #dropHistory} set, or null.
     *
     * <p>Readable because a drop is not the only way to complete a transfer: a paste makes the same change, from
     * the keyboard, and must land in the same stack. Two histories would mean Ctrl+Z undoing whichever kind of
     * transfer the user happened to do last rather than the last thing they did.
     */
    public History dropHistory() {
        return transferHistory;
    }

    /**
     * What is held for a paste, or {@link Transfer#NONE}.
     *
     * <p>One per {@link Gui}, so two trees in a window can exchange rows and a window can paste what another of
     * its panes cut. It is deliberately <em>not</em> the OS clipboard: that one carries text (see
     * {@link #clipboard}), and a typed payload has nowhere to ride across a process boundary.
     *
     * <p>Not a {@code State}, unlike the drag: this changes when a command puts something in it and at no other
     * time, so there is nothing to observe per frame. A menu that wants to grey its Paste entry reads it at the
     * moment the menu is built, which is the moment the answer matters.
     */
    public Transfer transfer() {
        return transfer;
    }

    /** Put something in hand for a paste, or {@link Transfer#NONE} to drop what is held. Safe from any thread. */
    public Gui transfer(Transfer held) {
        this.transfer = held == null ? Transfer.NONE : held;
        return this;
    }

    /** The drag in flight over this tree, or null — for a renderer drawing the ghost and the drop indicator. */
    public DragSession dragSession() {
        return input.dragSession();
    }

    /**
     * Observe {@code node}'s computed geometry: the handler is called with its {@link NodeLayout} whenever the
     * node's box changes, <b>including the first time it has one</b>. Runs on a worker thread.
     *
     * <p><b>This is the seam for "my geometry is real now".</b> Everything else a node can be told about is
     * input; geometry was observable only by polling {@link Node#layout} or by subscribing to {@link #layout()}
     * and diffing, and an application that did neither had no way to know when its box arrived. The failure that
     * produces is silent — code that runs too early reads {@link NodeLayout#ABSENT}, does nothing, reports
     * nothing, and is never asked again — which is why it belongs in the framework rather than in each
     * application's notes.
     *
     * <p><b>Lifecycle callbacks are not a substitute, and neither is the window.</b> {@code WindowSpec.onCreated}
     * means the OS window exists; nothing has been measured yet, so a node's box is still absent there. The first
     * call to this handler is the moment after that — and because it is an observer rather than a one-shot, the
     * same registration also covers every later window resize, zoom change and DPI change, which a
     * "first frame" callback would not.
     *
     * <p>Only a change of <em>box</em> fires it: the border rect and the content rect are compared, so scrolling
     * a container does not (its viewport did not move, its contents did). A node laid out to zero — inside a
     * hidden parent, say — is reported honestly as zero rather than withheld; a consumer that cannot draw at
     * that size checks and waits for the next call.
     */
    public Gui onResize(Node node, java.util.function.Consumer<NodeLayout> handler) {
        return watchResize(node, handler, false);
    }

    /**
     * As {@link #onResize}, delivered on the GUI thread inside the layout pass that produced the change, in the
     * ordered lane the {@code *Ui} seams share — for a handler whose work has to land in the same frame as the
     * layout it reacts to. It runs while the framework holds the frame, so it must be short: measure, mutate a
     * handle, return. Anything that computes belongs on {@link #onResize}.
     */
    public Gui onResizeUi(Node node, java.util.function.Consumer<NodeLayout> handler) {
        return watchResize(node, handler, true);
    }

    private Gui watchResize(Node node, java.util.function.Consumer<NodeLayout> handler, boolean ordered) {
        readModels.onResize(node.id(), handler, ordered);
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
     * Drop every registration {@code node} holds — input handlers, ordered stages, claims, focusability, and its
     * geometry observer — and clear focus if it held it. Removing a node does this automatically; this is for
     * releasing a node's registrations while keeping the node.
     */
    public Gui releaseNode(Node node) {
        releaseNodeId(node.id());
        return this;
    }

    /** The reconciler's removal seam: everything keyed by a node id is dropped when the node leaves the tree. */
    private void releaseNodeId(long id) {
        input.clearHandlers(id);
        readModels.forget(id);
        navigator.forget(id);
    }

    /**
     * Advance every navigation in flight by one step, dropping the ones that finished. A walk that is waiting on
     * a reveal it just asked for does nothing here beyond noticing that it is still waiting.
     */
    private void stepNavigations() {
        if (navigator.step()) {
            // A walk mid-flight owes a frame whatever else happened: its next step is the only thing that will
            // carry it on, and in a loop that parks nothing else is going to ask.
            wake("navigation in flight");
        }
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

    // --- navigation (docs/reference/navigation.md) ---

    /**
     * Name a place in this tree: {@code gui.landmark("prefs.theme.accent", swatch)}. From then on that name is an
     * address — {@link #navigate} brings the user to it, a hyperlink in a document can point at it, a macro can
     * step through it, and a test can ask to be taken there rather than knowing how to get there.
     *
     * <p><b>A name for a node, not for a route.</b> Nothing about the address says which tab, which branch or how
     * far down the page the node is, and that is the whole point: the route is derived, every time, from the tree
     * as it stands. Move the swatch to a different tab and every link to it still works.
     *
     * <p>Names are the application's to organise; dotted paths read well and sort well, and nothing here parses
     * them. A name may not contain {@code '/'} — that separates the window from the landmark in an
     * {@link Address} — and re-using a name rebinds it, so a rebuilt panel naming its parts again is not an
     * error. The binding is released with the node, like every other registration keyed by node id.
     */
    public Gui landmark(String name, Node node) {
        navigator.landmark(name, node.id());
        return this;
    }

    /** Forget the landmark called {@code name}, if there is one. */
    public Gui clearLandmark(String name) {
        navigator.clearLandmark(name);
        return this;
    }

    /** The node named by {@code name}, if this tree has that landmark. */
    public java.util.Optional<Node> landmarkNode(String name) {
        return navigator.landmarkNode(name);
    }

    /**
     * Declare how {@code node} makes a descendant of it reachable — the seam that lets navigation cross a tab
     * panel, a collapsed branch or a drawer without knowing what any of those are. See {@link Reveal}, which is
     * where the reasoning lives; the widgets that conceal things register their own, so an application composing
     * them needs this only for a container it wrote itself.
     */
    public Gui reveals(Node node, Reveal reveal) {
        navigator.reveals(node.id(), reveal);
        return this;
    }

    /**
     * Take the user to the landmark called {@code name} in this tree: reveal it through every container that is
     * concealing it, scroll it into view, and give it the keyboard.
     *
     * <p>Everything it does, it does through the commands the widgets already expose — the same
     * {@code select}, {@code expand} and {@code scrollIntoView} a click ends up calling — so a page arrived at
     * this way is in exactly the state it would be in had the user clicked their way there, handlers and all.
     * It is not synthesised input: input needs a place on screen to aim at, and half of what navigation does is
     * make the target have one.
     *
     * <p>Takes frames rather than returning done; see {@link Navigation} for why, and for the arrival.
     */
    public Navigation navigate(String name) {
        return navigate(Address.of(name));
    }

    /**
     * Navigate to {@code address}. An address naming a different window is <b>published</b> rather than walked,
     * so it reaches that window (and, through {@code GuiApp}, opens it if it is closed); one naming this window
     * or no window at all is walked here.
     */
    public Navigation navigate(Address address) {
        return navigator.navigate(address);
    }

    /**
     * The name this GUI's window is known by, so addresses that name a window can be routed. Set by
     * {@code GuiApp} for every window it opens by name; an application embedding a {@link Gui} itself may set it.
     */
    public Gui windowKey(String key) {
        navigator.windowKey(key);
        return this;
    }

    /** The name this GUI's window is known by, or blank if nothing has said. */
    public String windowKey() {
        return navigator.windowKey();
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
     * Bind the conventional undo chords — Ctrl+Z, Ctrl+Shift+Z and Ctrl+Y — to {@code history}, claimed by
     * {@code node} at {@code scope}.
     *
     * <p>Nesting is the point, and it needs nothing new: a focused editor's own history is a
     * {@link ClaimScope#FOCUSED} claim, an application's is {@link ClaimScope#GLOBAL}, and claim precedence
     * already says the specific one wins while it applies. So Ctrl+Z undoes your typing while the cursor is in a
     * field and undoes the last document command when it is not, without either history knowing the other exists.
     *
     * <p>The commands run on a worker thread, like any other claim. A history whose changes must apply in the
     * frame's own order — one editing a model the GUI thread reconciles from — claims {@link #claimUi} instead
     * and calls {@link History#undo()} there.
     */
    public Gui history(Node node, History history, ClaimScope scope) {
        claim(node, Shortcut.of(Key.Z, Modifier.CONTROL), scope, history::undo);
        claim(node, Shortcut.of(Key.Z, Modifier.CONTROL, Modifier.SHIFT), scope, history::redo);
        claim(node, Shortcut.of(Key.Y, Modifier.CONTROL), scope, history::redo);
        return this;
    }

    /** The application-wide binding: {@link #history(Node, History, ClaimScope)} on the root, always in force. */
    public Gui history(History history) {
        return history(root, history, ClaimScope.GLOBAL);
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
     * Declare the cursor shape over {@code node} and its descendants ({@code null} to clear).
     *
     * <p>Most affordances need no declaration: the framework already knows a node is clickable because it has
     * a click handler, that a text node is editable, and where its scrollbars are — so those cursors follow
     * from registering the behaviour rather than from remembering to describe it. This is for what it cannot
     * infer: a slider and a text field both use the drag seam, so only the slider can say it is grabbable.
     */
    public Gui cursor(Node node, CursorShape shape) {
        input.setCursor(node.id(), shape);
        return this;
    }


    /**
     * Install the sink notified when the desired pointer cursor changes (§8.3) — e.g. the I-beam over editable
     * text. The application maps {@link CursorShape} onto its window's cursor. Called on the GUI thread.
     */
    public Gui onCursorChange(java.util.function.Consumer<CursorShape> sink) {
        input.cursorSink(sink);
        return this;
    }

    /**
     * Declare that dragging {@code node} should hold the pointer for the length of the gesture: locked on the
     * drag's START, released on its END.
     *
     * <p>This is for a drag that means a <b>displacement</b> and never asks where the pointer is — turning a
     * camera, panning a plane. Such a gesture has no natural end, so the window's edge should not be one
     * either, and a lock is what removes it: the pointer stops travelling, the motion keeps arriving, and a
     * drag can turn something a dozen times over without running out of desk.
     *
     * <p>A drag that means a <b>place</b> must not ask for this. A slider and a text selection need the
     * absolute position a lock destroys and the visible cursor a lock hides — and a handler that differences
     * {@link dev.vexelray.gui.core.input.DragEvent#x()} instead of reading its {@code dx()} reads nothing at
     * all once locked, so declaring this is also a promise about how the handler is written.
     */
    public Gui dragLocksPointer(Node node, boolean locks) {
        input.setDragLocksPointer(node.id(), locks);
        return this;
    }

    /**
     * Install the sink notified when the pointer should be locked ({@code true}) or released ({@code false}).
     * The application maps it onto its input backend's pointer lock. Called on the GUI thread, and only on a
     * change, so an application need not de-duplicate.
     *
     * <p>The same seam as {@link #onCursorChange} and for the same reason: the framework can say what it wants
     * of a pointer and cannot reach an OS to get it.
     */
    public Gui onPointerLock(java.util.function.Consumer<Boolean> sink) {
        input.pointerLockSink(sink);
        return this;
    }

    /**
     * The look every widget and the renderer's own chrome resolve their colours through — {@link Theme#DARK}
     * unless replaced. There is no other source of colour in the framework.
     */
    public Theme theme() {
        return theme;
    }

    /**
     * Install the theme. Set it before building the UI: roles resolve when a widget writes a prop, so a swap after
     * the fact reaches the renderer's chrome and everything that restyles on interaction, but leaves already-written
     * props alone (see {@link Theme}).
     */
    public Gui theme(Theme theme) {
        this.theme = theme == null ? Theme.DARK : theme;
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
        return trees.box();
    }

    /** A horizontal box. */
    public Node row() {
        return trees.row();
    }

    /** A vertical box. */
    public Node column() {
        return trees.column();
    }

    /**
     * A node carrying {@code s} — which is the whole of what makes it a text node.
     *
     * <p><b>Sugar, not a second kind.</b> This is exactly {@code box().text(s)}, and the two are
     * interchangeable: a node draws text when it has text, so there is no way to make one that holds a string
     * and refuses to show it. That used to be possible and was the quietest bug in the framework — see
     * {@code RetainedNode.hasText}. Prefer this spelling when the node <em>is</em> a label; reach for
     * {@code box().text(...)} when text is arriving on a node that already exists for another reason.
     *
     * <p>An empty string is still text: it is a label whose content has not arrived, and it holds its line's
     * height so the row around it does not jump when the content lands.
     */
    public Node text(String s) {
        return trees.text(s);
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
        trees.batch(edits);
    }

    /**
     * Tell a parked host loop that a frame is owed. Never throws into its caller.
     *
     * <p>A wake that fails is a window that stops updating, so it says so once rather than silently —
     * and it must not take the publish or the handler down with it, both of which have already
     * succeeded by the time this runs.
     */
    /**
     * The latest published layout, as a {@link LayoutReader} for the handles {@link Trees} mints.
     *
     * <p>A method reference rather than a lambda over the field, because {@code Trees} is built before
     * {@code readModels} is and the compiler will not let a lambda close over a blank final that early. The
     * laziness is real either way: a handle does not ask about geometry until something reads one.
     */
    private LayoutSnapshot publishedLayout() {
        return readModels.layoutSnapshot();
    }

    private void wake(String why) {
        if (!woken.compareAndSet(false, true)) {
            return;   // a frame is already owed, and saying so twice does not owe two
        }
        if (Probe.ON) {
            String id = "Gui@" + Integer.toHexString(System.identityHashCode(this));
            if (workListener != NO_WAKE) {
                // The other half of the gap analysis (docs/reference/automation.md §4). loop.park says the loop went to
                // sleep and for how long it was allowed to; this says what woke it, and why. A gap that ends in
                // a wake and a gap that ends because the budget ran out are different findings.
                Probe.mark(Lane.FRAME, "wake", why + " on " + id);
            } else if (!tracedUnwired) {
                // Once per tree, not once per mutation. A palette built at startup and opened later
                // mutates freely while hidden, and a line per mutation buries the one that matters
                // under hundreds that do not — which is how a diagnostic becomes noise and then gets
                // ignored. The host wires every tree it presents, every frame, so reaching here means
                // nothing is presenting this one — and a tree nobody draws is owed no frame.
                tracedUnwired = true;
                Probe.mark(Lane.FRAME, "wake.unwired", why + " on " + id + " - no listener, nothing is"
                        + " presenting this tree, so no frame is owed; further notices suppressed");
            }
        }
        try {
            workListener.run();
        } catch (Throwable t) {
            if (!warnedWakeFailed) {
                warnedWakeFailed = true;
                System.err.println("vexelray-gui: the onWork listener threw; frames may stop arriving "
                        + "when nothing else wakes the loop. " + t);
            }
        }
    }

    /**
     * Tell a parked host loop a frame is owed because input arrived from <b>outside the OS event queue</b>.
     *
     * <p>For input that did not come from a device backend. Every other reason a frame is due already wakes the
     * loop on its own: a mutation wakes it through {@link #publishMutation}, time passing wakes it through the
     * clock, and a real click wakes it because the OS delivered the event that carried it. Input published
     * straight onto the input topic has none of the three — it is not a mutation, and no event arrived — so a
     * loop that parks indefinitely when nothing is looking at the window never dispatches it.
     *
     * <p><b>Which is a silence that reads exactly like a bug in the application.</b> The publish succeeds, an
     * automation driver's every command answers {@code ok}, and nothing happens: no click lands, no key is
     * typed, the tree never changes. It comes right the instant any real event arrives, so it presents as the
     * clicks having been ignored rather than as the loop having been asleep — the same misreading
     * {@link #onWork} exists to prevent for mutations, in the one place where a mutation is not what is
     * waiting.
     *
     * <p>Named for its caller's situation rather than made general on purpose: an application has no reason to
     * call this, because everything an application does to a tree goes through a mutation.
     */
    public void wakeForInput() {
        wake("injected input");
    }

    /**
     * Whether a frame is currently owed — something has been published that no frame has drained yet.
     *
     * <p>The read side of the wake, for anything that needs to know whether the tree has settled: chiefly the
     * automation driver's {@code settle}, which waits for a mutation to be reflected before it reads or clicks
     * again (docs/reference/automation.md §5).
     *
     * <p><b>What it does not say.</b> A handler still running on a worker has not published yet, so it is owed
     * nothing and this reports false while that work is still in flight — the same limit
     * {@link #onWork} describes from the other side. So this answers "has the loop caught up with what it has
     * been told", exactly, and never "is the application finished thinking". A caller that needs the second
     * has to wait on the application's own state, which only the application can name.
     */
    public boolean frameOwed() {
        return woken.get();
    }

    /**
     * How a host loop that parks between frames is told a mutation is waiting for it.
     *
     * <p><b>Required by any loop that sleeps</b>, and its absence is a window that stops updating rather
     * than a slow one. Click handlers run on the worker executor, off the GUI thread — so a handler
     * publishes its mutation <em>after</em> the frame that dispatched the click has already drained,
     * reconciled and presented. A loop that then asks only its animation clock is told there is nothing
     * to do, parks, and leaves the mutation queued: the button was clicked, the state changed, and the
     * screen keeps showing what it showed before. It comes right the instant any OS event arrives, so it
     * presents as "the UI only updates when I move the mouse" — which points at input handling rather
     * than at the loop, and it is worst on a touchpad where a click carries no movement with it.
     *
     * <pre>{@code
     * gui.onWork(app::postWake);          // alongside kron.onWork(app::postWake)
     * }</pre>
     *
     * <p>Both are needed and they are not redundant: this one covers changes the application makes, the
     * clock's covers time passing. Either alone leaves half the reasons a frame is due unaccounted for.
     *
     * <p>Fires on <em>every</em> publish, including from the GUI thread mid-frame, which costs at most
     * one extra pass that finds nothing to do. Filtering by thread would be an optimisation paid for in
     * exactly the currency this is trying to save.
     *
     * <p>One listener, last call wins — so one call site per {@code Gui}. A second, from a profiler or
     * an adapter installing its own, silently replaces the first and the loop stops waking.
     */
    public void onWork(Runnable listener) {
        this.workListener = java.util.Objects.requireNonNull(listener, "listener");
        // Re-arm: a wake recorded while nothing was listening was delivered to NO_WAKE and reached no loop, so
        // the newly wired listener must not inherit it as "already told". Without this, a tree that was edited
        // before it was presented would suppress the first real wake it ever had.
        woken.set(false);
        if (Probe.ON) {
            Probe.mark(Lane.FRAME, "wake.wired", "Gui@" + Integer.toHexString(System.identityHashCode(this)));
        }
    }

    /**
     * Attach the source that says how far behind its laid-out position each node is drawn — see
     * {@link LayoutMotion}. {@link LayoutMotion#NONE} (the default) draws everything exactly where layout put it.
     *
     * <p>One source per tree, last call wins, for the same reason as {@link #onWork}: two of them would each
     * report a displacement for the same node and the tree would be drawn at whichever one was asked second.
     * A source that wants to compose several transitions composes them on its own side, where it can see them
     * both.
     */
    public Gui motion(LayoutMotion source) {
        this.motion = java.util.Objects.requireNonNull(source, "source");
        return this;
    }

    /** The attached motion source, or {@link LayoutMotion#NONE}. */
    public LayoutMotion motion() {
        return motion;
    }

    /**
     * Whether a frame is owed: mutations are queued and nothing has drawn them yet.
     *
     * <p>For a host that wants to check rather than be told — a diagnostic, or a belt-and-braces term in
     * a pacing supplier. {@link #onWork} is the load-bearing half; this cannot close the race on its own,
     * because a handler still running on a worker has not published yet and so has nothing to report.
     */
    public boolean hasPendingWork() {
        return pump.hasPending();
    }

    /**
     * Drain + reconcile + (re)layout for one frame; returns the retained root to render (may be null on the very
     * first call before the root's Create is drained — it won't be, since we drain first). {@code tm} supplies
     * text intrinsic sizes.
     */
    public RetainedNode frame(float viewportW, float viewportH, TextMeasurer tm) {
        // The frame the last wake asked for is this one, so the next edit has to ask again. First thing, before
        // anything below can publish: see the field's note on why this is the top and not the bottom.
        woken.set(false);
        // Dispatch this frame's input first, against the previous frame's laid-out tree (§8, §10): a click may
        // register a mutation, which the drain below then applies in the same frame.
        try (Zone probeZone = Probe.zone(Lane.LAYOUT, "dispatch input")) {
            input.dispatch(reconciler.root());
        }
        // Drain the mutation pump on the GUI thread: the subscriber applies each Mutation to the reconciler in
        // FIFO order (single writer). The tree is up to date afterward.
        pump.drain();
        // Navigation advances here: after the drain, so it reads the tree every edit so far has produced, and
        // before the layout, so a reveal it issues is applied by the very next drain and laid out by the frame
        // after. One step per frame is not a delay, it is the only honest cadence — see Navigation.
        stepNavigations();
        RetainedNode r = reconciler.root();
        // Size and scale, asked once. Metrics reads zoom and density rather than being pushed them: a worker's
        // shortcut commits to the State from its own thread and the frame notices, so nothing outside this
        // thread ever writes the reconciler's dirty flags. It also publishes the window size if it moved, before
        // the relayout, so observers and the layout see the same value this frame.
        Metrics.Frame m = metrics.measure(viewportW, viewportH);
        float layoutW = m.layoutW();
        float layoutH = m.layoutH();
        if (r != null) {
            boolean layoutRan = reconciler.layoutDirty() || m.moved();
            if (layoutRan) {
                // Counted as well as timed. Render-on-demand means a still window should lay out almost never,
                // so a layout count that tracks the frame count is not a slow layout - it is a dirty flag that
                // is always set, and no amount of making the pass faster will fix that.
                try (Zone probeZone = Probe.zone(Lane.LAYOUT, "flex layout")) {
                    FlexLayout.layout(r, layoutW, layoutH, m.layoutCtx(), tm);
                }
                metrics.layoutRan(m);
            }
            // Reveal requests are answered here: after the layout that says where everything is, and before the
            // publish that tells everyone else. A scroller that moved is laid out a second time rather than left
            // to catch up next frame — a container bakes its scroll offset into its children's positions, so one
            // that moved after the pass would publish rects for where its rows used to be, and the frame's own
            // hit-testing would aim at them. The extra pass is paid only on frames where something asked.
            for (RetainedNode asked : reconciler.takeReveals()) {
                if (dev.vexelray.gui.core.layout.Scrolling.reveal(asked)) {
                    // The second pass the comment above pays for. Named apart so its cost is attributable.
                    try (Zone probeZone = Probe.zone(Lane.LAYOUT, "flex layout (reveal)")) {
                        FlexLayout.layout(r, layoutW, layoutH, m.layoutCtx(), tm);
                    }
                    layoutRan = true;
                }
            }
            // The compute phase (docs/reference/layout-read-model.md §2.1): resolve everything that is a pure function of the
            // laid-out tree — caret-follow scroll, text metrics — then publish. It runs whenever the geometry could
            // have moved, which includes a caret move that reflows nothing, and it runs in *every* host: this is
            // what makes a field behave identically headless, on screen, and over the wire. Static frames do
            // neither, so the coalesced State still commits only on change.
            boolean geometryChanged = layoutRan || reconciler.geometryDirty();
            if (geometryChanged) {
                try (Zone probeZone = Probe.zone(Lane.LAYOUT, "resolve geometry")) {
                    readModels.resolve(r, tm);
                }
            }
            // Motion sits between the compute phase and publish, and the line matters in both directions
            // (LayoutMotion's javadoc says why). Settling reads the rects the compute phase just finished with,
            // so a transition starts from the truth; displacing then rewrites them, so publish, the renderer and
            // next frame's hit-testing all see the same drawn position.
            boolean moving = false;
            if (motion != LayoutMotion.NONE) {
                if (geometryChanged) {
                    Displacement.settle(r, motion);
                }
                moving = Displacement.displace(r, motion);
            }
            if (geometryChanged || moving) {
                // Last of the compute phase, because it is a fact about the rects everything above just
                // finished writing: displacement moves a node after layout placed it, and a clip resolved
                // before that would describe a frame nobody sees.
                Clip.resolve(r);
                readModels.publish(r);
            }
            publishDrag();
            if (moving) {
                // Something is short of where it belongs, so the next frame has work whether or not anything
                // else asks for one. Self-limiting: a displacement that decays to zero stops waking, and one
                // that does not is a motion source that never finishes, which this makes visible as a loop that
                // will not park rather than as a transition that silently freezes half-way.
                wake("motion");
            }
            reconciler.clearDirty();
        }
        return r;
    }


    /**
     * Publish the live drag, if it has changed since the last frame.
     *
     * <p>After layout and displacement, so the indicator a target resolved is published in the same frame the
     * geometry it was resolved against is — a subscriber drawing from it is never a frame out of step with the
     * rows it is drawing between.
     *
     * <p>On change only: a drag held still over one seam is the common case, and re-committing an identical value
     * sixty times a second would wake every subscriber for nothing. The record's equality is what makes that
     * comparison honest rather than a hand-written field-by-field check that forgets one.
     */
    private void publishDrag() {
        DragSession live = input.dragSession();
        DragState next = live == null
                ? DragState.NONE
                : new DragState(true, live.x(), live.y(), live.drop().effect(), live.drop().indicator());
        if (next.equals(lastDrag)) {
            return;
        }
        lastDrag = next;
        dragState.commit(setDrag, next);
    }

    @Override
    public void close() {
        input.close();
        mutationSub.close();
        navSub.close();
        workers.shutdownNow();
    }
}
