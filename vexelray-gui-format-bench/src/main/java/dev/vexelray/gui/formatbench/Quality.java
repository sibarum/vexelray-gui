package dev.vexelray.gui.formatbench;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.ImageSdg;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import sibarum.imagelib.Frames;
import sibarum.imagelib.Imagelib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How far a drawing is from the truth: the SVG rasterised by imagelib at four times the size and averaged down four by
 * four, in premultiplied colour. Compared in premultiplied colour, every channel of every pixel, in steps of 1/255: the
 * mean error, the worst, and the share of pixels any channel of which is more than {@value #VISIBLE} off.
 */
final class Quality {

    /** A step past which a difference is visible on most screens. */
    static final int VISIBLE = 8;

    /** The comparison: mean error, worst, and the share of pixels past {@link #VISIBLE}. */
    record Error(double mean, int worst, double visible) {
    }

    private Quality() {
    }

    /** The truth at {@code side}: straight RGBA. */
    static byte[] reference(Corpus.Art art, int side) {
        int k = 4, big = side * k;
        Frames f = Imagelib.rasterize(art.svg(), big, big);
        byte[] src = f.rgba();
        byte[] out = new byte[side * side * 4];
        for (int j = 0; j < side; j++) {
            for (int i = 0; i < side; i++) {
                double r = 0, g = 0, b = 0, a = 0;
                for (int y = 0; y < k; y++) {
                    for (int x = 0; x < k; x++) {
                        int o = ((j * k + y) * big + i * k + x) * 4;
                        double sa = (src[o + 3] & 0xFF) / 255.0;
                        r += (src[o] & 0xFF) * sa;
                        g += (src[o + 1] & 0xFF) * sa;
                        b += (src[o + 2] & 0xFF) * sa;
                        a += sa;
                    }
                }
                int o = (j * side + i) * 4;
                out[o] = (byte) Math.round(a > 0 ? r / a : 0);
                out[o + 1] = (byte) Math.round(a > 0 ? g / a : 0);
                out[o + 2] = (byte) Math.round(a > 0 ? b / a : 0);
                out[o + 3] = (byte) Math.round(a / (k * k) * 255);
            }
        }
        return out;
    }

    /** {@code got} against {@code want}, both straight RGBA of one size, compared premultiplied. */
    static Error compare(byte[] want, byte[] got) {
        long sum = 0;
        int worst = 0, visible = 0, pixels = want.length / 4;
        for (int o = 0; o < want.length; o += 4) {
            int wa = want[o + 3] & 0xFF, ga = got[o + 3] & 0xFF;
            int px = Math.abs(wa - ga);
            sum += px;
            for (int c = 0; c < 3; c++) {
                int d = Math.abs(Math.round((want[o + c] & 0xFF) * wa / 255f) - Math.round((got[o + c] & 0xFF) * ga / 255f));
                sum += d;
                px = Math.max(px, d);
            }
            worst = Math.max(worst, px);
            visible += px > VISIBLE ? 1 : 0;
        }
        return new Error(sum / (4.0 * pixels), worst, (double) visible / pixels);
    }

    /**
     * The SDG drawn on the GPU, by a node of a headless window ({@code GuiApp.capture}): {@code side} pixels square,
     * over {@code bg}, opaque. The reference is compared over the same grey ({@link #over}).
     */
    static byte[] gpu(Corpus.Art art, int side, float bg, Path dir) throws IOException {
        Gui gui = new Gui();
        gui.root().background(dev.vexelray.canvas.Color.rgb(bg, bg, bg))
                .append(gui.box().size(Length.dp(side), Length.dp(side)).sdg(ImageSdg.of(art.sdg())));
        Path out = dir.resolve(art.name() + "-" + side + "-gpu.png");
        GuiApp.capture(gui, side, side, bg, bg, bg, out.toString());
        Frames f = Imagelib.decode(Files.readAllBytes(out));
        return f.rgba();
    }

    /** Straight RGBA laid over an opaque grey {@code bg}: what a capture over that grey shows. */
    static byte[] over(byte[] straight, float bg) {
        byte[] out = new byte[straight.length];
        int g = Math.round(bg * 255);
        for (int o = 0; o < straight.length; o += 4) {
            float a = (straight[o + 3] & 0xFF) / 255f;
            for (int c = 0; c < 3; c++) {
                out[o + c] = (byte) Math.round((straight[o + c] & 0xFF) * a + g * (1 - a));
            }
            out[o + 3] = (byte) 255;
        }
        return out;
    }
}
