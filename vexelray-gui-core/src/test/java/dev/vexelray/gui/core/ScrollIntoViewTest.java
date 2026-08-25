package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Node.scrollIntoView()}: the least scrolling that puts a node inside every scrolling ancestor, answered in
 * the frame it was asked in.
 *
 * <p>Every assertion here reads the node's <em>published box</em> rather than only the offset, because that is the
 * property that matters and the one that is easy to get wrong: a container bakes its scroll into its children's
 * positions, so a scroller moved after the layout pass would report an offset that its rows disagree with.
 */
class ScrollIntoViewTest {

    private static final float ROW = 20f;
    private static final float VIEW = 100f;   // five rows fit

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    /** Fixed pixel length (1rem = 16px in the default context), so the arithmetic below is exact. */
    private static Length px(float p) {
        return Length.rem(p / 16f);
    }

    /** A {@code VIEW}-tall column of {@code count} {@code ROW}-tall rows — content taller than its box. */
    private static List<Node> rows(Gui gui, Node scroller, int count) {
        List<Node> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Node row = gui.box().width(Length.FILL).height(px(ROW));
            out.add(row);
            scroller.append(row);
        }
        return out;
    }

    private static Node scroller(Gui gui) {
        Node column = gui.column().width(px(200)).height(px(VIEW));
        gui.root().children(column);
        return column;
    }

    /** Minimal, and trailing-edge first: the row lands at the bottom of the viewport, not in the middle of it. */
    @Test
    void aRowBelowTheFoldComesUpByExactlyTheShortfall() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node column = scroller(gui);
            List<Node> rows = rows(gui, column, 20);
            gui.frame(400f, 300f, ScrollIntoViewTest::noText);
            assertTrue(column.layout().overflowY(), "the content is taller than the box");
            assertEquals(0f, column.layout().scrollY(), 0.5f, "and it opens at the top");

            rows.get(6).scrollIntoView();   // rows 0-4 are in view; row 6 sits 40px past the bottom edge
            gui.frame(400f, 300f, ScrollIntoViewTest::noText);

            assertEquals(40f, column.layout().scrollY(), 0.5f, "it came up by the shortfall and no further");
            assertEquals(column.layout().rect().y() + VIEW, bottom(rows.get(6)), 0.5f,
                    "which puts the row's own bottom on the viewport's bottom");
            assertEquals(column.layout().rect().y(), rows.get(2).layout().rect().y(), 0.5f,
                    "and the rows around it kept their places — this is a scroll, not a jump");
        }
    }

    /** A node already in view is a request that is already satisfied. */
    @Test
    void aRowInViewMovesNothing() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node column = scroller(gui);
            List<Node> rows = rows(gui, column, 20);
            gui.frame(400f, 300f, ScrollIntoViewTest::noText);

            rows.get(1).scrollIntoView();
            gui.frame(400f, 300f, ScrollIntoViewTest::noText);

            assertEquals(0f, column.layout().scrollY(), 0.5f, "nothing moved");
        }
    }

    /** Coming back is the same rule the other way: the row's top lands on the top of the viewport. */
    @Test
    void aRowAboveTheViewComesBackToTheLeadingEdge() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node column = scroller(gui);
            List<Node> rows = rows(gui, column, 20);
            gui.frame(400f, 300f, ScrollIntoViewTest::noText);
            rows.get(19).scrollIntoView();
            gui.frame(400f, 300f, ScrollIntoViewTest::noText);
            assertEquals(300f, column.layout().scrollY(), 0.5f, "at the end of the content");

            rows.get(2).scrollIntoView();
            gui.frame(400f, 300f, ScrollIntoViewTest::noText);

            assertEquals(40f, column.layout().scrollY(), 0.5f, "back by exactly what it takes to see row 2");
            assertEquals(column.layout().rect().y(), rows.get(2).layout().rect().y(), 0.5f,
                    "with its top on the viewport's top");
        }
    }

    /**
     * A node taller than the viewport cannot be brought inside it, so the rule that breaks the tie has to be
     * stated: show its top. Scrolling to its bottom would answer the request and show nothing that identifies it.
     */
    @Test
    void aTargetTallerThanTheViewportShowsItsTop() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node column = scroller(gui);
            rows(gui, column, 5);
            Node tall = gui.box().width(Length.FILL).height(px(300));
            column.append(tall);
            gui.frame(400f, 300f, ScrollIntoViewTest::noText);

            tall.scrollIntoView();
            gui.frame(400f, 300f, ScrollIntoViewTest::noText);

            assertEquals(100f, column.layout().scrollY(), 0.5f, "stopped where the tall box's top reached the edge");
            assertEquals(column.layout().rect().y(), tall.layout().rect().y(), 0.5f, "which is where it is drawn");
        }
    }

    /**
     * Nested scrollers each solve their own part, innermost first — and the outer one is asked about where the
     * target ended up, not where it was laid out, or the two scroll past each other.
     */
    @Test
    void nestedScrollersEachSolveTheirOwnPart() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node outer = gui.column().width(px(200)).height(px(VIEW));
            gui.root().children(outer);
            // Two panes, each taller than the outer viewport and each scrolling inside itself.
            Node firstPane = gui.column().width(Length.FILL).height(px(VIEW));
            Node secondPane = gui.column().width(Length.FILL).height(px(VIEW));
            outer.children(firstPane, secondPane);
            rows(gui, firstPane, 10);
            List<Node> deep = rows(gui, secondPane, 10);
            gui.frame(400f, 300f, ScrollIntoViewTest::noText);
            assertTrue(outer.layout().overflowY() && secondPane.layout().overflowY(), "both scroll");

            deep.get(9).scrollIntoView();   // last row of the second pane: off both viewports
            gui.frame(400f, 300f, ScrollIntoViewTest::noText);

            assertEquals(100f, secondPane.layout().scrollY(), 0.5f, "the pane scrolled to its own end");
            assertEquals(100f, outer.layout().scrollY(), 0.5f, "and the outer one brought the pane into view");
            float viewTop = outer.layout().rect().y();
            assertTrue(deep.get(9).layout().rect().y() >= viewTop - 0.5f
                            && bottom(deep.get(9)) <= viewTop + VIEW + 0.5f,
                    "so the row is inside the outer viewport, which is the whole of what was asked");
        }
    }

    private static float bottom(Node n) {
        return n.layout().rect().y() + n.layout().rect().h();
    }
}
