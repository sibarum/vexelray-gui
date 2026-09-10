package dev.vexelray.gui.harness;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Modals;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Modals} draws in the application's theme, not in the one its own tree was born with.
 *
 * <p>The defect this exists against: the dialogs build a {@link Gui} of their own, a fresh {@code Gui} is
 * {@link Theme#DARK}, and there was no way to say otherwise — so every dialog resolved its page, its message
 * and its buttons against a theme that was not the application's. Invisible in an application whose theme
 * <em>is</em> dark, which is why it survived as long as it did, and glaring in one whose theme is not. It went
 * from one application's defect to everyone's when the framework began installing these dialogs for every
 * application it runs.
 *
 * <p><b>Why this is a harness test and not a unit test.</b> {@code Modals.install} takes a {@code GuiApp},
 * because a dialog is a real OS window; there is no way to reach the seam without an application to install it
 * on. The harness supplies exactly that — a genuine frame loop over windows that are created and never mapped —
 * so a dialog can be put up and asked what it is wearing without one flashing onto the desktop mid-suite.
 *
 * <p>It is also the test that could not be written until {@link HarnessApp} owned its application on one
 * thread. A dialog makes itself modal, disabling every other window, and that was enough to stop a harness
 * that built its first window on one thread and the rest on another from being able to take them down again.
 *
 * <p>Needs a Vulkan device; this is an integration test, not a unit test. It fails to start rather than
 * silently proving nothing.
 */
class DialogsWearTheApplicationsLookTest {

    /**
     * The look reaches the dialogs' own tree, and a dialog put up afterwards is on that tree.
     *
     * <p>Two halves, and the second is the one that would go unnoticed: it is not enough that the seam is
     * called with <em>a</em> {@code Gui}, it has to be called with the tree the dialogs are actually built on.
     * A seam handed a throwaway would satisfy the first assertion and change nothing on screen.
     */
    @Test
    void theLookIsAppliedToTheTreeTheDialogsAreBuiltOn() {
        Gui gui = new Gui().theme(Theme.LIGHT);
        AtomicReference<Gui> dialogTree = new AtomicReference<>();

        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("harness", 400, 300));
             Modals modals = Modals.install(harness.app(), dialog -> {
                 dialogTree.set(dialog);
                 dialog.theme(gui.theme());
             })) {

            Gui dialogs = dialogTree.get();
            assertNotNull(dialogs, "install never handed the appearance seam anything to apply the look to");
            assertNotSame(gui, dialogs,
                    "the dialogs own a tree of their own — that is why the look has to be handed in at all");
            assertSame(Theme.LIGHT, dialogs.theme(),
                    "the dialogs' tree is still on the theme it was born with, so every dialog draws in it");

            // And the tree that was themed is the tree a dialog goes up on. Awaited rather than asserted
            // straight away because presenting a window is the loop's work, not this thread's.
            modals.present(Modal.of("Delete file", "This cannot be undone.")
                    .defaultButton("Delete", () -> { }));
            assertTrue(harness.await(() -> harness.windows().size() == 2, 5_000),
                    "the dialog never got as far as being given a window");
            assertTrue(modals.showing(), "the dialog has a window but the queue does not think it is up");
            assertSame(Theme.LIGHT, dialogTree.get().theme(),
                    "building a dialog put the tree back on the default theme");

            // Never on screen, like every other window the harness makes. A dialog is modal, so one that
            // slipped through would take the desktop's focus and the rest of the suite's keystrokes with it.
            assertFalse(harness.windows().get(1).real().isVisible(),
                    "the dialog window was mapped, so every later input assertion is the desktop's answer");
        }
    }

    /**
     * No appearance, no crash: the one-argument install is the library default look, deliberately. An
     * application that has made no decision about how it looks has none to pass on, and that is a different
     * thing from an application whose decision never arrived.
     */
    @Test
    void installingWithoutALookLeavesTheDialogsOnTheDefault() {
        Gui gui = new Gui();
        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("harness", 400, 300));
             Modals modals = Modals.install(harness.app())) {
            modals.present(Modal.of("Ready", "Nothing to decide.").defaultButton("OK", () -> { }));
            assertTrue(harness.await(() -> harness.windows().size() == 2, 5_000),
                    "a dialog installed without a look never got a window either");
            assertSame(Theme.DARK, gui.theme(), "and the application's own tree was left alone");
        }
    }
}
