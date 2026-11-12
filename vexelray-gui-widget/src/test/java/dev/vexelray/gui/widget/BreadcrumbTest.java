package dev.vexelray.gui.widget;

import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A breadcrumb is a projection of the chain it is handed: it reaches every ancestor and never the current place. */
class BreadcrumbTest {

    @Test
    void everyAncestorIsReachableAndTheCurrentPlaceIsNot() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> went = new ArrayList<>();
            Breadcrumb<String> crumbs = new Breadcrumb<String>(h.gui, s -> s);
            h.gui.mailbox(Breadcrumb.NAVIGATED, crumbs.address(), n -> went.add((String) n.item()));
            h.gui.root().children(crumbs.node());
            crumbs.path(List.of("This PC", "Users", "james", "Downloads"));
            h.frame();

            assertEquals(3, crumbs.ancestors().size(), "three ancestors and a current place");
            h.focus(crumbs.ancestors().get(1).node());
            h.tap(Key.ENTER);
            assertEquals(List.of("Users"), went);
        }
    }

    @Test
    void aLongPathCollapsesFromTheLeftAndTheEllipsisGoesToTheNearestHiddenAncestor() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> went = new ArrayList<>();
            Breadcrumb<String> crumbs = new Breadcrumb<String>(h.gui, s -> s).maxSegments(3);
            h.gui.mailbox(Breadcrumb.NAVIGATED, crumbs.address(), n -> went.add((String) n.item()));
            h.gui.root().children(crumbs.node());
            crumbs.path(List.of("a", "b", "c", "d", "e"));
            h.frame();

            assertEquals(2, crumbs.ancestors().size(), "an ellipsis and two ancestors, then the current place");
            assertNotNull(crumbs.overflow(), "the ellipsis stands in for what is hidden");
            h.focus(crumbs.overflow().node());
            h.tap(Key.ENTER);
            assertEquals(List.of("b"), went, "the ellipsis stands for a and b, and goes to the nearer");
        }
    }

    /**
     * A breadcrumb built and laid out, then hidden in the very next frame, fades out like anything else. Its chevrons
     * are {@code IconView}s, which draw their icon once layout has given them a size, so that first drawing lands in the
     * drain after the one that built them: in the hide's. It is the icon finishing its own build, not a change of shape,
     * and does not make the panel go at once (Vexplore's top bar, as Two Folders opened just after a folder was listed).
     */
    @Test
    void aPathHiddenTheFrameAfterItWasBuiltStillFades() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.exitMotion(new dev.vexelray.gui.core.layout.ExitMotion() {
                @Override
                public boolean leave(dev.vexelray.gui.core.model.RetainedNode node) {
                    return true;   // takes everything, and never lets go
                }

                @Override
                public float presence(dev.vexelray.gui.core.model.RetainedNode node) {
                    return 1f;
                }

                @Override
                public void left(dev.vexelray.gui.core.model.RetainedNode node) {
                }
            });
            Breadcrumb<String> crumbs = new Breadcrumb<String>(h.gui, s -> s);
            dev.vexelray.gui.core.Node pill = h.gui.row().children(crumbs.node());
            h.gui.root().children(h.gui.column().children(pill));
            h.frame();

            crumbs.path(List.of("This PC", "Users", "james", "Downloads"));
            h.frame();
            pill.visible(false);
            h.frame();
            assertTrue(h.retained(pill).leaving, "fading out as it was drawn, not gone at once");
        }
    }

    @Test
    void aNewPathReplacesTheOldOneCompletely() {
        try (HeadlessGui h = new HeadlessGui()) {
            Breadcrumb<String> crumbs = new Breadcrumb<String>(h.gui, s -> s);
            h.gui.root().children(crumbs.node());
            crumbs.path(List.of("a", "b", "c"));
            crumbs.path(List.of("x", "y"));
            h.frame();
            assertEquals(1, crumbs.ancestors().size());
            assertEquals(List.of("x", "y"), crumbs.path());
        }
    }
}
