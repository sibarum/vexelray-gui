package dev.vexelray.gui.widget;

import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A breadcrumb is a projection of the chain it is handed: it reaches every ancestor and never the current place. */
class BreadcrumbTest {

    @Test
    void everyAncestorIsReachableAndTheCurrentPlaceIsNot() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> went = new ArrayList<>();
            Breadcrumb<String> crumbs = new Breadcrumb<String>(h.gui, s -> s).onNavigate(went::add);
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
            Breadcrumb<String> crumbs = new Breadcrumb<String>(h.gui, s -> s).maxSegments(3).onNavigate(went::add);
            h.gui.root().children(crumbs.node());
            crumbs.path(List.of("a", "b", "c", "d", "e"));
            h.frame();

            assertEquals(3, crumbs.ancestors().size(), "an ellipsis and two ancestors, then the current place");
            h.focus(crumbs.ancestors().get(0).node());
            h.tap(Key.ENTER);
            assertEquals(List.of("b"), went, "the ellipsis stands for a and b, and goes to the nearer");
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
