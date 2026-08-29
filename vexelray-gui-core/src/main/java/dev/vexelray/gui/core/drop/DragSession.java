package dev.vexelray.gui.core.drop;

import dev.vexelray.gui.core.edit.History;

import java.util.List;
import java.util.Objects;

/**
 * One drag, from wherever it came from to whatever it lands on.
 *
 * <h2>Opened by a source, and there is more than one</h2>
 *
 * <p>A session is <b>constructed, not pressed into being</b>. A drag begun by the pointer in this window is one
 * source; a drag that walked in from another window of this process is a second; an OS file drag that was
 * already in flight before this process heard of it is a third, and it arrives with no press, no button and no
 * gesture — it is simply already happening. A session that could only be created by a {@code DragStarted} would
 * rule the last two out by construction, and they are the ones with the least room to be re-designed later.
 *
 * <p>What every source shares is exactly what is here: a payload that is complete up front, a pointer position
 * that changes, and an ending that is either a drop or a cancel. Everything else — thresholds, capture, which
 * button, whether Escape or the OS ends it — belongs to the source and is not represented here.
 *
 * <h2>Resolution is total, and bubbles</h2>
 *
 * <p>{@link #moveTo} asks each target under the pointer in turn, innermost first, and takes the first answer
 * that accepts. A node that declines therefore does not create a hole: its parent is asked next, and the
 * outermost decline is the honest {@link Drop#NONE}. This is what makes "every pixel resolves to exactly one
 * outcome" survive nesting, which a single-target lookup would not — a row that declines a foreign payload would
 * otherwise punch a row-shaped void in a tree that was perfectly willing to take it at the end.
 *
 * <h2>A drop the user never saw is not a drop</h2>
 *
 * <p>{@link #commit} refuses unless the session was rendered at least once. That is the protection against the
 * gesture that was meant to be a click: a fast press-twitch-release can satisfy any distance threshold, and
 * without this it would land a real, undoable mutation from a drag that never appeared on screen for even one
 * frame. Distance and time thresholds are a source's business and they help, but they are tuning; this is the
 * invariant, and it is stated where the commit happens rather than where the gesture is recognised, because it
 * has to hold for every source including the ones that have no thresholds at all.
 *
 * <p>The counterpart matters as much: a cancelled drag records <b>nothing</b>. Not a change and its immediate
 * undo — {@link History#record} discards the redo stack, so a cancelled drag would silently destroy work the
 * user could otherwise have redone, which is a worse outcome than the drag they just abandoned.
 *
 * <h2>Threading</h2>
 *
 * <p>Owned by the GUI thread: opened, moved, rendered and ended inside the frame. It is not synchronized,
 * because a drag observed by two threads has no coherent "where is the pointer now" to report anyway.
 */
public final class DragSession {

    /** The drop targets under a point, innermost first. Supplied by whoever can see the tree. */
    @FunctionalInterface
    public interface Lookup {

        /** Targets covering {@code (x, y)}, innermost first; empty where nothing is registered. */
        List<DropTarget> at(float x, float y);
    }

    private final Payload payload;
    private float x;
    private float y;
    private Drop drop = Drop.NONE;
    private int framesSeen;
    private boolean ended;

    private DragSession(Payload payload, float x, float y) {
        this.payload = Objects.requireNonNull(payload, "payload");
        this.x = x;
        this.y = y;
    }

    /**
     * Begin a drag of {@code payload}, already at {@code (x, y)}.
     *
     * <p>No resolution happens yet: a session opens knowing what is being dragged and where the pointer is, and
     * learns what would happen on the first {@link #moveTo}. Sources differ on whether they have a tree to
     * resolve against at the moment the drag begins — an OS drag does not, since it arrives mid-frame — so
     * opening does not require one.
     */
    public static DragSession open(Payload payload, float x, float y) {
        return new DragSession(payload, x, y);
    }

    /** What is being dragged. Complete from the moment the session opened. */
    public Payload payload() {
        return payload;
    }

    /** Where the pointer is, in client-space px. */
    public float x() {
        return x;
    }

    /** Where the pointer is, in client-space px. */
    public float y() {
        return y;
    }

    /** What would happen if the pointer were released now — the effect, the indicator to draw, and the change
     * that would be made. {@link Drop#NONE} until the first {@link #moveTo}, and wherever nothing accepts. */
    public Drop drop() {
        return drop;
    }

    /** How many frames this drag has been rendered for. Zero means the user has not seen it. */
    public int framesSeen() {
        return framesSeen;
    }

    /** Whether this session has already been committed or cancelled. */
    public boolean ended() {
        return ended;
    }

    /**
     * Move the pointer and re-resolve what would happen there.
     *
     * <p>Re-resolution is not conditional on the pointer having moved, and the caller should also call this when
     * the <em>tree</em> moved under a stationary pointer — a wheel scroll during a drag being the ordinary case.
     * A drop indicator that only updates on pointer motion tells the truth until the moment the user scrolls,
     * and then lies until they jiggle the mouse.
     */
    public void moveTo(float x, float y, Lookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        if (ended) {
            return;
        }
        this.x = x;
        this.y = y;
        Drop resolved = Drop.NONE;
        for (DropTarget target : lookup.at(x, y)) {
            Drop candidate = target.resolve(payload, x, y);
            if (candidate != null && candidate.accepts()) {
                resolved = candidate;
                break;   // innermost acceptance wins; an outer target does not get to override it
            }
        }
        this.drop = resolved;
    }

    /** Record that this drag has been rendered. Called once per frame the session is live, by whoever draws. */
    public void seen() {
        if (!ended) {
            framesSeen++;
        }
    }

    /**
     * End the drag by releasing, performing the resolved change through {@code history} if there is one to
     * perform and the user has actually seen this drag.
     *
     * <p>Ends the session either way: a release that lands on nothing is still the end of the gesture.
     *
     * @return the drop that was performed, or {@link Drop#NONE} if nothing was — which the caller should treat
     *         as a cancel for the purposes of animation, because from the user's side nothing happened
     */
    public Drop commit(History history) {
        Objects.requireNonNull(history, "history");
        if (ended) {
            return Drop.NONE;
        }
        ended = true;
        if (!drop.accepts() || framesSeen == 0) {
            return Drop.NONE;
        }
        history.perform(drop.change());
        return drop;
    }

    /**
     * End the drag without performing anything — Escape, an OS {@code DragLeave}, or a source tearing down.
     *
     * <p>Deliberately takes no {@link History}: there is nothing to tell it. The animation that returns the
     * dragged thing to where it came from is the caller's, and it is the only trace a cancel leaves.
     */
    public void cancel() {
        ended = true;
        drop = Drop.NONE;
    }

    @Override
    public String toString() {
        return "DragSession[" + payload + " at " + x + "," + y + " -> " + drop.effect()
                + (ended ? ", ended" : ", seen=" + framesSeen) + "]";
    }
}
