package dev.vexelray.gui.core.app;

import dev.vexelray.gui.core.Gui;

import java.nio.file.Path;

/**
 * The graphics choices a user has made for every VexelRay application at once, kept in the suite's own store:
 * {@code ~/.vexelray/settings.properties} ({@link AppHome#of AppHome.of("vexelray")}, which {@code -Dvexelray.home}
 * moves). The same keys a tree reads from an application's own settings ({@link Gui#graphics}), so a user who copies
 * a line from one file to the other gets what they expect; read here, they are the suite's voice
 * ({@link Gui#suiteGraphics}), which an application's own code or settings still speak over.
 *
 * <p>Written by the slow-frames offer ({@link SlowFrames}): when simplified graphics made a struggling machine fast
 * enough, it is saved here, so the next application launched on this machine starts with it, and when the user says
 * never to ask, that is saved here too.
 *
 * <p>Every write reads the file afresh first: several applications share it, and one that saved from what it read at
 * startup would undo whatever another wrote in between.
 */
public final class SuiteGraphics {

    /** The suite's application name: its home is {@code ~/.vexelray}. */
    public static final String SUITE = "vexelray";

    /** Set to {@code never} when the user asked not to be offered simplified graphics again. */
    public static final String OFFER = Gui.SIMPLIFIED_GRAPHICS + ".offer";

    private final Path file;
    private final Boolean flatShading;
    private final Boolean reducedMotion;
    private final boolean offerRefused;

    private SuiteGraphics(Path file) {
        this.file = file;
        Settings s = Settings.at(file);
        Boolean preset = s.has(Gui.SIMPLIFIED_GRAPHICS) ? s.getBoolean(Gui.SIMPLIFIED_GRAPHICS, false) : null;
        this.flatShading = s.has(Gui.FLAT_SHADING) ? Boolean.valueOf(s.getBoolean(Gui.FLAT_SHADING, false)) : preset;
        this.reducedMotion = s.has(Gui.REDUCED_MOTION)
                ? Boolean.valueOf(s.getBoolean(Gui.REDUCED_MOTION, false)) : preset;
        this.offerRefused = "never".equals(s.getString(OFFER, ""));
    }

    /** The suite's store as it is on disk now. */
    public static SuiteGraphics read() {
        return at(AppHome.of(SUITE).file("settings.properties"));
    }

    /** A store at {@code file}: a test's, or a deployment that keeps the suite's files elsewhere. */
    public static SuiteGraphics at(Path file) {
        return new SuiteGraphics(file);
    }

    /** The same store read again, as it is on disk now. */
    public SuiteGraphics reread() {
        return at(file);
    }

    /** The user's suite-wide choice of flat shading; null when they have not made one. */
    public Boolean flatShading() {
        return flatShading;
    }

    /** The user's suite-wide choice of reduced motion; null when they have not made one. */
    public Boolean reducedMotion() {
        return reducedMotion;
    }

    /** Whether the user asked never to be offered simplified graphics again. */
    public boolean offerRefused() {
        return offerRefused;
    }

    /** Tell {@code gui} what the user chose for every application. */
    public void applyTo(Gui gui) {
        gui.suiteGraphics(flatShading, reducedMotion);
    }

    /** Save simplified graphics as the user's choice for every application. */
    public void saveSimplified() {
        Settings s = Settings.at(file);
        s.putBoolean(Gui.SIMPLIFIED_GRAPHICS, true);
        s.remove(Gui.FLAT_SHADING);     // the preset speaks for both: a leftover key for one would talk over it
        s.remove(Gui.REDUCED_MOTION);
        s.save();
    }

    /** Save that the user does not want simplified graphics offered again, in any application. */
    public void refuseOffers() {
        Settings.at(file).putString(OFFER, "never").save();
    }
}
