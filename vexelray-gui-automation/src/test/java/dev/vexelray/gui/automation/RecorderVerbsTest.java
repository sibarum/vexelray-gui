package dev.vexelray.gui.automation;

import dev.vexelray.gui.recorder.Recorder;
import dev.vexelray.gui.recorder.RowFilter;
import org.junit.jupiter.api.Test;
import sibarum.probe.Lane;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ring verbs' words: what they parse, what they refuse, and that every one of them reads (so no connection
 * needs the hand for it). What the answers mean is the recorder module's to test.
 */
class RecorderVerbsTest {

    @Test
    void withTheProbeOffEveryVerbSaysHowToStartIt() {
        RecorderVerbs off = new RecorderVerbs(null);
        for (String verb : List.of("recorder", "rows", "gaps", "frames", "why")) {
            assertEquals("err the recorder is off: start with -Dprobe=...", off.answer(verb, ""));
        }
    }

    @Test
    void theRingVerbsReadAndNeedNoHand() {
        for (String line : List.of("recorder", "rows --lane gpu", "gaps 16", "frames worst 5", "why 3")) {
            assertTrue(RecorderVerbs.owns(line.split(" ")[0]), line);
            assertFalse(Automation.driving(line.split(" ")[0], ""), line);
        }
    }

    @Test
    void rowsTakesCsvviewsOptionsAndACount() {
        RowFilter f = RecorderVerbs.filter("--lane gpu,draw --kind present --thread main --grep fence --frame 73 7");
        assertEquals(new RowFilter(List.of("gpu", "draw"), "present", "main", "fence", 73, 7), f);
        assertEquals(RowFilter.ALL, RecorderVerbs.filter(""));
        assertThrows(IllegalArgumentException.class, () -> RecorderVerbs.filter("--color red"));
        assertThrows(IllegalArgumentException.class, () -> RecorderVerbs.filter("--lane"));
    }

    @Test
    void badArgumentsAreRefusedInWords() {
        Recorder ring = new Recorder(64, () -> 1);
        RecorderVerbs verbs = new RecorderVerbs(ring);
        assertEquals("err why takes a frame number; frames lists them", verbs.answer("why", ""));
        assertEquals("err 'x' is not a frame number", verbs.answer("why", "x"));
        assertEquals("err frames takes last N, worst N or since <mark>, not 'best'", verbs.answer("frames", "best 3"));
        assertEquals("err 'soon' is not a threshold in ms", verbs.answer("gaps", "soon"));
        assertEquals("err frame 3 is not in the ring, which holds no complete frame", verbs.answer("why", "3"));
    }

    @Test
    void aRingAnswersEveryVerbWithAFirstLineOfFacts() {
        long[] frame = {0};
        Recorder ring = new Recorder(64, () -> frame[0]);
        long seq = 1;
        for (int f = 1; f <= 3; f++) {
            frame[0] = f;
            ring.row(seq++, f * 20_000_000L, 0, "main", Lane.FRAME, "frame", "5.00ms");
            ring.row(seq++, f * 20_000_000L, 0, "main", Lane.FRAME, "frame.present", "#" + f);
            ring.row(seq++, f * 20_000_000L, 0, "main", Lane.FRAME, "loop.park", "10ms");
        }
        RecorderVerbs verbs = new RecorderVerbs(ring);
        assertTrue(verbs.answer("recorder", "").startsWith("ok rows=9 capacity_rows=64 over=+0.020s..+0.060s"));
        assertTrue(verbs.answer("rows", "--lane frame 2").startsWith("ok rows=2 over="));
        assertTrue(verbs.answer("gaps", "").startsWith("ok gaps=0 stalls=0 threshold_ms=100 over="));
        // Frame 1 is the first stamp the ring met, so it may be partial and is not listed.
        assertTrue(verbs.answer("frames", "").startsWith("ok frames=2 order=last held=2..3"));
        assertTrue(verbs.answer("frames", "1").startsWith("ok frames=1 order=last held=2..3 over="));
        String why = verbs.answer("why", "2");
        assertTrue(why.startsWith("ok frame=2 at=+0.035s took_us=5000"), why);
        assertTrue(why.endsWith("see     rows --frame 2"), why);
    }
}
