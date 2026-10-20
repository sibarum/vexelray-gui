package dev.vexelray.gui.iconplugin;

import dev.vexelray.canvas.DistanceField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gathering art into a set, packing it, and what the set says about itself so another can extend it. */
class BakeTest {

    @TempDir
    Path dir;

    /** A field that is all one value, its cell's number, so where it landed can be read back. */
    private static Atlas.Fields numbered(List<Drawing> order) {
        return (d, inner, spread) -> {
            order.add(d);
            int side = inner + 2 * spread;
            byte[] rgba = new byte[side * side * 4];
            java.util.Arrays.fill(rgba, (byte) order.size());
            return new DistanceField(rgba, inner, spread);
        };
    }

    @Test
    void foldersAreGatheredByNameWithBothSizesPaired() throws IOException {
        touch("small/save-mask.svg", "small/open-mask.png", "small/readme.txt", "small/stray.svg");
        touch("large/save-mask.svg");
        List<Atlas.Entry> entries = Gather.entries(dir, List.of(folder("small", "small", "-mask"),
                folder("large", "large", "-mask")), List.of(), List.of(), "");
        assertEquals(List.of("open", "save"), entries.stream().map(Atlas.Entry::name).toList());
        assertNull(entries.get(0).large());
        assertTrue(entries.get(1).small().file().endsWith("save-mask.svg"));
        assertTrue(entries.get(1).large().file().toString().contains("large"));
    }

    @Test
    void anIconNamedOutrightJoinsThem() throws IOException {
        touch("small/save.svg", "art/brush 64px.png");
        IconArt brush = new IconArt();
        brush.name = "brush";
        brush.large = new File("art/brush 64px.png");
        brush.coverage = "luminance";
        List<Atlas.Entry> entries = Gather.entries(dir, List.of(folder("small", "small", "")), List.of(brush),
                List.of(), "");
        assertEquals(List.of("brush", "save"), entries.stream().map(Atlas.Entry::name).toList());
        assertEquals(Mask.Coverage.LUMINANCE, entries.get(0).large().coverage());
    }

