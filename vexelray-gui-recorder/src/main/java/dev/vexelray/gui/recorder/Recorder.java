package dev.vexelray.gui.recorder;

import sibarum.probe.Lane;
import sibarum.probe.LiveSink;
import sibarum.probe.Probe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.LongSupplier;

/**
 * The flight recorder: a fixed ring of the probe's most recent rows, kept inside the application, so a running
 * program can be asked what it just did (docs/plans/flight-recorder.md §3).
 *
 * <h2>Writing: slot stores and nothing else</h2>
 *
 * Rows arrive through {@link LiveSink#row}, on whichever thread emitted them and inside the probe's output lock,
 * so every other thread that emits is waiting while this runs. A row is therefore one claim, eight stores into
 * column arrays allocated at construction, and one publish: no allocation, no lock of its own, no logging. The
 * strings are the probe's own, already built, so keeping a reference costs nothing. Each row is stamped with the
 * loop's frame in progress ({@code LoopCounters.frame()}), read inside the lock, so stamps never go backwards in
 * {@code seq} order and a frame's rows sit together in the ring.
 *
 * <p>When the ring is full the oldest row is overwritten. That is not loss: the span the ring covers simply
 * shrinks, and every answer says what span it read. Loss is a gap in {@code seq} — a row the probe numbered and
 * this sink never got — and is counted ({@link #lost()}).
 *
 * <h2>Reading: copy, then check nothing was overwritten meanwhile</h2>
 *
 * A reader never blocks the writer. It copies a run of slots into a {@link Rows} buffer of its own and then asks
 * whether any of them could have been overwritten while it copied, discarding those that could. This is a
 * sequence lock over the whole ring rather than per slot, with two counters:
 *
 * <ul>
 *   <li>{@code claimed} — the writer bumps it <em>before</em> touching a slot (a store-store fence orders the
 *       claim before the slot's stores);</li>
 *   <li>{@code published} — bumped with release semantics <em>after</em> the slot is complete.</li>
 * </ul>
 *
 * The reader acquires {@code published}, copies positions below it, issues a load-load fence, and reads
 * {@code claimed}. A slot at position {@code p} is reused only by the writer of {@code p + capacity}, which
 * claimed first; so if {@code claimed <= p + capacity} after the copy, the copy of {@code p} is the row that was
 * published there. Anything older is dropped from the front of the buffer, which is the honest outcome: the
 * span shrinks by what the writer got to first. A reader that falls a whole ring behind loses those rows, sees
 * the {@code seq} gap like any other loss, and says so.
 *
 * <p>Reads are positional ({@link #read(long, Rows)} from a ring position, {@link Rows#next()} to carry on), which
 * is what a streaming reader needs: it follows the ring from where it last stopped and is told when the ring
 * lapped it. The query verbs read the same way, chunk by chunk, so one answer never copies the whole ring at once.
 */
public final class Recorder implements LiveSink, AutoCloseable {

    /** The ring's size when nothing says otherwise: 2^18 rows (flight-recorder.md Q2). */
    public static final int DEFAULT_ROWS = 1 << 18;

    /** The system property that sizes the ring, in rows; rounded up to a power of two. */
    public static final String ROWS_PROPERTY = "recorder";

    /** The probe stamp for "no frame counter": every row is frame 0, and the frame verbs say they cannot answer. */
    public static final LongSupplier NO_FRAMES = () -> 0L;

