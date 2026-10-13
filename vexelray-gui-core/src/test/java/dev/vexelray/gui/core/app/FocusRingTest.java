package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.Composition;
import dev.vexelray.gui.core.input.FocusCause;
import dev.vexelray.gui.core.input.FocusState;
import dev.vexelray.gui.core.layout.LayoutContext;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.style.FocusRing;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.core.text.TextFaces;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The focus ring, asserted in the vertices: the renderer draws it, around whichever node has focus, from the
 * theme or the node's own {@code FocusRing}. What matters is where it lands in paint order (over later siblings,
 * under popups), where it lands in space (outset, or inside when the outset would be clipped away), and when it
 * shows at all.
 */
class FocusRingTest {

    private static final Color SIBLING = Color.rgba(0f, 1f, 0f, 1f);
    private static final Color POPUP = Color.rgba(0f, 0f, 1f, 1f);
    private static final Color ACCENT = Theme.DARK.color(Role.ACCENT);
    /** 0.125rem at a 16px root em: the standard ring's width, and its offset. */
    private static final float W = 2f;

    private static long nextId = 1;

    private static RetainedNode box(float x, float y, float w, float h) {
        RetainedNode n = new RetainedNode(nextId++);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        return n;
    }

    private static RetainedNode adopt(RetainedNode parent, RetainedNode child) {
        child.parent = parent;
        parent.children.add(child);
        return child;
    }

    private static Canvas emit(RetainedNode root, FocusState focus, Theme theme) {
        Canvas canvas = Canvases.core(400, 200);
        TreeRenderer.emit(root, canvas, TextFaces.standard(), theme, focus, LayoutContext.of(400f, 200f));
        return canvas;
    }

    private static Canvas emit(RetainedNode root, long focused, FocusCause cause) {
        return emit(root, new FocusState(focused, cause, true), Theme.DARK);
    }

    /** One ring's worth of what was drawn: (centreX, centreY, halfW, halfH, width, alpha). */
    private record Stroke(float cx, float cy, float halfW, float halfH, float width, float alpha) { }

    /** Whether vertex {@code v} is the ring's: an element with values of its own (a border), in the accent. */
    private static boolean isRing(float[] d, int v) {
        int rgba = CanvasVertex.OFF_COLOR / Float.BYTES;
        int at = v * CanvasVertex.FLOATS_PER_VERTEX;
        return Canvases.element(d, v) >= Composition.RECORDS_BASE
                && Math.abs(d[at + rgba] - ACCENT.r()) < 1e-4f
                && Math.abs(d[at + rgba + 1] - ACCENT.g()) < 1e-4f
                && Math.abs(d[at + rgba + 2] - ACCENT.b()) < 1e-4f;
    }

