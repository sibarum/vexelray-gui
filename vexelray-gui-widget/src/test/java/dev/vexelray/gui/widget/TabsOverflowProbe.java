package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Investigation, not a regression test: the real tab bar's geometry once it overflows, at three zooms. Skipped
 * unless asked for with {@code "-DargLine=-Dinvestigate=true"}; docs/plans/scrollbar-room.md §2 quotes it.
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
                h.frame();
                RetainedNode header = h.retained(tabs.header(0));
                RetainedNode bar = header.parent;
                RetainedNode page = bar.parent.children.get(1);
                float em = bar.scrollbarPx / 0.85f;
                System.out.printf(
                        "zoom %.1f em %.1f | bar y=%.1f h=%.1f overflowX=%b viewH=%.1f strip=%.1f (%.0f%% of bar) "
                                + "| header h=%.1f padY=%.1f textBox=%.1f (a 1.25em line needs %.1f) "
                                + "| page starts y=%.1f%n",
                        zoom, em, bar.y, bar.h, bar.overflowX, bar.viewH, bar.scrollbarPx,
                        100f * bar.scrollbarPx / bar.h, header.h, header.textPadYPx,
                        header.h - 2f * header.textPadYPx, 1.25f * header.textSizePx, page.y);
            }
        }
    }
}
