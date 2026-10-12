package dev.vexelray.gui.core;

import java.time.Duration;

/**
 * The clock a tree's animations run on, as far as a run that has no frame loop needs to know about it.
 *
 * <p>A window ticks its clock once per presented frame, from the host's {@code beforeFrame} hook, and the core
 * never sees it. A headless capture has no such loop, so an animation started while the tree was being built —
 * or by a handler that landed while it was being captured — would stay wherever it was when the picture was
 * taken, and where that is depends on thread timing. Registering the clock here (see
 * {@link Gui#animationClock(AnimationClock)}) is what lets {@code GuiApp.capture} carry it to rest instead.
 *
 * <p>Nothing on the interactive path reads this. It is the clock's own module that registers it — an
 * application wires nothing — and only a run that steps time itself asks.
 */
public interface AnimationClock {

    /**
     * Carry logical time forward by {@code step} and run whatever comes due, on the calling thread. Only for a run
     * that owns its time: a clock a frame loop is also ticking against the wall would be pushed ahead of it.
     */
    void advance(Duration step);

    /** Whether nothing is scheduled or animating, including work posted to the clock since it last ran. */
    boolean quiet();
}
