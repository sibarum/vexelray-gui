package dev.vexelray.gui.harness;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The harness's contract, stated about the window it does <em>not</em> start with: a second window opened
 * during a run is created and never mapped, exactly like the first one.
 *
 * <p>The bug this exists against had the main window right and everything else wrong. A host handing over a
 * pre-made window says nothing about the popups, menus and dialogs the application opens later — those were
 * created straight from the platform and shown by the presenter, so a unit test run would flash real windows
 * onto the desktop mid-suite. That is not a cosmetic problem: a mapped window takes real focus and real
 * keyboard input, so a test asking where a keystroke was routed gets its answer from the window manager
 * rather than from the framework, and passes or fails on what the desktop happened to be doing. Meanwhile
 * {@code HarnessWindow.isVisible()} kept reporting false, so nothing in the run could even see it happen.
 *
 * <p>Needs a Vulkan device; this is an integration test, not a unit test. It fails to start rather than
 * silently proving nothing.
 */
class PopupsAreNeverMappedTest {

    @Test
    void aWindowOpenedDuringTheRunIsCreatedAndNeverShown() {
        Gui gui = new Gui();
        Gui popupGui = new Gui();
        popupGui.root().append(popupGui.box().size(Length.dp(120), Length.dp(80)));

        // The window the framework hands the application at creation. Asserting on it is half the point: the
        // application edge and the harness have to be looking at the same object, or a test that drives the
        // window it was given is driving something the harness does not control.
        AtomicReference<NativeWindow> created = new AtomicReference<>();

        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("harness", 400, 300))) {
            harness.app().requestWindow(WindowSpec.of(WindowConfig.of("popup", 240, 160), popupGui)
                    .onCreated(created::set));

            assertTrue(harness.await(() -> created.get() != null, 5_000),
                    "the popup never got as far as being created");

            List<HarnessWindow> windows = harness.windows();
            assertEquals(2, windows.size(),
                    "every window the application opens has to be made through the harness's factory, and "
                            + "only " + windows.size() + " went through it");
            HarnessWindow popup = windows.get(1);
            assertSame(popup, created.get(),
                    "the application was handed a window the harness never wrapped");

            // What keeps this from being vacuous. The presenter shows a window once its first frame is on the
            // GPU, so this says the popup got all the way to the point where it would have appeared — an
            // assertion about a window that never presented would stay green through any regression.
            assertTrue(harness.await(() -> popup.shows() > 0, 5_000),
                    "the popup never presented a frame, so nothing below has been proven");

            assertFalse(popup.isVisible(),
                    "the harness must not report a window of its own as being on screen");
            // The one the wrapper cannot fake: isVisible() above is false by construction and would say the
            // same thing about a window the OS had mapped and focused. This is the platform's own answer.
            assertFalse(popup.real().isVisible(),
                    "the OS window under the popup was mapped — it is on screen, holding real focus and real "
                            + "input, and every focus or keyboard assertion in this run is now measuring the "
                            + "window manager instead of the framework");
            assertFalse(harness.window().real().isVisible(),
                    "and the main window is still off screen, as it always was");
        }
    }
}
