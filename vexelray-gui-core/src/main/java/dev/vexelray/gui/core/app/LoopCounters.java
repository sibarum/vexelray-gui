package dev.vexelray.gui.core.app;

/**
 * What the frame loop has done, read from any thread: the counters behind the automation socket's {@code meter}
 * verb and the frame stamp on the flight recorder's rows (docs/plans/meter.md §3, docs/plans/flight-recorder.md §3).
 *
 * <p><b>Always on, and never reset.</b> The loop writes plain fields and a reader sees each one through an acquire,
 * so keeping them costs an increment or a store where the loop already decides the thing counted. A reader that
 * wants a window over the loop snapshots every counter and later takes differences, which lets two readers measure
 * at once without either disturbing the other or the loop knowing that either exists. A set of readings is taken at
 * slightly different instants, so it is consistent to within one frame and no better.
 *
 * <p>The only write is {@link #focusOverride(Focus)}, a policy input the loop reads at each park.
 */
public interface LoopCounters {

    /** How many presented frame starts {@link #presentedStart} holds: the most recent this many. */
    int STARTS = 1_024;

    /**
     * The frame in progress, which is also the number of loop iterations begun: 1 during the first. It advances at the
     * top of each frame, before anything in that frame runs, so a row written from any thread can be stamped with the
     * frame it was written during. A frame the platform pulls while a window is being moved or resized is a frame of
     * its own here. An iteration updates the trees and may or may not draw, so this is not a count of frames drawn.
     */
    long frame();

    /** Iterations in which some window owed a draw and asked its presenter for one. */
    long drawn();

    /**
     * Iterations in which some window's frame was emitted and handed to its presenter: {@link #drawn} less the ones
     * the presenter skipped (minimized, a deferred resize, a surface out of date twice).
     */
    long presented();

    /**
     * When presented frame {@code n} (1 is the first, {@link #presented} the latest) began, in
     * {@link System#nanoTime()}, or {@code -1} if it has not happened or is older than the last {@link #STARTS}.
     * Frame start to frame start is the gap the loop's ceiling holds.
     */
    long presentedStart(long n);

    /** How many parks {@code kind} bounded. */
    long parks(Park kind);

    /** {@link GuiApp#postWake} calls, from every thread. */
    long wakes();

    /** Wakes that found one already pending, so earned no frame of their own. */
    long wakesCoalesced();

    /**
     * The focus the last park decided on: whether the idle floor applied to it. Under an override this is the
     * override's answer, not the windows'. False before the first park.
     */
    boolean focused();

    /** The override in force; {@link Focus#OS} when there is none. */
    Focus focusOverride();

    /**
     * Override what the loop believes about focus at each park, which decides whether the idle floor applies and
     * nothing else. The windows themselves are unchanged: this is a simulation of focus, not a change of it. Any
     * thread; the next park reads it.
     */
    void focusOverride(Focus focus);

    /** What bounded a park, which together with the counts is why the loop ran as often as it did. */
    enum Park {
        /** No bound at all: nothing was owed, nobody was looking, and the application had no deadline. */
        FOREVER,
        /** The application's own pacing: the next moment it said it needs a frame. */
        DEADLINE,
        /** The idle refresh, which holds while a window has focus ({@link GuiApp#idleRefresh}). */
        FLOOR,
        /** The ceiling's gap, held from the start of the frame ({@link GuiApp#maxFrameRate}). */
        CEILING,
        /** No park, because a frame was owed or the frame had already used its gap. */
        ZERO
    }

    /** What the loop takes for focus: the windows' answer, or one held in its place. */
    enum Focus {
        /** Ask the windows; no override. */
        OS,
        /** As if a window of this loop had focus. */
        ON,
        /** As if none had. */
        OFF
    }
}
