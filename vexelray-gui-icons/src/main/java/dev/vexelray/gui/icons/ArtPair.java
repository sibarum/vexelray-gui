package dev.vexelray.gui.icons;

import dev.vexelray.gui.core.ImageRegion;
import dev.vexelray.text.AtlasPixels;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A mark baked by the icon plugin's {@code pair} goal: its shape as a distance field and its colour, side by side in one
 * atlas per size it is shown at, so a look reads both from the texture it has bound. Read from {@code <name>.pair} and
 * {@code <name>-<size>.vxpx} beside a class.
 *
 * <p>The field is in the MTSDF layout at two ranges (the median of red, green and blue precise near the edge, alpha true
 * across the margin), for {@code Condense.MTSDF} in vexelray-shadelets; the colour is premultiplied. A part of the
 * drawing may have been lifted out of the shape and kept in the colour: {@link #lift()} says where it sits.
 */
public final class ArtPair {

    /** Where a lifted part sits: its centre and radius, as shares of the mark's square. */
    public record Lift(float x, float y, float radius) {
    }

    /**
     * One size's atlas: the field cell at its top left, {@code fieldSide} texels square, and the colour square
     * {@code size} across at {@code (colourX, colourY)}.
     */
    public record Atlas(int size, int width, int height, int colourX, int colourY, int fieldSide, byte[] rgba) {

        /** The field's cell, margin and all, in texture coordinates. */
        public ImageRegion field() {
            return ImageRegion.pixels(0, 0, fieldSide, fieldSide, width, height);
        }

        /** The colour square, in texture coordinates. */
        public ImageRegion colour() {
            return ImageRegion.pixels(colourX, colourY, size, size, width, height);
        }
    }

    private final Class<?> anchor;
    private final String name;
    private final int field;
    private final int spread;
    private final float near;
    private final int[] sizes;
    private final Map<Integer, int[]> cells;
    private final Lift lift;

    private ArtPair(Class<?> anchor, String name, int field, int spread, float near, int[] sizes,
                    Map<Integer, int[]> cells, Lift lift) {
        this.anchor = anchor;
        this.name = name;
        this.field = field;
        this.spread = spread;
        this.near = near;
        this.sizes = sizes;
        this.cells = cells;
        this.lift = lift;
    }

    /** The pair {@code name} beside {@code anchor}, as {@link Class#getResourceAsStream} finds it; null if none is. */
    public static ArtPair read(Class<?> anchor, String name) throws IOException {
        String text;
        try (InputStream in = anchor.getResourceAsStream(name + ".pair")) {
            if (in == null) {
                return null;
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> keys = new HashMap<>();
        for (String line : text.split("\\R")) {
            int eq = line.indexOf('=');
            if (!line.startsWith("#") && eq > 0) {
                keys.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        try {
            if (!"1".equals(keys.get("format"))) {
                throw new IOException("an art pair of format " + keys.get("format") + "; this reads 1");
            }
            int[] sizes = Arrays.stream(keys.get("sizes").split("\\s+")).mapToInt(Integer::parseInt).sorted()
                    .toArray();
            Map<Integer, int[]> cells = new HashMap<>();
            for (int size : sizes) {
                cells.put(size, Arrays.stream(keys.get("atlas." + size).split("\\s+")).mapToInt(Integer::parseInt)
                        .toArray());
            }
            Lift lift = null;
            if (keys.containsKey("lift")) {
                String[] l = keys.get("lift").split("\\s+");
                lift = new Lift(Float.parseFloat(l[0]), Float.parseFloat(l[1]), Float.parseFloat(l[2]));
            }
            return new ArtPair(anchor, name, Integer.parseInt(keys.get("field")), Integer.parseInt(keys.get("spread")),
                    Float.parseFloat(keys.get("near")), sizes, cells, lift);
        } catch (RuntimeException e) {
            throw new IOException("a malformed art pair " + name + ".pair: " + e, e);
        }
    }

    /** The field cell's side in texels: the shape's square and its margin each side. */
    public int fieldSide() {
        return field + 2 * spread;
    }

    /** The field's margin, in texels. */
    public int spread() {
        return spread;
    }

    /** The colour sizes, smallest first. */
    public int[] sizes() {
        return sizes.clone();
    }

    /** The share of the far range the near channels span. */
    public float near() {
        return near;
    }

    /** The margin as a share of the shape's square, each side. */
    public float margin() {
        return (float) spread / field;
    }

    /** Where the lifted part sits; null if nothing was lifted. */
    public Lift lift() {
        return lift;
    }

    /** The size to draw the mark at {@code px} pixels across with: the smallest at least that big, else the largest. */
    public int sizeFor(float px) {
        for (int s : sizes) {
            if (s >= px - 0.5f) {
                return s;
            }
        }
        return sizes[sizes.length - 1];
    }

    /** The atlas of {@code size}, one of the pair's sizes, read and inflated. */
    public Atlas atlas(int size) throws IOException {
        int[] cell = cells.get(size);
        if (cell == null) {
            throw new IllegalArgumentException(name + " has no size " + size + "; it has " + Arrays.toString(sizes));
        }
        String file = name + "-" + size + ".vxpx";
        try (InputStream in = anchor.getResourceAsStream(file)) {
            if (in == null) {
                throw new IOException("no " + file + " beside " + anchor.getName());
            }
            AtlasPixels pixels = AtlasPixels.read(in);
            if (pixels.width() != cell[0] || pixels.height() != cell[1] || pixels.channels() != 4) {
                throw new IOException(String.format(Locale.ROOT, "%s is %d x %d x %d; its pair says %d x %d x 4",
                        file, pixels.width(), pixels.height(), pixels.channels(), cell[0], cell[1]));
            }
            return new Atlas(size, cell[0], cell[1], cell[2], cell[3], fieldSide(), pixels.texels());
        }
    }
}
