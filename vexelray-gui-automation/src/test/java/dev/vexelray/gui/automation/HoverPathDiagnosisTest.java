package dev.vexelray.gui.automation;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.probe.CsvView;
import sibarum.probe.Probe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5 (docs/automation.md §8): diagnose a hover-path bug <b>from the log alone</b>.
 *
 * <p>Until this passes, the instrument is not trusted for troubleshooting — because everything else it does is
 * only worth having if a run it recorded can be read back by someone who was not there.
 *
 * <p><b>The bug is planted, and that is stated rather than hidden.</b> The historical bug this module was built
 * for is not in the tree to reproduce, so this plants one of the same class: a control that changes size when
 * the pointer hovers it, which is exactly what the framework's own UX rule forbids
 * (<i>never change the pointer target on hover</i>). If the instrument cannot diagnose a defect deliberately
 * placed in front of it, it certainly cannot diagnose one nobody placed.
 *
 * <p><b>Why the bug needs a path to exist at all.</b> The agent resolves the target's box, starts travelling,
 * and on the way the pointer crosses a control that grows under it — which pushes the target sideways. The
 * click then lands where the target used to be. Every step of that is legal, the layout is correct throughout,
 * and no single frame is wrong. A driver that teleported would click the resolved coordinate and hit, report
 * success, and the defect would stay invisible for exactly as long as nobody moved a mouse across that row.
 */
class HoverPathDiagnosisTest {

    private static final float W = 800f;
    private static final float H = 600f;

    /** Where this test JVM's correlation log is written (see the pom's {@code correlation} execution). */
    private static final Path LOG = Path.of("target", "a5-correlations.csv");

    private static final TextMeasurer NO_TEXT = new TextMeasurer() {
        @Override
        public float intrinsic(RetainedNode node, Axis axis, float px) {
            return 0f;
        }

        @Override
        public int offsetAt(String text, float localX, float px) {
            return 0;
        }

        @Override
        public float[] caretAdvances(String text, float px) {
            int n = text == null ? 0 : text.length();
            return new float[n + 1];
        }
    };

    @Test
    void aHoverPathBugIsVisibleInTheLogAndInvisibleWithoutOne() throws IOException {
        assertTrue(Probe.ON,
                "this test reads the log back, so surefire must start this JVM with -Dprobe.format=csv");

        // A loop thread and a driver thread, because the bug does not exist without them. Pump frames only
        // after the path and every move dispatches in one drain against one stale layout: nothing moves while
        // the pointer is in flight, the click lands where it was aimed, and the defect is invisible. That is
        // not a quirk of the test — it is why this class of bug survives hand-driven frame tests, and why the
        // instrument drives a real loop instead of stepping one.
        try (Gui gui = new Gui(sibarum.atchung.Atchung.create())) {
            AtomicInteger targetClicks = new AtomicInteger();

            // A row: a start pad, the offender, and the target. The offender is 100px wide at rest.
            Node offender = gui.box().role("button").size(Length.dp(100), Length.dp(100));
            Node target = gui.box().role("button").size(Length.dp(100), Length.dp(100));
            gui.onClick(target, targetClicks::incrementAndGet);
            // The defect, in one line, and it is a plausible one — a control that "helpfully" grows to show
            // more of itself when the pointer arrives. It moves everything to its right while doing so.
            gui.onState(offender, state -> offender.size(
                    Length.dp(state == dev.vexelray.gui.core.input.InteractionState.HOVER ? 300 : 100),
                    Length.dp(100)));
            gui.root().children(gui.row().width(Length.FILL).height(Length.dp(100)).scroll(false, false)
                    .children(gui.box().size(Length.dp(100), Length.dp(100)), offender, target));
            gui.frame(W, H, NO_TEXT);

            Probe.mark(sibarum.probe.Lane.APP, "agent.mark", "A5: travelling to the target across the offender");

            // The agent does what an agent does: resolve the target, then travel to it.
            String out = drivenWithFramesRunning(gui, driver -> driver.command("click " + target.id()));
            long deadline = System.nanoTime() + 500_000_000L;
            while (System.nanoTime() < deadline) {
                gui.frame(W, H, NO_TEXT);        // let any click handler land before counting
            }

            assertTrue(out.startsWith("ok"), out);
            // The bug reproduces: the click was correctly aimed and still missed, because the target moved
            // while the pointer was on its way to it.
            assertEquals(0, targetClicks.get(),
                    "the planted bug should make this click miss; if it landed, the scenario stopped reproducing");

            // --- now the part under test: can this be diagnosed from the file alone? ---
            List<CsvView.Row> log = CsvView.read(LOG);
            assertTrue(CsvView.loss(log).isEmpty(), "a diagnosis from a lossy log is a guess");

            List<CsvView.Row> hovers = CsvView.filter(log, "input", "state.hover", null, null);
            assertFalse(hovers.isEmpty(),
                    "the log has to record that something was hovered; motion alone does not say it mattered");
            // The offender is neither the start nor the target and was never named by the agent. Its appearance
            // in the log is the whole finding: something the pointer merely passed over changed state.
            assertTrue(hovers.stream().anyMatch(r -> r.detail().equals("node=" + offender.id())),
                    "the node that was crossed has to be identifiable: " + hovers.stream()
                            .map(CsvView.Row::detail).toList());

            // And the consequence is in the file too: a layout republished between the hover and the click,
            // which is what "the target moved while the pointer was travelling" looks like from outside.
            long hoverAt = hovers.stream()
                    .filter(r -> r.detail().equals("node=" + offender.id()))
                    .mapToLong(CsvView.Row::monoNanos).min().orElseThrow();
            long clickAt = CsvView.filter(log, "input", "pointer.press", null, null).stream()
                    .mapToLong(CsvView.Row::monoNanos).max().orElseThrow();
            boolean relaidOutMidPath = CsvView.filter(log, "layout", "layout.publish", null, null).stream()
                    .anyMatch(r -> r.monoNanos() > hoverAt && r.monoNanos() < clickAt);
            assertTrue(relaidOutMidPath,
                    "the log has to show the tree being laid out again between the hover and the click — "
                            + "that is the causal link a reader needs, and it is why one file in time order");
        }
    }

