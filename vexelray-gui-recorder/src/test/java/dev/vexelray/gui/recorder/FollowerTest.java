package dev.vexelray.gui.recorder;

import org.junit.jupiter.api.Test;
import sibarum.probe.Lane;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A streaming reader's cursor: where it starts, which rows it hands over, and that every row it never saw is
 * reported as a gap in order, whether the probe lost it or the writer lapped the reader.
 */
class FollowerTest {

    private static void write(Recorder r, long seq, Lane lane) {
        r.row(seq, seq * 10, seq * 7, "t", lane, "k" + seq, Long.toString(seq));
    }

    private static void write(Recorder r, long fromSeq, long toSeq) {
        for (long s = fromSeq; s <= toSeq; s++) {
            write(r, s, Lane.APP);
        }
    }

    /** What a sink was handed, in order: seqs as numbers, gaps as "lost a..b". */
    private static final class Seen implements Follower.Sink {
        final List<String> events = new ArrayList<>();

        @Override
        public void row(Rows rows, int i) {
            events.add(Long.toString(rows.seq(i)));
        }

        @Override
        public void lost(long fromSeq, long toSeq) {
            events.add("lost " + fromSeq + ".." + toSeq);
        }
    }

    private static List<String> drain(Follower f) {
        Seen seen = new Seen();
        while (f.poll(seen)) {
            // keep copying until the cursor stops moving
        }
        return seen.events;
    }

    @Test
    void startingLiveDeliversOnlyWhatIsPublishedAfter() {
        Recorder r = new Recorder(16, Recorder.NO_FRAMES);
        write(r, 1, 5);
        Follower f = new Follower(r, List.of(), -1);
        assertEquals(new StreamOpened(List.of(), -1, -1, 1, 5), f.opened());
        assertEquals(List.of(), drain(f), "nothing held before it started");
        write(r, 6, 8);
        assertEquals(List.of("6", "7", "8"), drain(f));
        assertFalse(f.poll(new Seen()), "a cursor with nothing new does not move");
        assertEquals(new Span(60, 80, 3, 0, 6, 8), f.span());
    }

    @Test
    void fromASeqTheRingHoldsStartsExactlyThere() {
        Recorder r = new Recorder(16, Recorder.NO_FRAMES);
        write(r, 1, 10);
        Follower f = new Follower(r, List.of(), 7);
        assertEquals(7, f.opened().startSeq());
        assertEquals(List.of("7", "8", "9", "10"), drain(f));
        write(r, 11, 11);
        assertEquals(List.of("11"), drain(f), "and carries on live from there");
    }

    @Test
    void historyOlderThanTheRingStartsAtTheOldestRowAndIsNotLoss() {
        Recorder r = new Recorder(8, Recorder.NO_FRAMES);
        write(r, 1, 20);     // the ring holds 13..20
        Follower f = new Follower(r, List.of(), 2);
        assertEquals(new StreamOpened(List.of(), 2, 13, 13, 20), f.opened());
        assertEquals(List.of("13", "14", "15", "16", "17", "18", "19", "20"), drain(f));
        assertEquals(0, f.lost(), "the ring overwrote those before anyone asked: its span shrank");
    }

    @Test
    void historyTheRingHasNotReachedWaitsForIt() {
        Recorder r = new Recorder(16, Recorder.NO_FRAMES);
        write(r, 1, 3);
        Follower f = new Follower(r, List.of(), 6);
        assertEquals(-1, f.opened().startSeq());
        write(r, 4, 7);
        assertEquals(List.of("6", "7"), drain(f));
    }

    @Test
    void aSeqGapInTheProbeIsReportedWhereItHappened() {
        Recorder r = new Recorder(16, Recorder.NO_FRAMES);
        Follower f = new Follower(r, List.of(), -1);
        write(r, 1, 2);
        write(r, 5, 6);      // 3 and 4 were numbered by the probe and never arrived
        assertEquals(List.of("1", "2", "lost 3..4", "5", "6"), drain(f));
        assertEquals(2, f.lost());
        assertEquals(new Span(10, 60, 4, 2, 1, 6), f.span());
    }

