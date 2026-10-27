package dev.vexelray.gui.scroll;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.ScrollRange;
import dev.vexelray.gui.core.ScrollSource;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.draw.Picture;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Committer;
import sibarum.atchung.State;

import java.util.ArrayList;
import java.util.List;

import static dev.vexelray.gui.scroll.Headless.H;
import static dev.vexelray.gui.scroll.Headless.W;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link Scrollbar} is an element: where its room comes from is ordinary layout, and what it does is ask its
 * source to move. The first two tests are docs/plans/scrolling.md §2.1's two cases with the real bar in them — the
 * ones scrollbar-room.md's step 1 solved inside {@code measure}, now solved by nothing but flow.
 */
class ScrollbarTest {

    private static final float BAR = 0.85f * 16f;   // Scrollbar.THICKNESS at the default root em

    private static Length px(float p) {
        return Length.rem(p / 16f);
    }

    private static Node page(Headless h) {
        Node page = h.gui.column().width(Length.FILL).height(Length.FILL);
        h.gui.root().children(page);
        return page;
    }

    private static Node rows(Headless h, int count, float w, float rowH) {
        Node column = h.gui.column();
        for (int i = 0; i < count; i++) {
            column.append(h.gui.box().width(px(w)).height(px(rowH)));
        }
        return column;
    }

    private static NodeLayout at(Node n) {
        return n.layout();
    }

    /** The tab bar: headers plus bar, and the body under them moves down by the bar. */
    @Test
    void aStripOfTabsGrowsByItsBarAndTheBodyMovesDown() {
        try (Headless h = new Headless()) {
            Node headers = h.gui.row();
            for (int i = 0; i < 10; i++) {
                headers.append(h.gui.box().width(px(100)).height(px(32)));
            }
            Scrollbars strip = Scrollbars.horizontal(h.gui, headers);
            Node body = h.gui.box().width(Length.FILL).height(Length.grow(1f));
            page(h).children(strip.node(), body);
            h.frame();

            Node bar = strip.horizontalBar().orElseThrow().node();
            assertEquals(32f, at(strip.scroller().node()).rect().h(), 0.5f, "the headers keep all of their height");
            assertEquals(32f, at(bar).rect().y(), 0.5f, "the bar is under them");
            assertEquals(BAR, at(bar).rect().h(), 0.5f, "at its own thickness");
            assertEquals(W, at(bar).rect().w(), 0.5f, "along the whole strip");
            assertEquals(32f + BAR, at(body).rect().y(), 0.5f, "and the body moved down for it");
        }
    }

    /** A page between two bars: the bar's width comes out of the content, and the page still fits. */
    @Test
    void aPageGivesItsBarItsWidthAndStillFits() {
        try (Headless h = new Headless()) {
            Scrollbars sb = Scrollbars.vertical(h.gui, rows(h, 20, 100, 40));
            Node title = h.gui.box().width(Length.FILL).height(px(30));
            Node status = h.gui.box().width(Length.FILL).height(px(30));
            page(h).children(title, sb.node(), status);
            h.frame();

            Node bar = sb.verticalBar().orElseThrow().node();
            NodeLayout s = at(sb.scroller().node());
            assertEquals(H - 60f, s.rect().h(), 0.5f, "the room the two bars leave");
            assertEquals(W - BAR, s.rect().w(), 0.5f, "less the bar's width");
            assertEquals(W - BAR, at(bar).rect().x(), 0.5f, "which stands beside it");
            assertEquals(H - 60f, at(bar).rect().h(), 0.5f, "as tall as the view");
            assertEquals(H - 30f, at(status).rect().y(), 0.5f, "and the status bar is still on screen");
        }
    }

    /** A page filling the window, for the input tests: 800 of content in a 300 view. */
    private static Scrollbars fullPage(Headless h) {
        Scrollbars sb = Scrollbars.vertical(h.gui, rows(h, 20, 100, 40));
        page(h).children(sb.node());
        h.frame();
        return sb;
    }

