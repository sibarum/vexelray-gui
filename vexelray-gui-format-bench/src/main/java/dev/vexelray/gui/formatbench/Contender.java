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

    SDG_CPU("SDG, CPU", "the drawing by SdgRender, the reference a GPU drawing is checked against") {
        @Override
        byte[] draw(Corpus.Art art, int side) {
            return SdgRender.render(art.sdg(), side, side).rgba();
        }
    },
    SVG_IMAGELIB("SVG, imagelib", "the SVG rasterised by imagelib (native), parse included, as Ico.java made icons") {
        @Override
        byte[] draw(Corpus.Art art, int side) {
            return Imagelib.rasterize(art.svg(), side, side).rgba();
        }
    },
    SVG_JAVA("SVG, Java", "the SVG, parsed once, rasterised by vexelray-fields' renderer at 4 × 4 samples a pixel") {
        @Override
        byte[] draw(Corpus.Art art, int side) {
            return art.parsed().render(side, side, 4).rgba();
        }
    },
    PNG_256("PNG 256, scaled", "a 256 px PNG, decoded once, scaled to the size by bilinear filtering") {
        @Override
        byte[] draw(Corpus.Art art, int side) {
            return Resample.bilinear(art.raster().get(256), side);
        }
    },
    PNG_1024("PNG 1024, scaled", "a 1024 px PNG, decoded once, scaled to the size by bilinear filtering") {
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

    static List<Contender> all() {
        return List.of(values());
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
