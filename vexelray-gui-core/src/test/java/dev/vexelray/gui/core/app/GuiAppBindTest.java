package dev.vexelray.gui.core.app;

import dev.vexelray.diag.Diagnostics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture path substitutes a placeholder for an image it cannot bind, and now says so. This watches the
 * warning fire — a diagnostic nobody has seen work is the same kind of thing as the bug it reports.
 */
class GuiAppBindTest {

    @BeforeEach
    void silence() {
        Diagnostics.reset();
    }

    @Test
    void anImageFromThisDeviceBindsAndSaysNothing() {
        Object device = new Object();
        assertTrue(GuiApp.bindable(device, device));
        assertEquals(List.of(), Diagnostics.recorded(),
                "the ordinary case is every frame; a diagnostic here would be noise");
    }

    @Test
    void anImageFromAnotherDeviceIsRefusedAndReported() {
        assertFalse(GuiApp.bindable(new Object(), new Object()));
        List<String> recorded = Diagnostics.recorded();
        assertEquals(1, recorded.size(), "the drop must be reported, not merely handled");
        assertTrue(recorded.get(0).contains("another Vulkan device"));
        assertTrue(recorded.get(0).contains("placeholder"),
                "and must say what was drawn instead, since that is what the reader is looking at");
    }

    @Test
    void aCaptureHoldingSeveralForeignImagesReportsOneProblem() {
        Object frame = new Object();
        for (int i = 0; i < 3; i++) {
            GuiApp.bindable(new Object(), frame);
        }
        assertEquals(1, Diagnostics.recorded().size(),
                "three viewports in one capture are one thing wrong with it, not three");
    }
}
