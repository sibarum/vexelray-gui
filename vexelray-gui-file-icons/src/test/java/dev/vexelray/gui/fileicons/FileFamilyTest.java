package dev.vexelray.gui.fileicons;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.IconSource;
import dev.vexelray.gui.core.ImageField;
import dev.vexelray.gui.core.ImageRegion;
import dev.vexelray.text.AtlasPixels;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The baked atlas and the generated enums agree: every sheet and emblem is a cell of the atlas, and holds its shape. */
class FileFamilyTest {

    private static final Color INK = new Color(1f, 1f, 1f, 1f);

    @Test
    void theAtlasIsTheSizeTheEnumsWereGeneratedFor() {
        AtlasPixels pixels = FileIconAtlas.read();
        assertEquals(AtlasLayout.WIDTH, pixels.width());
        assertEquals(AtlasLayout.HEIGHT, pixels.height());
        assertEquals(AtlasLayout.WIDTH * AtlasLayout.HEIGHT * 4, FileIconAtlas.INSTANCE.rgba().length);
    }

    @Test
    void everyCellIsInsideTheAtlasAndNoTwoShareOne() {
        Set<ImageRegion> seen = new HashSet<>();
        for (ImageField f : fields()) {
            ImageRegion r = f.region();
            assertSame(FileIconAtlas.INSTANCE, f.texture());
            assertTrue(r.u0() >= 0f && r.v0() >= 0f && r.u1() <= 1f && r.v1() <= 1f, r.toString());
            assertTrue(seen.add(r), r + " is a cell of its own");
        }
    }

    @Test
    void eachCellHoldsItsShapeWithItsMarginOutside() {
        byte[] atlas = FileIconAtlas.INSTANCE.rgba();
        for (ImageField f : fields()) {
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
            assertTrue(max > 128, f.region() + " has something inside its edge");
            assertTrue(border < 64, f.region() + "'s cell border reads far outside: " + border);
        }
    }

    @Test
    void familiesOfAKindShareTheirSheet() {
        assertSame(FileSheet.PAGE, FileFamily.SOURCE_CODE.sheet());
        assertSame(FileSheet.PAGE, FileFamily.SCRIPTS.sheet());
        assertSame(FileSheet.FOLDER, FileFamily.FOLDERS.sheet());
        assertNull(FileFamily.FOLDERS.emblem(), "a folder is its sheet alone");
        assertEquals(0.25f, FileSheet.PAGE.field(16f, INK).margin(), 1e-6f);
    }

    @Test
    void aFamilyIsFoundByItsFileName() {
        assertSame(FileFamily.DISK_IMAGES, FileFamily.named("disk-images"));
        assertNull(FileFamily.named("no-such-family"));
    }

    @Test
    void aFamilyIsFoundByAnExtensionItCovers() {
        assertSame(FileFamily.SOURCE_CODE, FileFamily.ofExtension("java"));
        assertSame(FileFamily.DATA_AND_CONFIG, FileFamily.ofExtension("JSON"));
        assertSame(FileFamily.UNKNOWN, FileFamily.ofExtension("no-such-extension"));
        assertSame(FileFamily.UNKNOWN, FileFamily.ofExtension(""));
        for (FileFamily family : FileFamily.values()) {
            for (String e : family.extensions()) {
                assertSame(family, FileFamily.ofExtension(e), "." + e);
            }
        }
    }

    @Test
    void everyFamilyHasAHueOnBothThemesAndAnInkWhenItHasAnEmblem() {
        for (FileFamily family : FileFamily.values()) {
            for (boolean dark : new boolean[] {true, false}) {
                assertTrue(family.hue(dark) != null, family + " has a hue");
                assertEquals(family.emblem() != null, family.ink(dark) != null, family + " has an ink iff an emblem");
            }
        }
        assertEquals(Color.rgb(0x3fd6ea), FileFamily.SOURCE_CODE.hue(true));
        assertEquals(Color.rgb(0xffffff), FileFamily.SOURCE_CODE.ink(false));
    }

    private static List<ImageField> fields() {
        List<ImageField> fields = new ArrayList<>();
        for (FileSheet sheet : FileSheet.values()) {
            fields.add(sheet.field(16f, INK));
        }
        for (FileFamily family : FileFamily.values()) {
            IconSource emblem = family.emblem();
            if (emblem != null) {
                fields.add(emblem.field(16f, INK));
            }
        }
        return fields;
    }
}
