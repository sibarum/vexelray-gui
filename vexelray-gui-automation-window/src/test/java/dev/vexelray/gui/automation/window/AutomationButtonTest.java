package dev.vexelray.gui.automation.window;

import dev.vexelray.gui.automation.Capability;
import dev.vexelray.gui.automation.Commands;
import dev.vexelray.gui.automation.Sockets;
import dev.vexelray.gui.core.WindowInstrument.Lamp;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What the debug run's button says about real sockets: in its lamps, its tooltip and its menu's first lines. */
class AutomationButtonTest {

    private static final Commands VERBS = line -> "ok " + line;

    @Test
    void oneSocketServingBothSaysWhereAndThatItIsShared() throws Exception {
        try (Sockets sockets = new Sockets(VERBS, "button-test", "Button Test", null)) {
            int port = sockets.enable(Capability.CONTROL, 0);
            sockets.enable(Capability.DIAGNOSTICS, port);
            assertEquals("Remote control: open, nothing connected yet, localhost:" + port + " (one socket, both)\n"
                    + "Diagnostics: open, nothing connected yet, localhost:" + port + " (one socket, both)",
                    AutomationButton.describe(sockets));
        }
    }

    @Test
    void aConnectionShowsAndStaysAsHistory() throws Exception {
        try (Sockets sockets = new Sockets(VERBS, "button-test", "Button Test", null)) {
            int port = sockets.enable(Capability.CONTROL, 0);
            try (java.net.Socket client = new java.net.Socket(InetAddress.getLoopbackAddress(), port)) {
                awaitSaid(sockets, "connected now (1), localhost:" + port);
            }
            awaitSaid(sockets, "open; something was connected, none now, localhost:" + port);
            sockets.disable(Capability.CONTROL);
            assertEquals("off; something was connected earlier", AutomationButton.say(sockets, Capability.CONTROL));
            assertEquals("off", AutomationButton.say(sockets, Capability.DIAGNOSTICS));
        }
    }

    @Test
    void aCapabilityTheRunDoesNotOfferHasNoLamp() {
        try (Sockets sockets = new Sockets(VERBS, "button-test", "Button Test", null,
                java.util.Set.of(Capability.DIAGNOSTICS))) {
            assertEquals("not available in this run", AutomationButton.say(sockets, Capability.CONTROL));
            assertNull(AutomationButton.lamp(sockets.state(Capability.CONTROL)));
            assertEquals(Lamp.OFF, AutomationButton.lamp(sockets.state(Capability.DIAGNOSTICS)));
        }
    }

    private static void awaitSaid(Sockets sockets, String want) throws InterruptedException {
        long until = System.nanoTime() + 5_000_000_000L;
        while (!want.equals(AutomationButton.say(sockets, Capability.CONTROL)) && System.nanoTime() < until) {
            Thread.sleep(5);
        }
        assertEquals(want, AutomationButton.say(sockets, Capability.CONTROL));
    }
}
