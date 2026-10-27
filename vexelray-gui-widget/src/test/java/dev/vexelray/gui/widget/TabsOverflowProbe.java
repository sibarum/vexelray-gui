package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Investigation, not a regression test: the real tab bar's geometry once it overflows, at three zooms. Skipped
 * unless asked for with {@code "-DargLine=-Dinvestigate=true"}; docs/plans/scrollbar-room.md §2 quotes the bar as it
 * was, and docs/plans/scrolling.md step 3 the bar as it is: headers in a Scroller, a scrollbar under them.
 */
@EnabledIfSystemProperty(named = "investigate", matches = "true")
class TabsOverflowProbe {

    @Test
    void probe() {
        for (float zoom : new float[]{1f, 1.5f, 2f}) {
            try (HeadlessGui h = new HeadlessGui()) {
                h.gui.zoom(zoom);
                Tabs tabs = new Tabs(h.gui);
                for (int i = 0; i < 30; i++) {
                    tabs.add("bash " + i, h.gui.text("page " + i));
                }
                h.gui.root().children(h.gui.column().width(Length.FILL).height(Length.FILL).children(tabs.node()));
                // The scrollbar is shown a frame after the range says the headers overflow.
                h.frame().frame().frame();
                RetainedNode header = h.retained(tabs.header(0));
                RetainedNode row = h.retained(tabs.bar());
                RetainedNode scroller = row.parent;
                RetainedNode strip = h.retained(tabs.strip());
                RetainedNode bar = strip.children.get(1);
                RetainedNode page = strip.parent.children.get(1);
                System.out.printf(
                        "zoom %.1f | strip y=%.1f h=%.1f | headers h=%.1f view %.1f wide of %.1f, overflowX=%b "
                                + "| header h=%.1f padY=%.1f textBox=%.1f (a 1.25em line needs %.1f) "
                                + "| bar shown=%b y=%.1f h=%.1f | page starts y=%.1f%n",
                        zoom, strip.y, strip.h, scroller.h, scroller.viewW, scroller.contentW, scroller.overflowX,
                        header.h, header.textPadYPx, header.h - 2f * header.textPadYPx, 1.25f * header.textSizePx,
                        bar.visible(), bar.y, bar.h, page.y);
            }
        }
    }
}