    @Test
    void aReaderTheWriterLapsIsToldWhichRowsItMissed() {
        Recorder r = new Recorder(8, Recorder.NO_FRAMES);
        Follower f = new Follower(r, List.of(), -1);
        write(r, 1, 3);
        assertEquals(List.of("1", "2", "3"), drain(f));
        write(r, 4, 30);     // far more than the ring holds before the reader comes back: it holds 23..30
        List<String> seen = drain(f);
        assertEquals("lost 4..22", seen.getFirst());
        assertEquals(List.of("23", "24", "25", "26", "27", "28", "29", "30"), seen.subList(1, seen.size()));
        assertEquals(19, f.lost());
    }

    @Test
    void lanesNarrowWhatIsDeliveredButNotWhatCountsAsLoss() {
        Recorder r = new Recorder(16, Recorder.NO_FRAMES);
        Follower f = new Follower(r, List.of("gpu", "frame"), -1);
        write(r, 1, Lane.GPU);
        write(r, 2, Lane.BUS);
        write(r, 3, Lane.FRAME);
        write(r, 6, Lane.BUS);   // 4 and 5 are lost: a gap is a gap whatever lane it was on
        write(r, 7, Lane.GPU);
        assertEquals(List.of("1", "3", "lost 4..5", "7"), drain(f));
        assertEquals(3, f.delivered());
        assertEquals(List.of("gpu", "frame"), f.opened().lanes());
    }

    @Test
    void anUnknownLaneIsRefusedNamingTheOnesThereAre() {
        Recorder r = new Recorder(16, Recorder.NO_FRAMES);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Follower(r, List.of("gpu", "paint"), -1));
        assertTrue(e.getMessage().startsWith("there is no lane 'paint'; the lanes are bus,"), e.getMessage());
    }

    @Test
    void aSmallChunkStillDeliversEverythingInOrder() {
        Recorder r = new Recorder(64, Recorder.NO_FRAMES);
        write(r, 1, 40);
        Follower f = new Follower(r, List.of(), 1, 3);
        List<String> seen = drain(f);
        assertEquals(40, seen.size());
        assertEquals("1", seen.getFirst());
        assertEquals("40", seen.getLast());
    }

    @Test
    void theRepliesAreAFirstLineOfFactsAndHashLinesBetween() {
        assertEquals("ok stream lanes=gpu,frame from_seq=- start_seq=- oldest_seq=13 head_seq=20",
                Replies.text(new StreamOpened(List.of("gpu", "frame"), -1, -1, 13, 20)));
        assertEquals("ok stream lanes=all from_seq=2 start_seq=13 oldest_seq=13 head_seq=20",
                Replies.text(new StreamOpened(List.of(), 2, 13, 13, 20)));
        assertEquals("# lost 4..22 rows=19", Replies.lost(4, 22));
        assertEquals("# idle quiet_ms=2000", Replies.idle(2000));
        assertEquals("# end rows=3 over=+0.000s..+0.000s lost=2 seq=1..6 why=for elapsed",
                Replies.text(new StreamEnded(new Span(10, 60, 3, 2, 1, 6), "for elapsed")));
        assertEquals("# end rows=0 over=- lost=0 seq=- why=a line arrived",
                Replies.text(new StreamEnded(Span.EMPTY, "a line arrived")));
    }

    @Test
    void aStreamedRowIsTheSameLineRowsPrints() {
        Recorder r = new Recorder(8, () -> 41);
        r.row(9, 90, 63, "main", Lane.GPU, "dxgi wait", "16.61ms, \"late\"");
        Rows rows = r.read(0, new Rows());
        assertEquals(Replies.csv(rows.row(0)), Replies.appendCsv(new StringBuilder(), rows, 0).toString());
        assertEquals("9,90,1970-01-01T00:00:00.000000063Z,main,gpu,dxgi wait,\"16.61ms, \"\"late\"\"\",41",
                Replies.csv(rows.row(0)));
    }
}
