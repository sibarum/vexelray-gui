package dev.vexelray.gui.core;

import dev.vexelray.target.ImageHandle;
import dev.vexelray.vulkan.present.MipChain;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pictures handed in from off the GUI thread: the frame contract's feed (docs/plans/frame-contract.md D). A fixed
 * ring of slots of one size, each written by a producer and handed over with a stamp, and shown by whichever node
 * names the feed ({@link Node#image(Feed)}). A still picture is a feed of one slot, written once; a video is a feed of
 * three, written for as long as it plays.
 *
 * <p><b>Pixels never cross the bus; the feed does.</b> A feed is a handle: it may be the payload of a message (a job's
 * reply carries the feed it wrote) and part of a view's state (the asked half chooses among its slots), and the
 * processor accepts it in both places as it accepts a node in a view's state. What it refers to, the slots' pixels, is
 * the one memory the producer and the GUI thread share (threading.md T7.3), and nothing else of either side is.
 *
 * <p><b>Made anywhere, drawn on any device.</b> Like {@link Pixels}, a feed holds no texture: the renderer of each
 * device that draws it keeps a texture of its own and uploads the slot it shows when that slot changes. So a job can
 * make one before there is a window, a headless capture draws it as a window does, and nothing about a feed is the
 * main thread's until it is drawn.
 *
 * <p><b>The life of a slot</b>, from the producer's side, on any thread: {@link #take} a free one (it is now the
 * caller's), write its levels into {@link #level}, and {@link #handOver} it with a stamp; or {@link #drop} it unwritten.
 * {@link #write} does the three at once for a picture already in hand. When no slot is free, {@code take} answers
 * {@code -1}: that is the backpressure, and a producer that gets it tries again later rather than waiting. A slot comes
 * free again when a newer one has been shown in its place, so a producer is never more than {@link #slots} frames
 * ahead of what is seen.
 *
 * <p><b>Which slot is shown</b> is the newest handed over, unless a part's asked half chooses another
 * ({@link Drawn#show}), which a video does by its stamps ({@link #due}). A still needs no asked half at all.
 *
 * <p><b>Its time is the producer's</b> (frame-contract.md, open question 2). A producer that plays says what the feed's
 * time is at a moment on the machine's clock, and whether it runs on from there ({@link #time}); an asked half then
 * asks which slot is due for its frame ({@link #due(Frame)}) and when the next one will be ({@link #next(Frame)}),
 * and holds no clock of its own. So a video that follows its sound re-times the feed from the sound as it goes, a
 * pause is a time that stops, and the part's state is the feed and nothing else. Stamps are on that time, and a
 * producer keeps them increasing for as long as the feed lives, round a loop and across a seek alike; a seek also says
 * which stamps are {@linkplain #stale stale}, so the frames made for where it was are never shown and their slots
 * come free.
 *
 * <p><b>Given back with {@link #close}</b>, from any thread, by whoever owns it: the producer stops being given slots,
 * a node naming it draws nothing, and each renderer lets go of its texture once no frame in flight samples it.
 */
public final class Feed implements ImageHandle {

    private static final int FREE = 0;
    private static final int TAKEN = 1;
    private static final int HANDED = 2;

    private final int width;
    private final int height;
    private final int slots;
    private final boolean picture;
    private final int levels;

    private final AtomicIntegerArray state;
    private final AtomicLongArray stamps;
    private final AtomicLongArray generations;
    /** Each slot's levels, made the first time it is taken, by whoever takes it, and kept: a feed allocates once. */
    private final byte[][][] chains;
    /** What wakes the window that draws this feed, set by its renderer when it first draws it. */
    private final AtomicReference<Runnable> wake = new AtomicReference<>();

    /** The feed's time, as its producer last said it ({@link #time}); null until it has. */
    private final AtomicReference<Timebase> time = new AtomicReference<>();
    /** Stamps below this are stale ({@link #stale}): never chosen, and freed when not shown. */
    private volatile long floor = Long.MIN_VALUE;

    /** The slot shown, and the one an asked half last chose; the GUI thread's alone. */
    private int shown = -1;
    private int chosen = -1;
    private volatile boolean closed;

    /** At {@code monoNanos} on the machine's clock the feed's time was {@code stamp}, and ran on from there or not. */
    private record Timebase(long monoNanos, long stamp, boolean running) {
    }

    private Feed(int width, int height, int slots, boolean picture) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("a feed has a size: " + width + " x " + height);
        }
        if (slots < 1) {
            throw new IllegalArgumentException("a feed has a slot at least: " + slots);
        }
        this.width = width;
        this.height = height;
        this.slots = slots;
        this.picture = picture;
        this.levels = picture ? MipChain.count(width, height) : 1;
        this.state = new AtomicIntegerArray(slots);
        this.stamps = new AtomicLongArray(slots);
        this.generations = new AtomicLongArray(slots);
        this.chains = new byte[slots][][];
    }

    /**
     * A feed of {@code slots} pictures of {@code width x height}. With {@code picture}, as {@code GuiApp.picture}
     * uploads: sRGB texels read in linear light, each slot a mip chain of {@link #levels} levels, so a picture drawn
     * smaller than it is reads the level nearest its size. One slot for a still; three for a picture that plays: one
     * shown, one landing, one being written.
     */
    public static Feed of(int width, int height, int slots, boolean picture) {
        return new Feed(width, height, slots, picture);
    }

    /**
     * A still: a feed of one slot holding {@code chain}, handed over now. For a producer with the picture already in
     * hand (a job that decoded a file), so the feed it answers with is the picture.
     */
    public static Feed still(int width, int height, boolean picture, byte[][] chain) {
        Feed f = new Feed(width, height, 1, picture);
        f.write(chain, 0L);
        return f;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int slots() {
        return slots;
    }

    public boolean picture() {
        return picture;
    }

    /** How many levels each slot holds: a whole mip chain for a picture, one otherwise. */
    public int levels() {
        return levels;
    }

    /** The size in bytes of level {@code level}: RGBA8, each level half the one above, at least one texel. */
    public int levelBytes(int level) {
        return MipChain.size(width, level) * MipChain.size(height, level) * 4;
    }

    // ------------------------------------------------------------------ the producer's side, any thread

    /** A free slot, now the caller's to write and hand over, or {@code -1} when none is free or the feed is closed. */
    public int take() {
        if (closed) {
            return -1;
        }
        for (int i = 0; i < slots; i++) {
            if (state.compareAndSet(i, FREE, TAKEN)) {
                if (chains[i] == null) {
                    byte[][] chain = new byte[levels][];
                    for (int l = 0; l < levels; l++) {
                        chain[l] = new byte[levelBytes(l)];
                    }
                    chains[i] = chain;
                }
                return i;
            }
        }
        return -1;
    }

    /** Level {@code level} of a slot the caller {@linkplain #take took}, to write into. */
    public byte[] level(int slot, int level) {
        requireState(slot, TAKEN, "write into");
        return chains[slot][level];
    }

    /**
     * Give a written slot to the feed, stamped: from now it may be shown, and it is not the caller's any more. The stamp
     * is whatever the producer and the part that chooses agree on (a video's presentation time); a still's is zero.
     */
    public void handOver(int slot, long stamp) {
        requireState(slot, TAKEN, "hand over");
        stamps.set(slot, stamp);
        generations.incrementAndGet(slot);
        state.set(slot, HANDED);
        Runnable w = wake.get();
        if (w != null) {
            w.run();
        }
    }

    /** Give back a slot taken and not handed over. */
    public void drop(int slot) {
        requireState(slot, TAKEN, "drop");
        state.set(slot, FREE);
    }

    /**
     * Take a slot, copy {@code chain} into it and hand it over: {@code false}, with nothing written, when no slot is
     * free. {@code chain} has {@link #levels} levels of {@link #levelBytes} each, as {@code MipChain.of} makes them.
     */
    public boolean write(byte[][] chain, long stamp) {
        if (chain.length != levels) {
            throw new IllegalArgumentException("a slot of this feed has " + levels + " levels; was given " + chain.length);
        }
        int slot = take();
        if (slot < 0) {
            return false;
        }
        for (int l = 0; l < levels; l++) {
            System.arraycopy(chain[l], 0, chains[slot][l], 0, levelBytes(l));
        }
        handOver(slot, stamp);
        return true;
    }

    /**
     * Say what the feed's time is: {@code stamp} at {@code monoNanos} on the machine's clock ({@code System.nanoTime()}'s,
     * as {@link Frame#monoNanos}), running on with that clock from there, or, not {@code running}, held at it. Said again
     * whenever it changes: a start, a pause, a seek, a late frame waited for, or a correction from what keeps the real
     * time (a video's sound). Until it is first said, the newest slot handed over is the one due, as for a still. Wakes
     * whoever draws the feed, so the change is drawn.
     */
    public void time(long monoNanos, long stamp, boolean running) {
        time.set(new Timebase(monoNanos, stamp, running));
        Runnable w = wake.get();
        if (w != null) {
            w.run();
        }
    }

    /**
     * Every slot stamped below {@code stamp} is stale, made for a place the producer has since left (a seek): it is
     * never chosen again, and its slot comes free at the next frame, unless it is the one still on screen, which stays
     * until a newer one is due in its place. Wakes whoever draws the feed, so the slots come free.
     */
    public void stale(long stamp) {
        floor = stamp;
        Runnable w = wake.get();
        if (w != null) {
            w.run();
        }
    }

    // ------------------------------------------------------------------ the asked half's side, the GUI thread

    /**
     * The feed's time at {@code monoNanos} on the machine's clock, as its producer said it ({@link #time});
     * {@link Long#MAX_VALUE} before it has, so the newest slot is the one due.
     */
    public long now(long monoNanos) {
        Timebase t = time.get();
        if (t == null) {
            return Long.MAX_VALUE;
        }
        return t.running() ? t.stamp() + (monoNanos - t.monoNanos()) : t.stamp();
    }

    /** Whether the feed's time runs: said so by its producer, and not since held. */
    public boolean running() {
        Timebase t = time.get();
        return t != null && t.running();
    }

    /**
     * The slot due in {@code frame}, by the feed's time at the frame's moment: what an asked half shows
     * ({@link Drawn#show}). {@code -1} when none is due yet, and then whatever is shown stays. Allocates nothing.
     */
    public int due(Frame frame) {
        return due(now(frame.monoNanos()));
    }

    /**
     * When, on the tree's clock (as {@link Frame#presentNanos}), the slot after the one due in {@code frame} comes due:
     * what an asked half answers. {@link Frame#NEVER} while the feed's time is held, or when no later slot is handed
     * over yet, in which case the hand-over itself wakes the window. Allocates nothing.
     */
    public long next(Frame frame) {
        Timebase t = time.get();
        if (t == null || !t.running()) {
            return Frame.NEVER;
        }
        long s = nextAfter(now(frame.monoNanos()));
        if (s == Frame.NEVER) {
            return Frame.NEVER;
        }
        long atMono = t.monoNanos() + (s - t.stamp());
        return frame.presentNanos() + Math.max(0L, atMono - frame.monoNanos());
    }

    /**
     * The handed-over slot whose stamp is the latest at or before {@code stamp}, or {@code -1} when every one is later or
     * none is handed over: the frame a video owes at that moment. Allocates nothing. A {@linkplain #stale stale} slot is
     * never due.
     */
    public int due(long stamp) {
        int best = -1;
        long bestStamp = Long.MIN_VALUE;
        long fl = floor;
        for (int i = 0; i < slots; i++) {
            if (state.get(i) == HANDED) {
                long s = stamps.get(i);
                if (s >= fl && s <= stamp && (best < 0 || s > bestStamp)) {
                    best = i;
                    bestStamp = s;
                }
            }
        }
        return best;
    }

    /** The handed-over slot with the latest stamp, or {@code -1}: what is shown when no part chooses. Never a stale one. */
    public int newest() {
        int best = -1;
        long bestStamp = Long.MIN_VALUE;
        long fl = floor;
        for (int i = 0; i < slots; i++) {
            if (state.get(i) == HANDED) {
                long s = stamps.get(i);
                if (s >= fl && (best < 0 || s > bestStamp)) {
                    best = i;
                    bestStamp = s;
                }
            }
        }
        return best;
    }

    /** The earliest stamp handed over after {@code stamp}, or {@link Frame#NEVER}: when a video next needs asking. */
    public long nextAfter(long stamp) {
        long next = Frame.NEVER;
        long fl = floor;
        for (int i = 0; i < slots; i++) {
            if (state.get(i) == HANDED) {
                long s = stamps.get(i);
                if (s >= fl && s > stamp && s < next) {
                    next = s;
                }
            }
        }
        return next;
    }

    /** The stamp a slot was handed over with. */
    public long stamp(int slot) {
        return stamps.get(slot);
    }

    /** The slot shown, or {@code -1} before any is. */
    public int shown() {
        return shown;
    }

    // ------------------------------------------------------------------ the renderer's side, the GUI thread

    /**
     * An asked half's choice, through its {@code Drawn}: {@code slot} instead of the newest, and it stays chosen until
     * the next choice, as everything a {@code Drawn} sets does. Ignored unless it names a slot handed over. Whether it
     * changes what is shown.
     */
    boolean choose(int slot) {
        if (slot < 0 || slot >= slots || state.get(slot) != HANDED) {
            return false;
        }
        chosen = slot;
        return slot != shown;
    }

    /**
     * The renderer's: settle which slot this frame shows (the asked half's choice, or the newest handed over), and free
     * every handed-over slot older than it, which nothing will show again, and every stale one but the slot on screen.
     * The slot shown, or {@code -1}. Asked again in the same frame, by a second run or a second device, it answers the
     * same.
     */
    public int select() {
        long fl = floor;
        if (fl != Long.MIN_VALUE) {
            for (int i = 0; i < slots; i++) {
                if (i != shown && state.get(i) == HANDED && stamps.get(i) < fl) {
                    state.compareAndSet(i, HANDED, FREE);
                }
            }
        }
        int pick = chosen >= 0 && state.get(chosen) == HANDED && stamps.get(chosen) >= fl ? chosen : newest();
        if (pick >= 0 && pick != shown) {
            long at = stamps.get(pick);
            int was = shown;
            shown = pick;
            for (int i = 0; i < slots; i++) {
                if (i != pick && state.get(i) == HANDED && (i == was || stamps.get(i) <= at)) {
                    state.compareAndSet(i, HANDED, FREE);
                }
            }
        }
        return shown;
    }

    /** The renderer's: how many times {@code slot} has been handed over, so a device uploads a slot once per writing. */
    public long generation(int slot) {
        return generations.get(slot);
    }

    /** The renderer's: the levels of a slot handed over, to upload. Read, never written, on the GUI thread. */
    public byte[][] chain(int slot) {
        requireState(slot, HANDED, "upload");
        return chains[slot];
    }

    /**
     * The renderer's: what a hand-over runs to get the slot drawn, a frame's request on the window that draws it. The
     * first renderer to draw the feed sets it, and a later one (a headless capture of the same tree) leaves it.
     */
    public void wake(Runnable wake) {
        this.wake.compareAndSet(null, wake);
    }

    // ------------------------------------------------------------------ letting go

    /** Done with: no slot is given out again, a node naming it draws nothing, and each renderer lets its texture go. */
    public void close() {
        closed = true;
        Runnable w = wake.get();
        if (w != null) {
            w.run();
        }
    }

    public boolean closed() {
        return closed;
    }

    private void requireState(int slot, int wanted, String what) {
        if (slot < 0 || slot >= slots) {
            throw new IllegalArgumentException("no slot " + slot + " in a feed of " + slots);
        }
        if (state.get(slot) != wanted) {
            throw new IllegalStateException("slot " + slot + " cannot be asked to " + what + " now: it is "
                    + switch (state.get(slot)) {
                        case FREE -> "free";
                        case TAKEN -> "taken";
                        default -> "handed over";
                    });
        }
    }

    @Override
    public String toString() {
        return "Feed[" + width + "x" + height + ", " + slots + (slots == 1 ? " slot" : " slots")
                + (picture ? ", picture" : "") + (closed ? ", closed" : "") + "]";
    }
}
