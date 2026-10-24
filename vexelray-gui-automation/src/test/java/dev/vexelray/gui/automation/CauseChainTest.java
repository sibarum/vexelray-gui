package dev.vexelray.gui.automation;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.probe.Cause;
import sibarum.probe.Probe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One command, one ID, from the agent's line to the tree's write (atchung's {@code docs/correlation.md}). A click
 * driven inside a command's cause is injected in it, dispatched in it, handled in it on the handler lane, and the
 * write the handler makes is recorded in it: so the probe file can say, of a write, which command made it.
 *
 * <p>Runs in the recording JVM, beside {@link HoverPathDiagnosisTest}, and reads the same file.
 */
class CauseChainTest {

    private static final float W = 800f;
    private static final float H = 600f;
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
    void aCommandsCauseReachesTheWriteItsClickMade() throws IOException {
        assertTrue(Probe.ON, "this test reads the log back, so surefire must start this JVM with -Dprobe.format=csv");

        long command = Cause.source("test.agent", Cause.Kind.COMMAND).next();
        AtomicLong handled = new AtomicLong();
        try (Gui gui = new Gui(sibarum.atchung.Atchung.create())) {
            Node target = gui.box().role("button").size(Length.dp(100), Length.dp(100));
            gui.onClick(target, () -> {
                handled.set(Cause.current());
                target.size(Length.dp(140), Length.dp(100));        // the write the command is answerable for
            });
            gui.root().children(gui.row().width(Length.FILL).height(Length.dp(100)).scroll(false, false)
                    .children(target));
            gui.frame(W, H, NO_TEXT);

            String[] out = new String[1];
            String driven = drivenWithFramesRunning(gui, driver -> {
                Cause.within(command, () -> out[0] = driver.command("click " + target.id()));
                return out[0];
            });
            assertTrue(driven.startsWith("ok"), driven);
            long deadline = System.nanoTime() + 500_000_000L;
            while (System.nanoTime() < deadline) {
                gui.frame(W, H, NO_TEXT);        // let the handler land and its write be drained
            }
        }

        assertEquals(command, handled.get(), "the handler ran on its lane inside the command's cause");
        List<String[]> caused = rowsIn(Cause.format(command));
        assertTrue(caused.stream().anyMatch(r -> r[0].equals("input") && r[1].equals("pointer.press")),
                "the injected press is the command's: " + kinds(caused));
        assertTrue(caused.stream().anyMatch(r -> r[0].equals("layout")),
                "and so is the write its handler made: " + kinds(caused));
    }

    /** Lane and kind of every row in {@code cause}, read by the header so the column's place is not assumed. */
    private static List<String[]> rowsIn(String cause) throws IOException {
        List<String> lines = Files.readAllLines(LOG);
        List<String> header = Arrays.asList(lines.get(0).split(","));
        int lane = header.indexOf("lane");
        int kind = header.indexOf("kind");
        int at = header.indexOf("cause");
        assertTrue(at >= 0 && at < header.indexOf("detail"), "the cause column, before detail: " + header);
        List<String[]> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            // Every column before detail is free of commas, and detail is last, so a bounded split is exact.
            String[] f = line.split(",", header.size());
            if (f.length > at && f[at].equals(cause)) {
                rows.add(new String[]{f[lane], f[kind]});
            }
        }
        return rows;
    }

    private static List<String> kinds(List<String[]> rows) {
        return rows.stream().map(r -> r[0] + "/" + r[1]).toList();
    }

    /** Run the driver on its own thread while this one keeps the loop turning, as HoverPathDiagnosisTest does. */
    private static String drivenWithFramesRunning(Gui gui, java.util.function.Function<Automation, String> work) {
        java.util.concurrent.CompletableFuture<String> answer = new java.util.concurrent.CompletableFuture<>();
        Thread driver = new Thread(() -> answer.complete(work.apply(new Automation(gui))), "cause-driver");
        driver.setDaemon(true);
        driver.start();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!answer.isDone() && System.nanoTime() < deadline) {
            gui.frame(W, H, NO_TEXT);
            Thread.onSpinWait();
        }
        return answer.getNow("err the driver never answered");
    }
}
