package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.layout.LayoutEnums.ScrollLock;
import dev.vexelray.gui.core.model.RetainedNode;
import sibarum.atchung.Atchung;
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

    /** Input edges are drained every frame; a generous mailbox with drop-oldest never sheds under real rates. */
    private static final int MAILBOX = 4096;

    /** Pixels scrolled per wheel notch. */
    private static final float WHEEL_STEP = 48f;

    private final Atchung bus;
    private final Topic<ClickEvent> clicks;
    private final Executor handlerExecutor;
    private final Runnable requestLayout;
    private final Pump pump;
    private final Subscription sub;
    private final Map<Long, Runnable> clickHandlers = new ConcurrentHashMap<>();
    private final Map<Long, Consumer<InteractionState>> stateHandlers = new ConcurrentHashMap<>();
    private final Map<Long, Consumer<DragEvent>> dragHandlers = new ConcurrentHashMap<>();
    private final Map<Long, Consumer<KeyEvent>> keyHandlers = new ConcurrentHashMap<>();
    // Typed-text handlers (CharTyped → codepoint) and caret-placement handlers (click → offset), both for
    // editable text nodes; registering either makes the node focusable.
    private final Map<Long, IntConsumer> charHandlers = new ConcurrentHashMap<>();
    /**
     * Declared preemption (see {@link ClaimScope}). Ordered only by scope precedence, never by registration, so
     * a focused element always outranks a global default. {@code inline} marks the framework's own claims, whose
     * commands mutate GUI-thread state (focus) and so must not be handed to the worker executor.
     */
    private record Claim(Shortcut chord, ClaimScope scope, long nodeId, Runnable command, boolean inline) {
    }

    private final List<Claim> claims = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Set<Long> focusable = ConcurrentHashMap.newKeySet();
    private final Map<Long, InteractionState> reportedState = new ConcurrentHashMap<>();

    // Keyboard/focus state (GUI-thread only, mutated during dispatch).
    private final EnumSet<Modifier> heldMods = EnumSet.noneOf(Modifier.class);
    private long focusedId = -1;
    private Topic<FocusEvent> focusTopic;
    private Topic<KeyRouted> keyRoutedTopic;

    // Held-key auto-repeat (§8.4): the last non-modifier key routed to a focused node, and when it next
    // re-fires. Synthesised on the per-frame dispatch() clock, since tactroller reports only the OS key edge.
    private static final long REPEAT_DELAY_NANOS = 400_000_000L;   // wait before the first repeat
    private static final long REPEAT_INTERVAL_NANOS = 40_000_000L; // ~25 repeats/second thereafter
    private Key repeatKey;
    private long repeatNextNanos;

    private RetainedNode currentRoot;
    private long pressTargetId = -1;
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
    // Cursor shape reporting (§8.3): the app-installed sink and the last shape reported (to fire only on change).
    private Consumer<CursorShape> cursorSink = s -> { };
    private CursorShape reportedCursor = CursorShape.DEFAULT;

    public InputDispatcher(Atchung bus, Topic<ClickEvent> clicks, Executor handlerExecutor) {
        this(bus, clicks, handlerExecutor, () -> { });
    }

    public InputDispatcher(Atchung bus, Topic<ClickEvent> clicks, Executor handlerExecutor, Runnable requestLayout) {
        this.bus = bus;
        this.clicks = clicks;
        this.handlerExecutor = handlerExecutor;
        this.requestLayout = requestLayout;
        this.pump = bus.pump();
        this.sub = pump.subscribe(InputTopics.INPUT, this::handle, MAILBOX, Backpressure.DROP_OLDEST);
        // Focus traversal is a framework *default*, not an interception: it is an ordinary global claim, so a
        // focused element that claims Tab (a multiline editor indenting) simply outranks it. Shift+Tab is left
        // unclaimed by such elements, which is what keeps a way out of the field.
        claimInternal(Shortcut.of(Key.TAB), () -> moveFocus(1));
        claimInternal(Shortcut.of(Key.TAB, Modifier.SHIFT), () -> moveFocus(-1));
    }

    /** Register a click handler for {@code nodeId}; replaces any prior handler for that node. */
    public void onClick(long nodeId, Runnable handler) {
        clickHandlers.put(nodeId, handler);
    }

    /**
     * Register an interaction-state handler for {@code nodeId}, invoked whenever the node's {@link InteractionState}
     * changes (NORMAL/HOVER/PRESSED). Replaces any prior handler for that node.
     */
    public void onState(long nodeId, Consumer<InteractionState> handler) {
        stateHandlers.put(nodeId, handler);
    }

    /**
     * Register a drag handler for {@code nodeId}: a left press on the node (or a descendant) captures the pointer
     * and delivers START, then MOVE for every subsequent motion while held (even off the node), then END on
     * release. Replaces any prior handler for that node.
     */
    public void onDrag(long nodeId, Consumer<DragEvent> handler) {
        dragHandlers.put(nodeId, handler);
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

    /** Register a global shortcut command — a {@link ClaimScope#GLOBAL} claim owned by no node. */
    public void registerShortcut(Shortcut shortcut, Runnable command) {
        claim(-1L, shortcut, ClaimScope.GLOBAL, command);
    }

    /** Claim {@code chord} for {@code nodeId} at {@code scope}; replaces any identical claim by the same node. */
    public void claim(long nodeId, Shortcut chord, ClaimScope scope, Runnable command) {
        claims.removeIf(c -> c.nodeId() == nodeId && c.scope() == scope && c.chord().equals(chord));
        claims.add(new Claim(chord, scope, nodeId, command, false));
    }

    /** Drop every claim {@code nodeId} holds on {@code chord}. */
    public void releaseClaim(long nodeId, Shortcut chord) {
        claims.removeIf(c -> c.nodeId() == nodeId && c.chord().equals(chord));
    }

    /** A framework default, run on the GUI thread because it mutates dispatch state. */
    private void claimInternal(Shortcut chord, Runnable command) {
        claims.add(new Claim(chord, ClaimScope.GLOBAL, -1L, command, true));
    }

    /** The topic every routed key press is reported on (set by Gui). */
    public void keyRoutedTopic(Topic<KeyRouted> topic) {
        this.keyRoutedTopic = topic;
    }

    /** The topic focus changes publish on (set by Gui). */
    public void focusTopic(Topic<FocusEvent> topic) {
        this.focusTopic = topic;
    }

    /** Install the sink notified (on the GUI thread) when the desired cursor shape changes (§8.3). */
    public void cursorSink(Consumer<CursorShape> sink) {
        this.cursorSink = sink == null ? s -> { } : sink;
    }

    /** Programmatically move focus to {@code nodeId} (or -1 to clear). */
    public void focus(long nodeId) {
        setFocus(nodeId);
    }

    /** The node holding keyboard focus, or {@code -1} for none. */
    public long focusedId() {
        return focusedId;
    }

    /** The currently focused node id, or -1. */
    public long focused() {
        return focusedId;
    }

    /** Drop {@code nodeId}'s handlers and focusability (call when the node is removed). */
    public void clearHandlers(long nodeId) {
        clickHandlers.remove(nodeId);
        stateHandlers.remove(nodeId);
        dragHandlers.remove(nodeId);
        keyHandlers.remove(nodeId);
        charHandlers.remove(nodeId);
        focusable.remove(nodeId);
        reportedState.remove(nodeId);
        if (focusedId == nodeId) {
            setFocus(-1);
        }
    }

    /**
     * Drain this frame's input against {@code root} (the laid-out tree, or {@code null} before the first layout).
     * Runs on the GUI thread: {@link Pump#drain()} delivers queued edges to {@link #handle} on this thread.
     */
    public void dispatch(RetainedNode root) {
        this.currentRoot = root;
        pump.drain();
        pumpKeyRepeat();
    }

    /** Release the bus subscription. */
    public void close() {
        sub.close();
    }

    // --- delivered on the GUI thread during dispatch() ---

    private void handle(InputEvent e) {
        switch (e) {
            case InputEvent.PointerMoved m -> {
                if (scrollDrag != null) {
                    dragScrollbar(m.x(), m.y());
                    return;
                }
                if (dragCapture != null) {
                    fireDrag(DragEvent.Phase.MOVE, m.x(), m.y());
                }
                hoverHit = HitTest.at(currentRoot, m.x(), m.y());
                refreshStates();
            }
            case InputEvent.ButtonPressed b when b.button() == MouseButton.LEFT -> {
                RetainedNode hit = HitTest.at(currentRoot, b.x(), b.y());
                // A press on a scrollbar thumb starts a thumb drag and consumes the press (no click/hover/drag).
                if (grabScrollbar(hit, b.x(), b.y())) {
                    return;
                }
                // Click focuses the nearest focusable node (or clears focus on empty space).
                RetainedNode focusTarget = ancestorFocusable(hit);
                setFocus(focusTarget == null ? -1 : focusTarget.id);
                pressTargetId = hit == null ? -1 : hit.id;
                pressHit = hit;
                hoverHit = hit;
                leftDown = true;
                // Capture for drag if the hit (or an ancestor) registered a drag handler.
                dragCapture = ancestorWithDragHandler(hit);
                if (dragCapture != null) {
                    fireDrag(DragEvent.Phase.START, b.x(), b.y());
                }
                refreshStates();
            }
            case InputEvent.ButtonReleased b when b.button() == MouseButton.LEFT -> {
                if (scrollDrag != null) {
                    scrollDrag = null;
                    return;
                }
                if (dragCapture != null) {
                    fireDrag(DragEvent.Phase.END, b.x(), b.y());
                    dragCapture = null;
                }
                RetainedNode hit = HitTest.at(currentRoot, b.x(), b.y());
                if (hit != null && hit.id == pressTargetId) {
                    fireClick(hit, b.x(), b.y());
                }
                pressTargetId = -1;
                pressHit = null;
                hoverHit = hit;
                leftDown = false;
                refreshStates();
            }
            case InputEvent.Scrolled s -> {
                RetainedNode hit = HitTest.at(currentRoot, s.x(), s.y());
                boolean changed = false;
                if (s.yOffset() != 0) {
                    RetainedNode t = ancestorScrollable(hit, false);
                    if (t != null) {
                        t.scrollY = clamp(t.scrollY - (float) s.yOffset() * WHEEL_STEP, 0f,
                                Math.max(0f, t.contentH - t.viewH));
                        refreshScrollLock(t);
                        changed = true;
                    }
                }
                if (s.xOffset() != 0) {
                    RetainedNode t = ancestorScrollable(hit, true);
                    if (t != null) {
                        t.scrollX = clamp(t.scrollX + (float) s.xOffset() * WHEEL_STEP, 0f,
                                Math.max(0f, t.contentW - t.viewW));
                        changed = true;
                    }
                }
                if (changed) {
                    requestLayout.run();
                }
            }
            case InputEvent.CharTyped c -> {
                // Typed text goes only to the focused, editable node — never conflated with the key command.
                if (focusedId != -1) {
                    IntConsumer handler = charHandlers.get(focusedId);
                    if (handler != null) {
                        int cp = c.codepoint();
                        handlerExecutor.execute(() -> handler.accept(cp));
                    }
                }
            }
            case InputEvent.KeyPressed k -> handleKeyDown(k.key());
            case InputEvent.KeyReleased k -> {
                Modifier mod = modifierOf(k.key());
                if (mod != null) {
                    heldMods.remove(mod);
                } else if (k.key() == repeatKey) {
                    repeatKey = null; // stop auto-repeat when the held key is lifted
                }
            }
            default -> {
                // Other events (focus-change edges from tactroller) are not routed here.
            }
        }
    }

    private void handleKeyDown(Key key) {
        Modifier mod = modifierOf(key);
        if (mod != null) {
            heldMods.add(mod);
            return; // a modifier keypress is not itself a chord or a command
        }
        KeyEvent e = new KeyEvent(key, Set.copyOf(heldMods));

        // 1. A claim, if one applies — preemption that was declared in advance (ClaimScope). The framework's own
        //    Tab traversal is registered this way rather than intercepted here, so a focused element that claims
        //    Tab simply outranks it. Claims do not arm auto-repeat: a held Tab inserts one soft tab, not a stream.
        Claim claimed = resolveClaim(new Shortcut(heldMods, key));
        if (claimed != null) {
            if (claimed.inline()) {
                claimed.command().run();
            } else {
                handlerExecutor.execute(claimed.command());
            }
            report(e, true);
            return;
        }

        // 2. Otherwise the focused node's key handler (caret motion, backspace, ...). Core takes nothing for
        //    itself here — if nobody claimed the chord, the focused element hears it.
        if (focusedId != -1) {
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
    private Claim resolveClaim(Shortcut chord) {
        Claim best = null;
        for (Claim c : claims) {
            if (!c.chord().equals(chord) || !applies(c)) {
                continue;
            }
            if (best == null || c.scope().ordinal() < best.scope().ordinal()) {
                best = c;
            }
        }
        return best;
    }

    private boolean applies(Claim c) {
        return switch (c.scope()) {
            case FOCUSED -> c.nodeId() == focusedId;
            case VISIBLE -> present(currentRoot, c.nodeId());
            case GLOBAL -> true;
        };
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
            bus.publish(keyRoutedTopic, new KeyRouted(e, focusedId, claimed));
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
        Consumer<KeyEvent> handler = keyHandlers.get(focusedId);
        if (handler != null) {
            // Use the live modifier set so pressing Ctrl mid-hold upgrades e.g. arrow-repeat to word-jump.
            KeyEvent e = new KeyEvent(repeatKey, Set.copyOf(heldMods));
            handlerExecutor.execute(() -> handler.accept(e));
        }
        repeatNextNanos = now + REPEAT_INTERVAL_NANOS; // frame-rate-bounded; no burst catch-up
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
        setFocus(order.get(next));
    }

    /** Focusable node ids in tree (DFS pre-order) — the Tab order. */
    private void collectFocusable(RetainedNode n, List<Long> out) {
        if (n == null) {
            return;
        }
        if (focusable.contains(n.id)) {
            out.add(n.id);
        }
        for (RetainedNode c : n.children) {
            collectFocusable(c, out);
        }
    }

    private void setFocus(long id) {
        if (id == focusedId) {
            return;
        }
        long old = focusedId;
        focusedId = id;
        if (focusTopic != null) {
            if (old != -1) {
                bus.publish(focusTopic, new FocusEvent(old, false));
            }
            if (id != -1) {
                bus.publish(focusTopic, new FocusEvent(id, true));
            }
        }
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

    /** If the press landed on a scrollbar thumb (of the hit node or an ancestor), begin a thumb drag. */
    private boolean grabScrollbar(RetainedNode hit, float x, float y) {
        for (RetainedNode n = hit; n != null; n = n.parent) {
            if (n.overflowY && inRect(n.vThumbRect(), x, y)) {
                scrollDrag = n;
                scrollDragVertical = true;
                scrollDragGrab = y - n.vThumbRect()[1];
                return true;
            }
            if (n.overflowX && inRect(n.hThumbRect(), x, y)) {
                scrollDrag = n;
                scrollDragVertical = false;
                scrollDragGrab = x - n.hThumbRect()[0];
                return true;
            }
        }
        return false;
    }

    /** Map the pointer to a scroll offset for the grabbed thumb, keeping the grab point under the cursor. */
    private void dragScrollbar(float x, float y) {
        RetainedNode n = scrollDrag;
        if (scrollDragVertical) {
            float travel = n.viewH - n.vThumbLen();
            float frac = travel > 0f ? clamp((y - scrollDragGrab - n.viewY) / travel, 0f, 1f) : 0f;
            n.scrollY = frac * Math.max(0f, n.contentH - n.viewH);
            refreshScrollLock(n);
        } else {
            float travel = n.viewW - n.hThumbLen();
            float frac = travel > 0f ? clamp((x - scrollDragGrab - n.viewX) / travel, 0f, 1f) : 0f;
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

    /** Within this many px of the locked edge, a scroll counts as "at the edge" and re-attaches the lock. */
    private static final float LOCK_EDGE_EPS = 4f;

    /**
     * Re-evaluate a scroll-locked container's attachment after the user scrolls it (§8.5): attached while the
     * offset sits at the locked edge, detached once the user scrolls away. A no-op for unlocked containers.
     */
    private static void refreshScrollLock(RetainedNode n) {
        ScrollLock lock = n.scrollLock();
        if (lock == ScrollLock.NONE) {
            return;
        }
        float maxY = Math.max(0f, n.contentH - n.viewH);
        n.scrollAttached = lock == ScrollLock.BOTTOM
                ? n.scrollY >= maxY - LOCK_EDGE_EPS
                : n.scrollY <= LOCK_EDGE_EPS;
    }

    /** Recompute each registered node's interaction state and fire the handler on any change. */
    private void refreshStates() {
        for (Map.Entry<Long, Consumer<InteractionState>> entry : stateHandlers.entrySet()) {
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
                InteractionState delivered = now;
                handlerExecutor.execute(() -> entry.getValue().accept(delivered));
            }
        }
        updateCursor();
    }

    /** Report the desired cursor shape for whatever is under the pointer — I-beam over editable text (§8.3). */
    private void updateCursor() {
        CursorShape desired = isTextTarget(hoverHit) ? CursorShape.TEXT : CursorShape.DEFAULT;
        if (desired != reportedCursor) {
            reportedCursor = desired;
            cursorSink.accept(desired);
        }
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

    /** The nearest ancestor-or-self of {@code hit} with a drag handler, or {@code null}. */
    private RetainedNode ancestorWithDragHandler(RetainedNode hit) {
        for (RetainedNode n = hit; n != null; n = n.parent) {
            if (dragHandlers.containsKey(n.id)) {
                return n;
            }
        }
        return null;
    }

    private void fireDrag(DragEvent.Phase phase, float x, float y) {
        RetainedNode n = dragCapture;
        Consumer<DragEvent> handler = dragHandlers.get(n.id);
        if (handler == null) {
            return;
        }
        DragEvent e = new DragEvent(phase, x, y, n.x, n.y, n.w, n.h);
        handlerExecutor.execute(() -> handler.accept(e));
    }

    private void fireClick(RetainedNode target, float x, float y) {
        for (RetainedNode n = target; n != null; n = n.parent) {
            Runnable handler = clickHandlers.get(n.id);
            if (handler != null) {
                handlerExecutor.execute(handler);
                break; // consumed; bubbling stops at the first handler
            }
        }
        bus.publish(clicks, new ClickEvent(target.id, x, y));
    }
}
