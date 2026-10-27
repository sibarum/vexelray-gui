package dev.vexelray.gui.core;

import dev.vexelray.gui.core.input.HitTest;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.text.TextLayout;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Node.overhang}: a node keeps its slot and grows past it to fit its content, as far as the room it is in,
 * drawn over its neighbours and hit first (docs/plans/scrolling.md §7.3). The second row of the text-field ladder.
 */
class OverhangTest {

    private static final float W = 400f;
    private static final float H = 300f;
    private static final float CELL = 10f;   // every character is 10px wide
    private static final float LINE = 20f;   // and every line 20px tall

    /** Characters of fixed width that wrap at whole characters: enough to make a text node's size exact. */
    private static final TextMeasurer TM = new TextMeasurer() {
        @Override
        public float intrinsic(RetainedNode node, Axis axis, float px) {
            String s = node.textString() == null ? "" : node.textString();
            return axis == Axis.HORIZONTAL ? s.length() * CELL : LINE;
        }

        @Override
        public float[] caretAdvances(String text, float px) {
            int len = text == null ? 0 : text.length();
            float[] xs = new float[len + 1];
            for (int i = 0; i <= len; i++) {
                xs[i] = i * CELL;
            }
            return xs;
        }

        @Override
        public List<TextLayout.LineSpan> lineSpans(String text, float wrapWidth, float px) {
            int len = text == null ? 0 : text.length();
            int per = wrapWidth <= 0f ? Math.max(1, len) : Math.max(1, (int) (wrapWidth / CELL));
            List<TextLayout.LineSpan> spans = new ArrayList<>();
            for (int start = 0; start < Math.max(1, len); start += per) {
                spans.add(new TextLayout.LineSpan(start, Math.min(len, start + per), start + per >= len));
            }
            return spans;
        }
    };

    private static Length px(float p) {
        return Length.rem(p / 16f);
    }

    private static RetainedNode frame(Gui gui) {
        return gui.frame(W, H, TM);
    }

    /** A column across the window holding {@code field} between two fixed boxes. */
    private static Node[] around(Gui gui, Node field) {
        Node above = gui.box().width(Length.FILL).height(px(40));
        Node below = gui.box().width(Length.FILL).height(px(40));
        gui.root().children(gui.column().width(Length.FILL).height(Length.FILL).children(above, field, below));
        return new Node[]{above, below};
    }

    @Test
    void aLineLongerThanItsBoxGrowsAcrossAndNothingAroundItMoves() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node field = gui.text("x".repeat(25)).wordWrap(false).width(px(100)).height(px(LINE)).overhang(true);
            Node[] neighbours = around(gui, field);
            RetainedNode root = frame(gui);

            NodeLayout f = field.layout();
            assertEquals(250f, f.rect().w(), 0.5f, "as wide as its text");
            assertEquals(0f, f.rect().x(), 0.5f, "from where its slot starts");
            assertEquals(40f, f.rect().y(), 0.5f);
            assertEquals(60f, neighbours[1].layout().rect().y(), 0.5f, "the box below kept its place: the slot is 20 tall");
            assertEquals(f.rect().w(), f.visibleRect().w(), 0.5f, "and all of it is seen");

