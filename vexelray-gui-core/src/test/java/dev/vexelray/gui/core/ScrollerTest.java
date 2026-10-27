package dev.vexelray.gui.core;

import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.tactroller.api.InputEvent;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Scroller}: a box that masks and scrolls one child, sized by one rule of its own, with no scrollbar in it
 * (docs/plans/scrolling.md §2–3).
 *
 * <p>The two layouts at the top are the plan's §2.1 pair. The bar in them is a plain fixed-size box standing in for
 * the {@code Scrollbar} element step 2 builds: nothing in the layout knows it is a bar, which is the point. Where
 * the room for it comes from is decided by ordinary flow and by the one thing a Scroller does that no other box
 * does — give way.
 */
class ScrollerTest {

    private static final float W = 400f;
    private static final float H = 300f;
    private static final float BAR = 14f;

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    /** Fixed pixel length (1rem = 16px in the default context), so the arithmetic is exact. */
    private static Length px(float p) {
        return Length.rem(p / 16f);
    }

    private static void frame(Gui gui) {
        Seams.of(gui).frame(W, H, ScrollerTest::noText);
    }

    /** The window's whole area as a column, to build in. */
    private static Node page(Gui gui) {
        Node page = gui.column().width(Length.FILL).height(Length.FILL);
        gui.root().children(page);
        return page;
    }

    /** A column of {@code count} rows, each {@code w}×{@code h}. */
    private static Node rows(Gui gui, int count, float w, float h) {
        Node column = gui.column();
        for (int i = 0; i < count; i++) {
            column.append(gui.box().width(px(w)).height(px(h)));
        }
        return column;
    }

    private static NodeLayout at(Node n) {
        return n.layout();
    }

    /**
     * The tab bar: a box sized to its content holds a Scroller of headers and a bar under it. The Scroller is as
     * tall as the headers, the bar adds its own height, and what is below moves down for it — the headers keep all
     * of theirs.
     */
    @Test
    void aBoxSizedToItsContentGrowsByTheBarAndItsNeighbourMovesDown() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node headers = gui.row();
            for (int i = 0; i < 10; i++) {
                headers.append(gui.box().width(px(100)).height(px(32)));
            }
            Scroller scroller = gui.scroller(headers).axes(true, false);
            Node bar = gui.box().width(Length.FILL).height(px(BAR));
            Node strip = gui.column().width(Length.FILL).children(scroller.node(), bar);
            Node body = gui.box().width(Length.FILL).height(Length.grow(1f));
            page(gui).children(strip, body);
            frame(gui);

