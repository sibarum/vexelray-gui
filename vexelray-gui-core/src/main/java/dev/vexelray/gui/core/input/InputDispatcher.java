package dev.vexelray.gui.core.input;

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
    private final Map<Shortcut, Runnable> shortcuts = new ConcurrentHashMap<>();
    private final Set<Long> focusable = ConcurrentHashMap.newKeySet();
    private final Map<Long, InteractionState> reportedState = new ConcurrentHashMap<>();

    // Keyboard/focus state (GUI-thread only, mutated during dispatch).
    private final EnumSet<Modifier> heldMods = EnumSet.noneOf(Modifier.class);
    private long focusedId = -1;
    private Topic<FocusEvent> focusTopic;

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

    /** Register a global shortcut command. */
    public void registerShortcut(Shortcut shortcut, Runnable command) {
        shortcuts.put(shortcut, command);
    }

    /** The topic focus changes publish on (set by Gui). */
    public void focusTopic(Topic<FocusEvent> topic) {
        this.focusTopic = topic;
    }

    /** Programmatically move focus to {@code nodeId} (or -1 to clear). */
    public void focus(long nodeId) {
        setFocus(nodeId);
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
                setFocus(ancestorFocusableId(hit));
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
            case InputEvent.KeyPressed k -> handleKeyDown(k.key());
            case InputEvent.KeyReleased k -> {
                Modifier mod = modifierOf(k.key());
                if (mod != null) {
                    heldMods.remove(mod);
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
            return; // a modifier keypress is not itself a shortcut or a command
        }
        // 1. Shortcuts get first refusal (before text input / Tab).
        Runnable command = shortcuts.get(new Shortcut(heldMods, key));
        if (command != null) {
            handlerExecutor.execute(command);
            return;
        }
        // 2. Tab / Shift+Tab traverse focus.
        if (key == Key.TAB) {
            moveFocus(heldMods.contains(Modifier.SHIFT) ? -1 : 1);
            return;
        }
        // 3. Otherwise the focused node's key handler (caret motion, backspace, ...).
        if (focusedId != -1) {
            Consumer<KeyEvent> handler = keyHandlers.get(focusedId);
            if (handler != null) {
                KeyEvent e = new KeyEvent(key, Set.copyOf(heldMods));
                handlerExecutor.execute(() -> handler.accept(e));
            }
        }
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

    /** Nearest focusable ancestor-or-self of {@code hit}, or -1. */
    private long ancestorFocusableId(RetainedNode hit) {
        for (RetainedNode n = hit; n != null; n = n.parent) {
            if (focusable.contains(n.id)) {
                return n.id;
            }
        }
        return -1;
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
