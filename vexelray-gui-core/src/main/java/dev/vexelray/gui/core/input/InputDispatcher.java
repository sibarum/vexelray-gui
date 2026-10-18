package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.drop.DragSource;
import dev.vexelray.gui.core.drop.Drop;
import dev.vexelray.gui.core.drop.Payload;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.drop.DragSession;
import dev.vexelray.gui.core.drop.DropTarget;
import dev.vexelray.gui.core.drop.DragState;
import dev.vexelray.gui.core.drop.DragWatch;
import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.drop.DragRelease;
import dev.vexelray.gui.core.layout.LayoutEnums.ScrollLock;
import dev.vexelray.gui.core.model.RetainedNode;
import sibarum.atchung.Atchung;
import sibarum.probe.Lane;
import sibarum.probe.Probe;
import sibarum.atchung.Backpressure;
import sibarum.atchung.Pump;
import sibarum.atchung.Subscription;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Framework-owned input dispatch (architecture.md §8). Subscribes a {@link Pump} to the {@link InputTopics#INPUT}
 * topic and, once per frame, drains it on the GUI thread and routes each edge against the current laid-out tree.
 *
 * <p>Scope so far:
 * <ul>
 *   <li><b>Clicks</b> — a left press then release on the same node fires a click, routed to the nearest ancestor
 *       (self included) with a registered handler (leaf→root bubbling) and re-published as a {@link ClickEvent}.</li>
 *   <li><b>Interaction state</b> — from pointer motion + left-button state, each node registered via
 *       {@link #onState} is told when its {@link InteractionState} changes (NORMAL/HOVER/PRESSED), so it can
 *       restyle. State is computed with ancestor-or-self coverage, so hovering a button's label counts as hovering
 *       the button.</li>
 * </ul>
 * Handlers run on the supplied executor (off the GUI thread by default), so a slow handler never stalls rendering.
 *
 * <p>Because {@link #dispatch} runs during the frame, before layout, hit-testing uses the previous frame's rects —
 * the standard one-frame-geometry latency, invisible in practice. Focus routing, capture, wheel, keyboard, and
 * shortcuts build on this same drain in later steps.
 */
public final class InputDispatcher {

    /**
     * Input edges are drained every frame into a bounded mailbox, and overflowing it <b>stops the process</b>.
     *
     * <p><b>This channel carries traffic of two loss classes</b>, which is the whole problem: pointer motion,
     * which coalesces harmlessly, and key, char and button edges, which do not — a shed keystroke is gone, and
     * reads downstream as a key the user never pressed. There is no policy that is right for both, and the
     * previous choice, {@code DROP_OLDEST}, was wrong for the half that matters: under a stall it sheds the
     * oldest, which is exactly the keystrokes, while the newest motion survives.
     *
     * <p>{@link Backpressure#FAIL} does not make this channel correct. It makes it <b>honest</b>. Losing an
     * input edge is not a degradation the framework can absorb on the application's behalf — it is a divergence
     * between what the user did and what the program believes, and every frame after it is computed from a
     * premise that is false. Atchung reports no dropped-message count, so the old policy's failures were not
     * merely bad, they were <em>invisible</em>: undetectable from this side, indistinguishable from a widget
     * bug, and diagnosed by staring at the consumer, which was working perfectly. A crash naming this topic and
     * this capacity is worse to experience and better in every other respect.
     *
     * <p>The alternatives remain closed. {@code BLOCK} would <b>deadlock</b>: in the standard wiring the input
     * bridge publishes from the frame loop and this pump drains on the same thread, so a full mailbox would have
     * the GUI thread waiting on itself. {@code COALESCE_LATEST} would discard keys outright. Two mailboxes
     * filtered by class would protect the keys but reorder them against motion, which breaks drag (press, move,
     * release must stay in sequence).
     *
     * <p><b>What this bound actually measures.</b> The input bridge publishes from {@code beforeFrame} and this
     * pump drains inside {@code Gui.frame}, on the same thread in the same loop iteration, so the mailbox is
     * filled and emptied within one frame and never accumulates across them. Its depth is therefore device rate
     * over frame rate: about 17 events for a 1&nbsp;kHz mouse at 60fps, about 200 at the 5&nbsp;Hz idle floor,
     * and roughly 1600 for an 8&nbsp;kHz mouse at that floor. Filling {@value #MAILBOX} takes something else
     * entirely — some four seconds of a 1&nbsp;kHz device against a loop that has stopped drawing. So this is
     * not a buffer sized against pointer traffic; it is the point past which the frame loop is not slow but
     * <b>stopped</b>, and a stopped loop still accepting input is worth crashing over.
     *
     * <p>The channel should still be split, and §5 says how: pointer <em>position</em> belongs on a coalesced
     * {@code State<PointerState>} rather than as edges here. But the reason is the loss classes above, not this
     * bound — a channel that cannot be given a correct policy is wrong while it is nowhere near full.
     */
    private static final int MAILBOX = 4096;

    /**
     * Wheel travel per notch, in ems of the target's layout — three lines, the platform convention. Relative
     * rather than a pixel count because a notch should move the same amount of <em>content</em> at any zoom or
     * DPI; pinned to 48px it moved a third as far through a 3× zoomed document.
     */
    private static final float WHEEL_STEP_EM = 3f;

    /** Within this much of the locked edge (in ems), a scroll counts as "at the edge" and re-attaches the lock. */
    private static final float LOCK_EDGE_EPS_EM = 0.25f;

    /**
     * How far the pointer must travel before a press on a drag source becomes a drag, and how long before it
     * becomes visible. Larger than the 2px a slider wants: a tree row is usually clicked, so the deadzone has to
     * survive the hand movement of an ordinary click rather than merely of a steady one.
     */
    private static final int DRAG_DISTANCE_PX = 6;
    private static final long DRAG_HOLD_MS = 90L;

    /**
     * How close in time and place a left press must follow the one before it to continue its sequence — to make
     * a double-click of a click, a triple of a double. Windows' defaults: 500ms, and a box four pixels wide at
     * 96 DPI, which is a quarter of an em at the default 16px and so stays a quarter of an em at any zoom.
     */
    private static final long MULTI_CLICK_NANOS = 500_000_000L;
    private static final float MULTI_CLICK_SLOP_EM = 0.25f;

    /** One em for {@code n}, from the layout; the default context's 16px if the node has not been laid out. */
    private static float em(RetainedNode n) {
        return n != null && n.emPx > 0f ? n.emPx : 16f;
    }

    private final Atchung bus;
    private final Topic<ClickEvent> clicks;
    private final Executor handlerExecutor;
    private final Runnable requestLayout;
    private final Pump pump;
    private final Subscription sub;
    private final Map<Long, Consumer<ClickEvent>> clickHandlers = new ConcurrentHashMap<>();
    private final Map<Long, Consumer<ClickEvent>> doubleClickHandlers = new ConcurrentHashMap<>();
    private final Map<Long, Consumer<ClickEvent>> contextHandlers = new ConcurrentHashMap<>();
    // Context-menu sources, and the one thing that shows what they build. Sources accumulate like state observers
    // and for the same reason: a widget's own defaults and the application's additions are independent concerns
    // that share one menu. Which node's sources run is a leaf→root question (see openMenu), so it is dispatch's
    // to answer -- the same walk as a click, with the same "the nearest one that has something wins" rule.
    private final Map<Long, java.util.List<Consumer<MenuSink>>> menuSources = new ConcurrentHashMap<>();
    private volatile MenuPresenter menuPresenter;
    // Interaction state has *observers*, plural: a widget restyling itself and a tooltip watching the same node
    // are independent concerns, and neither should have to know the other exists. Every other handler map here
    // replaces on re-registration; this one accumulates, because state is a fact about the node, not a command
    // channel with a single owner.
    private final Map<Long, java.util.List<Consumer<InteractionState>>> stateHandlers = new ConcurrentHashMap<>();
    private final Map<Long, Consumer<DragEvent>> dragHandlers = new ConcurrentHashMap<>();
    /** What each node would do with a drag released on it. Unrelated to dragHandlers -- see onDrop. */
    private final Map<Long, DropTarget> dropTargets = new ConcurrentHashMap<>();
    /** What each node offers up when a drag starts on it. */
    private final Map<Long, DragSource> dragSources = new ConcurrentHashMap<>();
    /**
     * Recognises press-move-release as a drag. Its own thresholds, not the onDrag path's: a slider must respond
     * to a one-pixel nudge with no delay, while a tree row must tolerate a click without reorganising itself, so
     * the two cannot share a setting. Fed pointer events only -- see feedDragGesture.
     */
    private final sibarum.tactroller.api.DragGesture drags =
            new sibarum.tactroller.api.DragGesture(DRAG_DISTANCE_PX, DRAG_HOLD_MS, Set.of(MouseButton.LEFT));
    /**
     * Recognises the touchpad's gestures in its contacts: one recogniser per window, fed the whole stream, so the
     * pans and pinches published on {@link InputTopics#GESTURE} and the swipes and taps that reach claims are one
     * reading of the same fingers. GUI thread only.
     */
    private final sibarum.tactroller.api.TouchpadGesture touchpad = new sibarum.tactroller.api.TouchpadGesture();
    private final GestureTriggers gestureTriggers = new GestureTriggers();
    /** The drag in flight, or null. GUI thread only. */
    private DragSession session;
    /**
     * What hears about every drag in the frame it moves, with the node that bounds each registration. Copy-on-write:
     * walked every frame of a drag, changed only when a widget is built or torn down — and possibly from a worker,
     * which a walk on the GUI thread must not see half-made.
     */
    private final java.util.concurrent.CopyOnWriteArrayList<Watch> dragWatches =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile History dropHistory;
    /** The reconciler's index, for judging a node by id (see {@link #nodeLookup}). GUI thread. */
    private java.util.function.LongFunction<RetainedNode> nodes = id -> null;
    /** The history a menu opened on a node records its edits into (see {@link #menuHistory}); any thread. */
    private volatile java.util.function.LongFunction<History> menuHistory = id -> null;
    private final Map<Long, Consumer<KeyEvent>> keyHandlers = new ConcurrentHashMap<>();
    // Typed-text handlers (CharTyped → codepoint) and caret-placement handlers (click → offset), both for
    // editable text nodes; registering either makes the node focusable.
    private final Map<Long, IntConsumer> charHandlers = new ConcurrentHashMap<>();

    /**
     * Handlers registered as an <b>ordered stage</b> rather than as application logic: they run inline on the GUI
     * thread during {@link #dispatch}, in arrival order, instead of being handed to the worker executor.
     *
     * <p>The distinction is what the handler <em>is</em>. An observer reacts to an event and has no ordering
     * requirement, so it belongs off-thread where a slow one cannot stall rendering. A command performs a state
     * transition that depends on arrival order — and handing each event to a pool separately orders the
     * invocations but not the effects, so a burst of typed characters can apply scrambled. Serialising delivery
     * would fix it by rebuilding the bus's own delivery machinery inside the dispatcher (architecture.md §0), and
     * would spend exactly the asynchrony the design went to some trouble to get. Running the transition here
     * instead costs nothing: the drain is already a total order, and applying an edit to a document is bounded
     * model work, not application logic — the same reason the reconciler runs here.
     */
    private final Map<Long, IntConsumer> charStages = new ConcurrentHashMap<>();
    private final Map<Long, Consumer<KeyEvent>> keyStages = new ConcurrentHashMap<>();
    private final Map<Long, Consumer<DragEvent>> dragStages = new ConcurrentHashMap<>();
    /**
     * Declared preemption (see {@link ClaimScope}). Ordered only by scope precedence, never by registration, so
     * a focused element always outranks a global default. {@code inline} marks the framework's own claims, whose
     * commands mutate GUI-thread state (focus) and so must not be handed to the worker executor.
     *
     * <p>A claim is on a {@link Trigger}: a chord, or a touchpad {@link Gesture} (a swipe, a tap), which resolve
     * the same way. A {@code null} chord is a claim on <em>every</em> trigger ({@link #claimAny}), and its command
     * hears which one it got. At the same scope a claim that named its trigger outranks it: the wildcard is the
     * floor of its scope, never a way of shadowing a key somebody asked for by name.
     */
    private record Claim(Trigger chord, ClaimScope scope, long nodeId, Consumer<Trigger> command, boolean inline) {

        static Claim of(Trigger chord, ClaimScope scope, long nodeId, Runnable command, boolean inline) {
            return new Claim(chord, scope, nodeId, pressed -> command.run(), inline);
        }

        boolean any() {
            return chord == null;
        }

        boolean matches(Trigger pressed) {
            return chord == null || chord.equals(pressed);
        }

        /** Whether this claim beats {@code other}: the narrower scope, and at one scope the named chord. */
        boolean outranks(Claim other) {
            if (scope != other.scope) {
                return scope.ordinal() < other.scope.ordinal();
            }
            return !any() && other.any();
        }
    }

    private final List<Claim> claims = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Set<Long> focusable = ConcurrentHashMap.newKeySet();
    private final Map<Long, InteractionState> reportedState = new ConcurrentHashMap<>();

    // Keyboard/focus state (GUI-thread only, mutated during dispatch).
    private final EnumSet<Modifier> heldMods = EnumSet.noneOf(Modifier.class);
    /**
     * Told whenever the held set changes (see {@code Gui.modifiers}). Modifiers were until now a fact this class
     * kept to itself — a modifier press returns here without routing anywhere, because it is not a command — and
     * that leaves anything whose <em>appearance</em> depends on a held key with nowhere to read it. Holding Ctrl
     * to see which words in a document are links is exactly that: no command, no click, nothing routed, and the
     * whole of what has to happen.
     */
    private Consumer<Set<Modifier>> modifierSink = m -> { };
    private long focusedId = -1;
    /** How {@link #focusedId} was last asked for: the focus ring's visibility rule reads it. */
    private FocusCause focusCause = FocusCause.PROGRAM;
    /** Whether the window holds OS focus, from tactroller's focus edges. The ring dims while it does not. */
    private boolean windowActive = true;
    private Runnable focusStateSink = () -> { };
    private Topic<FocusEvent> focusTopic;
    /**
     * Asked to bring a node into view when the <em>keyboard</em> moved focus to it (see {@link #revealSink}). Only
     * Tab and Shift+Tab call it: a click lands focus on something the pointer is already over, and scrolling it
     * would move the thing out from under the pointer that chose it.
     */
    private LongConsumer revealSink = id -> { };
    private Topic<KeyRouted> keyRoutedTopic;

    // Held-key auto-repeat (§8.4): the last non-modifier key routed to a focused node, and when it next
    // re-fires. Synthesised on the per-frame dispatch() clock, since tactroller reports only the OS key edge.
    private static final long REPEAT_DELAY_NANOS = 400_000_000L;   // wait before the first repeat
    private static final long REPEAT_INTERVAL_NANOS = 40_000_000L; // ~25 repeats/second thereafter
    private Key repeatKey;
    private long repeatNextNanos;

    /**
     * The dispatch conduit: every input edge drained is stamped with its position here, on the one thread that
     * drains. The order was always this — the drain is single-threaded — but it was implicit, recoverable only by
     * knowing which thread ran what. Stated explicitly, an observer downstream can tell a dropped edge from a
     * reordered one, which is the difference between a gap and a bug.
     */
    private final dev.vexelray.gui.core.Conduit conduit = new dev.vexelray.gui.core.Conduit();

    /** The stamp for the edge currently being routed; set once per {@link #handle} call. */
    private dev.vexelray.gui.core.Provenance current = new dev.vexelray.gui.core.Provenance(0L, -1L, 0L);

    private RetainedNode currentRoot;
    private long pressTargetId = -1;
    // Multi-click counting (ClickEvent.clicks, DragEvent.clicks): the last left press — when, where and on what — and which press of
    // its sequence it was. GUI-thread only, like the rest of the pointer state.
    private long lastPressNanos;
    private float lastPressX;
    private float lastPressY;
    private long lastPressHitId = -1;
    private int pressCount;
    // The right button's own press/release pairing. It shares nothing with the left button's on purpose: a right
    // press never focuses, never sets PRESSED, never captures a drag — it either becomes a context click (press
    // and release on the same node) or it becomes nothing.
    private long contextPressTargetId = -1;
    // Pointer capture for dragging: while a drag is active, MOVE events route to this node's handler regardless of
    // what's under the pointer, until the button is released.
    private RetainedNode dragCapture;
    // Scrollbar thumb drag: the grabbed container, its axis, and the pointer's offset within the thumb at grab.
    private RetainedNode scrollDrag;
    private boolean scrollDragVertical;
    private float scrollDragGrab;
    // Pointer/button tracking for interaction state. hoverHit is the topmost node currently under the pointer;
    // pressHit is the topmost node the left button went down on (both null when none / button up).
    private RetainedNode hoverHit;
    private RetainedNode pressHit;
    private boolean leftDown;
    // Last pointer position, kept so the cursor rule can ask whether it is over a scrollbar strip -- that is a
    // geometric question about chrome, not about a node, so a hit-test result alone cannot answer it.
    // Volatile because a PointerRide reads them from the thread driving its animation, to tell its own steps
    // from the hand's.
    private volatile float pointerX;
    private volatile float pointerY;
    /** A left press is down somewhere a drag could begin, and has not yet become one. See {@link #showArmed}. */
    private boolean armed;
    private float armedX;
    private float armedY;
    /** The release that pairs with a press a latched drag used up, so it does not click whatever is under it. */
    private boolean swallowLeftRelease;
    private boolean swallowRightRelease;
    /** The wheel, as motion. */
    private final ScrollGlide glide = new ScrollGlide();
    /** The focus ring, fading between the node focus left and the one it reached. */
    private final FocusFade ringFade = new FocusFade();
    /** A glided container re-decides its scroll lock by where it is heading; one instance, not one per frame. */
    private final Consumer<RetainedNode> relock = n -> refreshScrollLock(n, glide.targetY(n));
    /** Where the host puts the pointer when the GUI asks; nothing moves until one is installed. */
    private volatile PointerWarp pointerWarp = PointerWarp.NONE;
    /** What letting go of the button means during a drag: the user's preference. */
    private volatile DragRelease dragRelease = DragRelease.KEEP;
    /** Cursor shapes declared per node (see {@link #setCursor}); consulted ancestor-or-self. */
    private final Map<Long, CursorShape> nodeCursors = new ConcurrentHashMap<>();
    // Cursor shape reporting (§8.3): the app-installed sink and the last shape reported (to fire only on change).
    private Consumer<CursorShape> cursorSink = s -> { };
    private CursorShape reportedCursor = CursorShape.DEFAULT;
    /** Nodes whose drag wants the pointer locked for its duration (see {@link #setDragLocksPointer}). */
    private final java.util.Set<Long> dragLocks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Pointer lock reporting: the app-installed sink, and whether a lock is currently asked for. The same shape
    // as the cursor seam above, for the same reason -- the core cannot reach an OS, so it states an intent and
    // the application edge carries it out.
    private Consumer<Boolean> pointerLockSink = locked -> { };
    private boolean lockRequested;

    public InputDispatcher(Atchung bus, Topic<ClickEvent> clicks, Executor handlerExecutor) {
        this(bus, clicks, handlerExecutor, () -> { });
    }

    public InputDispatcher(Atchung bus, Topic<ClickEvent> clicks, Executor handlerExecutor, Runnable requestLayout) {
        this.bus = bus;
        this.clicks = clicks;
        this.handlerExecutor = handlerExecutor;
        this.requestLayout = requestLayout;
        this.pump = bus.pump();
        this.sub = pump.subscribe(InputTopics.INPUT, this::handle, MAILBOX, Backpressure.FAIL);
        // Focus traversal is a framework *default*, not an interception: it is an ordinary global claim, so a
        // focused element that claims Tab (a multiline editor indenting) simply outranks it. Shift+Tab is left
        // unclaimed by such elements, which is what keeps a way out of the field.
        claimInternal(Shortcut.of(Key.TAB), () -> moveFocus(1));
        claimInternal(Shortcut.of(Key.TAB, Modifier.SHIFT), () -> moveFocus(-1));
    }

    /** Register a click handler for {@code nodeId}; replaces any prior handler for that node. */
    public void onClick(long nodeId, Runnable handler) {
        clickHandlers.put(nodeId, e -> handler.run());
    }

    /**
     * The same, for a handler that needs to know what the click <b>meant</b> — Ctrl-click and Shift-click are
     * different commands from a click, and a selection cannot be driven without telling them apart. The modifiers
     * are already part of the {@link ClickEvent}; this is what hands one to the left-button handler as well as to
     * the right-button one.
     */
    public void onClick(long nodeId, Consumer<ClickEvent> handler) {
        clickHandlers.put(nodeId, handler);
    }

    /**
     * Register a double-click handler for {@code nodeId}; replaces any prior one. Fired on the release of the
     * second click of a sequence (see {@link ClickEvent#clicks()}), bubbling leaf→root like a click but along its
     * own walk, so a row may answer double-clicks while its list answers clicks.
     *
     * <p>It comes <b>in addition to</b> the click, never instead of it: the second click still reaches
     * {@link #onClick} first. Holding every click back until the double-click window had passed would make each
     * one half a second late, to spare a select-then-open handler from selecting twice — and selecting twice is
     * harmless. A third click in the sequence does not fire it again.
     */
    public void onDoubleClick(long nodeId, Runnable handler) {
        doubleClickHandlers.put(nodeId, e -> handler.run());
    }

    /** The same, for a handler that wants the {@link ClickEvent} — where the click landed, what was held. */
    public void onDoubleClick(long nodeId, Consumer<ClickEvent> handler) {
        doubleClickHandlers.put(nodeId, handler);
    }

    /**
     * Register a context-click (right-button) handler for {@code nodeId}; replaces any prior handler for that
     * node. The handler receives the full {@link ClickEvent} — a context action almost always needs the pointer
     * position (to anchor a menu), where a plain click almost never does.
     */
    public void onContextClick(long nodeId, Consumer<ClickEvent> handler) {
        contextHandlers.put(nodeId, handler);
    }

    /**
     * Register a context-menu source for {@code nodeId}: a function that, at the moment of a right click, says
     * what the menu should contain. Sources accumulate — a widget's defaults and the application's additions both
     * write into the one menu, in registration order — and are released with the node.
     */
    public void onContextMenu(long nodeId, Consumer<MenuSink> source) {
        menuSources.computeIfAbsent(nodeId, id -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(source);
    }

    /** Install what shows a context menu, replacing any prior one; {@code null} means no menus are shown. */
    public void menuPresenter(MenuPresenter presenter) {
        this.menuPresenter = presenter;
    }

    /** The installed presenter, or {@code null} — how a widget can tell whether it needs to install one. */
    public MenuPresenter menuPresenter() {
        return menuPresenter;
    }

    /**
     * Register an interaction-state observer for {@code nodeId}, invoked whenever the node's
     * {@link InteractionState} changes (NORMAL/HOVER/PRESSED). Observers accumulate — a restyle and a tooltip on
     * the same node are independent concerns — and are all released together when the node leaves the tree.
     */
    public void onState(long nodeId, Consumer<InteractionState> handler) {
        stateHandlers.computeIfAbsent(nodeId, id -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(handler);
    }

    /**
     * Register a drag handler for {@code nodeId}: a left press on the node (or a descendant) captures the pointer
     * and delivers START, then MOVE for every subsequent motion while held (even off the node), then END on
     * release. Replaces any prior handler for that node.
     */
    public void onDrag(long nodeId, Consumer<DragEvent> handler) {
        dragHandlers.put(nodeId, handler);
    }

    /**
     * Register {@code nodeId}'s answer to what would happen if a drag landed on it. Replaces any prior target.
     *
     * <p>Unrelated to {@link #onDrag}, and the two must not be confused: that one is a <em>manipulation</em> —
     * the pointer steering a slider or a scrollbar thumb, captured on press with no threshold. This is a drop
     * target, which does not capture anything, answers about drags it did not start, and may be asked about a
     * drag that began in another window or outside the process entirely.
     */
    public void onDrop(long nodeId, DropTarget target) {
        dropTargets.put(nodeId, target);
    }

    /** Register what {@code nodeId} offers up when a drag begins on it — see {@link DragSource}. */
    public void onDragSource(long nodeId, DragSource source) {
        dragSources.put(nodeId, source);
    }

    /** One {@link DragWatch}, and the node whose removal ends it. */
    private record Watch(long nodeId, DragWatch watch) {
    }

    /** Tell {@code watch} about every drag in this tree, in the frame it happens — see {@link DragWatch}. Several
     * watches may share a node; each is dropped when its node is. */
    public void watchDrag(long nodeId, DragWatch watch) {
        dragWatches.add(new Watch(nodeId, java.util.Objects.requireNonNull(watch, "watch")));
    }

    /**
     * Show every watch the live drag as it stands now. One {@link DragState} per frame, shared by all of them, so
     * an owner with several registrations can tell by identity that it has handled this frame already.
     */
    private void showWatches() {
        if (dragWatches.isEmpty()) {
            return;
        }
        DragState now = stateOf(session);
        for (Watch w : dragWatches) {
            w.watch().moved(now);
        }
    }

    /**
     * Tell every watch the drag is over, and what it did.
     *
     * <p>{@code last} is read from the session as it ended rather than from the last frame shown — a release
     * re-resolves at the release point, and may land somewhere the previous frame never drew — and is read
     * <em>before</em> a cancel, which clears the resolution the user was looking at when they gave up.
     */
    private void endWatches(DragState last, DropEffect performed) {
        updateCursor();   // the hand opens again, over whatever it let go above
        for (Watch w : dragWatches) {
            w.watch().ended(last, performed);
        }
    }

    private static DragState stateOf(DragSession s) {
        return DragState.of(s);
    }

    /** Where a completed drop records its change. Until one is set, drops resolve and draw but commit nothing —
     * which is the right default for a tree that has not said who owns its undo. */
    public void dropHistory(History history) {
        this.dropHistory = history;
    }

    /** The drag in flight, or null. For a renderer drawing the ghost and the indicator. */
    public DragSession dragSession() {
        return session != null && !session.ended() ? session : null;
    }

    /**
     * Feed one input event to the drag recogniser and act on whatever it produces.
     *
     * <p><b>Pointer events only.</b> {@code DragGesture} would also cancel on Escape if it were fed keys, and it
     * observes the stream rather than consuming from it — so the same Escape would reach the claim system and
     * dismiss a modal or a context menu at the same time. One key, two actions, and the layering that
     * {@code ContextMenuTest} exists to protect quietly broken. Escape for a drag is handled in
     * {@link #handleKeyDown} instead, where it can be consumed.
     */
    private void feedDragGesture(InputEvent event) {
        // The filter is the whole point and is easy to lose: DragGesture cancels on Escape if it is fed keys, and
        // it would do so *first*, leaving the dispatcher with no live session to consume the key by — so the same
        // Escape would go on to dismiss a modal as well. Pinned by escapeDuringADragIsNotAlsoAClaim.
        if (!(event instanceof InputEvent.ButtonPressed
                || event instanceof InputEvent.ButtonReleased
                || event instanceof InputEvent.PointerMoved)) {
            return;
        }
        for (sibarum.tactroller.api.DragEvent d : drags.feed(event)) {
            onDragGesture(d);
        }
    }

    /**
     * Feed one input event to the touchpad recogniser: publish what the fingers are doing for whatever manipulates
     * things directly, and run the claim on any swipe or tap that is a command. A gesture's claim resolves exactly
     * as a chord's does — scope, focus, disabled — because a command is a command whichever hand gave it.
     */
    private void feedTouchpad(InputEvent event) {
        for (sibarum.tactroller.api.GestureEvent g : touchpad.feed(event)) {
            bus.publish(InputTopics.GESTURE, g);
            for (Gesture t : gestureTriggers.feed(g)) {
                Claim claimed = resolveClaim(t);
                if (claimed == null) {
                    continue;
                }
                if (claimed.inline()) {
                    claimed.command().accept(t);
                } else {
                    handlerExecutor.execute(() -> claimed.command().accept(t));
                }
            }
        }
    }

    /** Open, move or end the session, according to what the recogniser says the gesture just did. */
    private void onDragGesture(sibarum.tactroller.api.DragEvent event) {
        switch (event) {
            case sibarum.tactroller.api.DragEvent.DragStarted s -> openSession(s);
            case sibarum.tactroller.api.DragEvent.DragOver o -> {
                if (session != null) {
                    session.moveTo(o.x(), o.y(), this::dropTargetsAt);
                }
            }
            case sibarum.tactroller.api.DragEvent.DragEnded e -> endSession(e);
        }
    }

    /**
     * Ask the node the press landed on for a payload, and open a session if it offers one.
     *
     * <p>The press point is used rather than the current one: by now the pointer has travelled past the
     * threshold, and resolving the source under it would pick up whatever it wandered onto instead of the thing
     * the user grabbed.
     */
    private void openSession(sibarum.tactroller.api.DragEvent.DragStarted started) {
        // Whatever happens next, the press has stopped being a candidate: it is a drag now, or nothing is.
        boolean wasArmed = armed;
        armed = false;
        RetainedNode from = HitTest.at(currentRoot, started.startX(), started.startY());
        for (RetainedNode n = from; n != null; n = n.parent) {
            DragSource source = dragSources.get(n.id);
            if (source == null) {
                if (ownsPointerDrag(n)) {
                    break;   // a text field's drag is a selection, not a pickup of what it sits in
                }
                continue;
            }
            Payload payload = source.payloadAt(started.startX(), started.startY());
            if (payload != null) {
                session = DragSession.open(payload, started.x(), started.y());
                session.moveTo(started.x(), started.y(), this::dropTargetsAt);
                updateCursor();   // the hand closes on what it picked up
                return;
            }
            // A source that declines does not stop an ancestor answering: the empty space below a tree's rows
            // belongs to the tree, and the tree may well want to offer something for it.
        }
        if (wasArmed) {
            disarmWatches();   // nothing here could be picked up after all
        }
    }

    /**
     * The button came up on a drag: keep it, latched, or put it down where the pointer is — whichever the user's
     * {@link DragRelease} says. Or the gesture was cancelled, by Escape while the button was down.
     *
     * <p>Kept, picking something up is a gesture rather than a posture — see {@link DragSession#latch}: letting go
     * is the hand relaxing, a long list is not a test of grip, and the drop is the next click, aimed. Dropped, it is
     * the convention the user may have twenty years of in their hands. Their call, not this framework's.
     */
    private void endSession(sibarum.tactroller.api.DragEvent.DragEnded ended) {
        if (session == null) {
            return;
        }
        if (ended.cancelled()) {
            cancelSession();
            return;
        }
        if (!dragRelease.keeps()) {
            commitSession(ended.x(), ended.y());
            return;
        }
        session.moveTo(ended.x(), ended.y(), this::dropTargetsAt);
        session.latch();
        updateCursor();   // the hand opens; the thing stays with it
        showWatches();    // and the source is told in this frame, so it can show the difference
    }

    /** End the drag without performing anything, and tell everyone that is all it did. */
    private void cancelSession() {
        DragState last = stateOf(session);
        session.cancel();
        session = null;
        endWatches(last, DropEffect.NONE);
    }

    /**
     * Put the latched drag down at {@code (x, y)}: the click that ends a carry. Performs the resolved change through
     * the drop history, and puts focus where the drop landed.
     */
    private void commitSession(float x, float y) {
        session.moveTo(x, y, this::dropTargetsAt);
        DragState last = stateOf(session);
        History history = dropHistory;
        Drop performed = history == null ? Drop.NONE : session.commit(history);
        session = null;
        if (performed.accepts()) {
            // Focus follows the drop, and this is load-bearing rather than a nicety. Ctrl+Z resolves by focus
            // (Gui.history's ClaimScope), but a drag is a pointer gesture with no focus relationship — so
            // dropping a row while a text field still held focus would send the user's first undo to the field's
            // typing history instead of to the drop they just made. Focusing what a click here would focus
            // realigns the two, and is what the user expects anyway: the thing just moved is the thing now acted on.
            RetainedNode landed = ancestorFocusable(HitTest.at(currentRoot, x, y));
            setFocus(landed == null ? -1 : landed.id, FocusCause.POINTER);
        }
        // After focus has moved, not before: what is under the drop point is often not the thing that was moved —
        // a carried card is pointer-transparent while it flies, so the hit finds the lane beneath it — and a watch
        // that knows better is the last word on where focus lands.
        endWatches(last, performed.effect());
    }

    /**
     * While a drag is latched, the pointer belongs to it: moves carry the thing, a left click puts it down, a right
     * click takes it home. Each press is consumed with the release that pairs with it, so the click that drops a
     * card is not also a click on whatever was under it, and the right click that cancels opens no menu. The wheel
     * is not taken — scrolling under a carried thing is the reason the hand was freed.
     *
     * @return whether the event was used up here
     */
    private boolean latchedPointer(InputEvent e) {
        if (e instanceof InputEvent.ButtonReleased r) {
            if (r.button() == MouseButton.LEFT && swallowLeftRelease) {
                swallowLeftRelease = false;
                pointerX = r.x();
                pointerY = r.y();
                return true;
            }
            if (r.button() == MouseButton.RIGHT && swallowRightRelease) {
                swallowRightRelease = false;
                return true;
            }
        }
        DragSession s = session;
        if (s == null || s.ended() || !s.latched()) {
            return false;
        }
        if (e instanceof InputEvent.PointerMoved m) {
            pointerX = m.x();
            pointerY = m.y();
            hoverHit = RetainedNode.enabledPart(HitTest.at(currentRoot, m.x(), m.y()));
            s.moveTo(m.x(), m.y(), this::dropTargetsAt);
            return true;
        }
        if (e instanceof InputEvent.ButtonPressed b && b.button() == MouseButton.LEFT) {
            pointerX = b.x();
            pointerY = b.y();
            swallowLeftRelease = true;
            commitSession(b.x(), b.y());
            return true;
        }
        if (e instanceof InputEvent.ButtonPressed b && b.button() == MouseButton.RIGHT) {
            swallowRightRelease = true;
            cancelSession();
            return true;
        }
        return false;
    }

    /** Tell every watch the press it was shown is over, without having become a drag. */
    private void disarmWatches() {
        for (Watch w : dragWatches) {
            w.watch().disarmed();
        }
    }

    /** Show every watch the press that is not yet a drag, once per frame while it lasts. */
    private void showArmed() {
        if (!armed || session != null) {
            return;
        }
        for (Watch w : dragWatches) {
            w.watch().armed(armedX, armedY, pointerX, pointerY);
        }
    }

    /** Whether a press here could become a drag: something on the way to the root offers one. */
    private boolean canDrag(RetainedNode hit) {
        for (RetainedNode n = hit; n != null; n = n.parent) {
            if (dragSources.containsKey(n.id)) {
                return true;
            }
            if (ownsPointerDrag(n)) {
                return false;
            }
        }
        return false;
    }

    /**
     * Whether a press on {@code n} is {@code n}'s to drag: it handles the pointer's travel itself, as a text field
     * selecting, a slider sliding or a divider resizing does. Such a node stands between the press and any drag
     * source above it — a card on a board is picked up by its face, not by the text field on it, whose drag is a
     * selection. The same rule {@link #ancestorWithDragHandler} applies the other way round, where a node that
     * answers clicks keeps a drag handler above it from capturing its press.
     */
    private boolean ownsPointerDrag(RetainedNode n) {
        return dragHandlers.containsKey(n.id) || dragStages.containsKey(n.id);
    }

    /**
     * Escape while a drag is in flight cancels it and goes no further.
     *
     * <p>Deliberately ahead of claim resolution rather than expressed as a claim. A drag is modal for as long as
     * it lasts — there is no sensible reading of "Escape" mid-drag that means anything but "not this one" — and
     * the claim system resolves a single winner by scope, so a drag would have to outrank both the VISIBLE claim
     * of an open context menu and the GLOBAL one of a modal to be sure of getting it. Inventing a scope above
     * GLOBAL to express "except during a drag" would put a gesture's lifetime into a system that is about where
     * a key is meaningful, which is a different question.
     *
     * @return whether the key was consumed here
     */
    private boolean escapeCancelledDrag(Key key) {
        if (key != Key.ESCAPE || session == null || session.ended()) {
            return false;
        }
        boolean held = !session.latched();
        cancelSession();
        if (held) {
            drags.reset();   // the button is still down; without this its release would complete a cancelled gesture
        }
        return true;
    }

    /**
     * The drop targets covering {@code (x, y)}, innermost first — the {@link DragSession.Lookup} for this tree.
     *
     * <p>Hit-testing finds the deepest node under the point and the chain to the root is walked from there, so a
     * container's target answers for every point in it that no descendant claimed. That is what makes resolution
     * total across nesting rather than only within one node: without the walk, a row that declined a payload
     * would leave a row-shaped hole in a tree that was perfectly willing to accept it.
     *
     * <p>The point is tested against the same rects the pointer is dispatched against, which during a transition
     * are the <em>drawn</em> ones — so a drag resolves against the tree the user can see, not the one it is on
     * its way to becoming.
     */
    public List<DropTarget> dropTargetsAt(float x, float y) {
        // A disabled target accepts nothing; the enabled container around it still answers for the point.
        RetainedNode hit = RetainedNode.enabledPart(HitTest.at(currentRoot, x, y));
        List<DropTarget> found = null;
        for (RetainedNode n = hit; n != null; n = n.parent) {
            DropTarget target = dropTargets.get(n.id);
            if (target != null) {
                if (found == null) {
                    found = new ArrayList<>(4);
                }
                found.add(target);
            }
        }
        return found == null ? List.of() : found;
    }

    /** Register a key handler for {@code nodeId} (also makes it focusable). Fires when the node holds focus. */
    public void onKey(long nodeId, Consumer<KeyEvent> handler) {
        keyHandlers.put(nodeId, handler);
        focusable.add(nodeId);
    }

    /** Explicitly mark a node focusable (e.g. a button) even without a key handler. */
    public void setFocusable(long nodeId, boolean canFocus) {
        if (canFocus) {
            focusable.add(nodeId);
        } else {
            focusable.remove(nodeId);
        }
    }

    /** Register a typed-text handler for {@code nodeId} (also makes it focusable). Fires per code point typed
     *  while the node holds focus — the text channel, separate from {@link #onKey}. */
    public void onChar(long nodeId, IntConsumer handler) {
        charHandlers.put(nodeId, handler);
        focusable.add(nodeId);
    }

    // --- ordered stages: run on the GUI thread during dispatch, in arrival order (see charStages) ---

    /** Register {@code nodeId}'s typed-text stage, run in arrival order on the GUI thread. */
    public void onCharUi(long nodeId, IntConsumer stage) {
        charStages.put(nodeId, stage);
        focusable.add(nodeId);
    }

    /** Register {@code nodeId}'s key-command stage, run in arrival order on the GUI thread. */
    public void onKeyUi(long nodeId, Consumer<KeyEvent> stage) {
        keyStages.put(nodeId, stage);
        focusable.add(nodeId);
    }

    /** Register {@code nodeId}'s drag stage, run in arrival order on the GUI thread. */
    public void onDragUi(long nodeId, Consumer<DragEvent> stage) {
        dragStages.put(nodeId, stage);
    }

    /**
     * Claim {@code chord} for {@code nodeId}, running the command on the GUI thread in arrival order — the
     * variant a claim that performs an edit needs, so it sequences against typed text rather than racing it.
     */
    public void claimUi(long nodeId, Trigger chord, ClaimScope scope, Runnable command) {
        claims.removeIf(c -> c.nodeId() == nodeId && c.scope() == scope && chord.equals(c.chord()));
        claims.add(Claim.of(chord, scope, nodeId, command, true));
    }

    /** Register a global shortcut command — a {@link ClaimScope#GLOBAL} claim owned by no node. */
    public void registerShortcut(Shortcut shortcut, Runnable command) {
        claim(-1L, shortcut, ClaimScope.GLOBAL, command);
    }

    /** Claim {@code chord} for {@code nodeId} at {@code scope}; replaces any identical claim by the same node. */
    public void claim(long nodeId, Trigger chord, ClaimScope scope, Runnable command) {
        claims.removeIf(c -> c.nodeId() == nodeId && c.scope() == scope && chord.equals(c.chord()));
        claims.add(Claim.of(chord, scope, nodeId, command, false));
    }

    /**
     * Claim <em>every</em> trigger — chord or gesture — for {@code nodeId} at {@code scope}, the command hearing
     * which one it was. Replaces any wildcard claim the node already holds; a claim naming its trigger at the same
     * scope still wins.
     */
    public void claimAny(long nodeId, ClaimScope scope, Consumer<Trigger> command) {
        claims.removeIf(c -> c.nodeId() == nodeId && c.any());
        claims.add(new Claim(null, scope, nodeId, command, false));
    }

    /** Drop the wildcard claim {@code nodeId} holds, if any; its claims on named chords stay. */
    public void releaseClaimAny(long nodeId) {
        claims.removeIf(c -> c.nodeId() == nodeId && c.any());
    }

    /** Drop every claim {@code nodeId} holds on {@code chord}. */
    public void releaseClaim(long nodeId, Trigger chord) {
        claims.removeIf(c -> c.nodeId() == nodeId && chord.equals(c.chord()));
    }

    /** A framework default, run on the GUI thread because it mutates dispatch state. */
    private void claimInternal(Shortcut chord, Runnable command) {
        claims.add(Claim.of(chord, ClaimScope.GLOBAL, -1L, command, true));
    }

    /** The topic every routed key press is reported on (set by Gui). */
    public void keyRoutedTopic(Topic<KeyRouted> topic) {
        this.keyRoutedTopic = topic;
    }

    /** The topic focus changes publish on (set by Gui). */
    public void focusTopic(Topic<FocusEvent> topic) {
        this.focusTopic = topic;
    }

    /**
     * Install the sink asked to bring a node into view, by the least scrolling, when the keyboard moves focus to
     * it (set by Gui, which answers with a {@code Mutation.Reveal}). This class has no mutation sink and does not
     * grow one: the request leaves as a node id, and what revealing means is Gui's to decide.
     */
    public void revealSink(LongConsumer sink) {
        this.revealSink = sink == null ? id -> { } : sink;
    }

    /**
     * Declare the cursor shape for {@code nodeId} and its descendants, overriding the inferred rules.
     *
     * <p>Needed where the affordance cannot be read off the registrations. A slider and a text field both use the
     * drag seam, so "has a drag handler" would put a grab hand over an editable field; the slider says
     * {@link CursorShape#GRAB} and the field says nothing. Everything the framework can infer -- clickable,
     * editable, scrollbar -- it infers, so this stays the exception.
     */
    public void setCursor(long nodeId, CursorShape shape) {
        if (shape == null) {
            nodeCursors.remove(nodeId);
        } else {
            nodeCursors.put(nodeId, shape);
        }
    }

    /** Install the sink notified (on the GUI thread) when the desired cursor shape changes (§8.3). */
    public void cursorSink(Consumer<CursorShape> sink) {
        this.cursorSink = sink == null ? s -> { } : sink;
    }

    /** Install where the pointer is put when the GUI asks — see {@link PointerRide}. */
    public void pointerWarp(PointerWarp warp) {
        this.pointerWarp = warp == null ? PointerWarp.NONE : warp;
    }

    /**
     * What letting go of the button means during a drag, from now on — see {@link DragRelease}. A drag already
     * latched stays latched until its click: the user is mid-gesture, and changing its ending under them would be
     * the drop they did not ask for. Safe from any thread.
     */
    public void dragRelease(DragRelease release) {
        this.dragRelease = java.util.Objects.requireNonNull(release, "release");
    }

    public DragRelease dragRelease() {
        return dragRelease;
    }

    /** Begin carrying the pointer with an animation, from where it is now. Safe from any thread. */
    public PointerRide ridePointer() {
        return new PointerRide(this, pointerWarp, pointerX, pointerY);
    }

    /** Where the pointer was last seen, client-space px. Safe from any thread; as fresh as the last drain. */
    float pointerX() {
        return pointerX;
    }

    float pointerY() {
        return pointerY;
    }

    /**
     * Glide wheel scrolling, closing half the distance to the target every {@code halfLifeNanos}; zero (the
     * default) scrolls at once. See {@link ScrollGlide}.
     */
    public void scrollGlide(long halfLifeNanos) {
        glide.halfLife(halfLifeNanos);
    }

    /**
     * Fade the focus ring in and out over {@code nanos}; zero (the default) shows and hides it at once. See
     * {@link FocusFade}.
     */
    public void focusFade(long nanos) {
        ringFade.duration(nanos);
        focusStateSink.run();
    }

    /**
     * Declare that a drag captured by {@code nodeId} should hold the pointer for its duration — the pointer is
     * locked on {@code START} and released on {@code END}.
     *
     * <p>For a drag that means a <em>displacement</em> and has no use for where the pointer is: turning a
     * camera, panning a plane. Such a gesture has no natural end, so the window's edge is not one either, and
     * locking is what removes it. A drag that means a <em>place</em> — a slider, a text selection — must not
     * ask for this: it needs the position the lock destroys, and the visible cursor the lock hides.
     */
    public void setDragLocksPointer(long nodeId, boolean locks) {
        if (locks) {
            dragLocks.add(nodeId);
        } else {
            dragLocks.remove(nodeId);
        }
    }

    /** Install the sink notified (on the GUI thread) when the pointer should be locked or released. */
    public void pointerLockSink(Consumer<Boolean> sink) {
        this.pointerLockSink = sink == null ? locked -> { } : sink;
    }

    /** Ask for or drop the lock, firing the sink only on a change so an application need not de-duplicate. */
    private void requestPointerLock(boolean wanted) {
        if (wanted == lockRequested) {
            return;
        }
        lockRequested = wanted;
        pointerLockSink.accept(wanted);
    }

    /** Programmatically move focus to {@code nodeId} (or -1 to clear). */
    public void focus(long nodeId) {
        setFocus(nodeId, FocusCause.PROGRAM);
    }

    /** The node holding keyboard focus, or {@code -1} for none. */
    public long focusedId() {
        return focusedId;
    }

    /**
     * Whether {@code nodeId} can take keyboard focus — registered by {@link #setFocusable} or implied by
     * registering a key/char handler. Read when publishing the semantic read-model, so a consumer can tell an
     * interactive node from decoration without guessing from its role.
     */
    public boolean isFocusable(long nodeId) {
        return focusable.contains(nodeId);
    }

    /** The currently focused node id, or -1. */
    public long focused() {
        return focusedId;
    }

    /** Drop {@code nodeId}'s handlers and focusability (call when the node is removed). */
    public void clearHandlers(long nodeId) {
        clickHandlers.remove(nodeId);
        doubleClickHandlers.remove(nodeId);
        contextHandlers.remove(nodeId);
        menuSources.remove(nodeId);
        stateHandlers.remove(nodeId);
        dragHandlers.remove(nodeId);
        dropTargets.remove(nodeId);
        dragSources.remove(nodeId);
        dragWatches.removeIf(w -> w.nodeId() == nodeId);
        keyHandlers.remove(nodeId);
        charHandlers.remove(nodeId);
        charStages.remove(nodeId);
        nodeCursors.remove(nodeId);
        keyStages.remove(nodeId);
        dragStages.remove(nodeId);
        claims.removeIf(c -> c.nodeId() == nodeId);
        focusable.remove(nodeId);
        reportedState.remove(nodeId);
        if (focusedId == nodeId) {
            setFocus(-1, FocusCause.PROGRAM);
        }
        // Drop live pointer references to the node too, or a drag/scroll in progress keeps steering a node that
        // is no longer in the tree — and hit-test ancestry walks a parent chain the reconciler has already cut.
        if (dragCapture != null && dragCapture.id == nodeId) {
            dragCapture = null;
            // The node steering the drag has gone, so the drag has too -- and a lock outlives nothing.
            requestPointerLock(false);
        }
        dragLocks.remove(nodeId);
        glide.forget(nodeId);
        if (scrollDrag != null && scrollDrag.id == nodeId) {
            scrollDrag = null;
        }
        if (hoverHit != null && hoverHit.id == nodeId) {
            hoverHit = null;
        }
        if (pressHit != null && pressHit.id == nodeId) {
            pressHit = null;
        }
        if (pressTargetId == nodeId) {
            pressTargetId = -1;
        }
    }

    /**
     * Drain this frame's input against {@code root} (the laid-out tree, or {@code null} before the first layout).
     * Runs on the GUI thread: {@link Pump#drain()} delivers queued edges to {@link #handle} on this thread.
     */
    public void dispatch(RetainedNode root) {
        this.currentRoot = root;
        dropDisabledFocus();
        pump.drain();
        pumpKeyRepeat();
        pumpDragGesture();
        // After this frame's notches have moved their targets, before the layout that places the content: the
        // offset written here is the one this frame is drawn at. Each step owes the next frame until it arrives.
        if (glide.step(System.nanoTime(), relock)) {
            requestLayout.run();
        }
        // A fading ring edits no prop, so it asks for its frames the way a focus move does.
        if (ringFade.step(System.nanoTime())) {
            focusStateSink.run();
        }
        settleDragSession();
        showArmed();
    }

    /**
     * Advance the drag recogniser's clock, on the same per-frame basis as auto-repeat and for the same reason:
     * its hold threshold needs time to pass, and a pointer held perfectly still after crossing the distance
     * produces no further events to carry it. Without this the drag would not begin until the pointer moved
     * again, which is precisely the moment the user is holding it steady to aim.
     */
    private void pumpDragGesture() {
        for (sibarum.tactroller.api.DragEvent d : drags.tick(System.nanoTime())) {
            onDragGesture(d);
        }
    }

    /**
     * Re-resolve the live drag against the tree as it now stands, then count the frame as seen.
     *
     * <p>Re-resolving every frame rather than only on pointer motion is what keeps the indicator honest when the
     * <em>tree</em> moves under a still pointer — a wheel scroll mid-drag being the ordinary case, and a
     * transition settling being the other. An indicator that updated only on motion would tell the truth until
     * the user scrolled and then lie until they jiggled the mouse.
     *
     * <p>Counting the frame here, at the end of dispatch, is what makes "a drop the user never saw does not
     * commit" fall out rather than needing enforcement: this frame goes on to render, so by the next dispatch
     * the count is honest. A flick whose whole gesture arrives in one drain reaches its release before any
     * dispatch has ended, so it commits nothing.
     */
    private void settleDragSession() {
        if (session == null) {
            return;
        }
        if (session.ended()) {
            session = null;
            return;
        }
        session.moveTo(session.x(), session.y(), this::dropTargetsAt);
        session.seen();
        // Last, so a watch sees the resolution this frame is drawn with — and ahead of the drain that follows
        // dispatch, so what it writes is laid out in this frame rather than the next.
        showWatches();
    }

    /** Release the bus subscription, and the presenter installed on this tree (it holds nodes of its own). */
    public void close() {
        sub.close();
        MenuPresenter presenter = menuPresenter;
        if (presenter != null) {
            menuPresenter = null;
            presenter.close();
        }
    }

    // --- delivered on the GUI thread during dispatch() ---

    private void handle(InputEvent e) {
        // Sequence every edge, not just the ones that publish an observation: a consumer that only ever sees key
        // routes still needs the gaps between them to be countable.
        current = conduit.next();
        // The touchpad recogniser reads pointer and button events too (where a gesture happens, and that a click
        // is not a tap), so it sees every edge, ahead of anything below that returns early.
        feedTouchpad(e);
        // A latched drag owns the pointer outright, ahead of everything, recogniser included: the click that puts
        // a carried thing down is not the start of a new gesture or a click on what is beneath it.
        if (latchedPointer(e)) {
            updateCursor();
            return;
        }
        // The drag recogniser observes the same stream, ahead of routing and without consuming from it: presses,
        // releases and moves still reach clicks, scrollbars and onDrag exactly as before, so nothing already
        // working has to learn that drags exist.
        feedDragGesture(e);
        switch (e) {
            case InputEvent.PointerMoved m -> {
                if (scrollDrag != null) {
                    pointerX = m.x();
                    pointerY = m.y();
                    dragScrollbar(m.x(), m.y());
                    updateCursor();   // stays closed while the grab is live, wherever the pointer has gone
                    return;
                }
                if (dragCapture != null) {
                    fireDrag(DragEvent.Phase.MOVE, m.x(), m.y(), m.dx(), m.dy());
                }
                pointerX = m.x();
                pointerY = m.y();
                hoverHit = RetainedNode.enabledPart(HitTest.at(currentRoot, m.x(), m.y()));
                refreshStates();
            }
            case InputEvent.ButtonPressed b when b.button() == MouseButton.LEFT -> {
                pointerX = b.x();
                pointerY = b.y();
                RetainedNode hit = HitTest.at(currentRoot, b.x(), b.y());
                // A press on a scrollbar thumb starts a thumb drag and consumes the press (no click/hover/drag).
                if (grabScrollbar(hit, b.x(), b.y())) {
                    pressCount = 0;   // and a click after it starts a sequence of its own
                    updateCursor();   // the scrollbar took the press: the hand closes on it
                    return;
                }
                RetainedNode open = RetainedNode.enabledPart(hit);
                if (open != hit) {
                    // On a disabled control: nothing at all. No focus move — not even away from whatever held it,
                    // which a press on empty space would do — no press state, no drag, and so no click on release.
                    pressCount = 0;
                    hoverHit = open;
                    refreshStates();
                    return;
                }
                countPress(hit, b.x(), b.y(), b.timestampNanos());
                // Click focuses the nearest focusable node (or clears focus on empty space).
                RetainedNode focusTarget = ancestorFocusable(hit);
                setFocus(focusTarget == null ? -1 : focusTarget.id, FocusCause.POINTER);
                pressTargetId = hit == null ? -1 : hit.id;
                pressHit = hit;
                hoverHit = hit;
                leftDown = true;
                // Held on something that could be picked up: a state of its own until it becomes a drag or a click.
                if (session == null && canDrag(hit)) {
                    armed = true;
                    armedX = b.x();
                    armedY = b.y();
                }
                // Capture for drag if the hit (or an ancestor) registered a drag handler.
                dragCapture = ancestorWithDragHandler(hit);
                if (dragCapture != null) {
                    fireDrag(DragEvent.Phase.START, b.x(), b.y());
                    requestPointerLock(dragLocks.contains(dragCapture.id));
                }
                refreshStates();
            }
            case InputEvent.ButtonReleased b when b.button() == MouseButton.LEFT -> {
                if (scrollDrag != null) {
                    scrollDrag = null;
                    pointerX = b.x();
                    pointerY = b.y();
                    hoverHit = RetainedNode.enabledPart(HitTest.at(currentRoot, b.x(), b.y()));
                    updateCursor();   // the hand opens again, over whatever the release landed on
                    return;
                }
                if (dragCapture != null) {
                    fireDrag(DragEvent.Phase.END, b.x(), b.y());
                    dragCapture = null;
                    requestPointerLock(false);
                }
                pointerX = b.x();
                pointerY = b.y();
                RetainedNode hit = HitTest.at(currentRoot, b.x(), b.y());
                RetainedNode open = RetainedNode.enabledPart(hit);
                // Disabled between the press and the release (a handler that disables what was just pressed): the
                // release lands on something closed, so it is not a click.
                if (hit != null && hit.id == pressTargetId && open == hit) {
                    fireClick(hit, b.x(), b.y());
                }
                pressTargetId = -1;
                pressHit = null;
                hoverHit = open;
                leftDown = false;
                if (armed) {
                    armed = false;
                    disarmWatches();   // it came up before it came loose: a click, and whatever was held springs back
                }
                refreshStates();
            }
            case InputEvent.ButtonPressed b when b.button() == MouseButton.RIGHT -> {
                // A right press pairs with its release and nothing more: no focus move, no PRESSED state, no drag
                // capture. What a context click *does* — select the row under it, open a menu — belongs to the
                // widget that receives it, not to the dispatcher.
                pointerX = b.x();
                pointerY = b.y();
                RetainedNode hit = HitTest.at(currentRoot, b.x(), b.y());
                // A disabled control opens no menu of its own, and does not pass the press to its row's.
                contextPressTargetId = hit == null || RetainedNode.enabledPart(hit) != hit ? -1 : hit.id;
            }
            case InputEvent.ButtonReleased b when b.button() == MouseButton.RIGHT -> {
                pointerX = b.x();
                pointerY = b.y();
                RetainedNode hit = HitTest.at(currentRoot, b.x(), b.y());
                if (hit != null && hit.id == contextPressTargetId) {
                    fireContextClick(hit, b.x(), b.y());
                }
                contextPressTargetId = -1;
            }
            case InputEvent.Scrolled s -> {
                RetainedNode hit = HitTest.at(currentRoot, s.x(), s.y());
                boolean changed = false;
                long now = System.nanoTime();
                // A notch is a fixed distance — position control, and reversible — delivered as a glide when the
                // application has asked for one (ScrollGlide), and at once when it has not.
                if (s.yOffset() != 0) {
                    RetainedNode t = ancestorScrollable(hit, false);
                    if (t != null) {
                        changed |= glide.by(t, 0f, -(float) s.yOffset() * WHEEL_STEP_EM * em(t), now);
                        // Judged by where the wheel is heading, not where the view has got to: a lock that stayed
                        // attached until the glide had left the edge would pin it back there every pass, and a
                        // tail-following view could never be scrolled up.
                        refreshScrollLock(t, glide.targetY(t));
                    }
                }
                if (s.xOffset() != 0) {
                    RetainedNode t = ancestorScrollable(hit, true);
                    if (t != null) {
                        changed |= glide.by(t, (float) s.xOffset() * WHEEL_STEP_EM * em(t), 0f, now);
                    }
                }
                if (changed) {
                    requestLayout.run();
                }
            }
            case InputEvent.CharTyped c -> {
                // Typed text goes only to the focused, editable node — never conflated with the key command.
                if (focusedId != -1) {
                    int cp = c.codepoint();
                    // The ordered stage runs here, on the drain thread, so a burst of characters applies in the
                    // order it was typed. An async handler is still offered the code point afterwards.
                    IntConsumer stage = charStages.get(focusedId);
                    if (stage != null) {
                        stage.accept(cp);
                    }
                    IntConsumer handler = charHandlers.get(focusedId);
                    if (handler != null) {
                        handlerExecutor.execute(() -> handler.accept(cp));
                    }
                }
            }
            case InputEvent.KeyPressed k -> handleKeyDown(k.key());
            case InputEvent.KeyReleased k -> {
                Modifier mod = modifierOf(k.key());
                if (mod != null) {
                    if (heldMods.remove(mod)) {
                        reportModifiers();
                    }
                } else if (k.key() == repeatKey) {
                    repeatKey = null; // stop auto-repeat when the held key is lifted
                }
            }
            case InputEvent.FocusChanged f -> {
                // A window that loses focus never sees the release of anything held over it: alt-tab away with
                // Ctrl down and the key comes up in somebody else's window. Left as it was, the set says Ctrl is
                // held for the rest of the session — and anything drawn from it (a document showing its links)
                // stays stuck in that state with no way for the user to clear it. Losing focus clears the held
                // set for the same reason it stops a repeat: what was held is no longer this window's business.
                if (windowActive != f.focused()) {
                    windowActive = f.focused();
                    focusStateSink.run();
                }
                if (!f.focused() && !heldMods.isEmpty()) {
                    heldMods.clear();
                    repeatKey = null;
                    reportModifiers();
                }
            }
            default -> {
                // Other device edges are not routed here.
            }
        }
    }

    private void handleKeyDown(Key key) {
        if (escapeCancelledDrag(key)) {
            return;
        }
        Modifier mod = modifierOf(key);
        if (mod != null) {
            if (heldMods.add(mod)) {
                reportModifiers();
            }
            return; // a modifier keypress is not itself a chord or a command
        }
        KeyEvent e = new KeyEvent(key, Set.copyOf(heldMods));

        // 1. A claim, if one applies — preemption that was declared in advance (ClaimScope). The framework's own
        //    Tab traversal is registered this way rather than intercepted here, so a focused element that claims
        //    Tab simply outranks it. Claims do not arm auto-repeat: a held Tab inserts one soft tab, not a stream.
        Shortcut pressed = new Shortcut(heldMods, key);
        Claim claimed = resolveClaim(pressed);
        if (claimed != null) {
            if (claimed.inline()) {
                claimed.command().accept(pressed);
            } else {
                handlerExecutor.execute(() -> claimed.command().accept(pressed));
            }
            report(e, true);
            return;
        }

        // 2. Otherwise the focused node's key handler (caret motion, backspace, ...). Core takes nothing for
        //    itself here — if nobody claimed the chord, the focused element hears it.
        if (focusedId != -1) {
            Consumer<KeyEvent> stage = keyStages.get(focusedId);
            if (stage != null) {
                stage.accept(e);   // ordered: a caret move must sequence against the typing around it
            }
            Consumer<KeyEvent> handler = keyHandlers.get(focusedId);
            if (handler != null) {
                handlerExecutor.execute(() -> handler.accept(e));
            }
            // Arm auto-repeat: this key re-fires after the initial delay until released (§8.4).
            repeatKey = key;
            repeatNextNanos = System.nanoTime() + REPEAT_DELAY_NANOS;
        }
        report(e, false);
    }

    /** The applicable claim on {@code chord} with the most specific scope, or null if none applies. */
    private Claim resolveClaim(Trigger chord) {
        Claim best = null;
        for (Claim c : claims) {
            if (!c.matches(chord) || !applies(c)) {
                continue;
            }
            if (best == null || c.outranks(best)) {
                best = c;
            }
        }
        return best;
    }

    private boolean applies(Claim c) {
        // A claim is input to the node that holds it, so a disabled one hears none — at any scope. Without this a
        // disabled editor's VISIBLE Ctrl+S, or a disabled panel's GLOBAL shortcut, would still run its command.
        if (disabled(c.nodeId())) {
            return false;
        }
        return switch (c.scope()) {
            case FOCUSED -> c.nodeId() == focusedId;
            case VISIBLE -> present(currentRoot, c.nodeId());
            case GLOBAL -> true;
        };
    }

    /**
     * Whether {@code nodeId} is closed to the user — disabled itself or under a disabled ancestor (see
     * {@code PropKey.DISABLED}). False for a node the tree does not hold. GUI thread: it reads the model.
     */
    public boolean disabled(long nodeId) {
        RetainedNode n = nodes.apply(nodeId);
        return n != null && n.disabledInTree();
    }

    /**
     * Install how this dispatcher looks a node up by id — the reconciler's index. Without it nothing is ever
     * judged disabled by id, which is the behaviour before the lookup existed, so a dispatcher in a test that
     * never sets it is unchanged.
     */
    public void nodeLookup(java.util.function.LongFunction<RetainedNode> lookup) {
        this.nodes = lookup == null ? id -> null : lookup;
    }

    /**
     * Install how a menu finds the history its edits belong in, from the node that was right-clicked — the one
     * bound nearest above it. Without it a menu's {@link MenuSink#edit} items are still done, only not undoable.
     */
    public void menuHistory(java.util.function.LongFunction<History> lookup) {
        this.menuHistory = lookup == null ? id -> null : lookup;
    }

    /**
     * Take focus away from a node that has been disabled since it was given focus, before this frame's input is
     * delivered — so a key typed in the same frame as the disable goes nowhere rather than into a closed field.
     * Depth-bounded, and allocation-free: it runs every frame.
     */
    private void dropDisabledFocus() {
        if (focusedId != -1 && disabled(focusedId)) {
            setFocus(-1, FocusCause.PROGRAM);
        }
    }

    private static boolean present(RetainedNode n, long id) {
        if (n == null) {
            return false;
        }
        if (n.id == id) {
            return true;
        }
        for (RetainedNode c : n.children) {
            if (present(c, id)) {
                return true;
            }
        }
        return false;
    }

    /** Report a routed key press to observers. Never consulted for routing — see {@link KeyRouted}. */
    private void report(KeyEvent e, boolean claimed) {
        if (keyRoutedTopic != null) {
            bus.publish(keyRoutedTopic, new KeyRouted(e, focusedId, claimed, current));
        }
    }

    /** Re-fire the held key's route at the repeat rate — called once per frame from {@link #dispatch}. */
    private void pumpKeyRepeat() {
        if (repeatKey == null || focusedId == -1) {
            return;
        }
        long now = System.nanoTime();
        if (now < repeatNextNanos) {
            return;
        }
        // Use the live modifier set so pressing Ctrl mid-hold upgrades e.g. arrow-repeat to word-jump.
        KeyEvent e = new KeyEvent(repeatKey, Set.copyOf(heldMods));
        Consumer<KeyEvent> stage = keyStages.get(focusedId);
        if (stage != null) {
            stage.accept(e);
        }
        Consumer<KeyEvent> handler = keyHandlers.get(focusedId);
        if (handler != null) {
            handlerExecutor.execute(() -> handler.accept(e));
        }
        repeatNextNanos = now + REPEAT_INTERVAL_NANOS; // frame-rate-bounded; no burst catch-up
    }

    /**
     * Install the sink told when anything in {@link #focusState()} changes. The focus ring is drawn from that state
     * and from no prop, so a focus move that edits no node still has to ask for a frame or the ring stays behind.
     */
    public void onFocusState(Runnable sink) {
        this.focusStateSink = sink == null ? () -> { } : sink;
    }

    /** Install the sink told when the held-modifier set changes. */
    public void onModifiers(Consumer<Set<Modifier>> sink) {
        this.modifierSink = sink == null ? m -> { } : sink;
    }

    /** The modifiers held right now — a snapshot, taken on the dispatch thread. */
    public Set<Modifier> modifiers() {
        return Set.copyOf(heldMods);
    }

    private void reportModifiers() {
        modifierSink.accept(Set.copyOf(heldMods));
    }

    private static Modifier modifierOf(Key key) {
        return switch (key) {
            case LEFT_SHIFT, RIGHT_SHIFT -> Modifier.SHIFT;
            case LEFT_CONTROL, RIGHT_CONTROL -> Modifier.CONTROL;
            case LEFT_ALT, RIGHT_ALT -> Modifier.ALT;
            case LEFT_SUPER, RIGHT_SUPER -> Modifier.SUPER;
            default -> null;
        };
    }

    private void moveFocus(int dir) {
        List<Long> order = new ArrayList<>();
        collectFocusable(currentRoot, order);
        if (order.isEmpty()) {
            return;
        }
        int idx = order.indexOf(focusedId);
        int next = idx < 0
                ? (dir > 0 ? 0 : order.size() - 1)
                : ((idx + dir) % order.size() + order.size()) % order.size();
        long target = order.get(next);
        setFocus(target, FocusCause.KEYBOARD);
        // Focus the user cannot see is focus they cannot use: the ring is clipped away with the node, and the
        // next keystroke goes somewhere off-screen. Asked even when focus did not move (a lone focusable that was
        // scrolled away), since Tab is the user asking where focus is.
        revealSink.accept(target);
    }

    /**
     * Focusable node ids in tree (DFS pre-order) — the Tab order.
     *
     * <p>A hidden subtree is not in it, and cannot be: hiding is how this framework says "not now" — a collapsed
     * subtree, the tab page that is not selected, a bar that opens on a shortcut — and a control the user cannot
     * see is a control they cannot answer. Tab landing on one takes the caret somewhere invisible and swallows
     * every keystroke after it. Judged on the node's own {@code visible()}, so the whole subtree under a hidden
     * container goes with it.
     */
    private void collectFocusable(RetainedNode n, List<Long> out) {
        // A disabled subtree goes with it, for the same reason: Tab stopping on what cannot be used.
        if (n == null || !n.visible() || n.disabled()) {
            return;
        }
        if (focusable.contains(n.id)) {
            out.add(n.id);
        }
        for (RetainedNode c : n.children) {
            collectFocusable(c, out);
        }
    }

    private void setFocus(long id, FocusCause cause) {
        if (id == focusedId) {
            // No move, so nothing to announce; but the latest way focus was asked for is the one a ring answers
            // to. Tab onto a button and then click it, and it is now a button that was clicked.
            if (focusCause != cause) {
                focusCause = cause;
                focusStateSink.run();
            }
            return;
        }
        long old = focusedId;
        ringFade.moved(old, focusCause, id);
        focusedId = id;
        focusCause = cause;
        focusStateSink.run();
        if (focusTopic != null) {
            if (old != -1) {
                bus.publish(focusTopic, new FocusEvent(old, false, cause));
            }
            if (id != -1) {
                bus.publish(focusTopic, new FocusEvent(id, true, cause));
            }
        }
    }

    /**
     * Where focus is, how it got there, and whether the window is in front: everything the renderer needs to
     * draw the focus ring, as one value it is handed rather than an object it reaches into.
     */
    public FocusState focusState() {
        return ringFade.applyTo(focusedId, focusCause, windowActive);
    }

    /** Nearest focusable ancestor-or-self of {@code hit}, or {@code null}. */
    private RetainedNode ancestorFocusable(RetainedNode hit) {
        for (RetainedNode n = hit; n != null; n = n.parent) {
            if (focusable.contains(n.id)) {
                return n;
            }
        }
        return null;
    }

    /**
     * Handle a press that landed on a scrollbar (of the hit node or an ancestor): on the thumb it begins a drag,
     * on the track either side of the thumb it pages toward the click. Either way the press is <b>consumed</b>.
     *
     * <p>Consuming the track matters as much as handling it. The strip is reserved space the content is clipped
     * out of, so a press there addresses the bar and nothing else — previously only the thumb was claimed and a
     * press on the track fell through to whatever lay beneath, which in a text field meant the caret jumped to
     * the end of the document because the point mapped past the last line.
     *
     * <p>The walk stops at a floating node. A float paints over its parent, strips included, and is hit before
     * it — so a press on a float is a press on the float, even where the parent's scrollbar lies beneath. Walking
     * on past it gave the press to a bar the float was covering: the × at the end of a field's find bar, laid
     * over the field's scrollbar, could not be clicked.
     */
    private boolean grabScrollbar(RetainedNode hit, float x, float y) {
        for (RetainedNode n = hit; n != null; n = n.floating() ? null : n.parent) {
            if (n.overflowY && inStripV(n, x, y)) {
                float[] thumb = n.vThumbRect();
                if (inRect(thumb, x, y)) {
                    scrollDrag = n;
                    scrollDragVertical = true;
                    scrollDragGrab = y - thumb[1];
                } else {
                    pageScroll(n, true, y < thumb[1] ? -1 : 1);
                }
                return true;
            }
            if (n.overflowX && inStripH(n, x, y)) {
                float[] thumb = n.hThumbRect();
                if (inRect(thumb, x, y)) {
                    scrollDrag = n;
                    scrollDragVertical = false;
                    scrollDragGrab = x - thumb[0];
                } else {
                    pageScroll(n, false, x < thumb[0] ? -1 : 1);
                }
                return true;
            }
        }
        return false;
    }

    /** Scroll {@code n} by one viewport in {@code dir} along an axis — what a press on the track does. */
    private void pageScroll(RetainedNode n, boolean vertical, int dir) {
        if (vertical) {
            n.scrollY = clamp(n.scrollY + dir * n.viewH, 0f, Math.max(0f, n.contentH - n.viewH));
            refreshScrollLock(n);
        } else {
            n.scrollX = clamp(n.scrollX + dir * n.viewW, 0f, Math.max(0f, n.contentW - n.viewW));
        }
        requestLayout.run();
    }

    /** The vertical scrollbar's reserved strip: to the right of the viewport, its full height. */
    private static boolean inStripV(RetainedNode n, float x, float y) {
        return x >= n.viewX + n.viewW && x < n.viewX + n.viewW + n.scrollbarPx
                && y >= n.viewY && y < n.viewY + n.viewH;
    }

    /** The horizontal scrollbar's reserved strip: below the viewport, its full width. */
    private static boolean inStripH(RetainedNode n, float x, float y) {
        return y >= n.viewY + n.viewH && y < n.viewY + n.viewH + n.scrollbarPx
                && x >= n.viewX && x < n.viewX + n.viewW;
    }

    /** Map the pointer to a scroll offset for the grabbed thumb, keeping the grab point under the cursor. */
    private void dragScrollbar(float x, float y) {
        RetainedNode n = scrollDrag;
        if (scrollDragVertical) {
            float travel = n.vThumbTravel();
            float frac = travel > 0f
                    ? clamp((y - scrollDragGrab - n.viewY - n.thumbInsetPx()) / travel, 0f, 1f) : 0f;
            n.scrollY = frac * Math.max(0f, n.contentH - n.viewH);
            refreshScrollLock(n);
        } else {
            float travel = n.hThumbTravel();
            float frac = travel > 0f
                    ? clamp((x - scrollDragGrab - n.viewX - n.thumbInsetPx()) / travel, 0f, 1f) : 0f;
            n.scrollX = frac * Math.max(0f, n.contentW - n.viewW);
        }
        requestLayout.run();
    }

    private static boolean inRect(float[] r, float x, float y) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    /** Nearest ancestor-or-self of {@code hit} that overflows on the given axis. */
    private static RetainedNode ancestorScrollable(RetainedNode hit, boolean horizontal) {
        for (RetainedNode n = hit; n != null; n = n.parent) {
            if (horizontal ? n.overflowX : n.overflowY) {
                return n;
            }
        }
        return null;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Re-evaluate a scroll-locked container's attachment after the user scrolls it (§8.5): attached while the
     * offset sits at the locked edge, detached once the user scrolls away. A no-op for unlocked containers.
     */
    private static void refreshScrollLock(RetainedNode n) {
        refreshScrollLock(n, n.scrollY);
    }

    /** As {@link #refreshScrollLock(RetainedNode)}, judging attachment by offset {@code y} rather than the current. */
    private static void refreshScrollLock(RetainedNode n, float y) {
        ScrollLock lock = n.scrollLock();
        if (lock == ScrollLock.NONE) {
            return;
        }
        float maxY = Math.max(0f, n.contentH - n.viewH);
        float eps = LOCK_EDGE_EPS_EM * em(n);
        n.scrollAttached = lock == ScrollLock.BOTTOM
                ? y >= maxY - eps
                : y <= eps;
    }

    /** Recompute each observed node's interaction state and tell every observer on any change. */
    private void refreshStates() {
        for (Map.Entry<Long, java.util.List<Consumer<InteractionState>>> entry : stateHandlers.entrySet()) {
            long id = entry.getKey();
            InteractionState now;
            if (leftDown && covers(id, pressHit) && covers(id, hoverHit)) {
                now = InteractionState.PRESSED;
            } else if (covers(id, hoverHit)) {
                now = InteractionState.HOVER;
            } else {
                now = InteractionState.NORMAL;
            }
            InteractionState prev = reportedState.getOrDefault(id, InteractionState.NORMAL);
            if (now != prev) {
                reportedState.put(id, now);
                if (Probe.ON) {
                    // The consequence of motion, not the motion itself. A path that crosses something records
                    // pointer.move either way; what says the crossing *mattered* is the node that changed state
                    // because of it — which is precisely the evidence a hover-path bug leaves and nothing else
                    // does (docs/reference/automation.md §2).
                    Probe.mark(Lane.INPUT, "state." + now.name().toLowerCase(java.util.Locale.ROOT),
                            "node=" + id);
                }
                InteractionState delivered = now;
                for (Consumer<InteractionState> observer : entry.getValue()) {
                    handlerExecutor.execute(() -> observer.accept(delivered));
                }
            }
        }
        updateCursor();
    }

    /**
     * Report the desired cursor for what the pointer is over, and what it is doing. Precedence is defined by
     * {@link CursorShape} -- a grab in progress beats a grab available, which beats a click, which beats text.
     *
     * <p>The affordance rules are inferred, not declared: a node is "clickable" because it has a click handler,
     * by the same ancestor-or-self walk clicks bubble by. That is what makes this a rule rather than a property
     * every widget has to remember to set -- registering the behaviour is what advertises it.
     */
    private void updateCursor() {
        CursorShape desired = desiredCursor();
        if (desired != reportedCursor) {
            reportedCursor = desired;
            cursorSink.accept(desired);
        }
    }

    private CursorShape desiredCursor() {
        // Something is being grabbed: the pointer is captured and still driving it, wherever it has wandered to.
        // A drag in flight is the same fact said by the transfer layer — the hand is holding what it picked up,
        // whatever it is passing over, and an I-beam above a text field on the way would promise the wrong gesture.
        if (scrollDrag != null) {
            return CursorShape.GRABBING;
        }
        // Latched, the hand has let go and the thing stays with it, waiting to be put down: open, not closed.
        if (session != null && !session.ended()) {
            return session.latched() ? CursorShape.GRAB : CursorShape.GRABBING;
        }
        if (leftDown && dragCapture != null) {
            CursorShape declared = declaredCursor(dragCapture);
            CursorShape held = declared == null ? null : declared.whileDragging();
            if (held != null) {
                return held;
            }
        }
        // A scrollbar under the pointer is grabbable -- framework chrome, so the framework knows it without
        // anyone declaring anything. Not an ancestor's past a float, which paints over that ancestor's strips.
        for (RetainedNode n = hoverHit; n != null; n = n.floating() ? null : n.parent) {
            if ((n.overflowY && inStripV(n, pointerX, pointerY)) || (n.overflowX && inStripH(n, pointerX, pointerY))) {
                return CursorShape.GRAB;
            }
        }
        // The nearest node that has something to say decides: an explicit declaration (a slider says GRAB), or what
        // the node answers to. Nearest, not "declarations first": a card says GRAB because it can be picked up, and
        // a button on the card is still a button — the pointer over it is about to click it, not to pick up the
        // card it sits on. A declaration on the very node wins over what is inferred about it.
        for (RetainedNode n = hoverHit; n != null; n = n.parent) {
            CursorShape declared = nodeCursors.get(n.id);
            if (declared != null) {
                return declared;
            }
            if (answersClicks(n.id)) {
                return CursorShape.POINTER;
            }
            if (isTextTarget(n)) {
                return CursorShape.TEXT;
            }
        }
        return CursorShape.DEFAULT;
    }

    /** The nearest declared cursor on {@code hit} or an ancestor, or null if none declared one. */
    private CursorShape declaredCursor(RetainedNode hit) {
        for (RetainedNode n = hit; n != null; n = n.parent) {
            CursorShape s = nodeCursors.get(n.id);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    /** Whether {@code n} is a text node that takes the text-placement cursor (editable, or selectable later). */
    private static boolean isTextTarget(RetainedNode n) {
        return n != null && n.editable();
    }

    /** Whether {@code handlerId} is {@code hit} or one of its ancestors (so a child hit covers its container). */
    private static boolean covers(long handlerId, RetainedNode hit) {
        for (RetainedNode n = hit; n != null; n = n.parent) {
            if (n.id == handlerId) {
                return true;
            }
        }
        return false;
    }

    /**
     * The nearest ancestor-or-self of {@code hit} with a drag handler, or {@code null} — and {@code null} too when
     * a node with a click handler comes first. A press belongs to the nearest thing that answers it: a button
     * floated over a text field is the button's press, and letting it fall through to the field's drag would put
     * the caret under the button as well as pressing it.
     */
    private RetainedNode ancestorWithDragHandler(RetainedNode hit) {
        for (RetainedNode n = hit; n != null; n = n.parent) {
            if (dragHandlers.containsKey(n.id) || dragStages.containsKey(n.id)) {
                return n;
            }
            if (answersClicks(n.id)) {
                return null;
            }
        }
        return null;
    }

    /** Whether {@code nodeId} has a click or a double-click handler: either way, a press on it is its press. */
    private boolean answersClicks(long nodeId) {
        return clickHandlers.containsKey(nodeId) || doubleClickHandlers.containsKey(nodeId);
    }

    private void fireDrag(DragEvent.Phase phase, float x, float y) {
        fireDrag(phase, x, y, 0f, 0f);
    }

    /**
     * Which press of its sequence this left press is: one more than the last if it came within
     * {@link #MULTI_CLICK_NANOS} of it, within {@link #MULTI_CLICK_SLOP_EM} of where it was, and on the same node;
     * otherwise the first of a new one.
     *
     * <p>"Within" means measurably after, not merely no later: a press stamped no later than the one before it has
     * no time to compare, which is what every synthetic press carries (a test, a harness, a replay stamping zero),
     * and two of those in a row are two clicks rather than a double-click nobody asked for.
     */
    private void countPress(RetainedNode hit, float x, float y, long nanos) {
        long hitId = hit == null ? -1 : hit.id;
        long gap = nanos - lastPressNanos;
        float slop = MULTI_CLICK_SLOP_EM * em(hit);
        boolean continues = pressCount > 0 && gap > 0 && gap <= MULTI_CLICK_NANOS && hitId == lastPressHitId
                && Math.abs(x - lastPressX) <= slop && Math.abs(y - lastPressY) <= slop;
        pressCount = continues ? pressCount + 1 : 1;
        lastPressNanos = nanos;
        lastPressX = x;
        lastPressY = y;
        lastPressHitId = hitId;
    }

    private void fireDrag(DragEvent.Phase phase, float x, float y, float dx, float dy) {
        RetainedNode n = dragCapture;
        DragEvent e = new DragEvent(phase, x, y, dx, dy, n.x, n.y, n.w, n.h, pressCount, heldMods);
        // Ordered first: click-to-caret moves the same document typing does, so it has to sequence with it.
        Consumer<DragEvent> stage = dragStages.get(n.id);
        if (stage != null) {
            stage.accept(e);
        }
        Consumer<DragEvent> handler = dragHandlers.get(n.id);
        if (handler != null) {
            handlerExecutor.execute(() -> handler.accept(e));
        }
    }

    private void fireClick(RetainedNode target, float x, float y) {
        // The press this release completes was counted when it went down; a click needs press and release on one
        // node, as continuing a sequence does, so that count is this click's.
        ClickEvent e = new ClickEvent(target.id, MouseButton.LEFT, x, y, pressCount, heldMods);
        bubble(clickHandlers, target, e);
        if (e.clicks() == 2) {
            bubble(doubleClickHandlers, target, e);
        }
        bus.publish(clicks, e);
    }

    /** The right-button twin of {@link #fireClick}: bubble to the first context handler, then publish. */
    private void fireContextClick(RetainedNode target, float x, float y) {
        ClickEvent e = new ClickEvent(target.id, MouseButton.RIGHT, x, y, heldMods);
        bubble(contextHandlers, target, e);
        openMenu(target, e);
        bus.publish(clicks, e);
    }

    /** Hand {@code e} to the nearest ancestor-or-self of {@code target} with a handler in {@code handlers}. */
    private void bubble(Map<Long, Consumer<ClickEvent>> handlers, RetainedNode target, ClickEvent e) {
        for (RetainedNode n = target; n != null; n = n.parent) {
            Consumer<ClickEvent> handler = handlers.get(n.id);
            if (handler != null) {
                handlerExecutor.execute(() -> handler.accept(e));
                return; // consumed; bubbling stops at the first handler
            }
        }
    }

    /**
     * The menu half of a right click: the <b>nearest node with sources owns it</b>, exactly as the nearest node
     * with a click handler owns a click. Walking leaf→root is what makes "a menu anywhere" composable — a row
     * inside a panel inside a page can each have one, and the innermost thing the user actually pointed at is the
     * one that answers, without any of them knowing the others exist.
     *
     * <p>The sources themselves run on the handler executor, not here: they read application state to decide what
     * is on the menu, and the GUI thread is mid-frame. A node whose sources contribute nothing has no menu — an
     * empty panel is never flashed — and with no presenter installed nothing is collected, which is said once if a
     * node on the way up has a menu that therefore goes undrawn.
     */
    private void openMenu(RetainedNode target, ClickEvent e) {
        MenuPresenter presenter = menuPresenter;
        if (presenter == null) {
            for (RetainedNode n = target; n != null; n = n.parent) {
                java.util.List<Consumer<MenuSink>> sources = menuSources.get(n.id);
                if (sources != null && !sources.isEmpty()) {
                    dev.vexelray.diag.Diagnostics.dropped("context-menu-presenter",
                            "the context menu of node " + n.id,
                            "no MenuPresenter is installed on this tree and none is registered as a "
                                    + "MenuPresenter.Default (vexelray-gui-widget's ContextMenu is), so menus are "
                                    + "collected and never drawn; install one with Gui.menus");
                    break;
                }
            }
            return;
        }
        for (RetainedNode n = target; n != null; n = n.parent) {
            java.util.List<Consumer<MenuSink>> sources = menuSources.get(n.id);
            if (sources == null || sources.isEmpty()) {
                continue;
            }
            handlerExecutor.execute(() -> {
                MenuCollector collector = new MenuCollector(e, menuHistory.apply(e.nodeId()));
                for (Consumer<MenuSink> source : sources) {
                    source.accept(collector);
                }
                java.util.List<MenuItem> items = collector.build();
                if (!items.isEmpty()) {
                    presenter.present(e, items);
                }
            });
            return;   // consumed; the walk stops at the first node that has a menu
        }
    }
}
