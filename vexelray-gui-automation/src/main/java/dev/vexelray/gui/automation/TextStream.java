package dev.vexelray.gui.automation;

import dev.vexelray.gui.recorder.Follower;
import dev.vexelray.gui.recorder.Recorder;
import dev.vexelray.gui.recorder.Replies;
import dev.vexelray.gui.recorder.Rows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * The flight recorder's {@code stream}, as text: the rows as the recorder takes them, written as correlation-log
 * lines on the connection that asked (flight-recorder.md §6, *For agents and files*).
 *
 * <pre>
 * ok stream lanes=frame,gpu from_seq=- start_seq=- oldest_seq=1 head_seq=34962
 * seq,t_mono_ns,t_wall,thread,lane,kind,detail,frame
 * 34963,6630598300,2026-10-17T22:00:26.074572200Z,main,gpu,dxgi wait,16.61ms,88
 * # lost 35000..35010 rows=11
 * # idle quiet_ms=2000
 * # end rows=812 over=+6.594s..+8.634s lost=11 seq=34963..35790 why=for elapsed
 * </pre>
 *
 * <p><b>The connection is the sender.</b> The stream runs on the connection's own thread, through one
 * {@link Follower}, so a slow reader slows only itself: the ring is the queue, and a reader that falls a whole ring
 * behind is told which rows it missed, in order with the rows around them. Nothing here runs on the probe's thread
 * or inside its lock — that is the recorder's slot stores and nothing else.
 *
 * <p><b>Batches, not rows.</b> Every {@link #BATCH_MS} it copies what has arrived and writes it with one flush. The
 * spike measured a 1 ms batch at 12–15% of a core and 10 ms at a few percent, with a row still under one Inspector
 * frame old on arrival (atchung {@code docs/spikes/elektroq-inspector-stream.md}).
 *
 * <p><b>Ending.</b> With {@code for <ms>} it ends when that has passed, ignoring input until then, so a script can
 * send its next line straight after. Without it, it ends when any line arrives, and leaves that line unread for the
 * server to answer next; or when the reader goes away, which the next write discovers. A stream with nothing to
 * send writes a {@code # idle} line every {@link #IDLE_MS}, so that write happens even when the application is
 * parked, and so a client's read timeout never mistakes a quiet application for a hung one.
 */
final class TextStream implements Commands.Streaming {

    /** How often the stream copies and writes what has arrived. */
    static final long BATCH_MS = 10;

    /** How long a stream stays silent before it says it is idle. */
    static final long IDLE_MS = 2_000;

    /** Written in pieces of about this size, so a long backfill is not held in one buffer. */
    private static final int FLUSH_CHARS = 256 * 1024;

    private final Recorder recorder;
    private final List<String> lanes;
    private final long from;
    private final long forMs;

    TextStream(Recorder recorder, List<String> lanes, long from, long forMs) {
        this.recorder = recorder;
        this.lanes = List.copyOf(lanes);
        this.from = from;
        this.forMs = forMs;
    }

    @Override
    public void run(BufferedReader in, PrintWriter out) throws IOException {
        Follower follower;
        try {
            follower = new Follower(recorder, lanes, from);
        } catch (IllegalArgumentException e) {
            out.println("err " + e.getMessage());
            return;
        }
        StringBuilder sb = new StringBuilder(FLUSH_CHARS + 4_096);
        sb.append(Replies.text(follower.opened())).append('\n').append(Replies.ROW_HEADER).append('\n');

        Follower.Sink sink = new Follower.Sink() {
            @Override
            public void row(Rows rows, int i) {
                Replies.appendCsv(sb, rows, i).append('\n');
            }

            @Override
            public void lost(long fromSeq, long toSeq) {
                sb.append(Replies.lost(fromSeq, toSeq)).append('\n');
            }
        };

        long started = System.nanoTime();
        long deadline = forMs > 0 ? started + forMs * 1_000_000L : Long.MAX_VALUE;
        long lastWrite = started;
        String why;
        while (true) {
            while (follower.poll(sink)) {
                if (sb.length() >= FLUSH_CHARS && !write(out, sb)) {
                    return;
                }
            }
            long now = System.nanoTime();
            if (sb.length() > 0) {
                if (!write(out, sb)) {
                    return;
                }
                lastWrite = now;
            } else if (now - lastWrite >= IDLE_MS * 1_000_000L) {
                sb.append(Replies.idle((now - lastWrite) / 1_000_000L)).append('\n');
                if (!write(out, sb)) {
                    return;
                }
                lastWrite = now;
            }
            if (now >= deadline) {
                why = "for elapsed";
                break;
            }
            if (forMs <= 0 && in.ready()) {
                why = "a line arrived";
                break;
            }
            if (Thread.currentThread().isInterrupted()) {
                why = "server stopping";
                break;
            }
            LockSupport.parkNanos(Math.min(BATCH_MS * 1_000_000L, Math.max(1L, deadline - now)));
        }
        // What arrived up to the end belongs to the stream: one last copy, so `for` covers its whole window.
        while (follower.poll(sink)) {
            if (sb.length() >= FLUSH_CHARS && !write(out, sb)) {
                return;
            }
        }
        sb.append(Replies.text(follower.ended(why)));
        out.println(sb);
        out.flush();
    }

    /** Write and clear {@code sb}; false when the reader has gone, which ends the stream with nothing more to say. */
    private static boolean write(PrintWriter out, StringBuilder sb) {
        out.append(sb);
        out.flush();
        sb.setLength(0);
        return !out.checkError();
    }
}
