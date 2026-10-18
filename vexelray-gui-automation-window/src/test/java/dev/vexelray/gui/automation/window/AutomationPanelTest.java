package dev.vexelray.gui.automation.window;

import dev.vexelray.gui.automation.Capability;
import dev.vexelray.gui.automation.Commands;
import dev.vexelray.gui.automation.Sockets;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.text.TextLayout;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The switches, flipped as a person flips them, against real sockets. */
class AutomationPanelTest {

    static {
        Atchung.onFatal(sibarum.atchung.Fatal.THROW);
    }

    private static final TextMeasurer CELLS = new TextMeasurer() {
        @Override
        public float intrinsic(RetainedNode node, Axis axis, float px) {
            String s = node.textString() == null ? "" : node.textString();
            return axis == Axis.HORIZONTAL ? s.length() * 10f : 10f;
        }

        @Override
        public int offsetAt(String text, float localX, float px) {
            return 0;
        }

        @Override
        public float[] caretAdvances(String text, float px) {
            float[] xs = new float[(text == null ? 0 : text.length()) + 1];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = i * 10f;
            }
            return xs;
        }

        @Override
        public List<TextLayout.LineSpan> lineSpans(String text, float wrapWidth, float px) {
            return List.of(new TextLayout.LineSpan(0, text == null ? 0 : text.length(), true));
        }
    };

    private static final Commands VERBS = line -> "ok " + line;

    @Test
    void aSwitchFlippedOnListensAndTheRowSaysWhere() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run);
             Sockets sockets = new Sockets(VERBS, "panel-test", "Panel Test", null);
             AutomationPanel panel = new AutomationPanel(gui, sockets)) {
            gui.root().children(panel.node());

            panel.toggle(Capability.CONTROL).on(true);
            int port = sockets.port(Capability.CONTROL);
            assertTrue(port > 0, "a blank port is a free one");
            assertEquals(Integer.toString(port), panel.port(Capability.CONTROL).text(), "the field shows what was bound");
            assertEquals("open, nothing connected yet, localhost:" + port, text(gui, panel.status(Capability.CONTROL)));
            assertTrue(text(gui, panel.footer()).startsWith("Drive it with: ottermate --port " + port));

            // The other's port in the field: one socket serving both.
            panel.port(Capability.DIAGNOSTICS).text(Integer.toString(port));
            panel.toggle(Capability.DIAGNOSTICS).on(true);
            assertEquals(port, sockets.port(Capability.DIAGNOSTICS));
            assertEquals("open, nothing connected yet, localhost:" + port + " (one socket, both)",
                    text(gui, panel.status(Capability.DIAGNOSTICS)));

            panel.toggle(Capability.CONTROL).on(false);
            assertFalse(sockets.on(Capability.CONTROL));
            assertEquals("off", text(gui, panel.status(Capability.CONTROL)));
            assertEquals("open, nothing connected yet, localhost:" + port, text(gui, panel.status(Capability.DIAGNOSTICS)),
                    "narrowed, not closed");
        }
    }

    @Test
    void aPortThatCannotBeBoundPutsTheSwitchBackAndSaysWhy() throws Exception {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run);
             Sockets sockets = new Sockets(VERBS, "panel-test", "Panel Test", null);
             AutomationPanel panel = new AutomationPanel(gui, sockets);
             ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            gui.root().children(panel.node());

            panel.port(Capability.DIAGNOSTICS).text(Integer.toString(taken.getLocalPort()));
            panel.toggle(Capability.DIAGNOSTICS).on(true);
            assertFalse(sockets.on(Capability.DIAGNOSTICS));
            assertFalse(panel.toggle(Capability.DIAGNOSTICS).on(), "the switch shows what listens, which is nothing");
            assertTrue(text(gui, panel.status(Capability.DIAGNOSTICS))
                    .startsWith("port " + taken.getLocalPort() + " could not be bound"));

            panel.port(Capability.DIAGNOSTICS).text("wednesday");
            panel.toggle(Capability.DIAGNOSTICS).on(true);
            assertEquals("'wednesday' is not a port", text(gui, panel.status(Capability.DIAGNOSTICS)));
        }
    }

    @Test
    void aSocketSwitchedElsewhereShowsHereToo() throws Exception {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run);
             Sockets sockets = new Sockets(VERBS, "panel-test", "Panel Test", null);
             AutomationPanel panel = new AutomationPanel(gui, sockets)) {
            gui.root().children(panel.node());
            int port = sockets.enable(Capability.CONTROL, 0);      // a launch flag, or another panel
            assertTrue(panel.toggle(Capability.CONTROL).on());
            assertEquals("open, nothing connected yet, localhost:" + port, text(gui, panel.status(Capability.CONTROL)));
        }
    }

    @Test
    void aConnectionShowsOnTheRowAndStaysAsHistory() throws Exception {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run);
             Sockets sockets = new Sockets(VERBS, "panel-test", "Panel Test", null);
             AutomationPanel panel = new AutomationPanel(gui, sockets)) {
            gui.root().children(panel.node());
            int port = sockets.enable(Capability.CONTROL, 0);
            try (java.net.Socket client = new java.net.Socket(InetAddress.getLoopbackAddress(), port)) {
                awaitText(gui, panel.status(Capability.CONTROL), "connected now (1), localhost:" + port);
                assertTrue(AutomationWindow.describe(sockets).startsWith("Remote control: connected now (1)"));
            }
            awaitText(gui, panel.status(Capability.CONTROL), "open; something was connected, none now, localhost:" + port);
            panel.toggle(Capability.CONTROL).on(false);
            assertEquals("off; something was connected earlier", text(gui, panel.status(Capability.CONTROL)));
            assertEquals("off", text(gui, panel.status(Capability.DIAGNOSTICS)));
        }
    }

    @Test
    void aCapabilityTheRunDoesNotOfferSaysSoAndCannotBeSwitched() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run);
             Sockets sockets = new Sockets(VERBS, "panel-test", "Panel Test", null,
                     java.util.Set.of(Capability.DIAGNOSTICS));
             AutomationPanel panel = new AutomationPanel(gui, sockets)) {
            gui.root().children(panel.node());
            assertEquals("not available in this run", text(gui, panel.status(Capability.CONTROL)));
            panel.toggle(Capability.CONTROL).on(true);       // not on screen; reached anyway, it still refuses
            assertFalse(sockets.on(Capability.CONTROL));
            assertFalse(panel.toggle(Capability.CONTROL).on());
        }
    }

    private static void awaitText(Gui gui, Node node, String want) throws InterruptedException {
        long until = System.nanoTime() + 5_000_000_000L;
        while (!want.equals(text(gui, node)) && System.nanoTime() < until) {
            Thread.sleep(5);
        }
        assertEquals(want, text(gui, node));
    }

    private static String text(Gui gui, Node node) {
        RetainedNode n = find(gui.frame(800f, 600f, CELLS), node.id());
        return n == null ? null : n.textString();
    }

    private static RetainedNode find(RetainedNode n, long id) {
        if (n == null || n.id == id) {
            return n;
        }
        for (RetainedNode c : n.children) {
            RetainedNode hit = find(c, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }
}
