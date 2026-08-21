package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.HitTest;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dim a dialog casts over the window it blocked: it covers everything, it is drawn last, it moves nothing,
 * and it is not a pointer target — the block itself belongs to the window manager, not to a sheet of pixels.
 */
class ModalScrimTest {

    private static final float W = 400f;
    private static final float H = 300f;

    /** Fixed-size boxes only — no text metrics needed. */
    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    /** The laid-out node with {@code id}, or null. */
    private static RetainedNode find(RetainedNode n, long id) {
        if (n == null) {
            return null;
        }
        if (n.id == id) {
            return n;
        }
        for (RetainedNode child : n.children) {
            RetainedNode hit = find(child, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    @Test
    void theScrimCoversTheWholeViewportAndIsDrawnLast() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node page = gui.box().width(Length.FILL).height(Length.FILL);
            gui.root().children(page);
            ModalScrim scrim = ModalScrim.install(gui);
            scrim.dim(true, Color.rgba(0f, 0f, 0f, 0.45f));

            RetainedNode root = gui.frame(W, H, ModalScrimTest::noText);
            RetainedNode sheet = find(root, scrim.node().id());
            assertNotNull(sheet, "the scrim is in the tree");
            assertEquals(0f, sheet.x, 0.001f);
            assertEquals(0f, sheet.y, 0.001f);
            assertEquals(W, sheet.w, 0.001f, "vw(100): as wide as the window");
            assertEquals(H, sheet.h, 0.001f, "vh(100): as tall as the window");
            assertSame(root.children.get(root.children.size() - 1), sheet,
                    "last child of the root — which is the only thing 'over everything' can mean here");
        }
    }

    @Test
    void dimmingMovesNothingOnThePage() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node page = gui.box().width(Length.FILL).height(Length.FILL);
            gui.root().children(page);
            ModalScrim scrim = ModalScrim.install(gui);

            RetainedNode before = find(gui.frame(W, H, ModalScrimTest::noText), page.id());
            float x = before.x;
            float y = before.y;
            float w = before.w;
            float h = before.h;

            scrim.dim(true, Color.rgba(0f, 0f, 0f, 0.45f));
            RetainedNode after = find(gui.frame(W, H, ModalScrimTest::noText), page.id());
            assertEquals(x, after.x, 0.001f, "a scrim that reflowed the page would be worse than no scrim");
            assertEquals(y, after.y, 0.001f);
            assertEquals(w, after.w, 0.001f);
            assertEquals(h, after.h, 0.001f);
        }
    }

    @Test
    void theScrimIsNeverThePointerTarget() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node page = gui.box().width(Length.FILL).height(Length.FILL);
            gui.root().children(page);
            ModalScrim scrim = ModalScrim.install(gui);
            scrim.dim(true, Color.rgba(0f, 0f, 0f, 0.45f));

            RetainedNode root = gui.frame(W, H, ModalScrimTest::noText);
            RetainedNode hit = HitTest.at(root, W / 2, H / 2);
            assertNotNull(hit);
            assertEquals(page.id(), hit.id,
                    "hitInert: the dim is drawn, and the block is the window manager's — not a click-eater");
        }
    }

    @Test
    void itStartsHiddenAndUndimmingHidesItAgain() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.root().children(gui.box().width(Length.FILL).height(Length.FILL));
            ModalScrim scrim = ModalScrim.install(gui);

            RetainedNode root = gui.frame(W, H, ModalScrimTest::noText);
            assertFalse(present(root, scrim.node().id()), "installing a scrim does not dim anything");

            scrim.dim(true, Color.rgba(0f, 0f, 0f, 0.45f));
            assertTrue(present(gui.frame(W, H, ModalScrimTest::noText), scrim.node().id()));

            scrim.dim(false, Color.rgba(0f, 0f, 0f, 0.45f));
            assertFalse(present(gui.frame(W, H, ModalScrimTest::noText), scrim.node().id()),
                    "and the window comes back undimmed when the dialog goes");
        }
    }

    @Test
    void aNullDimColourDrawsNothingAtAll() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.root().children(gui.box().width(Length.FILL).height(Length.FILL));
            ModalScrim scrim = ModalScrim.install(gui);

            scrim.dim(true, null);
            assertFalse(present(gui.frame(W, H, ModalScrimTest::noText), scrim.node().id()),
                    "an application that turned dimming off gets modality without the sheet");
        }
    }

    /** Whether the node with {@code id} is drawn this frame — in the tree, and visible. */
    private static boolean present(RetainedNode root, long id) {
        RetainedNode n = find(root, id);
        return n != null && n.visible();
    }
}
