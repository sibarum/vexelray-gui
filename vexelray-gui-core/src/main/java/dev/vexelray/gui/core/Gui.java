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
import dev.vexelray.gui.core.input.MenuPresenter;
import dev.vexelray.gui.core.input.MenuSink;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.style.Theme;
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

    /** The flat root em, in px, before zoom and DPI (§6 — no cascade). */
    private static final float ROOT_EM_PX = 16f;

    /** Default zoom bounds and step; override with {@link #zoomRange}. */
    private static final float DEFAULT_MIN_ZOOM = 0.25f;
    private static final float DEFAULT_MAX_ZOOM = 4f;
    private static final float DEFAULT_ZOOM_STEP = 1.25f;

    /** Density bounds: 1.0 conventional, 2.0 Retina-class, 3.0 exists; below 1 is not a real display. */
    private static final float MIN_DPI = 1f;
    private static final float MAX_DPI = 4f;

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
    // User zoom, as a coalesced State on the bus (mirroring the viewport): every Length resolves through it, so
    // changing it rescales the whole UI rather than any one property. Read at the top of each frame; a change
    // triggers a relayout the same way a resize does, with no cross-thread write to the reconciler.
    private final State<Float> zoom;
    private final Committer<Float, Float> setZoom;
    private float lastZoom = -1f;
    // Display density (points -> pixels), the other ambient factor every Length resolves through. Separate from
    // zoom because they answer different questions: density keeps a UI the same *physical* size on a denser
    // screen, zoom is the user asking for bigger. Conflating them is why a unit can honour one and not the other.
    private final State<Float> dpi;
    private final Committer<Float, Float> setDpi;
    private float lastDpi = -1f;
    // Zoom limits and step, configurable (see zoomRange). Read on the GUI thread and by zoomIn/zoomOut, which
    // may be called from a worker — volatile is enough, since a stale bound only misses by one step.
    private volatile float minZoom = DEFAULT_MIN_ZOOM;
    private volatile float maxZoom = DEFAULT_MAX_ZOOM;
    private volatile float zoomStep = DEFAULT_ZOOM_STEP;
    // The smallest canvas the UI is laid out on, whatever the window does (see minSize).
    private volatile Length minWidth = Length.ZERO;
    private volatile Length minHeight = Length.ZERO;
    // How far into its own dead space this GUI hands the window manager a resize grip (see resizeBorder), and
    // that Length resolved against this frame's zoom and density — read by the host right after frame().
    private volatile Length resizeBorder = Length.ZERO;
    private int resizeBorderPx;
    // Computed-layout read-model (docs/layout-read-model.md): the latest snapshot workers read via Node.layout(),
    // and the coalesced State observers subscribe to. Published after each layout pass.
    private final State<LayoutSnapshot> layoutState;
    private final Committer<LayoutSnapshot, LayoutSnapshot> setLayout;
    private volatile LayoutSnapshot latestLayout = LayoutSnapshot.EMPTY;
    private long layoutVersion;
    private final LayoutReader layoutReader = () -> latestLayout;
    /**
     * Geometry observers by node id (§ {@link #onResize}). Registered from any thread — a tree is often built
     * off the GUI thread — and delivered from {@link #publishLayout}, so the map is concurrent while each
     * watch's remembered box is read and written on the GUI thread alone.
     */
    private final java.util.concurrent.ConcurrentMap<Long, ResizeWatch> resizeWatches =
            new java.util.concurrent.ConcurrentHashMap<>();
    private float lastViewportW = -1f;
    private float lastViewportH = -1f;
    private float lastLayoutW = -1f;
    private float lastLayoutH = -1f;
    private volatile TextClipboard clipboard = new TextClipboard.InMemory();
    // The look. Not a State on the bus like zoom is: a Length resolves against zoom every frame, whereas a colour
    // is resolved once and written into a prop, so there is nothing per-frame to coalesce (see Theme's class note
    // on what that costs and where it is heading). Volatile because widgets read it from whichever thread built
    // them and from every state handler.
    private volatile Theme theme = Theme.DARK;
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

        State.Builder<Float> zb = State.of(1f);
        this.setZoom = zb.mutation("set", (current, next) -> next);
        this.zoom = zb.build();

        State.Builder<Float> db = State.of(1f);
        this.setDpi = db.mutation("set", (current, next) -> next);
        this.dpi = db.build();

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
        return zoom;
    }

    /**
     * Set the zoom factor, clamped to [{@value #MIN_ZOOM}, {@value #MAX_ZOOM}]. Safe from any thread — it commits
     * to the {@code State}, and the next {@link #frame} notices the change and relays out, so nothing writes to
     * the reconciler off the GUI thread.
     */
    public Gui zoom(float factor) {
        zoom.commit(setZoom, Math.max(minZoom, Math.min(maxZoom, factor)));
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
        this.minZoom = Math.max(0.01f, min);
        this.maxZoom = Math.max(this.minZoom, max);
        this.zoomStep = Math.max(1.0001f, step);
        return zoom(zoom.value());
    }

    /** Zoom in one step, clamped to the configured maximum. */
    public Gui zoomIn() {
        return zoom(zoom.value() * zoomStep);
    }

    /** Zoom out one step, clamped to the configured minimum. */
    public Gui zoomOut() {
        return zoom(zoom.value() / zoomStep);
    }

    /** Back to 1.0 — unscaled. */
    public Gui resetZoom() {
        return zoom(1f);
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
        this.minWidth = width == null ? Length.ZERO : width;
        this.minHeight = height == null ? Length.ZERO : height;
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
        this.resizeBorder = thickness == null ? Length.ZERO : thickness;
        return this;
    }

    /**
     * {@link #resizeBorder} in pixels, as of the last {@link #frame} — resolved there so it answers for the same
     * zoom and density that frame was laid out at. {@code 0} means "the platform's own".
     */
    public int resizeBorderPx() {
        return resizeBorderPx;
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
        return dpi;
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
     * legibility floor, so it cannot work in em and defer (docs/typeset.md §4.3).
     */
    public float rootEmPx() {
        return ROOT_EM_PX;
    }

    /**
     * Set the display density (see {@link #dpi()}), clamped to [{@value #MIN_DPI}, {@value #MAX_DPI}].
     *
     * <p><b>Feed this from the real surface scale, and lay out in the same space input arrives in.</b> The
     * framework lays out in whatever viewport {@link #frame} is handed and hit-tests input against those rects,
     * so the two must be the same coordinate space. On a Retina-class display, points and pixels differ by this
     * factor: lay out in framebuffer pixels and deliver input in {@code CLIENT} (point) space and every hit test
     * is out by it. On a 1:1 display the two spaces coincide, which is exactly why the mismatch survives
     * development on such a machine and surfaces on the first dense one.
     */
    public Gui dpi(float scale) {
        float clamped = Math.max(MIN_DPI, Math.min(MAX_DPI, scale));
        dpi.commit(setDpi, clamped);
        return this;
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
        if (handler == null) {
            resizeWatches.remove(node.id());
            return this;
        }
        resizeWatches.put(node.id(), new ResizeWatch(handler, ordered));
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
        resizeWatches.remove(id);
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
        // Zoom is read here rather than pushed: a worker's shortcut commits to the State from its own thread, and
        // the frame notices — so nothing outside this thread ever writes the reconciler's dirty flags.
        float z = zoom.value();
        float d = dpi.value();
        boolean zoomChanged = z != lastZoom || d != lastDpi;
        boolean viewportChanged = viewportW != lastViewportW || viewportH != lastViewportH;
        // The canvas the layout actually runs on: never smaller than the configured minimum. Resolved against a
        // context built from the *window* size, because a minimum in vw/vh against the clamped size would define
        // itself; em/dp — the units this is for — do not consult the viewport at all.
        LayoutContext windowCtx = new LayoutContext(ROOT_EM_PX, z, d, viewportW, viewportH);
        float layoutW = Math.max(viewportW, minWidth.scalarPx(windowCtx, viewportW));
        float layoutH = Math.max(viewportH, minHeight.scalarPx(windowCtx, viewportH));
        // Resolved every frame like everything else: a zoomed UI whose gutter grew has a grip that grew with it.
        resizeBorderPx = Math.round(resizeBorder.scalarPx(windowCtx, viewportW));
        boolean clampChanged = layoutW != lastLayoutW || layoutH != lastLayoutH;
        if (viewportChanged) {
            // Publish the new size on the bus (coalesced State) before relaying out, so observers and the layout
            // see the same value this frame.
            viewport.commit(setViewport, new Viewport(Math.round(viewportW), Math.round(viewportH)));
        }
        if (r != null) {
            // vw/vh resolve against the laid-out canvas, which is the area that actually exists to fill.
            LayoutContext layoutCtx = new LayoutContext(ROOT_EM_PX, z, d, layoutW, layoutH);
            boolean layoutRan = reconciler.layoutDirty() || viewportChanged || zoomChanged || clampChanged;
            if (layoutRan) {
                FlexLayout.layout(r, layoutW, layoutH, layoutCtx, tm);
                lastViewportW = viewportW;
                lastViewportH = viewportH;
                lastLayoutW = layoutW;
                lastLayoutH = layoutH;
                lastZoom = z;
                lastDpi = d;
            }
            // Reveal requests are answered here: after the layout that says where everything is, and before the
            // publish that tells everyone else. A scroller that moved is laid out a second time rather than left
            // to catch up next frame — a container bakes its scroll offset into its children's positions, so one
            // that moved after the pass would publish rects for where its rows used to be, and the frame's own
            // hit-testing would aim at them. The extra pass is paid only on frames where something asked.
            for (RetainedNode asked : reconciler.takeReveals()) {
                if (reveal(asked)) {
                    FlexLayout.layout(r, layoutW, layoutH, layoutCtx, tm);
                    layoutRan = true;
                }
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
     * Bring {@code target} inside the viewport of every ancestor that scrolls, by the least scrolling that does
     * it; @return whether any of them actually moved.
     *
     * <p>Innermost first, carrying the offset it applied: scrolling the inner container moves the target within
     * it, so an outer scroller has to be asked about where the target has just ended up rather than where it was
     * laid out. Without that, nested scrollers each solve the problem the other just changed.
     *
     * <p>Only containers that actually overflow are asked. One that fits its content has no offset to give, and
     * "reveal" on a page that does not scroll is not an error — it is a request that is already satisfied.
     */
    private static boolean reveal(RetainedNode target) {
        if (target == null || !target.visible() || target.parent == null) {
            return false;   // never in the tree, or gone from it since the ask
        }
        boolean moved = false;
        float x = target.x;
        float y = target.y;
        for (RetainedNode a = target.parent; a != null; a = a.parent) {
            if (!a.visible()) {
                return moved;   // an ancestor is hidden: its geometry is stale, and nothing under it is on screen
            }
            if (a.overflowY) {
                float applied = scrollBy(a, scrollDelta(y, target.h, a.viewY, a.viewH), true);
                y -= applied;
                moved |= applied != 0f;
            }
            if (a.overflowX) {
                float applied = scrollBy(a, scrollDelta(x, target.w, a.viewX, a.viewW), false);
                x -= applied;
                moved |= applied != 0f;
            }
        }
        return moved;
    }

    /**
     * The least scroll that puts {@code [pos, pos + size]} inside {@code [view, view + extent]}: negative to come
     * back, positive to go on, zero when it is already there.
     *
     * <p>The leading edge wins the tie. Going forward never scrolls past the point where the target's own top (or
     * left) reaches the edge of the viewport, which is what stops a row taller than the viewport from being
     * scrolled to its bottom — the top of something too big to see is the part that says what it is.
     */
    private static float scrollDelta(float pos, float size, float view, float extent) {
        float lead = pos - view;
        float trail = (pos + size) - (view + extent);
        if (lead < 0f) {
            return lead;
        }
        return trail > 0f ? Math.min(lead, trail) : 0f;
    }

    /** Move {@code n}'s scroll offset by {@code delta}, clamped to its content; @return what it actually moved. */
    private static float scrollBy(RetainedNode n, float delta, boolean vertical) {
        if (delta == 0f) {
            return 0f;
        }
        float was = vertical ? n.scrollY : n.scrollX;
        float max = vertical ? Math.max(0f, n.contentH - n.viewH) : Math.max(0f, n.contentW - n.viewW);
        float now = Math.max(0f, Math.min(was + delta, max));
        if (vertical) {
            n.scrollY = now;
        } else {
            n.scrollX = now;
        }
        return now - was;
    }

    /**
     * The compute phase: walk the laid-out tree and write each node's derived geometry onto it. Runs on the GUI
     * thread, after layout and before publish, and is the <b>only</b> stage allowed to compute it — publish copies,
     * renderers and widgets read (docs/layout-read-model.md §2.1).
     */
    private static void resolveGeometry(RetainedNode n, TextMeasurer tm) {
        if (!n.visible()) {
            // A hidden subtree is not laid out, so baking metrics from its stale rect would publish geometry
            // describing a position it does not occupy -- and a text node draws from those metrics, not from
            // its rect, so that geometry would be drawn if anything ever read it.
            n.textMetrics = null;
            return;
        }
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
            // An empty document is not "no text" — it is a document with one empty visual line: a caret boundary
            // at offset 0, and hard line number 1. Publishing that geometry is what lets a focused empty field
            // draw its blinking caret from the same read-model everything else reads, and an empty numbered
            // editor still show a "1" in its gutter. Without it the metrics were null, so the caret had nowhere
            // to be — a field looked dead precisely when it was inviting the first keystroke.
            float lineH = tm.intrinsic(n, Axis.VERTICAL, n.textSizePx);
            float viewW = n.viewW > 0f ? n.viewW : TextMetrics.contentWidth(n);
            float viewH = Math.max(1f, n.viewH > 0f ? n.viewH : n.h - 2f * TextMetrics.padY(n));
            float viewX = n.viewW > 0f ? n.viewX : n.x + n.textPadXPx;
            float viewY = n.viewW > 0f ? n.viewY : n.y + TextMetrics.padY(n);
            float top = n.multiline() ? viewY : viewY + switch (n.vAlign()) {
                case TOP -> 0f;
                case MIDDLE -> Math.max(0f, (viewH - lineH) * 0.5f);
                case BOTTOM -> Math.max(0f, viewH - lineH);
            };
            float left = viewX + switch (n.hAlign()) {
                case LEFT, JUSTIFY -> 0f;
                case CENTER -> viewW * 0.5f;
                case RIGHT -> viewW;
            };
            n.textMetrics = new TextMetrics(List.of(
                    new TextMetrics.VisualLine(0, 0, top, lineH, new float[]{left}, 1)));
            return;
        }
        float px = n.textSizePx;
        float[] adv = tm.caretAdvances(n.font(), s, px);
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
                n.lineSpans != null ? n.lineSpans : tm.lineSpans(n.font(), s, wraps ? viewW : 0f, px);

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
        deliverResizes(snap);            // before the State commit, so a handler's edits ride the same drain
        layoutState.commit(setLayout, snap);
    }

    /**
     * Tell each geometry observer whose box moved. Runs on the GUI thread, once per published layout, and does
     * nothing at all when nobody is watching — the common case, and the reason this can sit on the frame path.
     */
    private void deliverResizes(LayoutSnapshot snap) {
        if (resizeWatches.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, ResizeWatch> entry : resizeWatches.entrySet()) {
            NodeLayout computed = snap.node(entry.getKey());
            if (!computed.present()) {
                continue;                // not in the tree (or not laid out yet): there is no box to report
            }
            ResizeWatch watch = entry.getValue();
            if (computed.rect().equals(watch.box) && computed.content().equals(watch.content)) {
                continue;
            }
            // Recorded before delivery, not after: an ordered handler may mutate, and a mutation that lands in
            // this same frame must not be able to make the next pass look like a change we already announced.
            watch.box = computed.rect();
            watch.content = computed.content();
            if (watch.ordered) {
                watch.handler.accept(computed);
            } else {
                handlers.execute(() -> watch.handler.accept(computed));
            }
        }
    }

    /**
     * One geometry observer: where to send it, and the box it was last told about. The two rects are the
     * comparison, so a scroll — which moves contents inside an unchanged viewport — is not a resize.
     */
    private static final class ResizeWatch {
        private final java.util.function.Consumer<NodeLayout> handler;
        private final boolean ordered;
        private Rect box;
        private Rect content;

        ResizeWatch(java.util.function.Consumer<NodeLayout> handler, boolean ordered) {
            this.handler = handler;
            this.ordered = ordered;
        }
    }

    private static void collectLayout(RetainedNode n, Map<Long, NodeLayout> out) {
        out.put(n.id, new NodeLayout(true,
                new Rect(n.x, n.y, n.w, n.h),
                new Rect(n.viewX, n.viewY, n.viewW, n.viewH),
                n.cornerPx, n.cornerBottomPx,
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