            NodeLayout s = at(scroller.node());
            assertEquals(W, s.rect().w(), 0.5f, "as wide as the box it is in, which sizes it across");
            assertEquals(32f, s.rect().h(), 0.5f, "as tall as the headers");
            assertEquals(32f, s.viewH(), 0.5f, "and all of that is view: a Scroller reserves no strip");
            assertTrue(s.overflowX(), "the headers are wider than it is, so it scrolls");
            assertEquals(32f, at(bar).rect().y(), 0.5f, "the bar is under the headers, not over them");
            assertEquals(32f + BAR, at(strip).rect().h(), 0.5f, "the box is headers plus bar");
            assertEquals(32f + BAR, at(body).rect().y(), 0.5f, "and what is below moved down for the bar");
        }
    }

    /**
     * A page between a title and a status bar: the column is the window's height, so the Scroller's content — far
     * longer — is not its size. It gives way to exactly what the two bars leave.
     */
    @Test
    void aScrollerGivesWayToTheRoomItsParentHas() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node title = gui.box().width(Length.FILL).height(px(30));
            Scroller scroller = gui.scroller(rows(gui, 20, 100, 40)).axes(false, true);
            Node status = gui.box().width(Length.FILL).height(px(30));
            Node page = page(gui).children(title, scroller.node(), status);
            frame(gui);

            NodeLayout s = at(scroller.node());
            assertEquals(30f, s.rect().y(), 0.5f);
            assertEquals(H - 60f, s.rect().h(), 0.5f, "the room the two bars leave");
            assertEquals(H - 30f, at(status).rect().y(), 0.5f, "the status bar stays on screen");
            assertEquals(800f, s.contentH(), 0.5f);
            assertTrue(s.overflowY());
            assertFalse(at(page).overflowY(), "the page itself fits: one scroller, not two");
        }
    }

    /**
     * A vertical bar beside the Scroller in a row: the row is the window's width, and the bar's width comes out of
     * the Scroller, which is the one child that gives way.
     */
    @Test
    void aBarBesideItTakesItsWidthFromTheScroller() {
        try (Gui gui = new Gui(Atchung.create())) {
            Scroller scroller = gui.scroller(rows(gui, 20, 500, 40));
            Node bar = gui.box().width(px(BAR)).height(Length.FILL);
            Node row = gui.row().width(Length.FILL).height(Length.FILL).children(scroller.node(), bar);
            page(gui).children(row);
            frame(gui);

            NodeLayout s = at(scroller.node());
            assertEquals(W - BAR, s.rect().w(), 0.5f, "the Scroller gave the bar its width");
            assertEquals(W - BAR, at(bar).rect().x(), 0.5f, "and the bar stands beside it");
            assertEquals(BAR, at(bar).rect().w(), 0.5f, "at its own width");
            assertEquals(W - BAR, s.viewW(), 0.5f, "no strip inside: the view is the box");
            assertEquals(H, s.viewH(), 0.5f);
            assertTrue(s.overflowX() && s.overflowY(), "500×800 of content in it scrolls both ways");
        }
    }

    /** Two Scrollers short of room give it up in proportion to their content. */
    @Test
    void twoScrollersShareTheShortfallInProportionToTheirContent() {
        try (Gui gui = new Gui(Atchung.create())) {
            Scroller a = gui.scroller(rows(gui, 3, 100, 100)).axes(false, true);    // 300 of content
            Scroller b = gui.scroller(rows(gui, 6, 100, 100)).axes(false, true);    // 600
            page(gui).children(a.node(), b.node());
            frame(gui);

            assertEquals(100f, at(a.node()).rect().h(), 0.5f, "a third of the room, for a third of the content");
            assertEquals(200f, at(b.node()).rect().h(), 0.5f, "two thirds for two thirds");
        }
    }

    /**
     * A Scroller wrapped in a box sized to its content, with something fixed beside it: the box gives way by what
     * its Scroller can give, and no more. Here the wrapper is a row of a Scroller and a fixed-width strip, the shape
     * a bar beside a Scroller has, and it sits between a title and a status bar.
     */
    @Test
    void aBoxHoldingAScrollerGivesWayByWhatTheScrollerCanGive() {
        try (Gui gui = new Gui(Atchung.create())) {
            Scroller scroller = gui.scroller(rows(gui, 20, 100, 40)).axes(false, true);
            Node strip = gui.box().width(px(BAR)).height(px(60));   // fixed: it cannot give
            Node wrapper = gui.row().width(Length.FILL).children(scroller.node(), strip);
            Node title = gui.box().width(Length.FILL).height(px(30));
            Node status = gui.box().width(Length.FILL).height(px(30));
            Node page = page(gui).children(title, wrapper, status);
            frame(gui);

            assertEquals(H - 60f, at(wrapper).rect().h(), 0.5f, "the wrapper gave way to what the bars leave");
            assertEquals(H - 60f, at(scroller.node()).rect().h(), 0.5f, "and the Scroller in it is that tall");
            assertEquals(H - 30f, at(status).rect().y(), 0.5f);
            assertFalse(at(page).overflowY(), "nothing overflows the page");

            // Less room than the strip beside it needs (20px left): the wrapper stops at what cannot give.
            title.height(px(250));
            frame(gui);
            assertEquals(60f, at(wrapper).rect().h(), 0.5f, "never below the fixed strip");
        }
    }

    /**
     * Inside a box that declared it scrolls, nothing gives way along that axis: its room there is unbounded, and what
     * does not fit is what it scrolls. Without this an old-model scroller squeezes what it holds to fit its view and
     * never scrolls at all — what the demo's own page did to a chapter of Scrollers before it was caught.
     */
    @Test
    void nothingGivesWayInsideABoxThatScrolls() {
        try (Gui gui = new Gui(Atchung.create())) {
            Scroller scroller = gui.scroller(rows(gui, 20, 100, 40)).axes(false, true);
            Node wrapper = gui.column().width(Length.FILL).children(scroller.node());
            Node outer = gui.column().width(Length.FILL).height(px(200)).scroll(false, true).children(wrapper);
            page(gui).children(outer);
            frame(gui);

            assertEquals(800f, at(scroller.node()).rect().h(), 0.5f,
                    "the Scroller is its content: nothing asked it to give");
            assertTrue(at(outer).overflowY(), "the box that declared it scrolls is the one that scrolls");
        }
    }

    /** A Scroller with less content than room is its content's size: nothing to give, nothing given. */
    @Test
    void aScrollerWithRoomToSpareIsItsContent() {
        try (Gui gui = new Gui(Atchung.create())) {
            Scroller scroller = gui.scroller(rows(gui, 2, 100, 40)).axes(false, true);
            Node after = gui.box().width(Length.FILL).height(px(20));
            page(gui).children(scroller.node(), after);
            frame(gui);

            assertEquals(80f, at(scroller.node()).rect().h(), 0.5f);
            assertEquals(80f, at(after).rect().y(), 0.5f);
            assertFalse(at(scroller.node()).overflowY());
        }
    }

    /**
     * A child that fills, inside a Scroller that fills: it is the view while its content is shorter, and its
     * content's length once that is longer — which is the length that scrolls.
     */
    @Test
    void aFillingChildIsTheViewOrItsContentWhicheverIsLonger() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node content = gui.column().width(Length.FILL).height(Length.FILL);
            content.append(gui.box().width(px(100)).height(px(40)));
            Scroller scroller = gui.scroller(content).axes(false, true);
            scroller.node().width(Length.FILL).height(Length.FILL);
            page(gui).children(scroller.node());
            frame(gui);
            assertEquals(H, at(content).rect().h(), 0.5f, "short content: the child is the view");

            for (int i = 0; i < 19; i++) {
                content.append(gui.box().width(px(100)).height(px(40)));
            }
            frame(gui);
            assertEquals(800f, at(content).rect().h(), 0.5f, "long content: the child is its content");
            assertEquals(H, at(scroller.node()).rect().h(), 0.5f, "inside a Scroller the window's height");
            assertTrue(at(scroller.node()).overflowY());
        }
    }

    /** The Scroller is the scroller: what it holds never grows a scrollbar of its own. */
    @Test
    void itsChildNeverScrollsOnItsOwn() {
        try (Gui gui = new Gui(Atchung.create())) {
            // A column held to the Scroller's width (only y scrolls), holding something twice as wide. Anywhere
            // else that column would be an undeclared box long enough for a bar, and would scroll sideways.
            Node content = gui.column().width(Length.FILL);
            content.append(gui.box().width(px(2 * W)).height(px(40)));
            Scroller scroller = gui.scroller(content).axes(false, true);
            page(gui).children(scroller.node());
            frame(gui);

            assertFalse(at(content).overflowX(), "no second scroller inside the first");
            assertFalse(at(scroller.node()).overflowX(), "and x is not the Scroller's to scroll");
        }
    }

    /** Each axis publishes its range, and moving one axis leaves the other where it was. */
    @Test
    void eachAxisPublishesItsRangeAndMovesAlone() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node content = rows(gui, 20, 500, 40);
            Scroller scroller = gui.scroller(content);
            scroller.node().width(px(300)).height(px(200));
            page(gui).children(scroller.node());
            frame(gui);

            assertEquals(new ScrollRange(500f, 300f, 0f), scroller.x().range().value());
            assertEquals(new ScrollRange(800f, 200f, 0f), scroller.y().range().value());
            assertEquals(200f, scroller.x().range().value().max(), 0.5f, "x travels content less view");

            scroller.y().scrollTo(150f);
            frame(gui);
            assertEquals(150f, scroller.y().range().value().offset(), 0.5f);
            assertEquals(0f, scroller.x().range().value().offset(), 0.5f, "y alone moved");
            assertEquals(at(scroller.node()).rect().y() - 150f, at(content).rect().y(), 0.5f,
                    "the offset moved the child, and the range says where");

            scroller.x().scrollTo(9999f);
            frame(gui);
            assertEquals(200f, scroller.x().range().value().offset(), 0.5f, "clamped to content less view");
            assertEquals(150f, scroller.y().range().value().offset(), 0.5f, "and x alone moved");

            scroller.y().scrollBy(-50f);
            frame(gui);
            assertEquals(100f, scroller.y().range().value().offset(), 0.5f, "scrollBy is from where it is");
        }
    }

    /** An axis that does not scroll has nothing to scroll, and an offset asked of it is ignored. */
    @Test
    void anAxisThatDoesNotScrollPublishesNothingToScroll() {
        try (Gui gui = new Gui(Atchung.create())) {
            Scroller scroller = gui.scroller(rows(gui, 2, 500, 40)).axes(false, true);
            scroller.node().width(px(300)).height(px(200));
            page(gui).children(scroller.node());
            frame(gui);

            ScrollRange x = scroller.x().range().value();
            assertFalse(x.scrolls(), "x is not the Scroller's to scroll");
            assertEquals(300f, x.view(), 0.5f);

            scroller.x().scrollTo(50f);
            frame(gui);
            assertEquals(0f, at(scroller.node()).scrollX(), 0.5f);
        }
    }

    /** {@code scrollIntoView} through two Scrollers, one inside the other: both move, innermost first. */
    @Test
    void aRowIsRevealedThroughNestedScrollers() {
        try (Gui gui = new Gui(Atchung.create())) {
            List<Node> inner = new ArrayList<>();
            Node innerRows = gui.column();
            for (int i = 0; i < 10; i++) {
                Node row = gui.box().width(px(100)).height(px(40));
                inner.add(row);
                innerRows.append(row);
            }
            Scroller innerScroller = gui.scroller(innerRows).axes(false, true);
            innerScroller.node().height(px(100));   // declared: it does not give way, so it has something to scroll
            Node outerContent = gui.column().children(rows(gui, 1, 100, 300), innerScroller.node());
            Scroller outer = gui.scroller(outerContent).axes(false, true);
            outer.node().height(px(200));
            page(gui).children(outer.node());
            frame(gui);

            Node target = inner.get(8);   // 320..360 in the inner content; the inner Scroller is 300..400 in the outer
            target.scrollIntoView();
            frame(gui);

            NodeLayout o = at(outer.node());
            NodeLayout i = at(innerScroller.node());
            NodeLayout t = at(target);
            assertTrue(i.scrollY() > 0f, "the inner Scroller moved");
            assertTrue(o.scrollY() > 0f, "and so did the outer one");
            assertTrue(t.rect().y() >= i.rect().y() - 0.5f && t.rect().y() + 40f <= i.rect().y() + 100f + 0.5f,
                    "the row is inside the inner view");
            assertTrue(i.rect().y() + 100f <= o.rect().y() + 200f + 0.5f && t.rect().y() >= o.rect().y() - 0.5f,
                    "and the inner view's part with the row in it is inside the outer view");
        }
    }

    /** The wheel scrolls a Scroller with no bar anywhere, which is how a trackpad has to work. */
    @Test
    void theWheelScrollsItWithNoBar() {
        Atchung bus = Atchung.create();
        try (Gui gui = new Gui(bus)) {
            Scroller scroller = gui.scroller(rows(gui, 20, 100, 40)).axes(false, true);
            page(gui).children(scroller.node());
            frame(gui);

            bus.publish(gui.input(), new InputEvent.Scrolled(0, -1, 50, 50, 0));
            frame(gui);
            frame(gui);
            assertTrue(scroller.y().range().value().offset() > 0f, "a notch down moved the view down");
        }
    }
}
