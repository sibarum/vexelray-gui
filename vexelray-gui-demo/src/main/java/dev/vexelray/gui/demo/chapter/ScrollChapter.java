package dev.vexelray.gui.demo.chapter;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.ScrollRange;
import dev.vexelray.gui.core.ScrollSource;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.gui.scroll.Scrollbar;
import dev.vexelray.gui.scroll.Scrollbars;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Committer;
import sibarum.atchung.State;

/**
 * Scrolling as elements (docs/plans/scrolling.md): a {@code Scroller} masks and scrolls, a {@link Scrollbar} is a
 * node placed beside it, and where the bar's room comes from is decided by the box they are in, like any other
 * node's. The last section binds a bar to something that is not a Scroller at all.
 */
public final class ScrollChapter implements Chapter {

    @Override
    public String title() {
        return "Scrolling";
    }

    @Override
    public String blurb() {
        return "A Scroller masks and scrolls; a scrollbar is an element beside it. Its room is ordinary layout, "
                + "and it drives anything with a range.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();

        // ---- a tab bar: sized to its headers, so the bar adds to it when they overflow ------------------------

        Tabs tabs = new Tabs(gui);
        int[] count = {0};
        Runnable addTab = () -> {
            count[0]++;
            tabs.add("Tab " + count[0], Ui.prose(gui, "Page " + count[0] + "."));
        };
        for (int i = 0; i < 4; i++) {
            addTab.run();
        }
        tabs.node().height(Length.rem(7));
        Node addTen = Ui.button(gui, "Add ten tabs", () -> {
            for (int i = 0; i < 10; i++) {
                addTab.run();
            }
        });
        gui.landmark("scroll.tabs", tabs.node());
        gui.landmark("scroll.addtabs", addTen);

        // ---- fields that are cut off: they grow while focused, and scroll only past the room ----------------

        TextField path = new TextField(gui,
                "C:/Users/someone/Documents/GitHub/vexelray-gui/docs/plans/scrolling.md");
        path.node().width(Length.rem(14));
        TextField note = new TextField(gui, "One\nTwo\nThree\nFour\nFive\nSix\nSeven\nEight").multiline(true);
        note.node().width(Length.rem(14)).height(Length.rem(4));
        Node neighbour = Ui.prose(gui, "A neighbour the fields grow over while they have focus; it does not move.");
        gui.landmark("scroll.path", path.node());
        gui.landmark("scroll.note", note.node());

        // ---- a page: a fixed height, so the bar's width comes out of the text --------------------------------

        Node lines = gui.column().width(Length.FILL).gap(Length.rem(0.25f));
        for (int i = 1; i <= 40; i++) {
            lines.append(Ui.prose(gui, "Line " + i + ": a page longer than its box scrolls in a Scroller, and its "
                    + "bar stands beside it, taking its width from the page."));
        }
        Scrollbars page = Scrollbars.vertical(gui, lines);
        page.node().width(Length.FILL).height(Length.rem(12));
        gui.landmark("scroll.page", page.node());

        // ---- both ways --------------------------------------------------------------------------------------

        Node grid = gui.column().gap(Length.dp(4));
        for (int r = 0; r < 20; r++) {
            Node row = gui.row().gap(Length.dp(4));
            for (int c = 0; c < 20; c++) {
                row.append(gui.text(r + "," + c).size(Length.rem(3.5f), Length.rem(2f))
                        .background((r + c) % 2 == 0 ? Role.PANEL : Role.WELL)
                        .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE));
            }
            grid.append(row);
        }
        Scrollbars both = Scrollbars.both(gui, grid);
        both.node().width(Length.FILL).height(Length.rem(14));
        gui.landmark("scroll.both", both.node());

        // ---- a bar on something that is not a Scroller -------------------------------------------------------

        Panorama panorama = new Panorama(gui);
        Scrollbar pan = Scrollbar.horizontal(gui, panorama);
        gui.landmark("scroll.panorama", panorama.view);
        gui.landmark("scroll.pan", pan.node());

