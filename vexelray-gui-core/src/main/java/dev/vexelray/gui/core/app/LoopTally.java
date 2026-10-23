package dev.vexelray.gui.core.app;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * The store behind {@link LoopCounters}: plain fields the loop thread writes with a release and any thread reads with
 * an acquire. {@link GuiApp} owns one and every {@link GuiWindow} it drives marks its draws into it.
 *
 * <p><b>Hot path.</b> Every writer here runs once per frame or once per park, so nothing allocates, boxes or logs:
 * increments and array stores, each published with a release. The handles are resolved once, in the static
 * initialiser, which is not reflection at run time and is what a native image wants.
 *
 * <p>An iteration's draws are tallied when it closes ({@link #close}), which is at the next iteration's top and also
 * just before the loop parks, so a reader never waits on a park to see the frame that preceded it.
 */
final class LoopTally implements LoopCounters {

    private static final VarHandle FRAME;
    private static final VarHandle DRAWN;
    private static final VarHandle PRESENTED;
    private static final VarHandle WAKES;
    private static final VarHandle COALESCED;
    private static final VarHandle FOCUSED;
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            FRAME = lookup.findVarHandle(LoopTally.class, "frame", long.class);
            DRAWN = lookup.findVarHandle(LoopTally.class, "drawn", long.class);
            PRESENTED = lookup.findVarHandle(LoopTally.class, "presented", long.class);
            WAKES = lookup.findVarHandle(LoopTally.class, "wakes", long.class);
            COALESCED = lookup.findVarHandle(LoopTally.class, "coalesced", long.class);
            FOCUSED = lookup.findVarHandle(LoopTally.class, "focused", boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final Park[] PARKS = Park.values();

    // Loop thread writes, any thread reads.
    private long frame;
    private long drawn;
    private long presented;
    private boolean focused;
    private final long[] starts = new long[STARTS];
    private final long[] parks = new long[PARKS.length];

    // Any thread writes.
    private long wakes;
    private long coalesced;
    private volatile Focus override = Focus.OS;

    // Loop thread only: the iteration not yet tallied.
    private long started;
    private boolean rendered;
    private boolean drew;

    /** The top of a frame: tally the one before it, and advance the frame number. Loop thread. */
    void begin(long now) {
        close();
        started = now;
        FRAME.setRelease(this, frame + 1L);
    }

    /** A window owed a draw and asked its presenter for one this iteration. Loop thread. */
    void rendering() {
        rendered = true;
    }

    /** A window's frame was emitted for its presenter this iteration. Loop thread. */
    void drawing() {
        drew = true;
    }

    /** Whether a window's frame has been emitted in the iteration in progress, not yet tallied. Loop thread. */
    boolean drewThisIteration() {
        return drew;
    }

    /** Tally the iteration in progress, if anything in it is not tallied yet. Loop thread; idempotent. */
    void close() {
        if (rendered) {
            DRAWN.setRelease(this, drawn + 1L);
            rendered = false;
        }
        if (drew) {
            long n = presented + 1L;
            LONGS.setRelease(starts, (int) (n % STARTS), started);
            PRESENTED.setRelease(this, n);
            drew = false;
        }
    }

    /** One park, and what bounded it, decided with {@code focused}. Loop thread. */
    void park(Park kind, boolean focused) {
        int i = kind.ordinal();
        LONGS.setRelease(parks, i, parks[i] + 1L);
        FOCUSED.setRelease(this, focused);
    }

    /** One {@link GuiApp#postWake}, which {@code pending} says found a wake already pending. Any thread. */
    void wake(boolean pending) {
        WAKES.getAndAdd(this, 1L);
        if (pending) {
            COALESCED.getAndAdd(this, 1L);
        }
    }

    /** The override, for the loop's focus check. */
    Focus override() {
        return override;
    }

    @Override
    public long frame() {
        return (long) FRAME.getAcquire(this);
    }

    @Override
    public long drawn() {
        return (long) DRAWN.getAcquire(this);
    }

    @Override
    public long presented() {
        return (long) PRESENTED.getAcquire(this);
    }

    @Override
    public long presentedStart(long n) {
        long latest = presented();
        if (n < 1L || n > latest || latest - n >= STARTS) {
            return -1L;
        }
        long start = (long) LONGS.getAcquire(starts, (int) (n % STARTS));
        // The slot may have been reused while it was read: a frame that rolled out meanwhile is not held either.
        return presented() - n >= STARTS ? -1L : start;
    }

    @Override
    public long parks(Park kind) {
        return (long) LONGS.getAcquire(parks, kind.ordinal());
    }

    @Override
    public long wakes() {
        return (long) WAKES.getAcquire(this);
    }

    @Override
    public long wakesCoalesced() {
        return (long) COALESCED.getAcquire(this);
    }

    @Override
    public boolean focused() {
        return (boolean) FOCUSED.getAcquire(this);
    }

    @Override
    public Focus focusOverride() {
        return override;
    }

    @Override
    public void focusOverride(Focus focus) {
        override = java.util.Objects.requireNonNull(focus, "focus");
    }
}
