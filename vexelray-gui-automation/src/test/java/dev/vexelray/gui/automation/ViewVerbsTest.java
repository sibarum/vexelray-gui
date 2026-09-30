package dev.vexelray.gui.automation;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.WindowView;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verbs that set what a photograph is of — {@code zoom}, {@code dpi}, {@code resize} — and the router that
 * picks which window they are about.
 *
 * <p>The claim worth pinning is that each <em>answers with what happened</em>: a clamp, a window that would not
 * go smaller, are in the reply. A screenshot taken after a verb that said {@code ok} and did something else is
 * the failure the instrument exists to rule out.
 */
class ViewVerbsTest {

    private static final float W = 800f;
    private static final float H = 600f;

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
            return new float[text == null ? 1 : text.length() + 1];
        }
    };

    /** A window that can be sized, down to a floor — the application's minimum. */
    private static final class FakeWindow implements WindowControls {
        volatile int width;
        volatile int height;
        final int minW;
        final int minH;

        FakeWindow(int width, int height, int minW, int minH) {
            this.width = width;
            this.height = height;
            this.minW = minW;
            this.minH = minH;
        }

        @Override
        public void minimize() {
        }

        @Override
        public void toggleMaximize() {
        }

        @Override
        public boolean maximized() {
            return false;
        }

        @Override
        public void close() {
        }

        @Override
        public void capture(String path) {
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public void resize(int w, int h) {
            width = Math.max(minW, w);
            height = Math.max(minH, h);
        }
    }

    private static Gui deterministic() {
        return new Gui(sibarum.atchung.Atchung.create(), Runnable::run);
    }

    /** Runs {@code work} on a driver thread while this one lays out frames, as a real loop would. */
    private static String driven(Gui gui, Function<String[], String> work) {
        CompletableFuture<String> answer = new CompletableFuture<>();
        Thread driver = new Thread(() -> answer.complete(work.apply(new String[0])), "test-driver");
        driver.setDaemon(true);
        driver.start();
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (!answer.isDone() && System.nanoTime() < deadline) {
            gui.frame(W, H, NO_TEXT);
            Thread.onSpinWait();
        }
        return answer.getNow("err the driver never answered");
    }

    @Test
    void zoomSetsTheFactorAndSaysWhatItBecame() {
        try (Gui gui = deterministic()) {
            Automation automation = new Automation(gui, new FakeWindow(800, 600, 100, 100));
            String out = driven(gui, x -> automation.command("zoom 1.5"));
            assertEquals("ok zoom=1.5", out);
            assertEquals(1.5f, gui.zoom().value());
        }
    }

    @Test
    void zoomOutsideTheApplicationsRangeIsClampedAndSaidSo() {
        try (Gui gui = deterministic()) {
            gui.zoomRange(0.75f, 2f, 1.25f);
            Automation automation = new Automation(gui, new FakeWindow(800, 600, 100, 100));
            String out = driven(gui, x -> automation.command("zoom 9"));
            assertTrue(out.startsWith("ok zoom=2 "), out);
            assertTrue(out.contains("asked for 9"), "a clamp has to be in the reply: " + out);
        }
    }

    @Test
    void dpiSetsTheDensityAndSaysWhatItBecame() {
        try (Gui gui = deterministic()) {
            Automation automation = new Automation(gui, new FakeWindow(800, 600, 100, 100));
            assertEquals("ok dpi=2", driven(gui, x -> automation.command("dpi 2")));
            assertEquals(2f, gui.dpi().value());
        }
    }

    @Test
    void resizeTakesPixelsInEitherSpelling() {
        try (Gui gui = deterministic()) {
            FakeWindow window = new FakeWindow(800, 600, 100, 100);
            Automation automation = new Automation(gui, window);
            assertEquals("ok 1024x768", driven(gui, x -> automation.command("resize 1024x768")));
            assertEquals("ok 640x480", driven(gui, x -> automation.command("resize 640 480")));
            assertEquals("ok 500x400", driven(gui, x -> automation.command("resize 500px 400px")));
            assertEquals(500, window.width());
        }
    }

    @Test
    void resizeTakesEmResolvedAtTheCurrentZoom() {
        try (Gui gui = deterministic()) {
            Automation automation = new Automation(gui, new FakeWindow(800, 600, 100, 100));
            float em = gui.rootEmPx() * gui.zoom().value() * gui.dpi().value();
            String out = driven(gui, x -> automation.command("resize 40em 30em"));
            assertEquals("ok " + Math.round(40 * em) + "x" + Math.round(30 * em), out);
        }
    }

    @Test
    void resizeSaysSoWhenTheWindowWillNotGoThatSmall() {
        try (Gui gui = deterministic()) {
            Automation automation = new Automation(gui, new FakeWindow(800, 600, 400, 300));
            String out = driven(gui, x -> automation.command("resize 100x100"));
            assertTrue(out.startsWith("ok 400x300"), out);
            assertTrue(out.contains("asked for 100x100"), out);
        }
    }

    @Test
    void resizeRefusesNonsenseRatherThanGuessing() {
        try (Gui gui = deterministic()) {
            Automation automation = new Automation(gui, new FakeWindow(800, 600, 100, 100));
            assertTrue(automation.command("resize").startsWith("err"));
            assertTrue(automation.command("resize 800").startsWith("err"));
            assertTrue(automation.command("resize wide tall").startsWith("err"));
            assertTrue(automation.command("resize 0x0").startsWith("err"));
            assertTrue(automation.command("zoom -1").startsWith("err"));
            assertTrue(new Automation(gui).command("resize 800x600").startsWith("err there is no window"));
        }
    }

    @Test
    void sizeReportsEverythingTheVerbsCanChange() {
        try (Gui gui = deterministic()) {
            String out = new Automation(gui, new FakeWindow(800, 600, 100, 100)).command("size");
            assertTrue(out.startsWith("ok 800x600 zoom=1 dpi=1 em="), out);
        }
    }

    // --- windows ---------------------------------------------------------------------------------------------

    @Test
    void windowsListsWhatIsOpenAndMarksTheChosenOne() {
        try (Gui main = deterministic(); Gui prefs = deterministic()) {
            Windows windows = new Windows(() -> List.of(
                    new WindowView("main", main, new FakeWindow(800, 600, 1, 1), true),
                    new WindowView("prefs", prefs, new FakeWindow(400, 300, 1, 1), false)));
            String listing = windows.command("windows");
            assertTrue(listing.startsWith("ok 2\n1 main 800x600"), listing);
            assertTrue(listing.contains("2 prefs 400x300"), listing);
            assertTrue(listing.lines().filter(l -> l.startsWith("1 ")).findFirst().orElseThrow().endsWith(" *"),
                    "the main window is chosen until another is: " + listing);
        }
    }

    @Test
    void aCommandGoesToTheWindowThatWasChosen() {
        try (Gui main = deterministic(); Gui prefs = deterministic()) {
            Windows windows = new Windows(() -> List.of(
                    new WindowView("main", main, new FakeWindow(800, 600, 1, 1), true),
                    new WindowView("prefs", prefs, new FakeWindow(400, 300, 1, 1), false)));
            assertEquals("ok prefs", windows.command("window pre"));
            assertTrue(windows.command("size").startsWith("ok 400x300"), "the prefs window was chosen");
            assertEquals("ok main", windows.command("window 1"));
            assertTrue(windows.command("size").startsWith("ok 800x600"));
        }
    }

    @Test
    void theOtherWindowsViewIsLeftAlone() {
        try (Gui main = deterministic(); Gui prefs = deterministic()) {
            FakeWindow prefsWindow = new FakeWindow(400, 300, 1, 1);
            Windows windows = new Windows(() -> List.of(
                    new WindowView("main", main, new FakeWindow(800, 600, 1, 1), true),
                    new WindowView("prefs", prefs, prefsWindow, false)));
            windows.command("window prefs");
            String out = driven(prefs, x -> windows.command("zoom 2"));
            assertEquals("ok zoom=2", out);
            assertEquals(2f, prefs.zoom().value());
            assertEquals(1f, main.zoom().value(), "zoom in one window must not reach the other");
        }
    }

    @Test
    void anAmbiguousOrUnknownNameIsRefusedNotGuessed() {
        try (Gui a = deterministic(); Gui b = deterministic()) {
            Windows windows = new Windows(() -> List.of(
                    new WindowView("tool-a", a, WindowControls.NONE, true),
                    new WindowView("tool-b", b, WindowControls.NONE, false)));
            assertTrue(windows.command("window tool").startsWith("err"), "two names begin with it");
            assertTrue(windows.command("window nothing").startsWith("err"));
            assertTrue(windows.command("window 9").startsWith("err"));
        }
    }

    @Test
    void twoWindowsWithOneTitleAreToldApart() {
        try (Gui a = deterministic(); Gui b = deterministic()) {
            Windows windows = new Windows(() -> List.of(
                    new WindowView("main", a, WindowControls.NONE, true),
                    new WindowView("Inspector", b, WindowControls.NONE, false),
                    new WindowView("Inspector", a, WindowControls.NONE, false)));
            String listing = windows.command("windows");
            assertTrue(listing.contains(" Inspector ") && listing.contains(" Inspector#2 "), listing);
            assertEquals("ok Inspector#2", windows.command("window Inspector#2"));
        }
    }

    @Test
    void aWindowThatClosedIsReportedNotReplaced() {
        try (Gui main = deterministic(); Gui prefs = deterministic()) {
            List<WindowView> open = new java.util.concurrent.CopyOnWriteArrayList<>(List.of(
                    new WindowView("main", main, WindowControls.NONE, true),
                    new WindowView("prefs", prefs, WindowControls.NONE, false)));
            Windows windows = new Windows(() -> List.copyOf(open));
            windows.command("window prefs");
            open.remove(1);
            String out = windows.command("size");
            assertTrue(out.startsWith("err") && out.contains("closed"), out);
            assertEquals("ok main", windows.command("window main"));
            assertSame(main, open.get(0).gui());
        }
    }
}
