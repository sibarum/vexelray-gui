package dev.vexelray.gui.recorder;

import org.junit.jupiter.api.Test;
import sibarum.probe.Lane;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ring: what it holds after it wraps, what it counts as lost, and that a reader copying while the writer runs
 * never sees a torn row.
 */
class RecorderTest {

    private static void write(Recorder r, long seq) {
        r.row(seq, seq * 10, seq * 7, "t", Lane.APP, "k", Long.toString(seq));
    }

    @Test
    void theSizeIsRoundedUpToAPowerOfTwo() {
        assertEquals(8, new Recorder(5, Recorder.NO_FRAMES).capacity());
        assertEquals(8, new Recorder(8, Recorder.NO_FRAMES).capacity());
        assertEquals(1 << 18, new Recorder(Recorder.DEFAULT_ROWS, Recorder.NO_FRAMES).capacity());
    }

    @Test
    void aFullRingOverwritesTheOldestAndSaysWhatItHolds() {
        Recorder r = new Recorder(8, Recorder.NO_FRAMES);
        for (long s = 1; s <= 20; s++) {
            write(r, s);
        }
        assertEquals(20, r.published());
        assertEquals(12, r.oldest());
        assertEquals(8, r.held());
        Rows rows = r.read(0, new Rows(16));
        assertEquals(8, rows.size());
        assertEquals(12, rows.first());
        assertEquals(12, rows.skipped(), "positions 0..11 were asked for and are gone");
        assertEquals(13, rows.seq(0));
        assertEquals(20, rows.seq(7));
        assertEquals("20", rows.detail(7));
        assertEquals(200, rows.monoNanos(7));
        assertEquals(20, rows.next());
        // Overwriting is not loss: every row the probe numbered arrived.
        assertEquals(0, r.lost());
    }

    @Test
    void aSeqGapIsLossAndTheFirstRowIsNot() {
        Recorder r = new Recorder(8, Recorder.NO_FRAMES);
        write(r, 100);   // attached mid-run: the rows before were never this sink's
        write(r, 101);
        write(r, 104);
        write(r, 105);
        assertEquals(2, r.lost());
        Span span = Scan.all(r, (rows, i) -> {
        });
        assertEquals(4, span.rows());
        assertEquals(2, span.lost());
        assertEquals(100, span.firstSeq());
        assertEquals(105, span.lastSeq());
    }

    @Test
    void eachRowIsStampedWithTheFrameInProgress() {
        AtomicLong frame = new AtomicLong(41);
        Recorder r = new Recorder(8, frame::get);
        write(r, 1);
        frame.set(42);
        write(r, 2);
        Rows rows = r.read(0, new Rows());
        assertEquals(41, rows.frame(0));
        assertEquals(42, rows.frame(1));
        assertTrue(r.stamped());
        assertFalse(new Recorder(8, Recorder.NO_FRAMES).stamped());
    }

    @Test
    void readingAheadOfTheWriterReturnsNothingAndStaysPut() {
        Recorder r = new Recorder(8, Recorder.NO_FRAMES);
        write(r, 1);
        Rows rows = r.read(1, new Rows());
        assertTrue(rows.isEmpty());
        assertEquals(1, rows.next());
        write(r, 2);
        rows = r.read(rows.next(), rows);
        assertEquals(1, rows.size());
        assertEquals(2, rows.seq(0));
    }

    @Test
    void aStreamingReaderFollowsFromWhereItStopped() {
        Recorder r = new Recorder(64, Recorder.NO_FRAMES);
        Rows rows = new Rows(5);
        long next = 0;
        long expected = 1;
        for (long s = 1; s <= 40; s++) {
            write(r, s);
            if (s % 7 == 0) {
                do {
                    rows = r.read(next, rows);
                    for (int i = 0; i < rows.size(); i++) {
                        assertEquals(expected++, rows.seq(i));
                    }
                    next = rows.next();
                } while (!rows.isEmpty());
            }
        }
        assertEquals(36, expected, "every row up to the last read, once, in order");
    }

    /**
     * A reader copying while the writer laps it must never return a row whose fields come from two different
     * writes. Each row's fields are a function of its seq, so a torn row is detectable; a reader that falls behind
     * may lose rows (a seq gap, reported), but never mixes them.
     */
    @Test
    void aReaderCopyingWhileTheWriterRunsNeverSeesATornRow() throws Exception {
        AtomicLong frame = new AtomicLong();
        Recorder r = new Recorder(1 << 10, frame::get);
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<String> torn = new AtomicReference<>();
        String[] details = new String[1 << 12];
        for (int i = 0; i < details.length; i++) {
            details[i] = Integer.toString(i);
        }
        Thread writer = new Thread(() -> {
            for (long s = 1; s <= 3_000_000; s++) {
                frame.set(s / 4);
                r.row(s, s * 10, s * 7, "t", Lane.values()[(int) (s % Lane.values().length)], "k",
                        details[(int) (s & (details.length - 1))]);
            }
            done.set(true);
        });
        long[] seen = {0};
        Thread reader = new Thread(() -> {
            Rows rows = new Rows(256);
            while (!done.get() && torn.get() == null) {
                long p = r.oldest();
                rows = r.read(p, rows);
                for (int i = 0; i < rows.size(); i++) {
                    long s = rows.seq(i);
                    boolean ok = rows.monoNanos(i) == s * 10 && rows.wallNanos(i) == s * 7
                            && rows.frame(i) == s / 4
                            && rows.detail(i).equals(details[(int) (s & (details.length - 1))])
                            && rows.lane(i).equals(Lane.values()[(int) (s % Lane.values().length)].key())
                            && (i == 0 || s == rows.seq(i - 1) + 1)
                            && rows.first() + i == s - 1;
                    if (!ok) {
                        torn.set("row " + i + " of a chunk at " + rows.first() + ": seq " + s + " mono "
                                + rows.monoNanos(i) + " frame " + rows.frame(i) + " detail " + rows.detail(i));
                        return;
                    }
                    seen[0]++;
                }
            }
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        assertNull(torn.get());
        assertTrue(seen[0] > 0, "the reader read something while the writer ran");
        assertEquals(0, r.lost());
    }

    @Test
    void anOffProbeStartsNoRecorder() {
        // The test JVM runs without -Dprobe, so there is nothing to record and the verbs say how to start it.
        assertNull(Recorder.start(16, Recorder.NO_FRAMES));
    }
}
