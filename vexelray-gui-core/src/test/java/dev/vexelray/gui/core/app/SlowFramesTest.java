package dev.vexelray.gui.core.app;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The slow-frames offer, driven frame by frame through a host that records what it was told to do. */
class SlowFramesTest {

    private static final long MS = 1_000_000L;

    /** A host that says yes to offering and writes down everything else. */
    private static final class Recording implements SlowFrames.Host {
        boolean worth = true;
        final List<String> did = new ArrayList<>();
        long askedWith = -1L;

        @Override
        public boolean worthOffering() {
            return worth;
        }

        @Override
        public void ask(long typicalFrameNanos) {
            askedWith = typicalFrameNanos;
            did.add("ask");
        }

        @Override
        public void switchOn() {
            did.add("switch");
        }

        @Override
        public void saveSimplified() {
            did.add("save");
        }

        @Override
        public void refuseOffers() {
            did.add("refuse");
        }

        @Override
        public void report(String key, String what, String why) {
        }
    }

    private final Recording host = new Recording();
    private final SlowFrames frames = new SlowFrames(host);
    private long clock;

    /** {@code n} frames drawn, each costing {@code costMs} and starting {@code gapMs} after the last. */
    private void draw(int n, long costMs, long gapMs) {
        for (int i = 0; i < n; i++) {
            frames.drawn(clock, costMs * MS);
            clock += gapMs * MS;
        }
    }

    /** Past the warm-up, with nothing counted yet. */
    private void warmUp() {
        draw(1, 10, SlowFrames.WARM_UP_NANOS / MS);
    }

    @Test
    void repeatedlySlowFramesAreOfferedSimplifiedGraphicsOnce() {
        warmUp();
        draw(SlowFrames.WINDOW, 300, 300);
        assertEquals(List.of("ask"), host.did);
        assertEquals(300 * MS, host.askedWith, "the prompt is told how slow");

        draw(SlowFrames.WINDOW * 3, 300, 300);
        assertEquals(List.of("ask"), host.did, "once a session, however long it goes on");
    }

    @Test
    void slowFramesDuringTheWarmUpAreStartupNotAMachine() {
        draw(110, 300, 100);   // 11 s, more than a window of slow frames, nearly all inside the warm-up
        assertTrue(host.did.isEmpty(), "the first ten seconds pay for shaders and uploads: " + host.did);
    }

    @Test
    void aBurstOfHitchesIsNotASlowMachine() {
        warmUp();
        draw(SlowFrames.WINDOW, 210, 50);   // a window of slow frames, but crammed into 2.4 s
        assertTrue(host.did.isEmpty(), "they have to span three seconds: " + host.did);
    }

    @Test
    void mostlyFastFramesWithSomeSlowOnesAreNotStruggling() {
        warmUp();
        for (int i = 0; i < 5; i++) {
            draw(3, 300, 400);
            draw(3, 20, 400);
        }
        assertTrue(host.did.isEmpty(), "half slow is not ten in twelve: " + host.did);
    }

    @Test
    void nothingIsOfferedWhenThereIsNothingToOffer() {
        host.worth = false;   // already simplified, or the user said never
        warmUp();
        draw(SlowFrames.WINDOW * 2, 300, 300);
        assertTrue(host.did.isEmpty());
    }

    @Test
    void aTrialThatHelpsIsSaved() {
        warmUp();
        draw(SlowFrames.WINDOW, 300, 300);
        frames.answered(SlowFrames.Answer.SWITCH);
        draw(SlowFrames.TRIAL_SKIP, 400, 400);   // the switch itself redraws everything: not judged
        draw(SlowFrames.TRIAL_FRAMES, 60, 100);
        assertEquals(List.of("ask", "switch", "save"), host.did);
    }

    @Test
    void halvingCountsAsHelpingEvenStillUnderFiveFramesASecond() {
        warmUp();
        draw(SlowFrames.WINDOW, 900, 900);
        frames.answered(SlowFrames.Answer.SWITCH);
        draw(SlowFrames.TRIAL_SKIP + SlowFrames.TRIAL_FRAMES, 400, 400);
        assertEquals(List.of("ask", "switch", "save"), host.did);
    }

    @Test
    void aSwitchThatDoesNotHelpStaysOnForTheSessionAndIsNotSaved() {
        warmUp();
        draw(SlowFrames.WINDOW, 300, 300);
        frames.answered(SlowFrames.Answer.SWITCH);
        draw(SlowFrames.TRIAL_SKIP + SlowFrames.TRIAL_FRAMES, 280, 300);
        assertEquals(List.of("ask", "switch"), host.did,
                "never switched off: that would hand the full load back to a machine shown to be struggling");
    }

    /**
     * Reduced motion does not make a frame cheaper, it asks for fewer of them, and on a struggling machine that is as
     * much help: the same 300 ms frames, now ten times further apart, are a tenth of the load.
     */
    @Test
    void fewerFramesAtTheSameCostIsHelpingToo() {
        warmUp();
        draw(SlowFrames.WINDOW, 300, 300);   // drawing all of the time
        frames.answered(SlowFrames.Answer.SWITCH);
        draw(SlowFrames.TRIAL_SKIP + SlowFrames.TRIAL_FRAMES, 300, 3000);   // a tenth of it
        assertEquals(List.of("ask", "switch", "save"), host.did);
    }

    /** The real check, on a real tree: nothing to offer once both choices are on, or the user said never. */
    @Test
    void worthOfferingUntilBothChoicesAreOn(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        SuiteGraphics none = SuiteGraphics.at(dir.resolve("none.properties"));
        try (dev.vexelray.gui.core.Gui gui = new dev.vexelray.gui.core.Gui(sibarum.atchung.Atchung.create(),
                Runnable::run)) {
            assertTrue(SlowFrames.worthOffering(none, gui), "nothing on: offered");
            gui.flatShading(true);
            assertTrue(SlowFrames.worthOffering(none, gui), "flat only: motion still has load to take off");
            gui.flatShading(false).reducedMotion(true);
            assertTrue(SlowFrames.worthOffering(none, gui), "motion only: shading still has load to take off");
            gui.simplifiedGraphics(true);
            assertFalse(SlowFrames.worthOffering(none, gui), "both on: nothing left to offer");

            gui.simplifiedGraphics(false);
            Settings.at(dir.resolve("never.properties")).putString(SuiteGraphics.OFFER, "never").save();
            assertFalse(SlowFrames.worthOffering(SuiteGraphics.at(dir.resolve("never.properties")), gui),
                    "refused: never offered again");
        }
        assertFalse(SlowFrames.worthOffering(none, null), "no tree yet");
    }

    @Test
    void notNowEndsItForTheSessionAndNeverIsSaved() {
        warmUp();
        draw(SlowFrames.WINDOW, 300, 300);
        frames.answered(SlowFrames.Answer.NOT_NOW);
        draw(SlowFrames.WINDOW * 2, 300, 300);
        assertEquals(List.of("ask"), host.did);

        Recording other = new Recording();
        SlowFrames again = new SlowFrames(other);
        again.drawn(0L, 10 * MS);
        long t = SlowFrames.WARM_UP_NANOS;
        for (int i = 0; i < SlowFrames.WINDOW; i++, t += 300 * MS) {
            again.drawn(t, 300 * MS);
        }
        again.answered(SlowFrames.Answer.NEVER);
        assertEquals(List.of("ask", "refuse"), other.did);
    }

    @Test
    void anAnswerWithNoQuestionIsIgnored() {
        frames.answered(SlowFrames.Answer.SWITCH);
        assertTrue(host.did.isEmpty());
    }
}
