package dev.vexelray.gui.iconplugin;

import dev.vexelray.canvas.DistanceField;

import java.io.IOException;
import java.util.List;

/**
 * Every icon's fields packed into one texture: large cells fill the top rows, small cells the rows under them, each
 * in the order the icons are named. A texel nobody writes reads as far outside, so a cell's margin and the unused end
 * of the last row draw nothing.
 *
 * @param rgba      the atlas, {@code width * height} RGBA8 texels, top row first
 * @param width     texels across
 * @param height    texels down
 * @param smallTop  the first row of small cells, in texels from the top
 * @param small     each icon's small cell, in order; -1 where it has no small drawing
 * @param large     each icon's large cell; -1 where it has no large drawing
 */
record Atlas(byte[] rgba, int width, int height, int smallTop, int[] small, int[] large) {

    /** {@code maxImageDimension2D}'s required minimum in the Vulkan spec: every device takes an atlas this size. */
    static final int VULKAN_GUARANTEED_DIMENSION = 4096;

    /** One icon and its drawings, either of which may be absent. */
    record Entry(String name, Drawing small, Drawing large) {
    }

    /** Makes a drawing a field: {@link #FROM_ART} in a build, something cheaper in a test. */
    interface Fields {
        DistanceField of(Drawing drawing, int inner, int spread) throws IOException;
    }

    /** Rasterise finely, through {@link Mask}, and make the field. */
    static final Fields FROM_ART = (d, inner, spread) ->
            DistanceField.of(Mask.read(d.file(), DistanceField.maskSide(inner), d.coverage(), d.invert()), inner, spread);

    int largeColumns(Grids g) {
        return width / g.largeCell();
    }

    int smallColumns(Grids g) {
        return width / g.smallCell();
    }

    /** Bakes {@code entries} at {@code grids}, {@code width} texels across, or as near square as fits when null. */
    static Atlas bake(List<Entry> entries, Grids grids, Integer width, Fields fields) throws IOException {
        int largeCount = (int) entries.stream().filter(e -> e.large() != null).count();
        int smallCount = (int) entries.stream().filter(e -> e.small() != null).count();
        int w = width != null ? width : width(largeCount, smallCount, grids);
        if (largeCount > 0 && w < grids.largeCell() || smallCount > 0 && w < grids.smallCell()) {
            throw new IllegalArgumentException("an atlas " + w + " texels across has no room for one cell");
        }
        int largeCols = w / grids.largeCell();
        int smallCols = w / grids.smallCell();
        int smallTop = ceil(largeCount, largeCols) * grids.largeCell();
        int height = Math.max(1, smallTop + ceil(smallCount, smallCols) * grids.smallCell());

        byte[] atlas = new byte[w * height * 4];
        for (int i = 3; i < atlas.length; i += 4) {
            atlas[i] = (byte) 255;
        }
        int[] small = new int[entries.size()];
        int[] large = new int[entries.size()];
        int nextLarge = 0;
        int nextSmall = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            large[i] = -1;
            small[i] = -1;
            if (e.large() != null) {
                int c = nextLarge++;
                blit(atlas, w, fields.of(e.large(), grids.largeInner(), grids.largeSpread()), grids.largeCell(),
                        (c % largeCols) * grids.largeCell(), (c / largeCols) * grids.largeCell());
                large[i] = c;
            }
            if (e.small() != null) {
                int c = nextSmall++;
                blit(atlas, w, fields.of(e.small(), grids.smallInner(), grids.smallSpread()), grids.smallCell(),
                        (c % smallCols) * grids.smallCell(), smallTop + (c / smallCols) * grids.smallCell());
                small[i] = c;
            }
        }
        return new Atlas(atlas, w, height, smallTop, small, large);
    }

    /**
     * A width for this many cells: of every row width that is a whole number of either cell, the one whose atlas has
     * the shortest longest side, and of those the least texture. Square-ish for a large set; for a set of a few, as
     * little as holds them, rather than a row of empty cells.
     */
    static int width(int largeCount, int smallCount, Grids g) {
        int least = Math.max(largeCount > 0 ? g.largeCell() : 0, smallCount > 0 ? g.smallCell() : 0);
        int best = least;
        long bestSide = Long.MAX_VALUE;
        long bestArea = Long.MAX_VALUE;
        for (int w = least; w <= VULKAN_GUARANTEED_DIMENSION; w++) {
            if (w % g.largeCell() != 0 && w % g.smallCell() != 0) {
                continue;
            }
            long h = height(largeCount, smallCount, g, w);
            long side = Math.max(w, h);
            long area = w * h;
            if (side < bestSide || side == bestSide && area < bestArea) {
                best = w;
                bestSide = side;
                bestArea = area;
            }
        }
        return best;
    }

    private static int height(int largeCount, int smallCount, Grids g, int w) {
        return ceil(largeCount, w / g.largeCell()) * g.largeCell() + ceil(smallCount, w / g.smallCell()) * g.smallCell();
    }

    private static void blit(byte[] atlas, int width, DistanceField f, int cell, int x0, int y0) {
        if (f.side() != cell) {
            throw new IllegalStateException("a field of " + f.side() + " in a cell of " + cell);
        }
        for (int y = 0; y < cell; y++) {
            System.arraycopy(f.rgba(), y * cell * 4, atlas, ((y0 + y) * width + x0) * 4, cell * 4);
        }
    }

    /** Rows of {@code per} that {@code n} cells take; none for none, however narrow the row. */
    static int ceil(int n, int per) {
        return n == 0 ? 0 : (n + per - 1) / per;
    }
}
