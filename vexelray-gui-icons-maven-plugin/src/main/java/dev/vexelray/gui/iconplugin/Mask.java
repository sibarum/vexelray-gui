package dev.vexelray.gui.iconplugin;

import sibarum.imagelib.Frames;
import sibarum.imagelib.Imagelib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A drawing as the mask {@link dev.vexelray.canvas.DistanceField#of} makes a field from: {@code fine} pixels square,
 * straight RGBA8, the shape wherever alpha is at least one half. Three kinds of art come in:
 *
 * <ul>
 *   <li><b>SVG</b>, rasterised at the mask's size by imagelib, so its own antialiasing, knockouts and clips come out
 *       as it means them. The shape is its alpha, as {@code vexelray-icons}' masks are drawn.</li>
 *   <li><b>A black-and-white raster</b>: dark on light, the shape the dark ({@link Coverage#LUMINANCE}). Any alpha it
 *       has is laid over white first, so a transparent background reads as background.</li>
 *   <li><b>A mask with transparency</b>: the shape its alpha, whatever its colour ({@link Coverage#ALPHA}).</li>
 * </ul>
 *
 * A raster is decoded at its own size, fitted into the square with its aspect kept and centred, and read between its
 * pixels bilinearly before the half is cut: a 32 px drawing becomes a smooth-edged shape rather than a staircase, and
 * a field made from it is as good as the drawing's curves, not its pixel grid.
 */
final class Mask {

    /** Which part of a drawing is the shape. */
    enum Coverage {
        /** SVG: {@link #ALPHA}. A raster: {@link #ALPHA} if any pixel is less than opaque, else {@link #LUMINANCE}. */
        AUTO,
        /** The shape is where the drawing is opaque. */
        ALPHA,
        /** The shape is where the drawing is dark, over white. */
        LUMINANCE;

        static Coverage parse(String s) {
            if (s == null || s.isBlank()) {
                return AUTO;
            }
            try {
                return valueOf(s.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("coverage '" + s + "' is none of auto, alpha, luminance");
            }
        }
    }

    /** Below this alpha somewhere, a raster is taken as a mask with transparency rather than black on white. */
    private static final int OPAQUE_ENOUGH = 250;

    /** What lies past a raster's edge once it is fitted into the square: background, whether or not it is inverted. */
    private static final float OUTSIDE = 0f;

    private Mask() {
    }

    /** The drawing at {@code file} as a mask {@code fine} pixels square. */
    static byte[] read(Path file, int fine, Coverage coverage, boolean invert) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        Imagelib.Kind kind = Imagelib.probe(bytes);
        if (kind == Imagelib.Kind.VECTOR) {
            Frames frames = Imagelib.rasterize(bytes, fine, fine);
            return fromRgba(frames.rgba(0), fine, fine, fine, coverage == Coverage.AUTO ? Coverage.ALPHA : coverage,
                    invert);
        }
        if (kind == Imagelib.Kind.RASTER) {
            Frames frames = Imagelib.decode(bytes);
            return fromRgba(frames.rgba(0), frames.width(), frames.height(), fine, coverage, invert);
        }
        throw new IOException("not an image imagelib reads (SVG, PNG, JPEG, BMP, GIF, TGA, TIFF, WebP, QOI, ICO): "
                + file);
    }

    /**
     * {@code rgba}, {@code w} by {@code h}, as a mask {@code fine} pixels square: each pixel's share of shape by
     * {@code coverage} (flipped if {@code invert}), fitted and centred with its aspect kept, sampled bilinearly, and
     * written as alpha with the colour white.
     */
    static byte[] fromRgba(byte[] rgba, int w, int h, int fine, Coverage coverage, boolean invert) {
        if (w <= 0 || h <= 0 || rgba.length != w * h * 4) {
            throw new IllegalArgumentException("a drawing " + w + " x " + h + " is " + w * h * 4 + " bytes; was given "
                    + rgba.length);
        }
        Coverage c = coverage == Coverage.AUTO ? (translucent(rgba) ? Coverage.ALPHA : Coverage.LUMINANCE) : coverage;
        float[] share = new float[w * h];
        for (int i = 0; i < share.length; i++) {
            float a = (rgba[i * 4 + 3] & 0xFF) / 255f;
            float s;
            if (c == Coverage.ALPHA) {
                s = a;
            } else {
                float luma = (0.2126f * (rgba[i * 4] & 0xFF) + 0.7152f * (rgba[i * 4 + 1] & 0xFF)
                        + 0.0722f * (rgba[i * 4 + 2] & 0xFF)) / 255f;
                s = 1f - (a * luma + (1f - a));
            }
            share[i] = invert ? 1f - s : s;
        }
        if (w == fine && h == fine) {
            return write(share, fine);
        }
        float scale = (float) fine / Math.max(w, h);
        float offX = (fine - w * scale) / 2f;
        float offY = (fine - h * scale) / 2f;
        float[] out = new float[fine * fine];
        for (int fy = 0; fy < fine; fy++) {
            float sy = (fy + 0.5f - offY) / scale - 0.5f;
            for (int fx = 0; fx < fine; fx++) {
                float sx = (fx + 0.5f - offX) / scale - 0.5f;
                out[fy * fine + fx] = sample(share, w, h, sx, sy);
            }
        }
        return write(out, fine);
    }

    /** {@code share} between its pixels at {@code (x, y)}, edge pixels held out to half a pixel, nothing beyond. */
    private static float sample(float[] share, int w, int h, float x, float y) {
        if (x < -0.5f || y < -0.5f || x > w - 0.5f || y > h - 0.5f) {
            return OUTSIDE;
        }
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float tx = x - x0;
        float ty = y - y0;
        float a = at(share, w, h, x0, y0);
        float b = at(share, w, h, x0 + 1, y0);
        float c = at(share, w, h, x0, y0 + 1);
        float d = at(share, w, h, x0 + 1, y0 + 1);
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty;
    }

    private static float at(float[] share, int w, int h, int x, int y) {
        return share[Math.clamp(y, 0, h - 1) * w + Math.clamp(x, 0, w - 1)];
    }

    private static boolean translucent(byte[] rgba) {
        for (int i = 3; i < rgba.length; i += 4) {
            if ((rgba[i] & 0xFF) < OPAQUE_ENOUGH) {
                return true;
            }
        }
        return false;
    }

    private static byte[] write(float[] share, int fine) {
        byte[] mask = new byte[fine * fine * 4];
        for (int i = 0; i < share.length; i++) {
            mask[i * 4] = (byte) 255;
            mask[i * 4 + 1] = (byte) 255;
            mask[i * 4 + 2] = (byte) 255;
            mask[i * 4 + 3] = (byte) Math.round(Math.clamp(share[i], 0f, 1f) * 255f);
        }
        return mask;
    }
}
