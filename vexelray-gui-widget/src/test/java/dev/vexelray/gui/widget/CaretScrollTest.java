package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.text.TextMetrics;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Caret-follow scroll belongs to the computed-geometry phase, not to the renderer (docs/layout-read-model.md).
 *
 * <p>Today it lives in {@code TreeRenderer.updateHScroll}, which only ever runs from {@code GuiApp} — so a field
 * that overflows scrolls when a Vulkan renderer is attached and does not scroll headless or on a remote client.
 * These tests assert the behaviour that must hold in <em>every</em> host, and therefore fail until scroll is
 * resolved where the rest of the derived geometry is.
 *
 * <p>Geometry (monospace {@value HeadlessGui#CELL}px stub, 800px headless viewport): the field is {@code vw(10)} =
 * 80px wide at x=0, {@code pad = min(PAD_X, w*0.25) = 10}, so the text origin is x=10 and the visible band is
 * x∈[10,70]. Twenty characters measure 200px — far past the right edge — so the caret at the end is only reachable
 * with a resolved scroll of 140px.
 */
class CaretScrollTest {

    private static final String TWENTY = "abcdefghijklmnopqrst";

    /** Build a focused, 80px-wide field and type {@link #TWENTY} into it, leaving the caret at the end. */
    private static TextField overflowingField(HeadlessGui h) {
        TextField f = new TextField(h.gui, "");
        f.node().width(Length.vw(10));          // 10% of the 800px headless viewport = 80px
        h.gui.root().children(f.node());
        h.frame();
        h.focus(f.node());
        h.type(TWENTY);
        h.frame();                               // publish the read-model for the final edit
        return f;
    }

    @Test
    void caretStaysVisibleWhenTextOverflowsTheField() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = overflowingField(h);

            NodeLayout L = f.node().layout();
            assertTrue(L.present());
            TextMetrics T = L.text();
            assertNotNull(T);

            // The weakest defensible claim: an editable field scrolls to keep its caret inside its own box. This
            // is what makes the field usable at all, and it must not depend on which host is drawing it.
            float right = L.rect().x() + L.rect().w();
            assertTrue(T.caretX(TWENTY.length()) <= right,
                    "caret must be inside the field, but sits at x=" + T.caretX(TWENTY.length())
                            + " with the field ending at x=" + right
                            + " — caret-follow scroll never ran (it lives in the renderer)");
        }
    }

    @Test
    void clickInAScrolledFieldHitsTheCharacterUnderThePointer() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = overflowingField(h);

            // With scroll resolved (140px), xs[k] = 10 + 10k - 140, so the visible band x∈[10,70] shows offsets
            // 14..20 and a click at x=40 lands on offset 17. Unscrolled, the same click lands on offset 3.
            float y = f.node().layout().rect().y() + f.node().layout().rect().h() * 0.5f;
            h.click(40f, y);
            h.type("X");

            assertEquals("abcdefghijklmnopqXrst", f.text(),
                    "the click must land on the character drawn under the pointer, not on the one that would be "
                            + "there if the field had never scrolled");
        }
    }

    /**
     * Moving the caret reflows nothing — {@code CARET} is not layout-affecting — but it does move the view, so the
     * compute phase must re-run and the read-model republish off {@code geometryDirty} alone
     * (docs/layout-read-model.md §2.3). Without that, the metrics a widget reads describe the previous scroll.
     */
    @Test
    void caretMoveRepublishesGeometryWithoutAReflow() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = overflowingField(h);
            long scrolled = h.gui.layoutSnapshot().version();
            assertTrue(f.node().layout().text().caretX(TWENTY.length()) > 60f,
                    "precondition: the view has scrolled to follow the caret to the end");

            h.tap(Key.HOME);

            assertTrue(h.gui.layoutSnapshot().version() > scrolled,
                    "a caret move republishes the read-model even though the flex layout is unchanged");
            assertEquals(10f, f.node().layout().text().caretX(0), 0.5f,
                    "the view scrolled back, so offset 0 sits at the text origin again");
        }
    }
}
