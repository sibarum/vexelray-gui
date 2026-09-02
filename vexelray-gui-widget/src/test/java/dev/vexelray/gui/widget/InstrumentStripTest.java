package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.WindowInstrument;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.draw.Picture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The framework's own tools in the caption (docs/automation.md §7).
 *
 * <p>What is worth pinning here is not that a button draws. It is the three properties that make an instrument
 * strip a framework facility rather than a convenience: it is <b>absent</b> unless asked for, it sits
 * <b>between</b> the caption and the window controls rather than displacing them, and its click reaches the
 * <b>window</b> — so a bar in a popup commands the popup.
 */
class InstrumentStripTest {

    private static final float BUTTON_W = 46f;
    private static final float VIEW_W = 800f;

    /** A {@link WindowControls} that records what it was asked to do. */
    private static final class Recorder implements WindowControls {
        private final List<String> calls = new ArrayList<>();

        @Override public void minimize() {
            calls.add("minimize");
        }

        @Override public void toggleMaximize() {
            calls.add("toggleMaximize");
        }

        @Override public boolean maximized() {
            return false;
        }

        @Override public void close() {
            calls.add("close");
        }

        @Override public void capture(String path) {
            calls.add("capture " + path);
        }
    }

    private static WindowInstrument marker(String role, List<String> fired) {
        return new WindowInstrument(role, "", (box, ink) -> Picture.EMPTY, controls -> fired.add(role));
    }

    /** The centre of the nth 46dp button counting from the right edge of the bar. */
    private static float fromRight(int n) {
        return VIEW_W - (n + 0.5f) * BUTTON_W;
    }

    @Test
    void aBarHasNoInstrumentsUnlessAsked() {
        try (HeadlessGui h = new HeadlessGui()) {
            TitleBar bar = new TitleBar(h.gui, new Recorder(), "app");
            h.gui.root().children(bar.node());
            h.frame();

            // A shipped application must not find itself carrying tools it never asked for. Clicking where an
            // instrument would be has to reach the caption, which is to say: nothing here answers.
            assertFalse(h.gui.semanticSnapshot().nodes().values().stream()
                            .anyMatch(n -> n.role().startsWith("instrument-")),
                    "no instrument is present until the application opts in");
        }
    }

    @Test
    void instrumentsSitBetweenTheCaptionAndTheWindowControls() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> fired = new ArrayList<>();
            TitleBar bar = new TitleBar(h.gui, new Recorder(), "app")
                    .instruments(List.of(marker("instrument-one", fired)));
            h.gui.root().children(bar.node());
            h.frame();

            // Close, maximize and minimize keep the right edge — an instrument must not push the window controls
            // off the corner the user aims at without looking.
            h.click(fromRight(3), 16f);
            assertEquals(List.of("instrument-one"), fired,
                    "the fourth slot from the right is the instrument, so the three caption buttons are untouched");
        }
    }

    @Test
    void anInstrumentCommandsTheWindow() {
        try (HeadlessGui h = new HeadlessGui()) {
            Recorder rec = new Recorder();
            TitleBar bar = new TitleBar(h.gui, rec, "app")
                    .instruments(List.of(WindowInstrument.screenshot(() -> "shot.png")));
            h.gui.root().children(bar.node());
            h.frame();

            h.click(fromRight(3), 16f);
            assertEquals(List.of("capture shot.png"), rec.calls,
                    "the instrument asks the window it is in, not the application");
        }
    }

    @Test
    void theStripIsReplacedRatherThanAppended() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> fired = new ArrayList<>();
            TitleBar bar = new TitleBar(h.gui, new Recorder(), "app")
                    .instruments(List.of(marker("instrument-one", fired)));
            h.gui.root().children(bar.node());
            h.frame();

            bar.instruments(List.of(marker("instrument-two", fired)));
            h.frame();

            long count = h.gui.semanticSnapshot().nodes().values().stream()
                    .filter(n -> n.role().startsWith("instrument-"))
                    .count();
            assertEquals(1L, count, "which tools this window has needs one answer, not a history");

            h.click(fromRight(3), 16f);
            assertEquals(List.of("instrument-two"), fired);
        }
    }

    @Test
    void aBarInAnyWindowGetsAWorkingScreenshot() {
        try (HeadlessGui h = new HeadlessGui()) {
            // A bar built before its window, as every bar but the main one is.
            TitleBar bar = new TitleBar(h.gui, null, "popup")
                    .instruments(List.of(WindowInstrument.screenshot(() -> "popup.png")));
            h.gui.root().children(bar.node());
            h.frame();

            // What the host does when it opens the window. The bar must take these rather than mint its own
            // from the NativeWindow: a native window cannot photograph itself, so a bar that built its own got
            // a working minimize, maximize and close and a screenshot that silently did nothing — on every
            // window except the main one.
            Recorder rec = new Recorder();
            var spec = bar.commands(dev.vexelray.gui.core.app.WindowSpec.of(
                    dev.vexelray.os.WindowConfig.of("popup", 400, 300), h.gui));
            spec.onControls().accept(rec);

            h.click(fromRight(3), 16f);
            assertEquals(List.of("capture popup.png"), rec.calls,
                    "the instrument has to reach the window the host supplied, not a no-op");
        }
    }

    @Test
    void aMarkIsReauthoredForTheBoxItGot() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Rect> authored = new ArrayList<>();
            WindowInstrument tool = new WindowInstrument("instrument-mark", "", (box, ink) -> {
                authored.add(box);
                return Picture.EMPTY;
            }, controls -> { });

            TitleBar bar = new TitleBar(h.gui, new Recorder(), "app").instruments(List.of(tool));
            h.gui.root().children(bar.node());
            h.frame();
            h.frame();

            // A Picture resolves no units of its own, so a mark authored against anything but the box the layout
            // settled on is drawn at the wrong size the moment the window is zoomed.
            assertFalse(authored.isEmpty(), "the mark has to be authored against a real box");
            Rect box = authored.get(authored.size() - 1);
            assertTrue(box.w() > 0f && box.h() > 0f, "and a laid-out one: " + box);
        }
    }
}