    @Test
    void draggingTheThumbMovesTheViewAndKeepsTheGrabUnderThePointer() {
        try (Headless h = new Headless()) {
            Scrollbars sb = fullPage(h);
            Node bar = sb.verticalBar().orElseThrow().node();
            float x = at(bar).rect().x() + BAR / 2f;
            Thumb before = Thumb.of(sb.scroller().y().range().value(), H, BAR);

            float grabAt = before.start() + 10f;
            h.press(x, grabAt);
            assertEquals(0f, sb.scroller().y().range().value().offset(), 0.5f, "taking hold moves nothing");
            h.move(x, grabAt + before.travel() / 2f);
            assertEquals(250f, sb.scroller().y().range().value().offset(), 2f, "half the travel is half the range");
            h.release(x, grabAt + before.travel() / 2f);

            Thumb after = Thumb.of(sb.scroller().y().range().value(), H, BAR);
            assertEquals(grabAt + before.travel() / 2f, after.start() + 10f, 1.5f,
                    "the point taken hold of is still under the pointer");
        }
    }

    @Test
    void aPressOnTheTrackPagesTowardIt() {
        try (Headless h = new Headless()) {
            Scrollbars sb = fullPage(h);
            Node bar = sb.verticalBar().orElseThrow().node();
            float x = at(bar).rect().x() + BAR / 2f;

            h.press(x, H - 10f).release(x, H - 10f);
            assertEquals(300f, sb.scroller().y().range().value().offset(), 0.5f, "down by one view");
            h.press(x, 5f).release(x, 5f);
            assertEquals(0f, sb.scroller().y().range().value().offset(), 0.5f, "and back up by one");
        }
    }

    @Test
    void theWheelOverTheBarScrollsWhatItShows() {
        try (Headless h = new Headless()) {
            Scrollbars sb = fullPage(h);
            Node bar = sb.verticalBar().orElseThrow().node();
            h.wheel(-1, at(bar).rect().x() + BAR / 2f, H / 2f);
            assertEquals(48f, sb.scroller().y().range().value().offset(), 0.5f,
                    "one notch, though the bar is not inside what it scrolls");
        }
    }

    @Test
    void withNothingToScrollItKeepsItsRoomAndDrawsNoThumb() {
        try (Headless h = new Headless()) {
            Scrollbars sb = Scrollbars.vertical(h.gui, rows(h, 2, 100, 40));
            page(h).children(sb.node());
            h.frame().frame();
            Node bar = sb.verticalBar().orElseThrow().node();
            assertEquals(BAR, at(bar).rect().w(), 0.5f, "its room is kept: nothing appears or goes");
            assertEquals(2, marks(h, bar), "a rim and a trough, and no thumb");
        }
    }

    /** A source that is not a Scroller: no layout behind it, and the bar drives it all the same. */
    @Test
    void itDrivesAnySourceTheSameWay() {
        try (Headless h = new Headless()) {
            Camera camera = new Camera();
            Scrollbar bar = Scrollbar.horizontal(h.gui, camera);
            page(h).children(bar.node());
            h.frame().frame();
            assertEquals(3, marks(h, bar.node()), "a thumb, from the camera's range");

            float y = at(bar.node()).rect().y() + BAR / 2f;
            h.press(W - 5f, y).release(W - 5f, y);
            assertEquals(List.of(100f), camera.asked, "a press past the thumb asks for a page on");

            h.wheel(-1, W / 2f, y);
            assertEquals(2, camera.asked.size(), "the wheel asks too");

            camera.publish(new ScrollRange(1000f, 1000f, 0f));
            h.frame();
            assertEquals(2, marks(h, bar.node()), "and when it has nothing to show, the thumb goes");
        }
    }

    private static int marks(Headless h, Node n) {
        Picture p = h.retained(n).picture();
        return p == null ? 0 : p.marks().size();
    }

    /** A 3D view's pan, say: a range it owns, and offsets it is asked for and keeps a record of. */
    private static final class Camera implements ScrollSource {
        private final State<ScrollRange> range;
        private final Committer<ScrollRange, ScrollRange> set;
        final List<Float> asked = new ArrayList<>();

        Camera() {
            State.Builder<ScrollRange> b = State.of(new ScrollRange(1000f, 100f, 0f));
            set = b.mutation("set", (current, next) -> next);
            range = b.build();
        }

        void publish(ScrollRange r) {
            range.commit(set, r);
        }

        @Override
        public State<ScrollRange> range() {
            return range;
        }

        @Override
        public void scrollTo(float offset) {
            asked.add(offset);
        }
    }
}
