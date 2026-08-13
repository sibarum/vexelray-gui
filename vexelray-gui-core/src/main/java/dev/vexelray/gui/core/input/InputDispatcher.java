package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.RetainedNode;
import sibarum.atchung.Atchung;
import sibarum.atchung.Backpressure;
import sibarum.atchung.Pump;
import sibarum.atchung.Subscription;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Framework-owned input dispatch (architecture.md §8). Subscribes a {@link Pump} to the {@link InputTopics#INPUT}
 * topic and, once per frame, drains it on the GUI thread and routes each edge against the current laid-out tree.
 *
 * <p>v1 scope: left-button click resolution. A press hit-tests to the topmost node under the pointer and remembers
 * it; a release on the same node fires a click — routed to the nearest ancestor (self included) with a registered
 * handler (leaf→root bubbling), and re-published as a {@link ClickEvent} on the click topic for workers. Handlers
 * run on the supplied executor (off the GUI thread by default), so a slow handler never stalls rendering.
 *
 * <p>Because {@link #dispatch} runs during the frame, before layout, hit-testing uses the previous frame's rects —
 * the standard one-frame-geometry latency, invisible in practice. Focus routing, capture, wheel, keyboard, and
 * shortcuts build on this same drain in later steps.
 */
public final class InputDispatcher {

    /** Input edges are drained every frame; a generous mailbox with drop-oldest never sheds under real rates. */
    private static final int MAILBOX = 4096;

    private final Atchung bus;
    private final Topic<ClickEvent> clicks;
    private final Executor handlerExecutor;
    private final Pump pump;
    private final Subscription sub;
    private final Map<Long, Runnable> clickHandlers = new ConcurrentHashMap<>();

    private RetainedNode currentRoot;
    private long pressTargetId = -1;

    public InputDispatcher(Atchung bus, Topic<ClickEvent> clicks, Executor handlerExecutor) {
        this.bus = bus;
        this.clicks = clicks;
        this.handlerExecutor = handlerExecutor;
        this.pump = bus.pump();
        this.sub = pump.subscribe(InputTopics.INPUT, this::handle, MAILBOX, Backpressure.DROP_OLDEST);
    }

    /** Register a click handler for {@code nodeId}; replaces any prior handler for that node. */
    public void onClick(long nodeId, Runnable handler) {
        clickHandlers.put(nodeId, handler);
    }

    /** Drop {@code nodeId}'s click handler (call when the node is removed). */
    public void clearHandlers(long nodeId) {
        clickHandlers.remove(nodeId);
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
            case InputEvent.ButtonPressed b when b.button() == MouseButton.LEFT -> {
                RetainedNode hit = HitTest.at(currentRoot, b.x(), b.y());
                pressTargetId = hit == null ? -1 : hit.id;
            }
            case InputEvent.ButtonReleased b when b.button() == MouseButton.LEFT -> {
                RetainedNode hit = HitTest.at(currentRoot, b.x(), b.y());
                if (hit != null && hit.id == pressTargetId) {
                    fireClick(hit, b.x(), b.y());
                }
                pressTargetId = -1;
            }
            default -> {
                // Motion, scroll, keys, focus: consumed here in later steps.
            }
        }
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