    @Test
    void aNameTheBaseHoldsIsRefusedNamingWhatToUse() throws IOException {
        touch("small/save.svg");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Gather.entries(dir,
                List.of(folder("small", "small", "")), List.of(), List.of("open", "save"),
                "dev.vexelray.gui.icons.Icon"));
        assertTrue(e.getMessage().contains("dev.vexelray.gui.icons.Icon"), e.getMessage());
        assertTrue(e.getMessage().contains("SAVE"), e.getMessage());
    }

    @Test
    void aBadNameOrTwoDrawingsAtOneSizeAreRefused() throws IOException {
        touch("small/Save Icon.svg");
        assertThrows(IllegalArgumentException.class, () -> Gather.entries(dir, List.of(folder("small", "small", "")),
                List.of(), List.of(), ""));
        touch("twice/a/save.svg", "twice/b/save.png");
        assertThrows(IllegalArgumentException.class, () -> Gather.entries(dir,
                List.of(folder("twice/a", "small", ""), folder("twice/b", "small", "")), List.of(), List.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> Gather.entries(dir,
                List.of(folder("twice/a", "medium", "")), List.of(), List.of(), ""));
    }

    @Test
    void largeCellsFillTheTopAndSmallCellsTheRowsUnder() throws IOException {
        Drawing d = new Drawing(dir, Mask.Coverage.AUTO, false);
        List<Atlas.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            entries.add(new Atlas.Entry("i" + i, d, i % 2 == 0 ? d : null));
        }
        Grids g = Grids.SUITE;
        Atlas a = Atlas.bake(entries, g, 2 * g.largeCell(), numbered(new ArrayList<>()));
        assertEquals(2 * g.largeCell(), a.width());
        assertArrayEquals(new int[]{0, -1, 1, -1, 2}, a.large());
        assertArrayEquals(new int[]{0, 1, 2, 3, 4}, a.small());
        // 3 large in rows of 2, then 5 small in rows of 2 (192 / 72).
        assertEquals(2 * g.largeCell(), a.smallTop());
        assertEquals(2 * g.largeCell() + 3 * g.smallCell(), a.height());
        // The second large cell is icon 2's, the fourth field made (large then small, per icon), right of the first.
        assertEquals(4, a.rgba()[g.largeCell() * 4] & 0xFF);
        // Past the last small cell nothing was written: far outside, and opaque.
        int lastRow = a.height() - 1;
        assertEquals(0, a.rgba()[(lastRow * a.width() + a.width() - 1) * 4] & 0xFF);
        assertEquals(255, a.rgba()[(lastRow * a.width() + a.width() - 1) * 4 + 3] & 0xFF);
    }

    @Test
    void anUnsetWidthIsAWholeNumberOfACellAndAsSquareAsTheCellsAllow() {
        Grids g = Grids.SUITE;
        for (int n : new int[]{5, 40, 125, 400}) {
            int w = Atlas.width(n, n, g);
            assertTrue(w % g.largeCell() == 0 || w % g.smallCell() == 0, n + " icons: " + w);
            int h = Atlas.ceil(n, w / g.largeCell()) * g.largeCell() + Atlas.ceil(n, w / g.smallCell()) * g.smallCell();
            assertTrue(Math.max(w, h) <= 1.25 * Math.min(w, h), n + " icons: " + w + " x " + h);
        }
        // A set of one takes one column of either cell, not a row of empty ones.
        assertEquals(g.largeCell(), Atlas.width(1, 1, g));
        assertEquals(g.smallCell(), Atlas.width(0, 1, g));
    }

    @Test
    void theManifestReadsBackAndReservesTheBasesNamesToo() throws IOException {
        SetManifest m = new SetManifest("dev.sibarum.pix.PixIcon", new Grids(64, 16, 48, 12), List.of("brush", "lasso"),
                List.of("open", "save"));
        SetManifest back = SetManifest.read(new ByteArrayInputStream(m.text().getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(m, back);
        assertEquals(List.of("open", "save", "brush", "lasso"), back.reserved());
        assertEquals("META-INF/vexelray-icons/dev.sibarum.pix.PixIcon.properties",
                SetManifest.resource("dev.sibarum.pix.PixIcon"));
    }

    @Test
    void namesBecomeConstantsAndClassNamesFileNames() {
        assertEquals("PIN_UP", Sources.constant("pin-up"));
        assertEquals("MODELS_3D", Sources.constant("3d-models"));
        assertThrows(IllegalArgumentException.class, () -> Sources.constant("3"));
        assertEquals("pix-icon", Sources.kebab("PixIcon"));
        assertEquals("icon", Sources.kebab("Icon"));
    }

    @Test
    void theGeneratedEnumNamesEachIconWithItsCells() {
        String src = Sources.iconEnum("dev.sibarum.pix", "PixIcon", "PixIconAtlas", List.of("brush", "pen-pressure"),
                new int[]{0, -1}, new int[]{-1, 0}, "dev.vexelray.gui.icons.Icon", "{@code src/main/icons}");
        assertTrue(src.contains("public enum PixIcon implements BakedIcon"), src);
        assertTrue(src.contains("BRUSH(\"brush\", 0, -1)"), src);
        assertTrue(src.contains("PEN_PRESSURE(\"pen-pressure\", -1, 0);"), src);
        assertTrue(src.contains("{@link dev.vexelray.gui.icons.Icon}"), src);
    }

    private ArtDirectory folder(String path, String size, String suffix) {
        ArtDirectory d = new ArtDirectory();
        d.path = new File(path);
        d.size = size;
        d.suffix = suffix;
        return d;
    }

    private void touch(String... paths) throws IOException {
        for (String p : paths) {
            Path f = dir.resolve(p);
            Files.createDirectories(f.getParent());
            Files.writeString(f, "");
        }
    }
}
