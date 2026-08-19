package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.text.TextLayout;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Tooltips: a short line of help that appears near a control after the pointer has rested on it, and vanishes the
 * moment the pointer leaves or presses.
 *
 * <p><b>A tooltip is never the thing under the pointer.</b> The bubble is a floating, {@code hitInert} last child
 * of the root: it draws over the page but hit-testing passes straight through it, so its appearance changes
 * <em>nothing</em> about what the pointer is on — no hover flicker, no stolen click, no shifted target. That is
 * the framework's rule about hover (chrome must never alter the pointer target), honored by construction rather
 * than by careful placement. It is also anchored to the control's box, not the pointer, so once shown it never
 * moves; and it floats, so showing it reflows nothing.
 *
 * <p><b>One bubble per instance, many targets.</b> {@link #attach} registers an interaction-state observer on the
 * target — observers accumulate, so a button's own hover restyle and its tooltip coexist without knowing about
 * each other. HOVER arms a delay timer; NORMAL or PRESSED disarms it and hides the bubble. The delay rides a
 * shared daemon {@link Timer}; at {@code delayMillis(0)} the bubble shows synchronously in the state handler,
 * which is what makes the widget deterministic under a headless harness.
 */
public final class Tooltip implements AutoCloseable {

    private static final Color TIP_BG = Color.rgb(0x232a3d);
    private static final Color TIP_BORDER = Color.rgb(0x3a445c);
    private static final Color TIP_INK = Color.rgb(0xd7deea);

    /** How far below the control the bubble sits, in px at density 1. */
    private static final float GAP_PX = 6f;

    private final Gui gui;
    private final Node bubble;

    private volatile long delayMillis = 500L;
    private volatile boolean attachedToRoot;
    private volatile boolean shown;

    /** The timer is created on first use and shared by every arm/disarm; daemon, so it never holds the JVM. */
    private Timer timer;
    private TimerTask pending;

    /** Build a tooltip bubble on {@code gui}; wire it to controls with {@link #attach}. */
    public Tooltip(Gui gui) {
        this.gui = gui;
        this.bubble = gui.text("")
                .visible(false)
                .hitInert(true)
                .background(TIP_BG)
                .corner(Length.rem(0.4f))
                .border(Length.rem(0.08f), TIP_BORDER)
                .elevation(Length.rem(0.75f))
                .textSize(Length.rem(0.9375f))
                .textColor(TIP_INK)
                .padding(Length.dp(4), Length.dp(10))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
    }

    /** How long the pointer must rest on a target before the bubble shows. Zero shows immediately (tests). */
    public Tooltip delayMillis(long millis) {
        this.delayMillis = Math.max(0L, millis);
        return this;
    }

    /**
     * Show {@code text} for {@code target}: after the pointer rests on it for the delay, the bubble appears just
     * below the target's box (clamped on-screen by the float layout), and hides on leave or press. Registration
     * is an added state observer, so the target's own hover styling keeps working.
     */
    public Tooltip attach(Node target, String text) {
        // Only a *fresh* entry arms the bubble: HOVER reached from NORMAL. HOVER reached from PRESSED is the
        // release half of a click — the user just acted on the control, and a tooltip popping up over the result
        // would be help arriving after the decision. It stays away until the pointer leaves and comes back.
        var last = new java.util.concurrent.atomic.AtomicReference<>(InteractionState.NORMAL);
        gui.onState(target, state -> {
            InteractionState previous = last.getAndSet(state);
            if (state == InteractionState.HOVER && previous == InteractionState.NORMAL) {
                arm(() -> showFor(target, text));
            } else if (state != InteractionState.HOVER) {
                disarm();
                hide();
            }
        });
        return this;
    }

    /** The bubble node — for tests and styling beyond the defaults. */
    public Node node() {
        return bubble;
    }

    /** Whether the bubble is currently visible. */
    public boolean shown() {
        return shown;
    }

    /** Release the bubble and stop the timer. Attached observers lapse with their target nodes. */
    @Override
    public void close() {
        synchronized (this) {
            if (pending != null) {
                pending.cancel();
                pending = null;
            }
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
        }
        gui.releaseNode(bubble);
        if (attachedToRoot) {
            bubble.remove();
        }
    }

    // --- internals ---

    /** Schedule {@code show} after the delay, replacing any armed timer. Zero delay runs it here and now. */
    private synchronized void arm(Runnable show) {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
        if (delayMillis == 0L) {
            show.run();
            return;
        }
        if (timer == null) {
            timer = new Timer("vexelray-tooltip", true);
        }
        pending = new TimerTask() {
            @Override
            public void run() {
                show.run();
            }
        };
        timer.schedule(pending, delayMillis);
    }

    private synchronized void disarm() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }

    /** Place the bubble just below {@code target}'s published box and show it. Anchored to the box, not the
     *  pointer, so it appears once and stays put. */
    private void showFor(Node target, String text) {
        var rect = target.layout().rect();
        if (rect.w() <= 0f || rect.h() <= 0f) {
            return;   // not laid out (or hidden since): nothing to anchor to
        }
        if (!attachedToRoot) {
            attachedToRoot = true;
            gui.root().append(bubble);
        }
        float dpi = Math.max(0.0001f, gui.dpi().value());
        bubble.text(text);
        bubble.floatAt(Length.dp(rect.x() / dpi), Length.dp((rect.y() + rect.h() + GAP_PX) / dpi));
        bubble.visible(true);
        shown = true;
    }

    private void hide() {
        if (shown) {
            shown = false;
            bubble.visible(false);
        }
    }
}