    /** Run the driver on its own thread while this one keeps the loop turning — a real application's shape. */
    private static String drivenWithFramesRunning(Gui gui, java.util.function.Function<Automation, String> work) {
        java.util.concurrent.CompletableFuture<String> answer = new java.util.concurrent.CompletableFuture<>();
        Thread driver = new Thread(() -> answer.complete(work.apply(new Automation(gui))), "a5-driver");
        driver.setDaemon(true);
        driver.start();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!answer.isDone() && System.nanoTime() < deadline) {
            gui.frame(W, H, NO_TEXT);
            Thread.onSpinWait();
        }
        return answer.getNow("err the driver never answered");
    }

    @Test
    void theSameActionWithoutAPathLeavesNoSuchEvidence() throws IOException {
        // The control, and the point of the whole module. Publishing the click as a single edge — what a
        // teleporting driver does — produces a log in which nothing was ever hovered, so a reader would
        // correctly conclude that nothing crossed anything. The bug would still be there, and invisible.
        try (Gui gui = new Gui(sibarum.atchung.Atchung.create(), Runnable::run)) {
            Node offender = gui.box().role("button").size(Length.dp(100), Length.dp(100));
            AtomicInteger hovers = new AtomicInteger();
            gui.onState(offender, state -> {
                if (state == dev.vexelray.gui.core.input.InteractionState.HOVER) {
                    hovers.incrementAndGet();
                }
            });
            gui.root().children(gui.row().width(Length.FILL).height(Length.dp(100)).scroll(false, false)
                    .children(gui.box().size(Length.dp(100), Length.dp(100)), offender,
                            gui.box().size(Length.dp(100), Length.dp(100))));
            gui.frame(W, H, NO_TEXT);

            gui.bus().publish(dev.vexelray.gui.core.input.InputTopics.INPUT,
                    new sibarum.tactroller.api.InputEvent.PointerMoved(250, 50, 240, 0, 0));
            gui.frame(W, H, NO_TEXT);

            assertEquals(0, hovers.get(),
                    "a jump hovers nothing on the way, which is why a teleporting driver reports a clean run");
        }
    }
}
