package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.nav.Address;
import dev.vexelray.gui.core.nav.Navigation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Navigating to a landmark: the containers between here and there un-conceal it, in order, and the arrival means
 * the user can see it.
 *
 * <p>The assertions that matter are about the <em>route being derived</em>. Nothing here tells navigation that
 * the target is on the second tab or inside a collapsed branch; the panel and the tree each declared how they
 * un-hide something, and the walk found the rest. A container that concealed a landmark and declared nothing is
 * the failure case, and it fails loudly rather than arriving at something invisible.
 */
class NavigationTest {

    /** Step frames until the navigation finishes, so a test reads as "go there, then look". */
    private static Node arrive(HeadlessGui h, Navigation nav) {
        for (int i = 0; i < 32 && !nav.done(); i++) {
            h.frame();
        }
        try {
            return nav.arrival().get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new AssertionError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void aLandmarkOnAHiddenTabIsRevealedByTheTabPanel() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tabs tabs = new Tabs(h.gui);
            Node first = h.gui.column().width(Length.FILL).height(Length.FILL);
            Node second = h.gui.column().width(Length.FILL).height(Length.FILL);
            Node buried = h.gui.text("accent").width(Length.em(6)).height(Length.em(2));
            second.append(buried);
            tabs.add("General", first);
            tabs.add("Theme", second);
            h.gui.root().append(tabs.node());
            h.gui.landmark("prefs.theme.accent", buried);
            h.frame();

            assertEquals(0, tabs.selected(), "the panel starts on its first tab");
            assertFalse(h.retained(second).visible(), "and the landmark is on the page that is hidden");

            Node arrived = arrive(h, h.gui.navigate("prefs.theme.accent"));

            assertEquals(buried.id(), arrived.id(), "the navigation arrives at the node the name was bound to");
            assertEquals(1, tabs.selected(), "and it got there by selecting the tab, not by unhiding a page");
            assertTrue(h.retained(second).visible(), "so the landmark is actually on screen");
        }
    }

    @Test
    void aLandmarkNobodyCanRevealFailsRatherThanArrivingAtSomethingInvisible() {
        try (HeadlessGui h = new HeadlessGui()) {
            // A container that hides a child and declares no Reveal — the case the invariant exists to catch.
            Node drawer = h.gui.column().width(Length.FILL).visible(false);
            Node inside = h.gui.text("hidden").width(Length.em(4)).height(Length.em(2));
            drawer.append(inside);
            h.gui.root().append(drawer);
            h.gui.landmark("drawer.item", inside);
            h.frame();

            Navigation nav = h.gui.navigate("drawer.item");
            RuntimeException failure = assertThrows(RuntimeException.class, () -> arrive(h, nav));

            assertInstanceOf(Navigation.Unreachable.class, failure,
                    "an unreachable landmark is a reported failure, not a silent arrival: " + failure);
            assertTrue(failure.getMessage().contains("drawer.item"), failure.getMessage());
        }
    }

    @Test
    void anUnknownLandmarkGivesUpRatherThanWaitingForever() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.frame();
            Navigation nav = h.gui.navigate("nothing.here");
            for (int i = 0; i < 300 && !nav.done(); i++) {
                h.frame();
            }
            assertTrue(nav.done(), "a name nothing is bound to must not leave a navigation running for ever");
            assertThrows(ExecutionException.class, () -> nav.arrival().get());
        }
    }

    @Test
    void aLandmarkLeavesWithItsNode() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node box = h.gui.box().size(Length.em(4), Length.em(2));
            h.gui.root().append(box);
            h.gui.landmark("gone", box);
            h.frame();
            assertTrue(h.gui.landmarkNode("gone").isPresent(), "bound while the node is in the tree");

            box.remove();
            h.frame();

            assertTrue(h.gui.landmarkNode("gone").isEmpty(),
                    "a name for a node that no longer exists is a name for nothing");
        }
    }

    @Test
    void anAddressNamesAWindowAndALandmark() {
        Address a = Address.parse("prefs/theme.accent");
        assertEquals("prefs", a.window());
        assertEquals("theme.accent", a.landmark());
        assertEquals("prefs/theme.accent", a.toString());

        Address bare = Address.parse("theme.accent");
        assertFalse(bare.windowNamed(), "a bare name means whichever window it reaches");
        assertEquals("prefs", bare.inWindow("prefs").window(), "and binds to one when it is routed");
        assertEquals("prefs", bare.inWindow("prefs").inWindow("other").window(),
                "binding an address that already names a window changes nothing");
    }

    @Test
    void navigatingToAnotherWindowIsPublishedRatherThanWalkedHere() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.windowKey("main");
            Node box = h.gui.box().size(Length.em(4), Length.em(2));
            h.gui.root().append(box);
            h.gui.landmark("theme.accent", box);
            h.frame();

            Navigation nav = h.gui.navigate(Address.of("prefs", "theme.accent"));

            assertTrue(nav.done(), "a destination in someone else's window is handed to the bus, not walked here");
            assertNotNull(assertThrows(ExecutionException.class, () -> nav.arrival().get()).getCause());
        }
    }
}
