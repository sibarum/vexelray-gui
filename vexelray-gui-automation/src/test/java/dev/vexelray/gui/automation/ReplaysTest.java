package dev.vexelray.gui.automation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaysTest {

    @AfterEach
    void forget() {
        Replays.clear();
    }

    @Test
    void replayIsADiagnosticAnsweredByWhatTheProgramDeclaredBeforeAnyWindowIs() {
        Windows windows = new Windows(List::of);
        assertEquals(Capability.DIAGNOSTICS, windows.needs("replay"));
        assertFalse(windows.drives("replay"));
        assertTrue(windows.command("replay").startsWith("err replay: this program declares no replay"));

        Replays.declare(() -> "ok replay ticks=3 sends=5 differ=0");
        assertEquals("ok replay ticks=3 sends=5 differ=0", windows.command("REPLAY"));
    }
}
