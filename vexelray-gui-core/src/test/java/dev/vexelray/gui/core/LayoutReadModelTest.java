package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layout read-model (docs/layout-read-model.md): after a frame, a {@link Node} reports its own computed box
 * through {@link Node#layout()}, read lock-free off the published snapshot.
 */
class LayoutReadModelTest {

    // A trivial measurer — this test uses fixed-size boxes, so no text metrics are needed.
    private static float noText(dev.vexelray.gui.core.model.RetainedNode n,
                                dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    @Test
    void nodeReportsItsComputedBoxAfterLayout() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node a = gui.box().width(Length.rem(2)).height(Length.rem(2)); // 32x32 at 1rem = 16px
            gui.root().children(a);

            assertFalse(a.layout().present(), "no computed layout before the first frame");

            gui.frame(200f, 100f, LayoutReadModelTest::noText);

            NodeLayout la = a.layout();
            assertTrue(la.present(), "laid out after a frame");
            assertEquals(32f, la.rect().w(), 0.5f);
            assertEquals(32f, la.rect().h(), 0.5f);
            assertEquals(0f, la.rect().x(), 0.5f);
            assertEquals(0f, la.rect().y(), 0.5f);

            assertEquals(200f, gui.root().layout().rect().w(), 0.5f, "root fills the viewport width");
            assertEquals(100f, gui.root().layout().rect().h(), 0.5f);

            assertTrue(gui.layoutSnapshot().version() >= 1, "the snapshot is versioned per published frame");
        }
    }

    @Test
    void snapshotIsPublishedAsBusState() {
        try (Gui gui = new Gui(Atchung.create())) {
            long[] seen = {0};
            gui.layout().onCommit(s -> seen[0] = s.version());

            Node a = gui.box().width(Length.rem(1)).height(Length.rem(1));
            gui.root().children(a);
            gui.frame(50f, 50f, LayoutReadModelTest::noText);

            assertTrue(seen[0] >= 1, "observers receive the computed-layout snapshot via the State");
        }
    }

    /**
     * A resize relays out even with a clean tree, so it must also re-run the compute phase and republish — the
     * geometry moved without any mutation to mark it dirty (docs/layout-read-model.md §2.3).
     */
    @Test
    void resizingACleanTreeRepublishesTheSnapshot() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node a = gui.box().width(Length.percent(50)).height(Length.rem(1));
            gui.root().children(a);
            gui.frame(200f, 100f, LayoutReadModelTest::noText);

            long v1 = gui.layoutSnapshot().version();
            assertEquals(100f, a.layout().rect().w(), 0.5f, "50% of a 200px viewport");

            gui.frame(400f, 100f, LayoutReadModelTest::noText);   // resize only — no mutation, tree is clean

            assertTrue(gui.layoutSnapshot().version() > v1, "a resize republishes the read-model");
            assertEquals(200f, a.layout().rect().w(), 0.5f, "and the republished box reflects the new viewport");
        }
    }

    /** A static frame publishes nothing: the coalesced State still commits only on change. */
    @Test
    void staticFramePublishesNothing() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.root().children(gui.box().width(Length.rem(1)).height(Length.rem(1)));
            gui.frame(200f, 100f, LayoutReadModelTest::noText);

            long v = gui.layoutSnapshot().version();
            gui.frame(200f, 100f, LayoutReadModelTest::noText);

            assertEquals(v, gui.layoutSnapshot().version(), "nothing changed, so nothing is republished");
        }
    }
}
