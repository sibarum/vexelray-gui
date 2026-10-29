package dev.vexelray.gui.core;

import dev.vexelray.gui.core.drop.DragSession;
import dev.vexelray.gui.core.drop.DragSource;
import dev.vexelray.gui.core.drop.DragState;
import dev.vexelray.gui.core.drop.Transfer;
import dev.vexelray.gui.core.drop.Transfers;
import dev.vexelray.gui.core.drop.DropTarget;
import dev.vexelray.gui.core.layout.Clip;
import dev.vexelray.gui.core.layout.Displacement;
import dev.vexelray.gui.core.layout.FlexLayout;
import dev.vexelray.gui.core.layout.LayoutContext;
import dev.vexelray.gui.core.layout.Metrics;
import dev.vexelray.gui.core.layout.ReadModels;
import dev.vexelray.gui.core.layout.LayoutMotion;
import dev.vexelray.gui.core.layout.EnterMotion;
import dev.vexelray.gui.core.layout.ExitMotion;
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
import dev.vexelray.gui.core.input.Menu;
import dev.vexelray.gui.core.input.MenuPresenter;
import dev.vexelray.gui.core.input.MenuSink;
import dev.vexelray.gui.core.input.Gesture;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.input.Trigger;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.model.SemanticNode;
import dev.vexelray.gui.core.nav.Address;
import dev.vexelray.gui.core.nav.Navigation;
import dev.vexelray.gui.core.nav.Navigator;
import dev.vexelray.gui.core.nav.Reveal;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.core.text.TextMetrics;
import dev.vexelray.diag.Diagnostics;
import sibarum.probe.Cause;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The framework facade and the boundary between worker threads and the GUI thread. Workers build UI and mutate it
 * through {@link Node} handles minted here (each publishes a {@code Create}); the GUI thread calls {@link dev.vexelray.gui.core.spi.Seams#frame}
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

    private static final sibarum.probe.Log LOG = sibarum.probe.Log.of("gui");

    /**
     * The internal tree-mutation channel, named by the tree ({@code vexelray.gui.<tree>.mutations}) so it never
     * collides with application traffic or with another tree's on a shared bus; losslessly drained on the GUI thread
     * each frame.
     */
    private final Topic<Mutation> mutationTopic;

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

    /** Framework click events, resolved from raw input by the dispatcher: this tree's ({@link #clicks()}). */
    private final Topic<ClickEvent> clickTopic;

    /** Keyboard focus changes (gained/lost per node), this tree's. */
    private final Topic<FocusEvent> focusEventTopic;

    /**
     * How many frames a {@link #navigate} may spend before it gives up. Counted in frames rather than
     * milliseconds so a headless test driving frames by hand fails at the same point a running application does
     * — and generously, because every legitimate step (a tab selecting, a branch expanding, a transition
     * settling) is one frame, and no realistic destination is hundreds of containers deep.
     */
    private static final int MAX_NAV_FRAMES = 240;

    /** Every routed key press, whatever took it — the observation channel (see {@link KeyRouted}). */
    private final Topic<KeyRouted> keyRouteTopic;

    /** Where nodes come from: the id counter, the publisher seam, and the batch. See {@link Trees}. */
    private final Trees trees;
    private final Atchung bus;
    private final MutationSink sink;
    private final Pump pump;
    /**
     * The widgets' mailboxes ({@link #mailbox}): a tick of the GUI's own, run on the GUI thread at the start of each
     * frame's drain. A tick rather than more of {@link #pump} because a logic tick hands another tick all of its
     * writes at once (atchung's {@code Tick}), so a frame applies all of what the logic wrote in one of its ticks or
     * none of it, never half. Input and the tree's own mutations stay on the pump, as they always have.
     */
    private final sibarum.atchung.Tick widgets;
    private final sibarum.atchung.Inbox widgetInbox;
    /** The widgets' addressed mailboxes, routed by address: see {@link #mailbox(Topic, Object, sibarum.atchung.Subscriber)}. */
    private final Routes routes;
    /** How many unnamed trees this process has made: what names each one apart from the others. */
    private static final java.util.concurrent.atomic.AtomicInteger TREES = new java.util.concurrent.atomic.AtomicInteger();
    /** The names of the open trees on each bus, so a second of one name is refused rather than sharing topics. */
    private static final Map<Atchung, java.util.Set<String>> NAMES = new java.util.WeakHashMap<>();
    /** This tree's name among the others on a bus: see {@link #name()}. */
    private final String treeName;
    /** This tree's node-event topics ({@link #hear}), one per kind heard so far. GUI thread only. */
    private final Map<NodeEvent<?>, Topic<?>> nodeTopics = new java.util.HashMap<>();

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
     * <p>Cleared at the very top of {@link dev.vexelray.gui.core.spi.Seams#frame}, before the drain rather than after it. A write that lands
     * mid-frame then re-arms the wake and costs one extra frame that finds nothing to do; clearing at the end
     * would instead swallow that write's wake and leave it sitting until something unrelated woke the loop. Over
     * -waking is a wasted frame, under-waking is a UI that stops updating.
     */
    private final java.util.concurrent.atomic.AtomicBoolean woken = new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * What the loop still owes this tree — a wake not yet taken, the frame that took it, the handlers that frame
     * dispatched — as one count that never touches zero while work is handed between them. What
     * {@link #frameOwed} answers from; {@link #woken} alone is cleared at the top of the frame, and a reader that
     * asked it was told "settled" while the click it had just sent was still being dispatched.
     */
    private final Owed owed = new Owed();
    /** Whether a frame has ever finished, so the first one can pay the unit a new tree starts owing. GUI thread. */
    private boolean framed;
    /**
     * Whether a frame is owed a <em>draw</em> — the half of render-on-change the wake is not (docs/plans/frame-loop.md).
     *
     * <p>A wake says "run a frame": drain, lay out, answer input. Whether that frame then has anything new to show
     * is a different question, answered by what the frame found — an edit that changed the tree, a layout that
     * moved something, motion — and by {@link #requestFrame} for what the tree cannot see. GUI thread only;
     * {@link #frameRequested} is the cross-thread door into it. Starts true, because the first frame always draws.
     */
    private boolean drawNeeded = true;
    /** Set from any thread by {@link #requestFrame}; folded into {@link #drawNeeded} by the next frame. */
    private final java.util.concurrent.atomic.AtomicBoolean frameRequested =
            new java.util.concurrent.atomic.AtomicBoolean();
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
    /** The two-press undo every {@link #history} binding and {@link #undo}/{@link #redo} call goes through. */
    private final Undoing undoing;
    /** Which history each node bound, by node id — what {@link #historyFor} walks up through. */
    private final java.util.concurrent.ConcurrentMap<Long, History> boundHistories =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Reconciler reconciler;
    private final InputDispatcher input;
    private final Node root;
    /**
     * Size and scale: the window, the zoom, the density, and the minimums and limits that shape them. Every
     * {@link Length} resolves against what this holds, and {@link dev.vexelray.gui.core.spi.Seams#frame} asks it once per pass — see
     * {@link Metrics}, which owns the remembered values the dirty-check compares against.
     */
    private final Metrics metrics = new Metrics();
    /** Where nodes are drawn relative to where layout put them; NONE until a motion source is attached. */
    private volatile LayoutMotion motion = LayoutMotion.NONE;
    /** Whether the last frame's displacement walk left any node displaced, so this one owes a walk to undo it. */
    private boolean displaced;
    /** How many displacement walks the frame has made: a test's evidence that a still tree walks nothing. */
    private long displaceWalks;
    /**
     * The frame's number for telling a continuous move from a discrete one (Displacement.settle). One apart per frame,
     * and two apart after the loop has been parked, so a move on the first frame after a pause starts a new run
     * rather than continuing one that ended before it: a parked loop draws no frames to count the gap in.
     */
    private long motionFrame;
    private long motionFrameNanos;
    /** Longer than any frame a drag or a ramp is drawn at, and shorter than a person's pause between two changes. */
    private static final long MOTION_FRAME_GAP_NANOS = 150_000_000L;
    /** The scroll glide's half-life and the focus fade's duration as asked for; applied unless motion is reduced. */
    private volatile long glideNanos;
    private volatile long focusFadeNanos;
    /** The zoom glide's half-life as asked for; applied unless motion is reduced. */
    private volatile long zoomGlideNanos;
    /** The two graphics choices, each as asked for and as the run overrules it. */
    private final GraphicsChoice flatShading = new GraphicsChoice(FLAT_SHADING);
    private final GraphicsChoice reducedMotion = new GraphicsChoice(REDUCED_MOTION);
    /** What colour nodes are drawn in relative to the colour they were given; NONE until a source is attached. */
    private volatile dev.vexelray.gui.core.style.PaintMotion paintMotion =
            dev.vexelray.gui.core.style.PaintMotion.NONE;
    /** How much of a node arriving or leaving is drawn; NONE until a source is attached. */
    private volatile EnterMotion enterMotion = EnterMotion.NONE;
    private volatile ExitMotion exitMotion = ExitMotion.NONE;
    /** Whether the last frame found something arriving or leaving, so this one must walk for presence again. */
    private boolean presencing;
    /** What a presence walk collects finished departures into: one list, cleared per walk, so the frame allocates none. */
    private final java.util.List<RetainedNode> gone = new java.util.ArrayList<>();
    /** How many presence walks the frame has made: a test's evidence that an arrival with no motion walks nothing. */
    private long presenceWalks;
    /** What a run without a frame loop steps to bring animations to rest; null until a clock registers. */
    private volatile AnimationClock animationClock;
    /**
     * What is held for a paste, and the live drag as a published read-model. A drag and a cut are the same
     * change made two ways, which is why one component holds both — see {@link Transfers}.
     */
    private final Transfers transfers = new Transfers();
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
    /** Guards installing the default context-menu presenter: see {@link dev.vexelray.gui.core.spi.Seams#onContextMenu}. */
    private final Object menusLock = new Object();
    // The look. Not a State on the bus like zoom is: a Length resolves against zoom every frame, whereas a colour
    // is resolved once and written into a prop, so there is nothing per-frame to coalesce (see Theme's class note
    // on what that costs and where it is heading). Volatile because widgets read it from whichever thread built
    // them and from every state handler.
    private volatile Theme theme = Theme.DARK;
    /** The focus state and theme the read-models were last published against; see the frame's publish step. */
    private dev.vexelray.gui.core.input.FocusState publishedFocus = dev.vexelray.gui.core.input.FocusState.NONE;
    private Theme publishedTheme;
    /** Set by {@link #close}. */
    private volatile boolean closed;

    /** Create a GUI on its own private Atchung bus. */
    public Gui() {
        this(Atchung.create());
    }

    /**
     * Create a GUI on a shared Atchung bus — hand in the same bus the application uses so input publishers
     * ({@code tactroller-atchung}), widgets, and workers all meet the framework on one fabric.
     *
     * <p><b>Any number of trees may share a bus</b>, each under its own name: every topic a tree uses for itself — its
     * mutations, its input, its clicks, focus and key routes, its node events — is named by the tree
     * ({@code vexelray.gui.<tree>.mutations}), and every message its widgets publish carries an address qualified by
     * it. So two windows on one bus never hear each other's input or apply each other's edits, and a message can
     * travel between them with nothing bridging it. Navigation ({@code Address.NAVIGATE}) is the one topic they share
     * on purpose: an address names its window, and each tree takes only its own.
     *
     * <p>The tree is named {@code tree<n>}, numbered in the order this process made them; see {@link #name()}.
     */
    public Gui(Atchung bus) {
        this(bus, "tree" + TREES.incrementAndGet());
    }

    /**
     * Create a GUI called {@code name} on a shared Atchung bus: as {@link #Gui(Atchung)}, with a name chosen by whoever
     * makes the window ("main", "settings", "editor-2" for the second of a kind). The name is the tree's on this bus
     * until the tree is {@link #close closed}.
     *
     * @throws IllegalArgumentException when {@code name} is empty or holds a {@code '.'}, which separates it from a
     *                                  widget's name in an {@link #address}
     * @throws IllegalStateException    when another open tree on {@code bus} already has the name: the two would
     *                                  share every topic, and each apply the other's edits
     */
    public Gui(Atchung bus, String name) {
        java.util.Objects.requireNonNull(name, "name");
        if (name.isEmpty() || name.indexOf('.') >= 0) {
            throw new IllegalArgumentException("a GUI's name is not empty and holds no '.': \"" + name + "\"");
        }
        synchronized (NAMES) {
            if (!NAMES.computeIfAbsent(bus, b -> new java.util.HashSet<>()).add(name)) {
                throw new IllegalStateException("a GUI called \"" + name + "\" is already open on this bus: two trees"
                        + " of one name would share every topic; name each window's tree apart");
            }
        }
        this.treeName = name;
        this.mutationTopic = Topic.of("vexelray.gui." + name + ".mutations", Mutation.class);
        this.clickTopic = Topic.of("vexelray.gui." + name + ".clicks", ClickEvent.class);
        this.focusEventTopic = Topic.of("vexelray.gui." + name + ".focus", FocusEvent.class);
        this.keyRouteTopic = Topic.of("vexelray.gui." + name + ".keys", KeyRouted.class);
        this.bus = bus;
        this.pump = bus.pump();
        // One inbox for every widget, so commands to two widgets are applied in the order they were sent.
        this.widgets = bus.tick(() -> wake("widget message"));
        this.widgetInbox = widgets.inbox("widgets");
        this.routes = new Routes(widgetInbox, name);
        // Where nodes come from, and the seam their handles write through. It resolves Node.layout() through a
        // reader that is consulted lazily, which is what lets it be built before the read-model it will read —
        // a handle minted here does not ask about geometry until something reads one.
        this.trees = new Trees(bus, mutationTopic, this::wake, this::publishedLayout);
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
        this.mutationSub = pump.subscribe(mutationTopic, reconciler::apply, MUTATION_MAILBOX, Backpressure.BLOCK,
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
        this.navSub = pump.subscribe(Address.NAVIGATE, navigator::accept, MUTATION_MAILBOX, Backpressure.BLOCK);
        // Framework input dispatch on the same bus. Every handler runs in the dispatch, on the GUI thread, inside the
        // frame and the cause of the input it answers; the frame that ran one wakes the loop (see frameBody).
        //
        // Dirtying layout is a change like any other, and it is the one that publishes no Mutation: a
        // scroll offset moves, the tree has to be laid out again, and nothing on the bus says so. In a
        // loop that redrew unconditionally the distinction never mattered; in one that parks, a
        // relayout nobody asked a frame for is a tree that stays exactly as it was last drawn.
        this.input = new InputDispatcher(bus, name, clickTopic, () -> {
            reconciler.markLayoutDirty();
            wake("layout requested");
        });
        this.input.focusTopic(focusEventTopic);
        this.input.keyRoutedTopic(keyRouteTopic);
        // Tab reveals what it lands on, exactly as Node.scrollIntoView would: posted during dispatch, drained this
        // frame and answered after this frame's layout, so no frame shows focus out of view.
        this.input.revealSink(id -> sink.post(new Mutation.Reveal(id)));
        // Disabled is judged on the tree (an ancestor's counts), so the dispatcher needs to find a node by id.
        this.input.nodeLookup(reconciler::node);
        // A menu's edits go where the right-clicked node's other edits go: the history bound nearest above it.
        this.input.menuHistory(this::historyFor);

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
            public dev.vexelray.gui.core.input.FocusCause focusCause() {
                return input.focusState().cause();
            }

            @Override
            public dev.vexelray.gui.core.style.FocusRing focusRing() {
                return theme.focusRing();
            }

            @Override
            public String landmarkName(long id) {
                return navigator.landmarkName(id);
            }
        });
        this.layoutReader = readModels.reader();
        // After the reader, because the events it sends carry node handles, and a handle reads its geometry there.
        LayoutReader reads = this.layoutReader;
        this.undoing = new Undoing(navigator, input, reconciler::node, reconciler::root,
                id -> sink.post(new Mutation.Reveal(id)),   // as Tab reveals what it lands on
                this::wake, id -> new Node(id, sink, reads));

        // The modifiers held right now, published the same way, and a State rather than a topic for the same
        // reason: this is a *condition*, not an event. Nobody wants the edges — they want to know what is held.
        State.Builder<java.util.Set<Modifier>> mb = State.of(java.util.Set.<Modifier>of());
        this.setModifiers = mb.mutation("set", (held, next) -> next);
        this.modifierState = mb.build();
        input.onModifiers(held -> modifierState.commit(setModifiers, held));
        // The focus ring is drawn from the dispatcher's focus state, not from a prop, so moving it edits nothing
        // the frame loop can see.
        input.onFocusState(this::requestFrame);

        // The root is created here rather than when Trees was built: its id was needed before the subscription
        // existed, and its Create must be published after it, or it goes onto the bus with nobody listening.
        this.root = trees.createRoot();
    }

    /** The Atchung bus this GUI publishes mutations, events, and (via a bridge) input on. */
    public Atchung bus() {
        return bus;
    }

    /**
     * This tree's name among the others on a bus: what its node-event topics are named by, and the first part of every
     * one of its widgets' {@link #address addresses}.
     */
    public String name() {
        return treeName;
    }

    /**
     * Where a widget of this tree called {@code widget} is on the bus: {@code "<tree>.<widget>"}, such as
     * {@code "main.path"}. Every message a widget publishes carries its address, and every command for it is routed by
     * it, so a widget's name need only be unique in its own tree: the "path" field of two windows sharing a bus are
     * {@code "main.path"} and {@code "editor-2.path"}, and a reply to either goes to the one that spoke.
     */
    public String address(String widget) {
        return treeName + "." + widget;
    }

    /**
     * Set the zoom factor, clamped to the configured range ({@link #zoomRange}; by default
     * 0.5 to 3). Safe from any thread: on the GUI thread, or before the first frame, it is at once; from another, it
     * is applied at the top of the next frame, so whatever restyles from the zoom runs on the GUI thread and never on
     * the caller's (see {@link #scale}).
     */
    public Gui zoom(float factor) {
        scale(() -> metrics.zoom(factor));
        return this;
    }

    /** What runs a widget's Timing: the host's, installed through Seams.clock, or null for none. */
    private volatile dev.vexelray.gui.core.spi.Clock clock;

    /** The thread frames run on, from the first frame; null before it. */
    private volatile Thread frameThread;

    /** Zoom and density writes made off the frame thread, applied in order at the top of the next frame. */
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> scaleWrites =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Make a zoom or density write on the frame thread. The zoom and density are bus {@code State}s whose listeners
     * run on the thread that commits, and the widgets that restyle from them (a typeset block rebuilding, a status
     * line) touch nodes, so a commit from a logic thread would run widget code there. So one made off the frame thread
     * waits for the next frame, and one made on it, or before frames begin, is made at once.
     */
    private void scale(Runnable write) {
        Thread frames = frameThread;
        if (frames == null || frames == Thread.currentThread()) {
            write.run();
        } else {
            scaleWrites.add(write);
            requestFrame();
        }
    }

    /**
     * Set the zoom limits and the step {@link #zoomIn}/{@link #zoomOut} move by. The step is a <b>factor</b>, not
     * an increment: zoom is perceived as a ratio, so a fixed increment is a huge jump at the bottom of the range
     * and an imperceptible one at the top. 1.25 means each press is 25% more.
     *
     * <p>Re-clamps the current factor immediately, so narrowing the range cannot leave the UI outside it.
     */
    public Gui zoomRange(float min, float max, float step) {
        scale(() -> metrics.zoomRange(min, max, step));
        return this;
    }

    /**
     * Zoom in one step, clamped to the configured maximum: gliding there if {@link #zoomGlide} is set, and from where a
     * glide is already heading, so three presses are three steps. {@link #zoom(float)} is always at once.
     */
    public Gui zoomIn() {
        scale(metrics::zoomIn);
        requestFrame();
        return this;
    }

    /** Zoom out one step, clamped to the configured minimum, as {@link #zoomIn} does. */
    public Gui zoomOut() {
        scale(metrics::zoomOut);
        requestFrame();
        return this;
    }

    /** Back to 1.0 — unscaled, gliding as a step does. */
    public Gui resetZoom() {
        scale(metrics::resetZoom);
        requestFrame();
        return this;
    }

    /**
     * The smallest canvas the UI is laid out on. When the window is smaller than this, the content is still laid
     * out at the minimum and the root scrolls over it, rather than the UI being squeezed into a size it cannot
     * represent — where a flexible region absorbs the whole deficit and disappears.
     *
     * <p><b>The root stays the window.</b> Only its flow is laid out on the minimum, so what is anchored to the
     * root's frame rather than its flow — a {@link Node#dock docked} title bar, an overlay — keeps to the window
     * and never scrolls: a window's controls are reachable at any zoom. A root that disallows scrolling on an
     * axis crops its content on that axis instead.
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
     * is exactly when this one is what saves the layout.
     *
     * <p><b>Size it to what cannot scroll, not to the content.</b> Every window under the minimum scrolls as a
     * whole, so a minimum as tall as content that a growing body already scrolls gives each such window two
     * scrollbars side by side, the root's and the body's, and scrolls the status bar off the bottom. A window
     * arrives under it more often than it sounds: a size saved from an earlier run, or a screen shorter than the
     * first-run size. The layout reports that case through {@code vexelray.diag}
     * (docs/reference/scrolling.md §2, §3).
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
     * {@link #resizeBorder} in pixels, as of the last {@link dev.vexelray.gui.core.spi.Seams#frame} — resolved there so it answers for the same
     * zoom and density that frame was laid out at. {@code 0} means "the platform's own".
     */
    public int resizeBorderPx() {
        return metrics.resizeBorderPx();
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
     * framework lays out in whatever viewport {@link dev.vexelray.gui.core.spi.Seams#frame} is handed and hit-tests input against those rects,
     * so the two must be the same coordinate space. On a Retina-class display, points and pixels differ by this
     * factor: lay out in framebuffer pixels and deliver input in {@code CLIENT} (point) space and every hit test
     * is out by it. On a 1:1 display the two spaces coincide, which is exactly why the mismatch survives
     * development on such a machine and surfaces on the first dense one.
     */
    public Gui dpi(float scale) {
        scale(() -> metrics.dpi(scale));
        return this;
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
     * Declare {@code node}'s context menu: what is on it and the message each item sends, set before the right click
     * rather than worked out during it (vexelray-framework's {@code docs/threading.md}, T7.5). The form for an
     * application's menus; {@link dev.vexelray.gui.core.spi.Seams#onContextMenu} is the widget library's.
     *
     * {@snippet :
     * gui.menu(row, Menu.builder()
     *         .item("Open", open, new Open(file))
     *         .item("Delete", !file.readOnly(), delete, new Delete(file))
     *         .build());
     * }
     *
     * <p><b>A declaration replaces the last one</b>, unlike sources, which accumulate: it is the node's menu as of now,
     * so when what the menu should hold changes, declare it again. {@link Menu#NONE} takes it away. It follows the
     * same nearest-node rule as a source, and on a node that has both, the sources' items come first, so a widget's
     * defaults lead and the application's follow.
     */
    public Gui menu(Node node, Menu menu) {
        input.menu(node.id(), java.util.Objects.requireNonNull(menu, "menu"));
        if (!menu.isEmpty() && input.menuPresenter() == null) {
            presentMenus();
        }
        return this;
    }

    /** Install the default presenter if this tree still has none, once even when two threads race to ask. */
    private void presentMenus() {
        synchronized (menusLock) {
            MenuPresenter.Default made = DefaultMenus.PRESENTER;
            if (input.menuPresenter() == null && made != null) {
                input.menuPresenter(made.create(this));
            }
        }
    }

    /** The {@link MenuPresenter.Default} on the class path, looked up once: the first registered, or none. */
    private static final class DefaultMenus {
        static final MenuPresenter.Default PRESENTER = java.util.ServiceLoader
                .load(MenuPresenter.Default.class, Gui.class.getClassLoader())
                .findFirst()
                .orElse(null);
    }

    /**
     * The topic this tree hears device input on ({@code vexelray.gui.<tree>.input}): what a window's input source
     * publishes to, such as tactroller-atchung's {@code InputPublisher} named {@code gui.input().name()}, and what a
     * driver that types or clicks into this tree publishes on. Per tree, so two windows on one bus hear their own.
     */
    public Topic<sibarum.tactroller.api.InputEvent> input() {
        return input.input();
    }

    /** The touchpad gestures this tree's dispatcher recognises, published on its own topic: see {@link #input()}. */
    public Topic<sibarum.tactroller.api.GestureEvent> gestures() {
        return input.gestures();
    }

    /** The click topic: {@code gui.bus().subscribe(gui.clicks(), ...)} to react to clicks anywhere (any button). */
    public Topic<ClickEvent> clicks() {
        return clickTopic;
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
        return transfers.held();
    }

    /** Put something in hand for a paste, or {@link Transfer#NONE} to drop what is held. Safe from any thread. */
    public Gui transfer(Transfer held) {
        transfers.hold(held);
        return this;
    }

    /** The drag in flight over this tree, or null — for a renderer drawing the ghost and the drop indicator. */
    public DragSession dragSession() {
        return input.dragSession();
    }

    private Gui watchResize(Node node, java.util.function.Consumer<NodeLayout> handler) {
        readModels.onResize(node.id(), handler);
        return this;
    }

    /**
     * Drop every registration {@code node} holds — input handlers, claims, focusability, and its
     * geometry observer — and clear focus if it held it. Removing a node does this automatically; this is for
     * releasing a node's registrations while keeping the node.
     */
    public Gui releaseNode(Node node) {
        releaseNodeId(node.id());
        return this;
    }

    /** The reconciler's removal seam: everything keyed by a node id is dropped when the node leaves the tree. */
    private void releaseNodeId(long id) {
        boundHistories.remove(id);
        input.clearHandlers(id);
        for (Topic<?> topic : nodeTopics.values()) {
            routes.remove(topic, id);
        }
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

    /**
     * Bind {@code shortcut} to a message: pressing it sends {@code message} through {@code to}. The form for an
     * application's shortcuts (vexelray-framework's {@code docs/threading.md}, T7.5): the binding is declared, the
     * dispatcher decides on the GUI thread who the chord reaches, and what leaves is the message.
     */
    public <T> Gui shortcut(Shortcut shortcut, sibarum.atchung.Sender<T> to, T message) {
        seams.shortcut(shortcut, sends(to, message));
        return this;
    }

    /**
     * Claim {@code chord} for {@code node} at {@code scope}, and send {@code message} through {@code to} when it is
     * pressed: {@link #claim(Node, Trigger, ClaimScope, Runnable)}'s preemption, with a message where the command was.
     * The form for an application's claims (vexelray-framework's {@code docs/threading.md}, T7.5).
     */
    public <T> Gui claim(Node node, Trigger chord, ClaimScope scope, sibarum.atchung.Sender<T> to, T message) {
        seams.claim(node, chord, scope, sends(to, message));
        return this;
    }

    /**
     * Make {@code node} something a drag can begin on, carrying {@code payload}: the declared form of
     * {@link dev.vexelray.gui.core.spi.Seams#onDragSource}, for an application (vexelray-framework's {@code docs/threading.md}, T7.5). The payload is
     * the node's, set ahead of time, so the dispatcher needs no answer when the press turns into a drag; declare it
     * again when what the node stands for changes. {@code null} makes the node not draggable.
     */
    public Gui draggable(Node node, dev.vexelray.gui.core.drop.Payload payload) {
        if (payload == null) {
            input.dropDragSource(node.id());
        } else {
            input.onDragSource(node.id(), (x, y) -> payload);
        }
        return this;
    }

    /** What a declared binding runs: the send it names, and nothing else. */
    private static <T> Runnable sends(sibarum.atchung.Sender<T> to, T message) {
        java.util.Objects.requireNonNull(to, "to");
        java.util.Objects.requireNonNull(message, "message");
        return () -> to.send(message);
    }

    /** Drop every claim {@code node} holds on {@code chord}. */
    public Gui releaseClaim(Node node, Trigger chord) {
        input.releaseClaim(node.id(), chord);
        return this;
    }

    /** {@link #releaseClaim(Node, Trigger)}, kept so code compiled against it before still links. */
    public Gui releaseClaim(Node node, Shortcut chord) {
        return releaseClaim(node, (Trigger) chord);
    }

    /** Drop {@code node}'s {@link dev.vexelray.gui.core.spi.Seams#claimAny wildcard claim}; its claims on named chords stay. */
    public Gui releaseClaimAny(Node node) {
        input.releaseClaimAny(node.id());
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
     * <p>Each press goes through {@link #undo(History)} / {@link #redo(History)}, so an entry that names a
     * {@link dev.vexelray.gui.core.edit.Change#target() target} is undone in two presses when its target is not
     * focused — the first takes the user there — and an entry that names none applies at once, as before. The
     * entry is applied on a handler thread, as a claimed command always was.
     */
    public Gui history(Node node, History history, ClaimScope scope) {
        seams.claim(node, Shortcut.of(Key.Z, Modifier.CONTROL), scope, () -> undo(history));
        seams.claim(node, Shortcut.of(Key.Z, Modifier.CONTROL, Modifier.SHIFT), scope, () -> redo(history));
        seams.claim(node, Shortcut.of(Key.Y, Modifier.CONTROL), scope, () -> redo(history));
        boundHistories.put(node.id(), history);
        return this;
    }

    /**
     * The history {@code node}'s edits belong in: the one bound ({@link #history(Node, History, ClaimScope)}) on
     * the nearest ancestor-or-self, else the window's ({@link #history(History)}), else null. A widget records here
     * rather than keeping a stack of its own, so that what the user typed in a field is undone with everything
     * else they did in the window, and by the same Ctrl+Z (docs/plans/undo.md §5).
     *
     * <p>Safe from any thread: the ancestry is read from the published semantic snapshot, never the live tree, so
     * a node built since the last frame resolves to the window's history until it has been laid out once.
     */
    public History historyFor(Node node) {
        return historyFor(node.id());
    }

    /** {@link #historyFor(Node)} by id — what a menu opened on a node asks. */
    private History historyFor(long nodeId) {
        dev.vexelray.gui.core.layout.SemanticSnapshot sem = semanticSnapshot();
        for (long id = nodeId; id != -1; ) {
            History bound = boundHistories.get(id);
            if (bound != null) {
                return bound;
            }
            SemanticNode n = sem.node(id);
            if (!n.present()) {
                break;
            }
            id = n.parentId();
        }
        return boundHistories.get(root.id());
    }

    /** The window's history — the one bound on the root by {@link #history(History)} — or null. */
    public History history() {
        return boundHistories.get(root.id());
    }

    /**
     * Undo one step of {@code history} the way Ctrl+Z does: if the entry on top edits a control the user is not
     * at, take them there and stop; if it is disabled, show it and refuse; if it has left the tree, refuse;
     * otherwise apply it (docs/plans/undo.md §2). From any thread — an Undo button or menu item calls this rather
     * than {@link History#undo()}, which would change something out of sight. Judged in the next frame; what it
     * came to is told to {@link dev.vexelray.gui.core.spi.Seams#onUndo}.
     */
    public Gui undo(History history) {
        undoing.request(java.util.Objects.requireNonNull(history, "history"), false);
        return this;
    }

    /** {@link #undo(History)}, in the other direction, revealing and refusing for exactly the same reasons. */
    public Gui redo(History history) {
        undoing.request(java.util.Objects.requireNonNull(history, "history"), true);
        return this;
    }

    /** The application-wide binding: {@link #history(Node, History, ClaimScope)} on the root, always in force. */
    public Gui history(History history) {
        java.util.Objects.requireNonNull(history, "history").announceOn(bus, address(History.ADDRESS));   // heard in a mailbox
        return history(root, history, ClaimScope.GLOBAL);
    }

    /**
     * Every key press and what became of it — which node had focus, and whether a claim preempted delivery.
     * Fires for <b>all</b> keys including ones the framework or a claim handled, so an extension can see keys it
     * would otherwise never be told about. Observation only: subscribing cannot cancel or redirect anything.
     */
    public Topic<KeyRouted> keyRoutes() {
        return keyRouteTopic;
    }

    /** The focus-change topic: {@code gui.bus().subscribe(gui.focusEvents(), ...)}. */
    public Topic<FocusEvent> focusEvents() {
        return focusEventTopic;
    }

    /**
     * Where keyboard focus is right now, how it got there, and whether the window is in front — the value the
     * renderer draws the focus ring from (see {@link dev.vexelray.gui.core.style.FocusRing}).
     */
    public dev.vexelray.gui.core.input.FocusState focusState() {
        return input.focusState();
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
     * Begin carrying the pointer along with an animation, until the hand takes it back — see
     * {@link dev.vexelray.gui.core.input.PointerRide}. Safe from any thread.
     */
    public dev.vexelray.gui.core.input.PointerRide ridePointer() {
        return input.ridePointer();
    }

    /**
     * What letting go of the button means during a drag: {@link dev.vexelray.gui.core.drop.DragRelease#KEEP} (the
     * default) keeps it with the pointer until a click puts it down, with the wheel free meanwhile;
     * {@link dev.vexelray.gui.core.drop.DragRelease#DROP} puts it down where the button comes up, the way most other
     * software does.
     *
     * <p>A preference, and the user's rather than the application's: wire it to a setting they can reach. Every drag
     * in this tree follows it, so an application has one protocol whichever it is. Safe from any thread, and takes
     * effect from the next drag.
     */
    public Gui dragRelease(dev.vexelray.gui.core.drop.DragRelease release) {
        input.dragRelease(release);
        return this;
    }

    /** What letting go of the button means during a drag — see {@link #dragRelease(dev.vexelray.gui.core.drop.DragRelease)}. */
    public dev.vexelray.gui.core.drop.DragRelease dragRelease() {
        return input.dragRelease();
    }

    /**
     * Glide the wheel: each notch moves where the view is heading, and the view closes half the remaining distance
     * every {@code halfLife}. {@link java.time.Duration#ZERO} (the default) scrolls at once, which is the
     * reduced-motion setting, and what a test that steps frames by hand wants.
     *
     * <p>Opt-in for the reason every motion here is: the application decides how its time passes. A notch is still a
     * fixed distance either way — the wheel is position control, and that is not changed — only its delivery is.
     * See {@code ScrollGlide} for why the curve is a half-life and not a duration.
     */
    public Gui scrollGlide(java.time.Duration halfLife) {
        glideNanos = halfLife.toNanos();
        applyMotionKnobs();
        return this;
    }

    /**
     * Fade the focus ring: in round the node focus reaches and out round the one it left, each over
     * {@code duration}. {@link java.time.Duration#ZERO} (the default) shows and hides it at once — opt-in, like
     * {@link #scrollGlide}, because the application decides how its time passes.
     */
    public Gui focusFade(java.time.Duration duration) {
        focusFadeNanos = duration.toNanos();
        applyMotionKnobs();
        return this;
    }

    /**
     * Glide the zoom steps ({@link #zoomIn}, {@link #zoomOut}, {@link #resetZoom}): each moves where the zoom is
     * heading, and the zoom closes half the remaining distance every {@code halfLife}, as the wheel does under
     * {@link #scrollGlide}. {@link java.time.Duration#ZERO} (the default) steps at once, which is the reduced-motion
     * setting. {@link #zoom(float)} is always at once: a restore or a slider says exactly where it wants to be.
     */
    public Gui zoomGlide(java.time.Duration halfLife) {
        zoomGlideNanos = halfLife.toNanos();
        applyMotionKnobs();
        return this;
    }

    // ------------------------------------------------------------ graphics choices
    //
    // Two choices, kept apart because each costs the user something different: flat shading gives up depth cues
    // (what is raised, what is pressed), reduced motion gives up continuity (where a thing came from). The people who
    // want one off are mostly not the people who want the other: a weak GPU still wants its transitions, and a user
    // who needs motion reduced still wants to see what is raised. Simplified graphics is both at once.
    //
    // Each is decided by, in rising order: the code or the user's settings; the run's preset,
    // -Dvexelray.simplifiedGraphics; the run's own property for that choice, -Dvexelray.flatShading or
    // -Dvexelray.reducedMotion. A run's word is final.

    /** The settings key a user's choice of {@link #flatShading(boolean)} is saved under. */
    public static final String FLAT_SHADING = "flatShading";

    /** The settings key a user's choice of {@link #reducedMotion(boolean)} is saved under. */
    public static final String REDUCED_MOTION = "reducedMotion";

    /** The settings key for {@link #simplifiedGraphics(boolean)}, both choices at once. */
    public static final String SIMPLIFIED_GRAPHICS = GraphicsChoice.PRESET;

    /** {@code -Dvexelray.flatShading=true|false}: decides {@link #flatShading()} for one run. */
    public static final String FLAT_SHADING_PROPERTY = GraphicsChoice.property(FLAT_SHADING);

    /** {@code -Dvexelray.reducedMotion=true|false}: decides {@link #reducedMotion()} for one run. */
    public static final String REDUCED_MOTION_PROPERTY = GraphicsChoice.property(REDUCED_MOTION);

    /**
     * {@code -Dvexelray.simplifiedGraphics=true|false}: decides both choices for one run, except one its own property
     * decides. Prefixed like {@code vexelray.foreground}, the other framework-wide choice a run is launched with.
     */
    public static final String SIMPLIFIED_GRAPHICS_PROPERTY = GraphicsChoice.property(SIMPLIFIED_GRAPHICS);

    /**
     * Flat shading: every box drawn without depth. Lit fills are drawn plain, shadows are dropped and letterpressed
     * text is drawn once, so the GPU pays for a box's edge and nothing more. The same layout in the same colours.
     *
     * <p>A user's preference more than the application's, like {@link #dragRelease}: wire it to a setting they can
     * reach ({@link #graphics(dev.vexelray.gui.core.app.Settings)}). Safe from any thread; takes effect from the next
     * frame. {@link #FLAT_SHADING_PROPERTY} or {@link #SIMPLIFIED_GRAPHICS_PROPERTY}, when set, wins over this.
     */
    public Gui flatShading(boolean on) {
        flatShading.ask(on);
        requestFrame();
        return this;
    }

    /** Whether boxes are drawn flat — see {@link #flatShading(boolean)}. */
    public boolean flatShading() {
        return flatShading.on();
    }

    /**
     * Reduced motion: every animation lands on its end state at once. The scroll glide and the focus fade as if set
     * to {@link java.time.Duration#ZERO}, a steady caret, and whatever a clock attached to this tree (Kronometer's
     * {@code KronoGui}) times. The motion knobs keep what the application asked for, so turning this off again
     * restores them.
     *
     * <p>A user's preference more than the application's: wire it to a setting they can reach
     * ({@link #graphics(dev.vexelray.gui.core.app.Settings)}). Safe from any thread; takes effect from the next frame,
     * and an animation already running finishes as it began. {@link #REDUCED_MOTION_PROPERTY} or
     * {@link #SIMPLIFIED_GRAPHICS_PROPERTY}, when set, wins over this.
     */
    public Gui reducedMotion(boolean on) {
        reducedMotion.ask(on);
        applyMotionKnobs();
        requestFrame();
        return this;
    }

    /** Whether motion is reduced — see {@link #reducedMotion(boolean)}. */
    public boolean reducedMotion() {
        return reducedMotion.on();
    }

    /**
     * What the operating system says about reduced motion — Windows' "Animation effects" off, macOS's "Reduce
     * motion", a Linux desktop's animations disabled — as {@code dev.vexelray.os.NativePlatform#reducedMotion}
     * reports it; {@code null} where it cannot say. The default {@link #reducedMotion()} has until the code or the
     * user's settings ask, because a user who needs motion reduced has already said so once, to the OS.
     *
     * <p>{@code GuiApp} tells every tree it presents; a tree with no window (a test, a headless capture) is never
     * told, and keeps motion. Safe from any thread; takes effect from the next frame.
     */
    public Gui systemReducedMotion(Boolean reduced) {
        reducedMotion.system(reduced);
        applyMotionKnobs();
        requestFrame();
        return this;
    }

    /**
     * What the user chose for every VexelRay application — {@code null} for a choice they have not made — over the
     * OS's word and under anything this application's code or settings ask. {@code GuiApp} reads it from the suite's
     * store ({@code SuiteGraphics}) and tells every tree it presents; it is also how simplified graphics is switched on
     * when a struggling machine is offered it, without touching what the application itself asked.
     */
    public Gui suiteGraphics(Boolean flat, Boolean reduced) {
        flatShading.suite(flat);
        reducedMotion.suite(reduced);
        applyMotionKnobs();
        requestFrame();
        return this;
    }

    /**
     * Simplified graphics: {@link #flatShading(boolean) flat shading} and {@link #reducedMotion(boolean) reduced
     * motion} together, the setting for a machine that should spend as little as it can on drawing.
     */
    public Gui simplifiedGraphics(boolean on) {
        flatShading.ask(on);
        return reducedMotion(on);
    }

    /** Whether both choices are on — see {@link #simplifiedGraphics(boolean)}. */
    public boolean simplifiedGraphics() {
        return flatShading() && reducedMotion();
    }

    /**
     * The user's saved graphics choices from {@code settings}: {@link #SIMPLIFIED_GRAPHICS} first, as both, then
     * {@link #FLAT_SHADING} and {@link #REDUCED_MOTION}, each over it. A key the store does not have asks nothing,
     * so its choice stays as it was: what the code asked, or else the OS's default.
     */
    public Gui graphics(dev.vexelray.gui.core.app.Settings settings) {
        if (settings.has(SIMPLIFIED_GRAPHICS)) {
            boolean both = settings.getBoolean(SIMPLIFIED_GRAPHICS, false);
            flatShading.ask(both);
            reducedMotion.ask(both);
        }
        if (settings.has(FLAT_SHADING)) {
            flatShading.ask(settings.getBoolean(FLAT_SHADING, false));
        }
        if (settings.has(REDUCED_MOTION)) {
            reducedMotion.ask(settings.getBoolean(REDUCED_MOTION, false));
        }
        applyMotionKnobs();
        requestFrame();
        return this;
    }

    /** Hand the dispatcher the motion knobs as they apply now: what was asked for, or none while motion is reduced. */
    private void applyMotionKnobs() {
        boolean still = reducedMotion();
        input.scrollGlide(still ? 0L : glideNanos);
        input.focusFade(still ? 0L : focusFadeNanos);
        long zoomHalfLife = still ? 0L : zoomGlideNanos;
        scale(() -> metrics.zoomGlide(zoomHalfLife));   // turning motion off mid-glide lands the zoom: a write
    }

    /**
     * The look every widget and the renderer's own chrome resolve their colours through — {@link Theme#DARK}
     * unless replaced. There is no other source of colour in the framework.
     */
    public Theme theme() {
        return theme;
    }

    /**
     * Install the theme, at any time. A node holds a {@code Paint} and a {@code Finish}, not a colour, and the renderer
     * resolves them against the tree's theme every frame — so a swap after the UI is built repaints all of it, with
     * nothing rewritten. It lands with the frame's other edits, and whatever the tree's {@code PaintMotion} does to a
     * hover it does to every colour the swap changes.
     *
     * <p>What it cannot reach is a colour resolved early and written as one: {@code background(theme.color(role))} is
     * the same constant in every theme after it. Write the role.
     */
    public Gui theme(Theme theme) {
        this.theme = theme == null ? Theme.DARK : theme;
        sink.post(new Mutation.Retheme(this.theme));
        return this;
    }

    /**
     * The theme the tree is drawn in: the last one {@link #theme(Theme)} asked for whose edit the tree has applied.
     * The renderer's, so that a swap is drawn in the same frame its fades start; everything else asks {@link #theme()}.
     */
    public Theme drawnTheme() {
        return reconciler.theme();
    }

    /**
     * Ask for the next frame to be <em>drawn</em>, for a change the tree cannot see. Safe from any thread; any
     * number of calls before the frame arrives ask for one draw.
     *
     * <p><b>Most code never needs this.</b> A frame draws whenever the tree changed — any {@code Node} write that
     * actually changes a value, structure, layout, scroll, motion — because the change itself is what says so.
     * What is left is content drawn outside the tree and shown in it: a viewport a GPU pass re-rendered (those from
     * {@code GuiApp.viewport} ask on their own), a texture rewritten under a node whose props did not move.
     */
    public void requestFrame() {
        frameRequested.set(true);
        wake("frame requested");
    }

    /**
     * Whether the frame just run has something new to show, and clear it — the host's question between
     * {@link dev.vexelray.gui.core.spi.Seams#frame} and drawing. A host that does not ask simply draws every frame, as before.
     *
     * <p>GUI thread, after {@link dev.vexelray.gui.core.spi.Seams#frame}. Not cleared by {@code frame} itself: a frame that runs and is not drawn
     * (a minimized window still drains) leaves the change owed to the next one that is.
     */
    public boolean takeDrawNeeded() {
        boolean was = drawNeeded;
        drawNeeded = false;
        return was;
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
     * A {@link Scroller} around {@code content}: a box that masks it and scrolls it along both axes, with no bar.
     * Place {@link Scroller#node()}; put a bar beside it, bound to {@link Scroller#x()} or {@link Scroller#y()}
     * (docs/plans/scrolling.md).
     */
    public Scroller scroller(Node content) {
        return new Scroller(this, trees.scroller(), content);
    }

    /** A Scroller's own watch on its node's scroll: the range it publishes (ReadModels.onScroll). */
    void onScroll(Node node, java.util.function.Consumer<NodeLayout> handler) {
        readModels.onScroll(node.id(), handler);
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

    /**
     * A widget's mailbox: {@code topic}'s messages delivered to {@code stage} on the GUI thread, in each frame's
     * drain, after input is dispatched and with the tree's own mutations, before layout. This is how a message from
     * an application's logic reaches a widget's state: the logic publishes a command record and the widget applies
     * it here, on the thread that owns it, so the widget needs no lock and the logic never touches a widget
     * (vexelray-framework's {@code docs/threading.md}, T3.8).
     *
     * <p>For widget code, which is the framework's: what runs in {@code stage} runs inside the frame budget. The
     * topic's kind decides what waits: on a state topic only the last write per key since the last frame, on an edge
     * topic every message, in order. Every widget's mailbox is one queue, so two widgets' messages are applied in the
     * order they were sent, and what one logic tick wrote is applied in one frame, whole. An edge mailbox that
     * overflows is a fault naming the topic.
     *
     * @param capacity how many edges may wait for one frame
     */
    public <T> Subscription mailbox(Topic<T> topic, sibarum.atchung.Subscriber<T> stage, int capacity) {
        return widgetInbox.subscribe(topic, stage, capacity);
    }

    /**
     * As {@link #mailbox(Topic, sibarum.atchung.Subscriber, int)}, handed only the messages whose key is in
     * {@code keys}: a filter a view declares on a state topic (vexelray-framework's {@code docs/threading.md}, T4.8), so
     * it is never handed a message it would discard. Only a state topic has keys, so a filter on an edge is refused.
     */
    public <T> Subscription mailbox(Topic<T> topic, sibarum.atchung.Subscriber<T> stage, int capacity,
                                    java.util.Set<?> keys) {
        return widgetInbox.subscribe(topic, stage, capacity, keys);
    }

    /**
     * A widget's mailbox for the messages on {@code topic} {@link Addressed addressed} to it: as
     * {@link #mailbox(Topic, sibarum.atchung.Subscriber, int)}, routed by address, so a widget is handed only its own.
     * Every widget on a topic shares one route table, and a message for an address nobody answers to is dropped and
     * counted. GUI thread only, as building and closing a widget are.
     *
     * @throws IllegalStateException when another mailbox on {@code topic} already answers to {@code address}
     */
    public <T extends Addressed> Subscription mailbox(Topic<T> topic, Object address,
                                                      sibarum.atchung.Subscriber<T> stage) {
        return routes.add(topic, address, stage);
    }

    /**
     * Hear {@code kind} of event on {@code node} as a message: the form for a view, which is an application's
     * (vexelray-framework's {@code docs/threading.md}, T7.4). The dispatcher publishes each event on this tree's topic
     * for the kind, addressed to the node, and it reaches {@code stage} in the widget tick, on the GUI thread, after
     * input is dispatched and before the tree's mutations are applied: so a click is heard, and what the view writes
     * about it drawn, in the frame the click arrived.
     *
     * {@snippet :
     * gui.hear(swatch, NodeEvent.CLICK, click -> pick.send(new Pick(colour)))
     *    .hear(swatch, NodeEvent.STATE, state -> swatch.border(look.at(state)));
     * }
     *
     * <p><b>A message, not a call.</b> The event is queued with every other message for the widgets, under its kind:
     * every click arrives, in order; for {@link NodeEvent#STATE} and {@link NodeEvent#RESIZE} only the last per node
     * in a tick, so hovering across a row and off it again within a frame is heard as where the pointer ended.
     * Hearing a node is what makes it a target, as registering a handler did: a click bubbles to the nearest node that
     * hears clicks, and hearing {@link NodeEvent#KEY} makes a node focusable.
     *
     * <p><b>Once per node and kind, and never over a widget's own.</b> A node a widget already handles that kind on
     * belongs to the widget, which says what happened to it on topics of its own; hearing it as well is refused rather
     * than either silently replacing the other. Released with the node.
     *
     * <p>The node-level registrations ({@link dev.vexelray.gui.core.spi.Seams#onClick}, {@link dev.vexelray.gui.core.spi.Seams#onState} and the rest) are the widget library's, which
     * is the GUI and needs its events during dispatch, in order with the input around them.
     *
     * @throws IllegalStateException when the node already has its one handler of {@code kind}
     */
    public <E> Gui hear(Node node, NodeEvent<E> kind, sibarum.atchung.Subscriber<? super E> stage) {
        long id = node.id();
        if (kind.taken(input, this, id)) {
            throw new IllegalStateException("node " + id + " already has a " + kind.name() + " handler, which is a "
                    + "widget's: hear what the widget publishes about it instead of the node beneath it");
        }
        Topic<NodeEvent.Message<E>> topic = nodeTopic(kind);
        routes.add(topic, id, message -> stage.on(message.event()));
        kind.listen(input, this, id, event -> bus.publish(topic, new NodeEvent.Message<>(id, event)));
        return this;
    }

    /** This tree's topic for {@code kind}, made the first time a node hears one. */
    @SuppressWarnings("unchecked")
    private <E> Topic<NodeEvent.Message<E>> nodeTopic(NodeEvent<E> kind) {
        return (Topic<NodeEvent.Message<E>>) nodeTopics.computeIfAbsent(kind, k -> {
            // Named per tree, because node ids are per tree: two trees on one bus would each hear the other's node 7
            // (T4.6). The kind decides whether a node's events fold.
            String name = "vexelray.gui." + treeName + ".node." + k.name();
            Class<NodeEvent.Message<E>> type = (Class<NodeEvent.Message<E>>) (Class<?>) NodeEvent.Message.class;
            return k.isState()
                    ? Topic.state(name, type, NodeEvent.Message::node)
                    : Topic.edge(name, type);
        });
    }

    /** {@link NodeEvent#RESIZE}'s side of the read-model, for {@link NodeEvent}. */
    boolean watchesResize(long id) {
        return readModels.watchesResize(id);
    }

    /** {@link NodeEvent#RESIZE}'s registration: in the layout pass, so the message leaves with the size it reports. */
    void watchResize(long id, java.util.function.Consumer<NodeLayout> publish) {
        readModels.onResize(id, publish);
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
        // Owed before the flag is set, so a frame that takes the flag the instant it is set has a unit to take.
        owed.owe();
        if (!woken.compareAndSet(false, true)) {
            owed.pay();
            return;   // a frame is already owed, and saying so twice does not owe two
        }
        if (Probe.ON) {
            String id = "Gui@" + Integer.toHexString(System.identityHashCode(Gui.this));
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
                LOG.warn("the onWork listener threw; frames may stop arriving "
                        + "when nothing else wakes the loop", t);
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
     * {@link dev.vexelray.gui.core.spi.Seams#onWork} exists to prevent for mutations, in the one place where a mutation is not what is
     * waiting.
     *
     * <p>Named for its caller's situation rather than made general on purpose: an application has no reason to
     * call this, because everything an application does to a tree goes through a mutation.
     */
    public void wakeForInput() {
        wake("injected input");
    }

    /**
     * Whether the loop still owes this tree anything: a wake no frame has taken, a frame that has taken one and
     * not yet published, a handler that frame dispatched and that has not returned — or the tree's first frame,
     * which a new tree is owed from the start.
     *
     * <p>The read side of the wake, for anything that needs to know whether the tree has settled: chiefly the
     * automation driver's {@code settle}, which waits for a mutation to be reflected before it reads or clicks
     * again (docs/reference/automation.md §5). It is one count rather than the wake flag because the flag is
     * cleared at the top of the frame, and a reader that asked it between there and the publish — in 0.1, or while the
     * click's handler ran on its worker — was told nothing was owed; see {@link Owed}.
     *
     * <p><b>What it does not say.</b> Work the application starts for itself — on {@link #async}, on a thread of
     * its own, on its clock — is not the loop's debt, and this reports false while it is in flight. So this
     * answers "has the loop caught up with what it has been told, and with the handlers that told it more",
     * exactly, and never "is the application finished thinking". A caller that needs the second has to wait on
     * the application's own state, which only the application can name.
     */
    public boolean frameOwed() {
        return owed.any();
    }

    /**
     * Block until {@link #frameOwed} is false, bounded — {@code true} once it is, {@code false} if it still is
     * when {@code timeout} runs out. Woken by the payment that clears the debt rather than by a publish, since a
     * frame that changed nothing publishes nothing and a wait on the next commit would run to its timeout.
     */
    public boolean awaitNothingOwed(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
        return owed.awaitPaid(timeout, unit);
    }

    /** What {@link #frameOwed} is owed, as a phrase a refusal can quote: {@code "frame still owed"}, {@code "a handler still running"}. */
    public String whatIsOwed() {
        return owed.describe();
    }

    /**
     * Attach the source that says how far behind its laid-out position each node is drawn — see
     * {@link LayoutMotion}. {@link LayoutMotion#NONE} (the default) draws everything exactly where layout put it.
     *
     * <p>One source per tree, last call wins, for the same reason as {@link dev.vexelray.gui.core.spi.Seams#onWork}: two of them would each
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
     * How many times the frame has walked the tree to displace it ({@link LayoutMotion}). Public, unlike
     * {@link #presenceWalks}, because the motion sources it measures live in other modules and their tests are the
     * ones that can say whether a still tree walks nothing.
     */
    public long displaceWalks() {
        return displaceWalks;
    }

    /**
     * Attach the source that brings an arriving node up from nothing — see {@link EnterMotion}.
     * {@link EnterMotion#NONE} (the default) shows every node fully the frame it is added. One source per tree, last
     * call wins, from the next edit on.
     */
    public Gui enterMotion(EnterMotion source) {
        this.enterMotion = java.util.Objects.requireNonNull(source, "source");
        reconciler.presences(enterMotion, exitMotion);
        return this;
    }

    /** How many times the frame has walked the tree for presence (arrival and departure); for tests. */
    long presenceWalks() {
        return presenceWalks;
    }

    /** The attached enter motion source, or {@link EnterMotion#NONE}. */
    public EnterMotion enterMotion() {
        return enterMotion;
    }

    /**
     * Attach the source that a {@code Node.remove()} is offered to first, and that draws a node it takes leaving
     * before it is detached — see {@link ExitMotion}. {@link ExitMotion#NONE} (the default) removes at once. One
     * source per tree, last call wins, from the next removal on.
     */
    public Gui exitMotion(ExitMotion source) {
        this.exitMotion = java.util.Objects.requireNonNull(source, "source");
        reconciler.presences(enterMotion, exitMotion);
        return this;
    }

    /** The attached exit motion source, or {@link ExitMotion#NONE}. */
    public ExitMotion exitMotion() {
        return exitMotion;
    }

    /**
     * Attach the source that says what colour each node is drawn in on its way to the colour it was given — see
     * {@link dev.vexelray.gui.core.style.PaintMotion}. {@code PaintMotion.NONE} (the default) draws every colour the
     * moment it is written, which is also the reduced-motion setting. One source per tree, last call wins, as for
     * {@link #motion(LayoutMotion)}.
     */
    public Gui paintMotion(dev.vexelray.gui.core.style.PaintMotion source) {
        this.paintMotion = java.util.Objects.requireNonNull(source, "source");
        // The reconciler is where a colour changes, so it is what tells the source: from the next change on.
        reconciler.repaints(source);
        return this;
    }

    /** The attached paint motion source, or {@code PaintMotion.NONE}. */
    public dev.vexelray.gui.core.style.PaintMotion paintMotion() {
        return paintMotion;
    }

    /**
     * Register the clock this tree's animations run on, for a run with no frame loop to tick it — see
     * {@link AnimationClock}. The clock's own module calls this when it is attached; last call wins.
     */
    public Gui animationClock(AnimationClock clock) {
        this.animationClock = java.util.Objects.requireNonNull(clock, "clock");
        return this;
    }

    /** The registered animation clock, or {@code null} when nothing animates on one. */
    public AnimationClock animationClock() {
        return animationClock;
    }

    /**
     * Whether a frame is owed: mutations are queued and nothing has drawn them yet.
     *
     * <p>For a host that wants to check rather than be told — a diagnostic, or a belt-and-braces term in
     * a pacing supplier. {@link dev.vexelray.gui.core.spi.Seams#onWork} is the load-bearing half; this cannot close the race on its own,
     * because work an application's logic is still doing has not published yet and so has nothing to report.
     */
    public boolean hasPendingWork() {
        return pump.hasPending();
    }

    private RetainedNode frameBody(float viewportW, float viewportH, TextMeasurer tm) {
        // Dispatch this frame's input first, against the previous frame's laid-out tree (§8, §10): a click may
        // register a mutation, which the drain below then applies in the same frame.
        try (Zone probeZone = Probe.zone(Lane.LAYOUT, "dispatch input")) {
            // A handler's effect is not always a mutation, which wakes the loop by being published: it may start a
            // motion or send a message, and to a loop that parks, an effect nothing asked a frame for does not
            // exist until some unrelated event causes one. So a frame that ran a handler asks for the next one,
            // at the cost of a frame per input that finds nothing to do; and it asks before this frame pays.
            if (input.dispatch(reconciler.root())) {
                wake("handler returned");
            }
        }
        // The widgets' mailboxes, before the mutation drain, so the node writes a command makes (a field's text,
        // say) are applied in this frame's drain rather than the next's. Everything staged is applied: a frame has
        // no deadline to carry anything past.
        widgets.run(Long.MAX_VALUE);
        // Drain the mutation pump on the GUI thread: the subscriber applies each Mutation to the reconciler in
        // FIFO order (single writer). The tree is up to date afterward.
        pump.drain();
        // A panel rebuilt by removing what it held and appending new nodes is a replacement: what leaves lets go of its
        // place and fades out over what arrives, rather than doubling the panel (Reconciler.liftReplaced).
        reconciler.liftReplaced();
        // Undo is judged here, against the tree the drain just brought up to date, and before navigation steps,
        // so a reveal it asks for takes its first step this frame.
        undoing.step();
        // Navigation advances here: after the drain, so it reads the tree every edit so far has produced, and
        // before the layout, so a reveal it issues is applied by the very next drain and laid out by the frame
        // after. One step per frame is not a delay, it is the only honest cadence — see Navigation.
        stepNavigations();
        RetainedNode r = reconciler.root();
        // Zoom and density written from another thread since the last frame, in the order they were made: applied
        // here so the State commits, and everything that restyles from it runs, on this thread.
        for (Runnable write = scaleWrites.poll(); write != null; write = scaleWrites.poll()) {
            write.run();
        }
        // A zoom step on its way moves one frame's worth before the size and scale are asked, so this frame is laid
        // out at the zoom it is drawn at; while it is still on its way, the next frame is owed.
        if (metrics.stepZoomGlide(System.nanoTime()) && metrics.zoomGliding()) {
            requestFrame();
        }
        // Size and scale, asked once. Metrics reads zoom and density rather than being pushed them: a worker's
        // shortcut commits to the State from its own thread and the frame notices, so nothing outside this
        // thread ever writes the reconciler's dirty flags. It also publishes the window size if it moved, before
        // the relayout, so observers and the layout see the same value this frame.
        Metrics.Frame m = metrics.measure(viewportW, viewportH);
        float layoutW = m.layoutW();
        float layoutH = m.layoutH();
        if (m.moved()) {
            // A replaced panel's leftovers were lifted at a rectangle in px, which a new size or scale makes meaningless.
            reconciler.dropLifted();
        }
        if (r != null) {
            boolean layoutRan = reconciler.layoutDirty() || m.moved();
            if (layoutRan) {
                // Counted as well as timed. Render-on-demand means a still window should lay out almost never,
                // so a layout count that tracks the frame count is not a slow layout - it is a dirty flag that
                // is always set, and no amount of making the pass faster will fix that.
                try (Zone probeZone = Probe.zone(Lane.LAYOUT, "flex layout")) {
                    FlexLayout.layout(r, viewportW, viewportH, layoutW, layoutH, m.layoutCtx(), tm);
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
                        FlexLayout.layout(r, viewportW, viewportH, layoutW, layoutH, m.layoutCtx(), tm);
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
            LayoutMotion motionNow = motion;
            long now = System.nanoTime();
            motionFrame += now - motionFrameNanos > MOTION_FRAME_GAP_NANOS ? 2 : 1;
            motionFrameNanos = now;
            if (motionNow != LayoutMotion.NONE) {
                // On a layout pass only, not on every frame whose geometry changed: on a frame with no layout (a caret
                // moved, say) every node still holds where and how big it was *drawn*, and settling that would record
                // a node mid-flight as where it belongs and leave it there until the next layout. Only layout moves
                // or resizes anything, so only a frame that laid out has anything to report.
                if (layoutRan) {
                    // Settle is where moves are detected and reported, so it runs on every layout pass whatever
                    // the source says; a move it reports makes the source active before it is asked below.
                    // On a frame whose window size, zoom or density changed, every node is recorded where it now
                    // is and none is reported: the whole window moving under the user's hand is not something
                    // arriving somewhere, and a page that slid its contents on every drag of the edge would be
                    // seasick. That is what lets a whole window be enrolled. The cost: a genuine move landing in
                    // the same frame as a resize jumps.
                    // A node moved on every frame (a drag, a ramp driving a size) is followed, not chased: see
                    // Displacement.settle.
                    Displacement.settle(r, m.moved() ? LayoutMotion.NONE : motionNow, motionFrame);
                }
                // Walked while the source has something in flight, and once more after the frame that last found
                // anything displaced, which is the walk that writes the resting positions back. Past that every
                // node already stands at its recorded position, layout or no layout, so the walk would rewrite
                // each one with itself: hover frames and idle wakes skip it.
                if (displaced || motionNow.active()) {
                    displaceWalks++;
                    moving = Displacement.displace(r, motionNow);
                }
            }
            displaced = moving;
            // Arrival and departure, in the same stage and for the same reason: everything after this reads what
            // is drawn. Walked only while something is arriving or leaving — an edit that started one says so, and
            // a walk that found one asks for the next — and the walk that finds everything at rest is the one that
            // writes the resting values back. A node that has finished leaving is detached here, before publish,
            // so the snapshot never names a node the tree no longer holds.
            boolean presenting = false;
            boolean departing = false;
            if (presencing | reconciler.takePresenceDirty()) {
                gone.clear();
                presenceWalks++;
                presenting = Displacement.present(r, enterMotion, exitMotion, gone);
                presencing = presenting;
                departing = reconciler.depart(gone);
            } else {
                departing = reconciler.depart(java.util.List.of());   // release the last frame's departures
            }
            // Focus is in the semantic snapshot but not in the tree, so a focus move that edits no node (a button
            // has nothing to restyle) has to republish on its own, or the snapshot says focus is where it was.
            // The theme likewise: its focus ring decides ringShown.
            dev.vexelray.gui.core.input.FocusState focusNow = input.focusState();
            boolean focusMoved = !focusNow.equals(publishedFocus) || theme != publishedTheme;
            if (geometryChanged || moving || focusMoved || departing) {
                // Last of the compute phase, because it is a fact about the rects everything above just
                // finished writing: displacement moves a node after layout placed it, and a clip resolved
                // before that would describe a frame nobody sees.
                Clip.resolve(r);
                readModels.publish(r);
                publishedFocus = focusNow;
                publishedTheme = theme;
            }
            transfers.publish(input.dragSession());
            if (moving) {
                // Something is short of where it belongs, so the next frame has work whether or not anything
                // else asks for one. Self-limiting: a displacement that decays to zero stops waking, and one
                // that does not is a motion source that never finishes, which this makes visible as a loop that
                // will not park rather than as a transition that silently freezes half-way.
                wake("motion");
            }
            // The same for colour: a fade in progress draws something different next frame, and stops asking when
            // it arrives.
            boolean fading = paintMotion.moving();
            if (fading) {
                wake("paint motion");
            }
            if (presenting) {
                wake("presence");
            }
            reconciler.clearDirty();
            if (departing) {
                // A node detached above leaves a gap the next layout closes (and the next drain may yet fill), and
                // a release still owed for it: both are the next frame's, so the next frame must come.
                reconciler.markLayoutDirty();
                wake("departure");
            }
            // Render-on-change: everything above that could alter a pixel. The reconciler's bit covers edits that
            // changed something (equal writes leave it clear); the layout covers a size or zoom change that no
            // edit made; geometry covers a caret that moved without reflowing; motion covers a transition between
            // frames. Taken with |=, because a frame that is run and not drawn keeps what it found for the next.
            if (reconciler.takeDrawDirty() | layoutRan | geometryChanged | moving | fading | presenting) {
                drawNeeded = true;
            }
        }
        if (frameRequested.getAndSet(false)) {
            drawNeeded = true;
        }
        return r;
    }


    @Override
    public void close() {
        closed = true;
        input.close();
        mutationSub.close();
        navSub.close();
        synchronized (NAMES) {
            java.util.Set<String> open = NAMES.get(bus);
            if (open != null) {
                open.remove(treeName);
            }
        }
    }

    // --- the widget library's and the host's seams: see dev.vexelray.gui.core.spi.Seams ---

    static {
        dev.vexelray.gui.core.spi.Seams.install(gui -> gui.seams);
    }

    /** This tree's seams, reached through {@code Seams.of(gui)} and from nothing an application is handed. */
    private final dev.vexelray.gui.core.spi.Seams seams = new GuiSeams();

    /** The seams' implementation: an inner class, so each one is the old method on this tree, moved. */
    private final class GuiSeams extends dev.vexelray.gui.core.spi.Seams {

        @Override
        public dev.vexelray.gui.core.input.PointerWarp pointerWarp() {
            return input.pointerWarp();
        }

        @Override
        public dev.vexelray.gui.core.spi.ScrollSource scrollSource(Scroller scroller, boolean vertical) {
            return vertical ? scroller.y() : scroller.x();
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams clock(dev.vexelray.gui.core.spi.Clock clock) {
            Gui.this.clock = clock;
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Clock clock() {
            return Gui.this.clock;
        }

        @Override
        public sibarum.atchung.State<DragState> drag() {
            return transfers.drag();
        }

        @Override
        public sibarum.atchung.State<java.util.Set<Modifier>> modifiers() {
            return modifierState;
        }

        @Override
        public sibarum.atchung.State<Viewport> viewport() {
            return metrics.viewport();
        }

        @Override
        public sibarum.atchung.State<Float> zoom() {
            return metrics.zoom();
        }

        @Override
        public sibarum.atchung.State<Float> dpi() {
            return metrics.dpi();
        }

        @Override
        public sibarum.atchung.State<LayoutSnapshot> layout() {
            return readModels.layout();
        }

        @Override
        public sibarum.atchung.State<SemanticSnapshot> semantics() {
            return readModels.semantics();
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onClick(Node node, Runnable handler) {
            input.onClick(node.id(), handler);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onClick(Node node, java.util.function.Consumer<ClickEvent> handler) {
            input.onClick(node.id(), handler);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onDoubleClick(Node node, Runnable handler) {
            input.onDoubleClick(node.id(), handler);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onDoubleClick(Node node, java.util.function.Consumer<ClickEvent> handler) {
            input.onDoubleClick(node.id(), handler);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onContextClick(Node node, java.util.function.Consumer<ClickEvent> handler) {
            input.onContextClick(node.id(), handler);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onContextMenu(Node node, java.util.function.Consumer<MenuSink> source) {
            input.onContextMenu(node.id(), source);
            if (input.menuPresenter() == null) {
                presentMenus();
            }
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams openMenu(ClickEvent at, java.util.function.Consumer<MenuSink> source) {
            if (input.menuPresenter() == null) {
                presentMenus();
            }
            input.openMenu(at, source);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams menus(MenuPresenter presenter) {
            input.menuPresenter(presenter);
            return this;
        }

        @Override
        public MenuPresenter menus() {
            return input.menuPresenter();
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onState(Node node, java.util.function.Consumer<InteractionState> handler) {
            input.onState(node.id(), handler);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onDrag(Node node, java.util.function.Consumer<DragEvent> handler) {
            input.onDrag(node.id(), handler);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onWheel(Node node, java.util.function.Consumer<dev.vexelray.gui.core.input.WheelEvent> handler) {
            input.onWheel(node.id(), handler);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onDrop(Node node, DropTarget target) {
            input.onDrop(node.id(), java.util.Objects.requireNonNull(target, "target"));
            return this;
        }

        @Override
        public java.util.List<DropTarget> dropTargetsAt(float x, float y) {
            return input.dropTargetsAt(x, y);
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onDragSource(Node node, DragSource source) {
            input.onDragSource(node.id(), java.util.Objects.requireNonNull(source, "source"));
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams watchDrag(Node node, dev.vexelray.gui.core.drop.DragWatch watch) {
            input.watchDrag(node.id(), watch);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onResize(Node node, java.util.function.Consumer<NodeLayout> handler) {
            watchResize(node, handler);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onKey(Node node, java.util.function.Consumer<KeyEvent> handler) {
            input.onKey(node.id(), handler);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onChar(Node node, java.util.function.IntConsumer handler) {
            input.onChar(node.id(), handler);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams reveals(Node node, Reveal reveal) {
            navigator.reveals(node.id(), reveal);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams shortcut(Shortcut shortcut, Runnable command) {
            input.registerShortcut(shortcut, command);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams shortcut(Key key, Runnable command, Modifier... mods) {
            return shortcut(Shortcut.of(key, mods), command);
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams claim(Node node, Trigger chord, ClaimScope scope, Runnable command) {
            input.claim(node.id(), chord, scope, command);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams claim(Node node, Shortcut chord, ClaimScope scope, Runnable command) {
            return claim(node, (Trigger) chord, scope, command);
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams claimAny(Node node, ClaimScope scope, java.util.function.Consumer<Trigger> command) {
            input.claimAny(node.id(), scope, command);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onUndo(java.util.function.Consumer<dev.vexelray.gui.core.edit.UndoEvent> sink) {
            undoing.sink(sink);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onCursorChange(java.util.function.Consumer<CursorShape> sink) {
            input.cursorSink(sink);
            return this;
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onPointerLock(java.util.function.Consumer<Boolean> sink) {
            input.pointerLockSink(sink);
            return this;
        }

        @Override
        public void onWork(Runnable listener) {
            Gui.this.workListener = java.util.Objects.requireNonNull(listener, "listener");
            if (Probe.ON) {
                Probe.mark(Lane.FRAME, "wake.wired", "Gui@" + Integer.toHexString(System.identityHashCode(this)));
            }
            // A wake recorded while nothing was listening was delivered to NO_WAKE and reached no loop, so it is
            // delivered again, to the listener that can act on it. It used to be cleared instead, so the next edit
            // would wake afresh — and that made a tree edited before it was presented read as settled until its
            // first frame: an automation driver connecting in that gap was told `ok v0` and then `(no tree yet)`
            // (docs/plans/background-runs.md). The flag stays set, because the frame it stands for is still owed.
            if (woken.get()) {
                try {
                    listener.run();
                } catch (Throwable t) {
                    if (!warnedWakeFailed) {
                        warnedWakeFailed = true;
                        LOG.warn("the onWork listener threw; frames may stop arriving "
                                + "when nothing else wakes the loop", t);
                    }
                }
            }
        }

        @Override
        public dev.vexelray.gui.core.spi.Seams onPointerWarp(dev.vexelray.gui.core.input.PointerWarp warp) {
            input.pointerWarp(warp);
            return this;
        }

        @Override
        public RetainedNode frame(float viewportW, float viewportH, TextMeasurer tm) {
            // The frame the last wake asked for is this one, so the next edit has to ask again. First thing, before
            // anything below can publish: see the field's note on why this is the top and not the bottom.
            //
            // The flag is cleared here but the debt is not: this frame holds the wake's unit until it has published,
            // and only then pays it. A reader asking in between is still owed this frame (Owed).
            boolean tookWake = woken.getAndSet(false);
            frameThread = Thread.currentThread();
            try {
                return frameBody(viewportW, viewportH, tm);
            } finally {
                if (tookWake) {
                    owed.pay();
                }
                if (!framed) {
                    framed = true;
                    owed.pay();   // the first frame, which a new tree is owed from the start
                }
            }
        }
    }
}