    private static List<Stroke> rings(Canvas canvas) {
        float[] d = canvas.toVertexArray();
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        int shape = CanvasVertex.OFF_SHAPE / Float.BYTES;
        int local = CanvasVertex.OFF_LOCAL / Float.BYTES;
        int screen = CanvasVertex.OFF_CLIPRS / Float.BYTES;
        List<Stroke> out = new ArrayList<>();
        for (int i = 0; i < canvas.vertexCount(); i++) {
            if (isRing(d, i)) {
                int v = i * stride;
                // The ring's colour and width are its record's: a border alone, (r, g, b, a, width).
                float[] border = Canvases.instance(canvas, i, 5);
                Stroke s = new Stroke(d[v + screen] - d[v + local], d[v + screen + 1] - d[v + local + 1],
                        d[v + shape], d[v + shape + 1], border[4], border[3]);
                if (out.isEmpty() || !near(out.get(out.size() - 1), s)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static boolean near(Stroke a, Stroke b) {
        return Math.abs(a.cx() - b.cx()) < 0.01f && Math.abs(a.cy() - b.cy()) < 0.01f
                && Math.abs(a.halfW() - b.halfW()) < 0.01f;
    }

    /** First vertex index drawn in {@code c}, or of the ring when {@code c} is null; -1 if never. */
    private static int first(Canvas canvas, Color c) {
        float[] d = canvas.toVertexArray();
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        int rgba = CanvasVertex.OFF_COLOR / Float.BYTES;
        for (int i = 0; i < canvas.vertexCount(); i++) {
            int v = i * stride;
            boolean hit = c == null ? isRing(d, i)
                    : d[v + rgba] == c.r() && d[v + rgba + 1] == c.g() && d[v + rgba + 2] == c.b();
            if (hit) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void theFocusedNodeGetsTheThemesRingStandingClearOfItsBox() {
        RetainedNode root = box(0, 0, 400, 200);
        RetainedNode button = adopt(root, box(100, 50, 80, 30));

        List<Stroke> rings = rings(emit(root, button.id, FocusCause.KEYBOARD));

        assertEquals(1, rings.size(), "one ring: " + rings);
        Stroke r = rings.get(0);
        assertEquals(140f, r.cx(), 0.01f, "centred on the box");
        assertEquals(65f, r.cy(), 0.01f);
        assertEquals(40f + 2f * W, r.halfW(), 0.01f, "offset plus width outside the box on every side");
        assertEquals(15f + 2f * W, r.halfH(), 0.01f);
        assertEquals(W, r.width(), 0.01f);
    }

    @Test
    void nothingFocusedNoRing() {
        RetainedNode root = box(0, 0, 400, 200);
        adopt(root, box(100, 50, 80, 30));

        assertTrue(rings(emit(root, -1L, FocusCause.KEYBOARD)).isEmpty());
    }

    /** Drawn at the host's close, so the next field along cannot paint over the ring's right edge. */
    @Test
    void theRingIsOverTheSiblingsAfterIt() {
        RetainedNode root = box(0, 0, 400, 200);
        RetainedNode field = adopt(root, box(0, 0, 100, 30));
        RetainedNode next = adopt(root, box(100, 0, 100, 30));
        next.set(PropKey.BACKGROUND, SIBLING);

        Canvas d = emit(root, field.id, FocusCause.KEYBOARD);
        assertTrue(first(d, SIBLING) >= 0 && first(d, null) > first(d, SIBLING),
                "the later sibling is painted first and the ring over it");
    }

    /** ...and before the host's floats, so every popup covers it. */
    @Test
    void theRingIsUnderAPopupFloatingAtTheRoot() {
        RetainedNode root = box(0, 0, 400, 200);
        RetainedNode field = adopt(root, box(10, 10, 100, 30));
        RetainedNode popup = adopt(root, box(20, 20, 100, 100));
        popup.set(PropKey.FLOAT_X, Length.ZERO);
        popup.set(PropKey.FLOAT_Y, Length.ZERO);
        popup.set(PropKey.BACKGROUND, POPUP);

        Canvas d = emit(root, field.id, FocusCause.KEYBOARD);
        assertTrue(first(d, null) >= 0 && first(d, null) < first(d, POPUP), "ring first, popup over it");
    }

    /** A control that fills a clipping container would lose an outset ring on every side, so it comes inside. */
    @Test
    void aRingThatWouldBeClippedAwayFallsInside() {
        RetainedNode root = box(0, 0, 400, 200);
        RetainedNode panel = adopt(root, box(50, 50, 200, 100));
        panel.set(PropKey.CLIP, Boolean.TRUE);
        RetainedNode editor = adopt(panel, box(50, 50, 200, 100));

        List<Stroke> rings = rings(emit(root, editor.id, FocusCause.KEYBOARD));

        assertEquals(1, rings.size());
        assertEquals(100f, rings.get(0).halfW(), 0.01f, "inside the box: it hugs the box's own edge");
        assertEquals(50f, rings.get(0).halfH(), 0.01f);
    }

    /** With room inside the clip, the same ring stays outside. */
    @Test
    void aRingWithRoomStaysOutside() {
        RetainedNode root = box(0, 0, 400, 200);
        RetainedNode panel = adopt(root, box(50, 50, 200, 100));
        panel.set(PropKey.CLIP, Boolean.TRUE);
        RetainedNode field = adopt(panel, box(100, 80, 50, 20));

        List<Stroke> rings = rings(emit(root, field.id, FocusCause.KEYBOARD));

        assertEquals(1, rings.size());
        assertEquals(25f + 2f * W, rings.get(0).halfW(), 0.01f);
    }

    @Test
    void aNodeThatOptsOutShowsNoRing() {
        RetainedNode root = box(0, 0, 400, 200);
        RetainedNode tree = adopt(root, box(0, 0, 200, 100));
        tree.set(PropKey.FOCUS_RING, FocusRing.NONE);

        assertTrue(rings(emit(root, tree.id, FocusCause.KEYBOARD)).isEmpty());
    }

    @Test
    void aNodesOwnRingReplacesTheThemes() {
        RetainedNode root = box(0, 0, 400, 200);
        RetainedNode field = adopt(root, box(100, 50, 80, 30));
        field.set(PropKey.FOCUS_RING, FocusRing.STANDARD.width(Length.rem(0.25f)).offset(Length.ZERO));

        List<Stroke> rings = rings(emit(root, field.id, FocusCause.KEYBOARD));

        assertEquals(1, rings.size());
        assertEquals(4f, rings.get(0).width(), 0.01f);
        assertEquals(40f + 4f, rings.get(0).halfW(), 0.01f, "no offset: it sits just outside the edge");
    }

    /** {@code :focus-visible}: a clicked button stays dark, a clicked field still says where typing goes. */
    @Test
    void aKeyboardOnlyRingSkipsAClickedButtonButNotAClickedField() {
        Theme theme = Theme.DARK.focusRing(FocusRing.STANDARD.when(FocusRing.Visibility.KEYBOARD));
        RetainedNode root = box(0, 0, 400, 200);
        RetainedNode button = adopt(root, box(0, 0, 80, 30));
        RetainedNode field = adopt(root, box(0, 50, 80, 30));
        field.set(PropKey.TEXT, "");
        field.set(PropKey.EDITABLE, Boolean.TRUE);

        assertTrue(rings(emit(root, new FocusState(button.id, FocusCause.POINTER, true), theme)).isEmpty());
        assertEquals(1, rings(emit(root, new FocusState(button.id, FocusCause.KEYBOARD, true), theme)).size());
        assertEquals(1, rings(emit(root, new FocusState(field.id, FocusCause.POINTER, true), theme)).size());
    }

    @Test
    void theRingDimsWhileTheWindowIsInTheBackground() {
        RetainedNode root = box(0, 0, 400, 200);
        RetainedNode button = adopt(root, box(100, 50, 80, 30));

        float active = rings(emit(root, new FocusState(button.id, FocusCause.KEYBOARD, true), Theme.DARK))
                .get(0).alpha();
        float inactive = rings(emit(root, new FocusState(button.id, FocusCause.KEYBOARD, false), Theme.DARK))
                .get(0).alpha();

        assertEquals(active * 0.5f, inactive, 1e-4f);
    }
}