            RetainedNode hit = HitTest.at(root, 200f, 50f);   // past the slot's right edge, inside the grown box
            assertEquals(field.id(), hit.id, "the grown part is the field's to the pointer too");
        }
    }

    @Test
    void wrappedTextGrowsDownAtItsSlotsWidthOverWhatIsBelow() {
        try (Gui gui = new Gui(Atchung.create())) {
            // 30 characters at 10 per 100px line: three lines, in a box one line tall.
            Node field = gui.text("y".repeat(30)).width(px(100)).height(px(LINE)).overhang(true);
            Node[] neighbours = around(gui, field);
            RetainedNode root = frame(gui);

            NodeLayout f = field.layout();
            assertEquals(100f, f.rect().w(), 0.5f, "wrapped text keeps its width");
            assertEquals(3 * LINE, f.rect().h(), 0.5f, "and grows to its lines");
            assertEquals(60f, neighbours[1].layout().rect().y(), 0.5f, "over the box below, which did not move");
            assertSame(field.id(), HitTest.at(root, 50f, 70f).id, "the pointer over the box below finds the field");
        }
    }

    @Test
    void contentThatFitsLeavesItExactlyWhereItWas() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node field = gui.text("fits").wordWrap(false).width(px(100)).height(px(LINE)).overhang(true);
            around(gui, field);
            RetainedNode root = frame(gui);
            assertEquals(100f, field.layout().rect().w(), 0.5f);
            assertTrue(root.overhangs().isEmpty(), "nothing grew, so nothing is drawn over anything");
        }
    }

    @Test
    void itStopsAtTheWindowAndMovesBackInsideIt() {
        try (Gui gui = new Gui(Atchung.create())) {
            // A slot at the right of the window, and a line wider than the whole window.
            Node field = gui.text("z".repeat(60)).wordWrap(false).width(px(100)).height(px(LINE)).overhang(true);
            Node spacer = gui.box().width(px(300)).height(px(LINE));
            gui.root().children(gui.row().width(Length.FILL).children(spacer, field));
            frame(gui);

            NodeLayout f = field.layout();
            assertEquals(W, f.rect().w(), 0.5f, "no wider than the room: what is left scrolls, as it would have");
            assertEquals(0f, f.rect().x(), 0.5f, "moved back so none of it is off the window");
        }
    }

    @Test
    void insideAScrollerItStopsAtTheScrollersBox() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node field = gui.text("w".repeat(30)).wordWrap(false).width(px(100)).height(px(LINE)).overhang(true);
            Scroller scroller = gui.scroller(gui.column().children(field)).axes(false, true);
            scroller.node().width(px(200)).height(px(100));
            gui.root().children(scroller.node());
            frame(gui);
            assertEquals(200f, field.layout().rect().w(), 0.5f, "past the Scroller it would be over its neighbours");
        }
    }

    /** A box its parent sized has all the room it is given: an editor filling its pane scrolls, it does not spread. */
    @Test
    void aBoxItsParentSizedDoesNotGrow() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node editor = gui.text("v".repeat(90)).width(Length.FILL).height(Length.FILL).overhang(true);
            Node pane = gui.column().width(px(100)).height(px(40)).children(editor);
            gui.root().children(gui.row().width(Length.FILL).height(Length.FILL).children(pane));
            RetainedNode root = frame(gui);
            assertEquals(100f, editor.layout().rect().w(), 0.5f);
            assertEquals(40f, editor.layout().rect().h(), 0.5f, "nine lines in a pane two lines tall stay in the pane");
            assertTrue(root.overhangs().isEmpty());
        }
    }

    /** Stretched across its parent, a box is the parent's width; it may still grow down, which is its own. */
    @Test
    void aStretchedBoxGrowsOnlyAlongTheAxisThatIsItsOwn() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node note = gui.text("u".repeat(30)).height(px(LINE)).overhang(true);   // width AUTO, stretched
            Node column = gui.column().width(px(100)).children(note);
            gui.root().children(column);
            frame(gui);
            assertEquals(100f, note.layout().rect().w(), 0.5f, "the column's width, not the line's");
            assertEquals(3 * LINE, note.layout().rect().h(), 0.5f, "and down to its three wrapped lines");
        }
    }

    @Test
    void turningItOffGivesTheRoomBack() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node field = gui.text("x".repeat(25)).wordWrap(false).width(px(100)).height(px(LINE)).overhang(true);
            around(gui, field);
            frame(gui);
            assertEquals(250f, field.layout().rect().w(), 0.5f);

            field.overhang(false);
            RetainedNode root = frame(gui);
            assertEquals(100f, field.layout().rect().w(), 0.5f, "back in its slot");
            assertTrue(root.overhangs().isEmpty());
            assertFalse(HitTest.at(root, 200f, 50f).id == field.id(), "and the pointer past it is not on it");
        }
    }
}