    private static final VarHandle CLAIMED;
    private static final VarHandle PUBLISHED;
    private static final VarHandle LOST;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            CLAIMED = l.findVarHandle(Recorder.class, "claimed", long.class);
            PUBLISHED = l.findVarHandle(Recorder.class, "published", long.class);
            LOST = l.findVarHandle(Recorder.class, "lost", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int capacity;
    private final int mask;
    private final LongSupplier frames;
    private final boolean stamped;

    private final long[] seqs;
    private final long[] monos;
    private final long[] walls;
    private final long[] stamps;
    private final String[] threads;
    private final Lane[] lanes;
    private final String[] kinds;
    private final String[] details;

    /** Positions claimed, then published; see the class comment. Written only by the writer. */
    @SuppressWarnings("unused")
    private long claimed;
    @SuppressWarnings("unused")
    private long published;
    /** Rows missing between ones received, since the first. Written only by the writer. */
    @SuppressWarnings("unused")
    private long lost;
    /** The last {@code seq} received; 0 before the first. Writer-only, guarded by the probe's lock. */
    private long lastSeq;

    private volatile boolean attached;

    /**
     * A ring of {@code rows} (rounded up to a power of two) whose rows are stamped from {@code frames}.
     * Not yet fed: {@link #attach()} it, or hand it rows directly as a test does.
     *
     * @param frames the frame in progress, safe to read from any thread and cheap ({@code LoopCounters::frame});
     *               {@link #NO_FRAMES} when the host has none
     */
    public Recorder(int rows, LongSupplier frames) {
        if (rows < 2) {
            throw new IllegalArgumentException("a ring needs at least 2 rows, not " + rows);
        }
        int cap = Integer.highestOneBit(rows);
        if (cap < rows) {
            cap <<= 1;
        }
        this.capacity = cap > 0 ? cap : 1 << 30;
        this.mask = capacity - 1;
        this.frames = frames == null ? NO_FRAMES : frames;
        this.stamped = frames != null && frames != NO_FRAMES;
        this.seqs = new long[capacity];
        this.monos = new long[capacity];
        this.walls = new long[capacity];
        this.stamps = new long[capacity];
        this.threads = new String[capacity];
        this.lanes = new Lane[capacity];
        this.kinds = new String[capacity];
        this.details = new String[capacity];
    }

    /**
     * A recorder fed by the probe, or {@code null} when the probe is off — there are no rows to record, and the
     * verbs that need one say how to start it.
     */
    public static Recorder start(int rows, LongSupplier frames) {
        if (!Probe.ON) {
            return null;
        }
        Recorder r = new Recorder(rows, frames);
        return r.attach() ? r : null;
    }

    /** The ring size {@code -Drecorder=<rows>} asks for, or {@link #DEFAULT_ROWS}. */
    public static int rowsFromProperty() {
        String v = System.getProperty(ROWS_PROPERTY, "").trim();
        if (v.isEmpty()) {
            return DEFAULT_ROWS;
        }
        try {
            return Math.max(2, Integer.parseInt(v));
        } catch (NumberFormatException e) {
            return DEFAULT_ROWS;
        }
    }

    /** Start taking the probe's rows. False when the probe is off. */
    public boolean attach() {
        attached = Probe.attach(this);
        return attached;
    }

    @Override
    public void close() {
        Probe.detach(this);
        attached = false;
    }

    // --- writing: the probe's thread, inside its lock ------------------------

    @Override
    public void row(long seq, long tMonoNs, long tWallNs, String thread, Lane lane, String kind, String detail) {
        // Writers are serialised by the probe's lock, so the writer's own reads of these fields are plain.
        if (lastSeq != 0 && seq != lastSeq + 1) {
            LOST.setRelease(this, (long) LOST.get(this) + (seq - lastSeq - 1));
        }
        lastSeq = seq;
        long p = (long) PUBLISHED.get(this);
        CLAIMED.setOpaque(this, p + 1);
        VarHandle.storeStoreFence();
        int i = (int) (p & mask);
        seqs[i] = seq;
        monos[i] = tMonoNs;
        walls[i] = tWallNs;
        stamps[i] = frames.getAsLong();
        threads[i] = thread;
        lanes[i] = lane;
        kinds[i] = kind;
        details[i] = detail;
        PUBLISHED.setRelease(this, p + 1);
    }

    // --- reading: any thread, never blocking the writer ----------------------

    /**
     * Copy rows from ring position {@code from} onward into {@code into}, as many as it holds, and return it.
     *
     * <p>If {@code from} has already been overwritten, copying starts at the oldest row still held, and
     * {@link Rows#skipped()} says how many positions were passed over. An empty result with {@link Rows#next()}
     * equal to {@code from} means nothing newer has been published yet.
     */
    public Rows read(long from, Rows into) {
        into.reset();
        long pub = (long) PUBLISHED.getAcquire(this);
        long lo = Math.max(Math.max(0L, from), pub - capacity);
        long hi = Math.max(lo, Math.min(pub, lo + into.capacity()));
        int k = 0;
        for (long p = lo; p < hi; p++, k++) {
            int i = (int) (p & mask);
            into.seq[k] = seqs[i];
            into.mono[k] = monos[i];
            into.wall[k] = walls[i];
            into.frame[k] = stamps[i];
            into.thread[k] = threads[i];
            into.lane[k] = lanes[i];
            into.kind[k] = kinds[i];
            into.detail[k] = details[i];
        }
        VarHandle.loadLoadFence();
        long claim = (long) CLAIMED.getAcquire(this);
        // Positions below this may have been reused while they were being copied.
        long valid = Math.max(lo, claim - capacity);
        long first = Math.min(valid, hi);
        into.set((int) (first - lo), k, first, hi, Math.max(0L, first - Math.max(0L, from)));
        return into;
    }

    /** The position the next row will be written at; rows {@code [oldest(), published())} are held. */
    public long published() {
        return (long) PUBLISHED.getAcquire(this);
    }

    /** The oldest position still held. */
    public long oldest() {
        return Math.max(0L, published() - capacity);
    }

    /** Rows the ring holds now. */
    public long held() {
        return published() - oldest();
    }

    /** How many rows the ring holds when full: a power of two. */
    public int capacity() {
        return capacity;
    }

    /** Rows numbered by the probe and never received, since the first row this recorder took. */
    public long lost() {
        return (long) LOST.getAcquire(this);
    }

    /** Whether rows carry a real frame stamp, rather than 0 because the host handed no frame counter. */
    public boolean stamped() {
        return stamped;
    }

    /** Whether the probe is feeding this recorder. */
    public boolean attached() {
        return attached;
    }

    /**
     * What the ring's own arrays cost per row, in bytes: four {@code long} columns and four references (taken as
     * 4 bytes, compressed). The strings a row points at are extra; see flight-recorder.md Q2 for a measured run.
     */
    public static int bytesPerSlot() {
        return 4 * Long.BYTES + 4 * 4;
    }
}
