package dev.vexelray.gui.formatbench;

import dev.vexelray.fields.sdg.SdgRender;
import sibarum.imagelib.Frames;
import sibarum.imagelib.Imagelib;

import java.util.List;

/**
 * One way to put a piece of art on the screen at a size, on the CPU: what it draws, straight RGBA, {@code side}
 * pixels square. Each is what an application does with that form: an SDG is drawn by its reference renderer, an SVG
 * rasterised (by imagelib, native, or by vexelray-fields' renderer in Java, 4 × 4 samples a pixel), and a shipped PNG
 * decoded once and scaled by bilinear filtering, as a texture is sampled.
 */
enum Contender {

    SDG_CPU("SDG, Java", "the drawing by SdgRender (Java, one thread): the reference, written to be read, not tuned") {
        @Override
        byte[] draw(Corpus.Art art, int side) {
            return SdgRender.render(art.sdg(), side, side).rgba();
        }
    },
    SDG_C("SDG, C", "the drawing by sdg-signed-distance-graphics' C rasterizer (native, one thread, /O2), called through "
            + "the foreign-function API: SdgRender's pixels, byte for byte, with the work it can skip skipped (tiles "
            + "wholly in or out of a shape, texel taps once a row and a column)") {
        @Override
        byte[] draw(Corpus.Art art, int side) {
            return NativeSdg.render(art, side, 1);
        }
    },
    SDG_C_THREADS("SDG, C, all cores", "the same C rasterizer on one thread a processor ("
            + Runtime.getRuntime().availableProcessors() + " here), the picture cut into bands of rows: the same "
            + "pixels; every other contender here is one thread") {
        @Override
        byte[] draw(Corpus.Art art, int side) {
            return NativeSdg.render(art, side, 0);
        }
    },
    SVG_IMAGELIB("SVG, imagelib", "the SVG rasterised by imagelib (native, built from Rust), parse included, as Ico.java made icons") {
        @Override
        byte[] draw(Corpus.Art art, int side) {
            return Imagelib.rasterize(art.svg(), side, side).rgba();
        }
    },
    SVG_JAVA("SVG, Java", "the SVG, parsed once, rasterised by vexelray-fields' renderer (Java, one thread) at 4 × 4 samples a pixel") {
        @Override
        byte[] draw(Corpus.Art art, int side) {
            return art.parsed().render(side, side, 4).rgba();
        }
    },
    PNG_256("PNG 256, scaled", "a 256 px PNG, decoded once, scaled to the size by bilinear filtering (Java)") {
        @Override
        byte[] draw(Corpus.Art art, int side) {
            return Resample.bilinear(art.raster().get(256), side);
        }
    },
    PNG_1024("PNG 1024, scaled", "a 1024 px PNG, decoded once, scaled to the size by bilinear filtering (Java)") {
        @Override
        byte[] draw(Corpus.Art art, int side) {
            return Resample.bilinear(art.raster().get(1024), side);
        }
    };

    final String label;
    final String how;

    Contender(String label, String how) {
        this.label = label;
        this.how = how;
    }

    abstract byte[] draw(Corpus.Art art, int side);

    /** Every contender that can run here: the C ones only where its library was built. */
    static List<Contender> all() {
        return java.util.Arrays.stream(values())
                .filter(c -> (c != SDG_C && c != SDG_C_THREADS) || NativeSdg.available()).toList();
    }

    /** Bilinear scaling of straight RGBA, through premultiplied colour, so a transparent neighbour never darkens. */
    static final class Resample {

        private Resample() {
        }

        static byte[] bilinear(Frames from, int side) {
            int w = from.width(), h = from.height();
            byte[] src = from.rgba();
            byte[] out = new byte[side * side * 4];
            double sx = (double) w / side, sy = (double) h / side;
            for (int j = 0; j < side; j++) {
                double fy = Math.clamp((j + 0.5) * sy - 0.5, 0, h - 1);
                int y0 = (int) fy, y1 = Math.min(y0 + 1, h - 1);
                double ay = fy - y0;
                for (int i = 0; i < side; i++) {
                    double fx = Math.clamp((i + 0.5) * sx - 0.5, 0, w - 1);
                    int x0 = (int) fx, x1 = Math.min(x0 + 1, w - 1);
                    double ax = fx - x0;
                    double[] acc = new double[4];
                    add(acc, src, (y0 * w + x0) * 4, (1 - ax) * (1 - ay));
                    add(acc, src, (y0 * w + x1) * 4, ax * (1 - ay));
                    add(acc, src, (y1 * w + x0) * 4, (1 - ax) * ay);
                    add(acc, src, (y1 * w + x1) * 4, ax * ay);
                    int o = (j * side + i) * 4;
                    double a = acc[3];
                    for (int c = 0; c < 3; c++) {
                        out[o + c] = (byte) Math.round(Math.clamp(a > 0 ? acc[c] / a : 0, 0, 255));
                    }
                    out[o + 3] = (byte) Math.round(Math.clamp(a, 0, 255));
                }
            }
            return out;
        }

        private static void add(double[] acc, byte[] src, int o, double k) {
            double a = src[o + 3] & 0xFF;
            for (int c = 0; c < 3; c++) {
                acc[c] += (src[o + c] & 0xFF) * a * k;
            }
            acc[3] += a * k;
        }
    }
}
