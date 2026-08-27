package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.draw.Picture;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plays {@link Cue}s: one-shot feedback on a node, timed by a {@link Ramp}, cleaned up afterwards.
 *
 * <pre>{@code
 * Cues cues = new Cues((p, done) -> krono.ramp(Dur.millis(240), Ease.LINEAR, p, done));
 * Cue submitted = Cue.scanline(theme.color(Role.ACCENT));
 * Cue rejected  = Cue.ring(theme.color(Role.DANGER));
 *
 * field.onSubmit(line -> { run(line); cues.play(field.node(), submitted); });
 * ...
 * void showError(String why) { cues.play(field.node(), rejected); }
 * void setProgrammatically(String s) { field.text(s); cues.play(field.node(), Cue.wash(highlight)); }
 * }</pre>
 *
 * <h2>What this owns, which is only two things</h2>
 *
 * The cue says what to paint and the ramp says when; what is left is <b>the box comes back clean</b>, and the
 * two ways that can fail.
 *
 * <p><b>It clears up after itself.</b> A cue paints into the node's overlay slot, whose identity value is null,
 * so settling is {@code overlay(null)} — no resting value to remember and none to guess wrong. That is the whole
 * reason a cue may be played on a node this class did not create and knows nothing about.
 *
 * <p><b>A node can only be showing one cue.</b> Cueing a node that is already mid-cue supersedes it rather than
 * overlapping with it: two cues writing one slot every frame would be a race decided by whichever landed last,
 * and the superseded one's settle would then wipe the new one's paint a duration later — a flash that clears
 * itself halfway through, only when the user was quick. The superseded cue's samples and its settle both become
 * no-ops, so a ramp that finishes long after it lost has nothing to undo. This is deliberately not
 * <em>queueing</em>: a cue means "this just happened", and three of them backed up behind each other are all
 * saying it about a moment that has passed.
 *
 * <h2>Reduced motion</h2>
 *
 * {@link #none()} is a {@code Cues} that plays nothing — which is the honest collapse, because a cue has no end
 * state to snap to. It exists only in the middle, so "instantly" means "not at all", and an application that
 * routes every cue through one of these can turn the whole class of them off in one place.
 *
 * <p>Safe to call from any thread: a cue is normally started from an input handler, which is on a worker.
 */
public final class Cues {

    /** The house timing, or null for a {@link #none()} — the one thing that makes play a no-op. */
    private final Ramp ramp;

    /** The cue currently on each node, by node id. Only holds an entry while something is playing. */
    private final Map<Long, Play> playing = new ConcurrentHashMap<>();

    /**
     * Cues timed by {@code ramp} — one duration and one curve for every cue played through this.
     *
     * <p>Give it a linear ramp unless the cues you play are all arriving somewhere. Most are not: a sweep
     * travels through and a flash rises and falls, and easing either one spends most of the duration with
     * nothing visibly happening.
     */
    public Cues(Ramp ramp) {
        this.ramp = Objects.requireNonNull(ramp, "ramp");
    }

    private Cues() {
        this.ramp = null;
    }

    /** Cues that never play — the reduced-motion path, and what a test that cares about nothing else can use. */
    public static Cues none() {
        return new Cues();
    }

    /** Whether this plays anything at all. */
    public boolean enabled() {
        return ramp != null;
    }

    /** Play {@code cue} on {@code node} at the house timing, superseding whatever it was showing. */
    public Cues play(Node node, Cue cue) {
        return play(node, cue, ramp);
    }

    /**
     * Play {@code cue} on {@code node} at its own timing — for the one cue that is not the house duration, most
     * often an error, which wants longer than an acknowledgement because it is asking to be read rather than
     * noticed. A {@link #none()} plays nothing whatever ramp is handed to it.
     */
    public Cues play(Node node, Cue cue, Ramp with) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(cue, "cue");
        if (ramp == null || with == null) {
            return this;   // reduced motion: a cue exists only in the middle, so there is nothing to snap to
        }
        Play play = new Play(node, cue);
        playing.put(node.id(), play);
        play.sample(0d);          // the start, before a single frame has elapsed
        with.run(play::sample, play::settle);
        return this;
    }

    /**
     * Stop whatever {@code node} is showing and clear it now. The running ramp is not cancelled — this class
     * does not own it — but it has been superseded, so its remaining samples and its settle do nothing.
     */
    public Cues stop(Node node) {
        Objects.requireNonNull(node, "node");
        Play play = playing.remove(node.id());
        if (play != null) {
            node.overlay(null);
        }
        return this;
    }

    /** Whether {@code node} is mid-cue. */
    public boolean isPlaying(Node node) {
        return node != null && playing.containsKey(node.id());
    }

    /** How many nodes are mid-cue — zero at rest, which is the invariant a test holds this to. */
    public int active() {
        return playing.size();
    }

    /** One cue in flight on one node. Its identity in the table is what tells a sample whether it still owns it. */
    private final class Play {

        private final Node node;
        private final Cue cue;

        Play(Node node, Cue cue) {
            this.node = node;
            this.cue = cue;
        }

        /**
         * Paint this cue at {@code t}, unless it has been superseded.
         *
         * <p>The box is read every sample rather than captured at the start: a node can be resized while a cue is
         * on it (a window drag, a zoom), and a sweep that kept painting into the box the node used to have would
         * be visibly wrong for the rest of its duration. It is the published layout, so it is one frame stale —
         * the same staleness every reader of the read model accepts, and invisible at these durations.
         */
        void sample(double t) {
            if (playing.get(node.id()) != this) {
                return;
            }
            Cue.Box box = Cue.Box.of(node.layout());
            if (box.isEmpty()) {
                return;   // not laid out yet, or no area: nothing to decorate, and nothing painted to clear
            }
            Picture picture = cue.at(clamp(t), box);
            node.overlay(picture == null || picture.isEmpty() ? null : picture);
        }

        /**
         * The cue is over: take the overlay back off, if this cue is still the one on the node.
         *
         * <p>{@code remove(key, value)} is the whole of the supersession rule — a settle that finds someone else
         * in the table lost, and a loser must not clear paint that is not its own.
         */
        void settle() {
            if (playing.remove(node.id(), this)) {
                node.overlay(null);
            }
        }
    }

    private static double clamp(double t) {
        return t < 0d ? 0d : Math.min(t, 1d);
    }
}
