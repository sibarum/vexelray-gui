package dev.vexelray.gui.core.input;

import sibarum.tactroller.api.Modifier;

import java.util.Set;

/**
 * A drag on a node that registered via {@code Gui.onDrag}. A left press on the node (or a descendant) captures the
 * pointer, so {@link Phase#MOVE} events keep arriving while the button is held even if the pointer leaves the node,
 * until {@link Phase#END} on release. Carries the pointer position and the captured node's border-box rect at the
 * event, so a handler can compute a fraction without touching the retained tree (it runs off the GUI thread).
 *
 * <h2>Two ways to read it, and they are not interchangeable</h2>
 * A handler that wants a <b>place</b> — which value on a slider, which character in a line — reads {@link #x()} /
 * {@link #y()}, or the fractions below. A handler that wants a <b>displacement</b> — turn by this much, pan by
 * this much — must read {@link #dx()} / {@link #dy()} and never difference successive positions.
 *
 * <p>The two agree while nothing unusual is happening, which is what makes differencing so easy to write and so
 * quietly wrong. They stop agreeing the moment the pointer is <b>locked</b>: a locked pointer is warped back to
 * a fixed point every frame, so its absolute position stops moving while the motion is still entirely real. A
 * handler differencing positions then reads nothing at all. Relative motion is what a lock produces and
 * absolute position is what it destroys.
 *
 * <h2>Which press, and what was held</h2>
 * {@link #clicks()} counts the press that started the gesture: 1 for a single press, 2 when it followed the one
 * before it closely enough in time and place to be a double-click, 3 for a triple, and so on. Every event of the
 * gesture carries the count of the press that opened it, so a double-click held and dragged is still a double
 * click on its last {@link Phase#MOVE}. Counted by the dispatcher, from the capture times on the press edges,
 * rather than by each widget off its own clock: a widget timing presses itself would be timing their
 * <em>delivery</em>, which a slow frame stretches.
 *
 * <p>The modifiers travel with the event for the reason {@link ClickEvent} gives: Shift+press is a different
 * command from a press, and the handler deciding what it meant should be holding everything that decided it.
 *
 * @param phase START on capture, MOVE while dragging, END on release
 * @param x     pointer client-space x, px
 * @param y     pointer client-space y, px
 * @param dx    pointer motion since the previous event, px — zero on START and END
 * @param dy    pointer motion since the previous event, px — zero on START and END
 * @param nodeX captured node's border-box x, px
 * @param nodeY captured node's border-box y, px
 * @param nodeW captured node's border-box width, px
 * @param nodeH captured node's border-box height, px
 * @param clicks    the press that started the gesture: 1 single, 2 double, 3 triple — never less than 1
 * @param modifiers the modifier keys held at this event
 */
public record DragEvent(Phase phase, float x, float y, float dx, float dy,
                        float nodeX, float nodeY, float nodeW, float nodeH,
                        int clicks, Set<Modifier> modifiers) {

    public enum Phase { START, MOVE, END }

    public DragEvent {
        clicks = Math.max(1, clicks);
        modifiers = modifiers == null || modifiers.isEmpty() ? Set.of() : Set.copyOf(modifiers);
    }

    /** A single press with nothing held — the plain kind. */
    public DragEvent(Phase phase, float x, float y, float dx, float dy,
                     float nodeX, float nodeY, float nodeW, float nodeH) {
        this(phase, x, y, dx, dy, nodeX, nodeY, nodeW, nodeH, 1, Set.of());
    }

    public boolean has(Modifier m) {
        return modifiers.contains(m);
    }

    /** Pointer position along the node's width as a 0..1 fraction (clamped). */
    public float fractionX() {
        return nodeW <= 0f ? 0f : clamp01((x - nodeX) / nodeW);
    }

    /** Pointer position along the node's height as a 0..1 fraction (clamped). */
    public float fractionY() {
        return nodeH <= 0f ? 0f : clamp01((y - nodeY) / nodeH);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
