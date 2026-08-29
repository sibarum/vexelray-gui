package dev.vexelray.gui.demo;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Cues;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * What a {@link Chapter} is handed: the GUI it builds into, the clock, the shared cue player, the activity rail,
 * and a way to ask for the window once there is one.
 *
 * <p>That last part is the only awkward shape here, and it is awkward for a real reason. The tree is built before
 * the window exists — it has to be, since the window is created against a {@link Gui} that already has a root —
 * so a chapter that wants to open a popup, raise a dialog or put a native file picker over the window cannot
 * simply be given a {@link GuiApp}. It registers what it would do, and {@link #started} runs it when the window
 * is there. A chapter never checks for null, and nothing runs too early.
 */
public final class Stage {

    private final Gui gui;
    private final KronoGui krono;
    private final Cues cues;
    private final Console console;
    private final List<Consumer<GuiApp>> pending = new CopyOnWriteArrayList<>();

    private volatile GuiApp app;

    Stage(Gui gui, KronoGui krono, Cues cues, Console console) {
        this.gui = gui;
        this.krono = krono;
        this.cues = cues;
        this.console = console;
    }

    public Gui gui() {
        return gui;
    }

    public Theme theme() {
        return gui.theme();
    }

    /** The frame clock. Every piece of motion in the gallery is a {@code ramp} taken from here. */
    public KronoGui krono() {
        return krono;
    }

    /** One cue player for the whole gallery: a cue is per-node, so chapters can share the player safely. */
    public Cues cues() {
        return cues;
    }

    /** The activity rail on the right of the window. */
    public Console console() {
        return console;
    }

    /**
     * Run {@code work} against the application window — now if it exists, otherwise the moment it does.
     *
     * <p>Registering is not the same as doing: a chapter typically uses this to attach handlers that will open
     * windows later, not to open one at build time.
     */
    public void onApp(Consumer<GuiApp> work) {
        GuiApp now = app;
        if (now != null) {
            work.accept(now);
            return;
        }
        pending.add(work);
    }

    /** Called once by {@code Demo} when the window is up; drains everything a chapter asked for. */
    void started(GuiApp started) {
        this.app = started;
        for (Consumer<GuiApp> work : pending) {
            work.accept(started);
        }
        pending.clear();
    }
}
