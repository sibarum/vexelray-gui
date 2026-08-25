package dev.vexelray.gui.core.app;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A window's standing is the owner it is created with, and nothing else — which is why it is checked here, on
 * the config the host would hand the platform, rather than by opening windows and looking at the screen.
 */
class StandingTest {

    private static final WindowConfig CONFIG = WindowConfig.of("Test", 400, 300);
    private static final long MAIN = 0xABCDL;

    /**
     * The property this whole type exists for: a peer names no owner, so the window manager is free to put the
     * main window in front of it when the user focuses the main window. An owned window is pinned above its
     * owner for as long as it lives, and no amount of clicking gets past that.
     */
    @Test
    void aPeerIsNotOwnedByTheMainWindow() {
        assertEquals(0L, Standing.PEER.place(CONFIG, MAIN).owner(),
                "a peer stands beside the main window, not on top of it");
    }

    /** A satellite is pinned above the main window — what a modal, a palette, or a popup needs. */
    @Test
    void aSatelliteIsOwnedByTheMainWindow() {
        assertEquals(MAIN, Standing.SATELLITE.place(CONFIG, MAIN).owner());
    }

    /**
     * Standing decides ownership and touches nothing else: a config carrying restored bounds and application-
     * drawn chrome must arrive at the platform still carrying them, or a window reopens somewhere other than
     * where the user left it.
     */
    @Test
    void standingChangesOwnershipAndNothingElse() {
        WindowConfig placed = CONFIG.at(120, 240).decorations(dev.vexelray.os.Decorations.CLIENT);
        WindowConfig satellite = Standing.SATELLITE.place(placed, MAIN);
        assertEquals(placed, satellite.ownedBy(0L), "everything but the owner survives");
        assertSame(placed, Standing.PEER.place(placed, MAIN), "a peer's config is the config as requested");
    }

    /** The default, because most windows are windows: only the ones that say so are pinned above the main one. */
    @Test
    void aSpecStandsBesideTheMainWindowUnlessItSaysOtherwise() {
        try (Gui gui = new Gui(Atchung.create())) {
            assertSame(Standing.PEER, WindowSpec.of(CONFIG, gui).standing());
            assertSame(Standing.SATELLITE, WindowSpec.of(CONFIG, gui).standing(Standing.SATELLITE).standing());
        }
    }

    /**
     * Standing survives the lifecycle withers. It is set where the window is described and read where the window
     * is created, and a callback added in between must not quietly return it to the default.
     */
    @Test
    void standingSurvivesTheOtherWithers() {
        try (Gui gui = new Gui(Atchung.create())) {
            WindowSpec spec = WindowSpec.of(CONFIG, gui)
                    .standing(Standing.SATELLITE)
                    .onCreated(w -> { })
                    .onClosed(() -> { })
                    .onCloseRequest(r -> { });
            assertSame(Standing.SATELLITE, spec.standing());
        }
    }

    /** Passing null asks for the default rather than for a spec that throws when the window is created. */
    @Test
    void nullStandingIsThePeerDefault() {
        try (Gui gui = new Gui(Atchung.create())) {
            assertSame(Standing.PEER, WindowSpec.of(CONFIG, gui).standing(Standing.SATELLITE).standing(null)
                    .standing());
        }
    }
}
