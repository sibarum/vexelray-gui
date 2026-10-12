package dev.vexelray.gui.icons;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.ImageField;
import dev.vexelray.gui.core.ImageRegion;
import dev.vexelray.text.AtlasPixels;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The baked atlas and the generated enum agree: every icon's cells are in the atlas, and hold its shape. */
class IconTest {

    private static final Color INK = new Color(1f, 1f, 1f, 1f);

    @Test
    void theAtlasIsTheSizeTheEnumWasGeneratedFor() {
        AtlasPixels pixels = IconAtlas.read();
        assertEquals(AtlasLayout.WIDTH, pixels.width());
        assertEquals(AtlasLayout.HEIGHT, pixels.height());
        assertEquals(AtlasLayout.WIDTH * AtlasLayout.HEIGHT * 4, IconAtlas.INSTANCE.rgba().length);
    }

    @Test
    void everyCellIsInsideTheAtlasAndNoTwoShareOne() {
        Set<ImageRegion> seen = new HashSet<>();
        for (Icon icon : Icon.values()) {
            for (IconSize size : IconSize.values()) {
                if (!icon.drawn(size)) {
                    continue;
                }
                ImageField f = icon.field(size, INK);
                ImageRegion r = f.region();
                assertSame(IconAtlas.INSTANCE, f.texture(), icon + " draws from the atlas");
                assertTrue(r.u0() >= 0f && r.v0() >= 0f && r.u1() <= 1f && r.v1() <= 1f, icon + " " + size + " " + r);
                assertTrue(seen.add(r), icon + " " + size + " has a cell of its own");
            }
        }
    }

    @Test
    void eachCellHoldsItsShapeWithItsMarginOutside() {
        byte[] atlas = IconAtlas.INSTANCE.rgba();
        for (Icon icon : Icon.values()) {
            for (IconSize size : IconSize.values()) {
                if (!icon.drawn(size)) {
                    continue;
                }
                ImageField f = icon.field(size, INK);
                int x0 = Math.round(f.region().u0() * AtlasLayout.WIDTH);
                int y0 = Math.round(f.region().v0() * AtlasLayout.HEIGHT);
                int max = 0;
                int border = 0;
                for (int y = 0; y < f.side(); y++) {
                    for (int x = 0; x < f.side(); x++) {
                        int v = atlas[((y0 + y) * AtlasLayout.WIDTH + x0 + x) * 4] & 0xFF;
                        max = Math.max(max, v);
                        if (x == 0 || y == 0 || x == f.side() - 1 || y == f.side() - 1) {
                            border = Math.max(border, v);
                        }
                    }
                }
                assertTrue(max > 128, icon + " " + size + " has something inside its edge");
                assertTrue(border < 64, icon + " " + size + "'s cell border reads far outside: " + border);
            }
        }
    }

    @Test
    void theDrawingIsChosenBySize() {
        assertEquals(IconSize.SMALL, IconSize.forPixels(16f));
        assertEquals(IconSize.SMALL, IconSize.forPixels(24f));
        assertEquals(IconSize.LARGE, IconSize.forPixels(32f));
        ImageField small = Icon.SAVE.field(16f, INK);
        ImageField large = Icon.SAVE.field(48f, INK);
        assertEquals(AtlasLayout.SMALL_CELL, small.side());
        assertEquals(AtlasLayout.LARGE_CELL, large.side());
        assertEquals(0.25f, small.margin(), 1e-6f);
        assertEquals(0.25f, large.margin(), 1e-6f);
    }

    @Test
    void anIconIsFoundByItsFileName() {
        assertSame(Icon.PIN_UP, Icon.named("pin-up"));
        assertNull(Icon.named("no-such-icon"));
    }
}