        // The chapter's own page is a Scroller with a bar beside it, like the sections in it: the gallery gives a
        // chapter the whole content area, and what is longer than that is the chapter's to scroll.
        Node sections = gui.column().width(Length.FILL).gap(Ui.GAP).alignItems(AlignItems.STRETCH).children(
                        Ui.strip(gui,
                                Ui.heading(gui, "A tab bar"),
                                Ui.prose(gui, "The bar is as tall as its headers. Add tabs until they do not fit: "
                                        + "a scrollbar appears under them, the bar grows by it and the page moves "
                                        + "down. The headers keep all of their height, and every title stays whole "
                                        + "and clickable."),
                                addTen, tabs.node()),
                        Ui.strip(gui,
                                Ui.heading(gui, "Fields that are cut off"),
                                Ui.prose(gui, "Click into either. A field whose text does not fit grows over its "
                                        + "neighbours to show all of it while it has focus, keeping its place, and "
                                        + "gives the room back when it loses focus. Only past the room it is in "
                                        + "does it scroll."),
                                gui.row().width(Length.FILL).gap(Ui.GAP).alignItems(AlignItems.START)
                                        .children(path.node(), note.node(), neighbour)),
                        Ui.strip(gui,
                                Ui.heading(gui, "A page"),
                                Ui.prose(gui, "Here the box has a fixed height, so the content gives way to it, "
                                        + "and the bar takes its width from the page beside it."),
                                page.node()),
                        Ui.strip(gui,
                                Ui.heading(gui, "Both ways"),
                                Ui.prose(gui, "A bar under, a bar beside, and a plain square where they meet."),
                                both.node()),
                        Ui.strip(gui,
                                Ui.heading(gui, "A bar on something else"),
                                Ui.prose(gui, "This bar is bound to a source that is not a Scroller: a view that "
                                        + "pans across a drawing four times its width, the way a 3D view would pan "
                                        + "its camera. Drag the thumb, press the track, or turn the wheel over "
                                        + "either."),
                                panorama.view, pan.node()));
        return Scrollbars.vertical(gui, sections).node();
    }

    /**
     * A view onto a drawing wider than itself, panned by redrawing at an offset: no layout, no Scroller. It owns its
     * range — its view is its width, as the layout gave it — and moves when asked, which is all a bar needs.
     */
    private static final class Panorama implements ScrollSource {

        /** How many views wide the drawing is. */
        private static final float WIDE = 4f;

        private final Gui gui;
        final Node view;
        private final State<ScrollRange> range;
        private final Committer<ScrollRange, ScrollRange> set;

        Panorama(Gui gui) {
            this.gui = gui;
            this.view = gui.box().width(Length.FILL).height(Length.rem(6)).background(Role.WELL)
                    .corner(Length.rem(0.5f)).clip(true);
            State.Builder<ScrollRange> b = State.of(ScrollRange.NONE);
            this.set = b.mutation("set", (current, next) -> next);
            this.range = b.build();
            gui.onResize(view, layout -> move(range.value().offset()));
            // The wheel over the view pans it too: it is not inside anything that scrolls.
            gui.onWheel(view, w -> scrollBy(w.dx() != 0f ? w.dx() : w.dy()));
        }

        @Override
        public State<ScrollRange> range() {
            return range;
        }

        @Override
        public void scrollTo(float offset) {
            move(offset);
        }

        private synchronized void move(float offset) {
            var box = view.layout();
            if (!box.present()) {
                return;
            }
            float w = box.rect().w();
            float h = box.rect().h();
            ScrollRange next = new ScrollRange(w * WIDE, w, Math.max(0f, Math.min(w * (WIDE - 1f), offset)));
            range.commitIfChanged(set, next);
            view.picture(draw(next, w, h));
        }

        /** Hills of circles along the whole drawing, drawn from where the view starts. */
        private dev.vexelray.gui.draw.Picture draw(ScrollRange r, float w, float h) {
            Sketch s = new Sketch();
            Color ink = gui.theme().color(Role.ACCENT);
            Color faint = gui.theme().color(Role.FAINT);
            float step = h * 0.5f;
            for (float x = 0f; x < r.content(); x += step) {
                float sx = x - r.offset();
                if (sx < -step || sx > w + step) {
                    continue;
                }
                float cy = h * (0.5f + 0.3f * (float) Math.sin(x / (r.view() * 0.35f)));
                s.circle(sx, cy, h * 0.12f, ink);
                s.line(sx, h, sx, cy, 1.5, faint);
            }
            return s.picture();
        }
    }
}
