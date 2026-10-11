package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.RetainedNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The wheel, as motion rather than as a series of jumps.
 *
 * <p>A wheel notch is a fixed distance, and that is right: it is position control, each detent moves the content a
 * known amount and the next one back moves it back. What is wrong is delivering the distance in one frame. Three
 * ems of content vanishing from one frame to the next is the slideshow every file browser scrolls like, and it
 * makes the eye re-find its place after every notch. So a notch moves a <em>target</em>, and the view eases toward
 * it.
 *
 * <h2>Exponential, on purpose</h2>
 *
 * <p>Each frame closes a fixed fraction of the remaining distance for the time that has passed — a half-life,
 * not a duration. That has the three properties a wheel needs and a fixed-length ramp has none of. <b>Notches add
 * up</b>: a second notch moves the target, and the view simply has further to go, without restarting anything or
 * pausing at the first. <b>It is honest about time</b>: the fraction is computed from the frame's real interval,
 * so the glide is the same at 60 Hz and 144 Hz and a dropped frame catches up rather than slowing it down. <b>There
 * is no end to jump at</b>: the view is always heading for the target and arrives at it when the remainder is a
 * fraction of a pixel, which is the only place a stop cannot be seen.
 *
 * <h2>Whose view it is</h2>
 *
 * <p>A glide owns the offset only while nothing else moves it. Dragging the thumb, a reveal, a scroll lock pinning
 * the tail, an application's {@code scrollTo} — any of them writes the offset directly, and a glide that kept
 * pulling toward its own target would fight it. So every step first checks the offset is still the one it wrote
 * last, and gives up silently if it is not.
 *
 * <p>GUI thread only: stepped by the dispatcher at the top of each frame, before the layout that places the content
 * at the new offset.
 */
final class ScrollGlide {

    /** Below this distance from the target the view is put on it: a quarter pixel is not a move anyone can see. */
    private static final float ARRIVED_PX = 0.25f;

    /** The most time one step will account for, so a frame after a long stall catches up rather than teleporting. */
    private static final long MAX_STEP_NANOS = 50_000_000L;

    /** How much time the first step of a new glide accounts for: one frame at 120 Hz. */
    private static final long FIRST_STEP_NANOS = 8_333_333L;

    private static final class Glide {
        final RetainedNode node;
        float targetX;
        float targetY;
        /** What this glide last wrote, to tell its own writes from anybody else's. */
        float wroteX;
        float wroteY;

        Glide(RetainedNode node) {
            this.node = node;
            this.targetX = node.scrollX;
            this.targetY = node.scrollY;
            this.wroteX = node.scrollX;
            this.wroteY = node.scrollY;
        }
    }

    private final List<Glide> glides = new ArrayList<>();
    private long halfLifeNanos;
    private long lastStep;

    /** How long the view takes to close half the distance to its target; zero moves it at once. */
    void halfLife(long nanos) {
        this.halfLifeNanos = Math.max(0L, nanos);
    }

    long halfLife() {
        return halfLifeNanos;
    }

    /**
     * Move {@code n}'s target by {@code (dx, dy)} — from the target it is already heading for, if it is gliding, so
     * notches add up — clamped to what the content allows.
     *
     * @return whether anything has to move
     */
    boolean by(RetainedNode n, float dx, float dy, long now) {
        if (halfLifeNanos == 0L) {
            float x = clamp(n.scrollX + dx, 0f, maxX(n));
            float y = clamp(n.scrollY + dy, 0f, maxY(n));
            boolean moved = x != n.scrollX || y != n.scrollY;
            n.scrollX = x;
            n.scrollY = y;
            return moved;
        }
        Glide g = find(n);
        if (g == null || !owns(g)) {
            if (g != null) {
                glides.remove(g);
            }
            g = new Glide(n);
            if (glides.isEmpty()) {
                // As though the last step were one frame ago, so the notch moves the view in the frame it arrives
                // in rather than the one after: a wheel that answers a frame late feels like a wheel that slips.
                lastStep = now - FIRST_STEP_NANOS;
            }
            glides.add(g);
        }
        g.targetX = clamp(g.targetX + dx, 0f, maxX(n));
        g.targetY = clamp(g.targetY + dy, 0f, maxY(n));
        return g.targetX != n.scrollX || g.targetY != n.scrollY;
    }

    /**
     * Advance every glide by the time since the last step.
     *
     * @param moved told about each container whose offset changed, so a scroll lock can re-decide whether it is at
     *              its edge
     * @return whether any offset was written — including the last step, which puts a view on its target and
     *         finishes — so the caller lays out this frame, and asks again next frame
     */
    boolean step(long now, Consumer<RetainedNode> moved) {
        if (glides.isEmpty()) {
            return false;
        }
        boolean wrote = false;
        long dt = Math.min(MAX_STEP_NANOS, Math.max(0L, now - lastStep));
        lastStep = now;
        // The fraction of the remaining distance still to go after dt: one half per half-life.
        float keep = (float) Math.pow(0.5, (double) dt / halfLifeNanos);
        for (int i = glides.size() - 1; i >= 0; i--) {
            Glide g = glides.get(i);
            RetainedNode n = g.node;
            if (!owns(g)) {
                glides.remove(i);   // somebody else moved it: it is theirs now (a node that leaves is forgotten)
                continue;
            }
            // Clamped every step against the content as it now measures, so a list that shrinks under a glide
            // ends at its new bottom rather than heading for an offset that no longer exists.
            g.targetX = clamp(g.targetX, 0f, maxX(n));
            g.targetY = clamp(g.targetY, 0f, maxY(n));
            float x = g.targetX + (n.scrollX - g.targetX) * keep;
            float y = g.targetY + (n.scrollY - g.targetY) * keep;
            boolean arrived = Math.abs(g.targetX - x) < ARRIVED_PX && Math.abs(g.targetY - y) < ARRIVED_PX;
            if (arrived) {
                x = g.targetX;
                y = g.targetY;
                glides.remove(i);
            }
            n.scrollX = x;
            n.scrollY = y;
            g.wroteX = x;
            g.wroteY = y;
            moved.accept(n);
            wrote = true;
        }
        return wrote;
    }

    /** Where {@code n} is heading vertically: its glide's target, or where it is if it is not gliding. */
    float targetY(RetainedNode n) {
        Glide g = find(n);
        return g != null && owns(g) ? g.targetY : n.scrollY;
    }

    /** Stop gliding {@code nodeId}, wherever it has got to. */
    void forget(long nodeId) {
        glides.removeIf(g -> g.node.id == nodeId);
    }

    private Glide find(RetainedNode n) {
        for (Glide g : glides) {
            if (g.node == n) {
                return g;
            }
        }
        return null;
    }

    private static boolean owns(Glide g) {
        return g.node.scrollX == g.wroteX && g.node.scrollY == g.wroteY;
    }

    private static float maxX(RetainedNode n) {
        return Math.max(0f, n.contentW - n.viewW);
    }

    private static float maxY(RetainedNode n) {
        return Math.max(0f, n.contentH - n.viewH);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
