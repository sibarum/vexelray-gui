package dev.vexelray.gui.core.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.probe.Lane;
import sibarum.probe.LiveSink;
import sibarum.probe.Probe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mutation lane (docs/plans/export.md M1): with the layout lane traced, every edit the reconciler applies is a
 * row saying what it was and what it did to the frame. Runs in its own JVM, started tracing (see this module's pom).
 */
class MutationLaneTest {

    private static final long ROOT = 1L;
    private static final long CHILD = 2L;

    /** The layout lane's rows written on this thread, as "kind | detail". */
    private final List<String> rows = new ArrayList<>();
    private final LiveSink sink = (seq, mono, wall, thread, lane, kind, detail) -> {
        if (lane == Lane.LAYOUT && thread.equals(Thread.currentThread().getName())) {
            synchronized (rows) {
                rows.add(kind + " | " + detail);
            }
        }
    };

    private Reconciler r;

    @BeforeEach
    void tree() {
        assertTrue(Probe.tracing(Lane.LAYOUT), "this test runs in the mutation-lane execution, with the probe tracing");
        r = new Reconciler(ROOT);
        r.apply(new Mutation.Create(ROOT, Map.of()));
        r.apply(new Mutation.Create(CHILD, Map.of()));
        r.apply(new Mutation.Insert(ROOT, CHILD, Mutation.Insert.END));
        r.clearDirty();
        r.takeDrawDirty();
        Probe.attach(sink);
    }

    @AfterEach
    void detach() {
        Probe.detach(sink);
    }

    private List<String> take() {
        synchronized (rows) {
            List<String> seen = List.copyOf(rows);
            rows.clear();
            return seen;
        }
    }

    @Test
    void eachEditSaysWhatItDidToTheFrame() {
        r.apply(new Mutation.SetProp(CHILD, PropKey.TRANSLATE_X, 1f));
        r.apply(new Mutation.SetProp(CHILD, PropKey.TRANSLATE_X, 1f));
        r.apply(new Mutation.SetProp(CHILD, PropKey.CARET, 3));
        r.apply(new Mutation.SetText(CHILD, "hello"));
        r.apply(new Mutation.SetProp(99L, PropKey.TRANSLATE_X, 1f));
        r.apply(new Mutation.Reveal(CHILD));
        assertEquals(List.of(
                "mutation draw | SetProp node=2 TRANSLATE_X=1.0",
                "mutation unchanged | SetProp node=2 TRANSLATE_X=1.0",
                "mutation geometry | SetProp node=2 CARET=3",
                "mutation layout | SetText node=2 chars=5",
                "mutation missing | SetProp node=99 TRANSLATE_X=1.0",
                "mutation queued | Reveal node=2"), take());
    }

    @Test
    void aBatchIsReportedEditByEdit() {
        r.apply(new Mutation.SetText(CHILD, "a"));
        take();
        r.apply(new Mutation.Batch(List.of(
                new Mutation.SetText(CHILD, "a"),
                new Mutation.SetText(CHILD, "ab"))));
        assertEquals(List.of(
                "mutation unchanged | SetText node=2 chars=1",
                "mutation layout | SetText node=2 chars=2"), take());
    }

    /** Judging each edit on its own clears the flags around it; what the frame owes must come through intact. */
    @Test
    void theFrameStillOwesWhatItOwed() {
        r.apply(new Mutation.SetText(CHILD, "laid out"));
        r.apply(new Mutation.SetProp(CHILD, PropKey.TRANSLATE_X, 0f));
        r.apply(new Mutation.SetText(CHILD, "laid out"));
        assertTrue(r.layoutDirty(), "an unchanged edit after a layout one leaves the layout owed");
        assertTrue(r.takeDrawDirty());
        r.clearDirty();
        r.apply(new Mutation.SetText(CHILD, "laid out"));
        assertFalse(r.layoutDirty(), "and an unchanged one on a clean frame owes nothing");
        assertFalse(r.takeDrawDirty());
    }

    @Test
    void textIsReportedByItsLengthNeverItsContent() {
        r.apply(new Mutation.SetText(CHILD, "a private file name.png"));
        List<String> seen = take();
        assertEquals(1, seen.size());
        assertFalse(seen.get(0).contains("private"), seen.get(0));
    }
}
