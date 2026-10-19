package dev.vexelray.gui.automation.window;

import dev.vexelray.gui.automation.Capability;
import dev.vexelray.gui.automation.Sockets;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowInstrument;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.Toggle;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * The control that switches a {@link Sockets}: one row per {@link Capability}, each its lamp, a switch, a port and
 * what it is doing now.
 *
 * <pre>
 * ·  Remote control   (o-)  [      ]  off; something was connected earlier
 * ●  Diagnostics      (-o)  [61184 ]  connected now (1), localhost:61184
 * </pre>
 *
 * <p><b>The lamp is the title bar's.</b> The same ring, fill and dot the plug button shows for that socket
 * ({@link AutomationWindow#lamp}), and the status says it in words, so the lamps are learnt here. A capability this
 * run does not offer has no switch or port, only its label and why.
 *
 * <p><b>The port says which socket.</b> Blank is a free port; the port the other capability is on joins its socket,
 * so both are served on one; any other port is a socket of its own. A port that cannot be bound puts the switch
 * back and says why on the row, and nothing that was listening stops.
 *
 * <p><b>It shows the sockets, not what was clicked.</b> Every row is redrawn from the {@link Sockets} after any
 * switch — this panel's, a launch flag's, another panel's — so the switch, the port and the status never disagree
 * with what is listening. A switch is not an edit, so it records no undo.
 *
 * <p>Every space is reserved: a status line that has something to say never moves the rows.
 */
public final class AutomationPanel implements AutoCloseable {

    private static final Length TEXT = Length.rem(0.8125f);
    private static final Length SMALL = Length.rem(0.75f);

    private static final String HINT = "Blank port: a free one. The other's port: one socket serving both.";

    private final Gui gui;
    private final Sockets sockets;
    private final Map<Capability, Row> rows = new EnumMap<>(Capability.class);
    private final Node footer;
    private final Node node;
    private final Runnable unwatch;

    public AutomationPanel(Gui gui, Sockets sockets) {
        this.gui = gui;
        this.sockets = sockets;
        Node table = gui.column().role("automation-sockets").width(Length.FILL).gap(Length.rem(0.6f));
        for (Capability c : Capability.values()) {
            Row row = new Row(c);
            rows.put(c, row);
            table.children(row.node);
        }
        Node hint = gui.text(HINT).width(Length.FILL).textSize(SMALL).textColor(Role.DIM);
        this.footer = gui.text("").role("automation-status").width(Length.FILL).height(Length.rem(2.6f))
                .textSize(SMALL).textColor(Role.DIM);
        this.node = gui.column().role("automation-panel").width(Length.FILL).height(Length.FILL)
                .padding(Length.rem(0.75f)).gap(Length.rem(0.6f))
                .children(table, hint, footer);
        this.unwatch = sockets.watch(s -> refresh());
        refresh();
    }

    /** The node to place in a layout. */
    public Node node() {
        return node;
    }

    // --- what a test reaches, as a person reaches it --------------------------------------------------------------

    Toggle toggle(Capability c) {
        return rows.get(c).toggle;
    }

    TextField port(Capability c) {
        return rows.get(c).port;
    }

    Node status(Capability c) {
        return rows.get(c).status;
    }

    Node footer() {
        return footer;
    }

    /** Stop following the sockets. They keep listening; this only stops being told. */
    @Override
    public void close() {
        unwatch.run();
    }

    /** Every row, and the footer, from what is listening now. */
    private void refresh() {
        rows.values().forEach(Row::show);
        StringBuilder say = new StringBuilder();
        int hand = sockets.port(Capability.CONTROL);
        int diagnostics = sockets.port(Capability.DIAGNOSTICS);
        int any = hand >= 0 ? hand : diagnostics;
        if (any >= 0) {
            say.append("Drive it with: ottermate --port ").append(any);
        }
        if (diagnostics >= 0 && !sibarum.probe.Probe.ON) {
            // The probe decides once, when it loads, so a recorder cannot be started here: say so where it is asked.
            say.append(say.isEmpty() ? "" : "\n")
                    .append("The recorder is off for this run: the probe is set at launch (-Dprobe=all, or --automation).");
        }
        footer.text(say.toString());
    }

    /** One capability's switch, port and status. */
    private final class Row {

        private final Capability capability;
        private final Toggle toggle;
        private final TextField port;
        private final Node status;
        private final Node lamp;
        private final Node node;
        /** The lamp's box, once laid out: a lamp is a picture, authored in pixels. */
        private volatile Rect box;
        /** Why the last switch on failed, until the next switch; {@code null} when it did not. */
        private volatile String failed;

        Row(Capability capability) {
            this.capability = capability;
            String word = capability.word();
            this.lamp = gui.box().role("automation-" + word + "-lamp").width(Length.rem(1f)).height(Length.rem(1f));
            gui.onResizeUi(lamp, computed -> {
                box = computed.rect();
                paintLamp();
            });
            this.toggle = new Toggle(gui, false).undoable(false).onChange(this::flipped);
            toggle.node().role("automation-" + word + "-switch");
            this.port = new TextField(gui, "").onSubmit(text -> {
                if (toggle.on()) {
                    flipped(true);     // Enter on a port that is on moves it there
                }
            });
            port.node().role("automation-" + word + "-port").width(Length.rem(5f));
            this.status = gui.text("off").role("automation-" + word + "-status").width(Length.FILL)
                    .wordWrap(false).textSize(TEXT).textColor(Role.DIM);
            Node label = gui.text(capability == Capability.CONTROL ? "Remote control" : "Diagnostics")
                    .width(Length.rem(8f)).wordWrap(false).textSize(TEXT);
            this.node = gui.row().role("automation-" + word).width(Length.FILL).gap(Length.rem(0.6f))
                    .alignItems(AlignItems.CENTER);
            if (sockets.available().contains(capability)) {
                node.children(lamp, label, toggle.node(), port.node(), status);
            } else {
                node.children(lamp, label, status);     // nothing to switch: only what it is, and why
            }
        }

        private void paintLamp() {
            Rect r = box;
            if (r == null) {
                return;
            }
            Theme theme = gui.theme();
            lamp.picture(WindowInstrument.lamp(r, AutomationWindow.lamp(sockets.state(capability)),
                    theme.color(capability == Capability.CONTROL ? AutomationWindow.CONTROL_LAMP
                            : AutomationWindow.DIAGNOSTICS_LAMP),
                    theme.color(AutomationWindow.USED_LAMP)));
        }

        /** On a worker thread, as every switch handler is. */
        private void flipped(boolean on) {
            failed = null;
            if (!on) {
                sockets.disable(capability);
                refresh();
                return;
            }
            String text = port.text().strip();
            try {
                sockets.enable(capability, text.isEmpty() ? 0 : Sockets.requested(text));
            } catch (NumberFormatException e) {
                failed = "'" + text + "' is not a port";
            } catch (IOException e) {
                failed = "port " + text + " could not be bound: " + e.getMessage();
            } catch (IllegalStateException e) {
                failed = e.getMessage();     // not offered in this run
            }
            refresh();
        }

        void show() {
            int bound = sockets.port(capability);
            toggle.show(bound >= 0);
            if (bound >= 0) {
                port.text(Integer.toString(bound));
            }
            status.text(bound < 0 && failed != null ? failed : AutomationWindow.say(sockets, capability));
            paintLamp();
        }
    }
}
