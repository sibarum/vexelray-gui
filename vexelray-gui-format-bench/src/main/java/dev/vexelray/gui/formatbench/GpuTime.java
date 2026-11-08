package dev.vexelray.gui.formatbench;

import dev.vexelray.gui.core.Feed;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.ImageSdg;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.vulkan.present.MipChain;

import java.util.Arrays;
import java.util.function.Function;

/**
 * The GPU's time to draw a piece of art at a size, by the stack's own renderer in a headless window
 * ({@code GuiApp.gpuMillis}, Vulkan timestamps): {@value #COPIES} copies of it stacked in one frame, less an empty
 * frame of the same size, over {@value #COPIES}; the median of {@value #FRAMES} frames each. Two contenders: the SDG
 * drawn as SDG, and the shipped 1024 px PNG drawn as a picture is (a still feed with its mip chain, sampled).
 */
final class GpuTime {

    static final int COPIES = 8, FRAMES = 30;

    /** What is timed on the GPU, and its column's label. */
    enum Kind {
        SDG("SDG, GPU"),
        PNG_1024("PNG 1024, GPU");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    private GpuTime() {
    }

    /** Milliseconds to draw {@code art} at {@code side} once, by {@code kind}. */
    static double millis(Corpus.Art art, int side, Kind kind) {
        double empty = median(GuiApp.gpuMillis(frame(side, null), side, side, FRAMES));
        Function<Gui, Node> one = kind == Kind.SDG
                ? gui -> gui.box().sdg(ImageSdg.of(art.sdg()))
                : gui -> gui.box().image(feed(art));
        double full = median(GuiApp.gpuMillis(frame(side, one), side, side, FRAMES));
        return Math.max(0, full - empty) / COPIES;
    }

    /** A frame {@code side} square, grey, and {@value #COPIES} copies of what {@code one} makes stacked over it. */
    private static Gui frame(int side, Function<Gui, Node> one) {
        Gui gui = new Gui();
        gui.root().background(dev.vexelray.canvas.Color.rgb(0.5f, 0.5f, 0.5f));
        if (one != null) {
            for (int i = 0; i < COPIES; i++) {
                gui.root().append(one.apply(gui).size(Length.dp(side), Length.dp(side))
                        .floatAt(Length.ZERO, Length.ZERO));
            }
        }
        return gui;
    }

    private static Feed feed(Corpus.Art art) {
        var f = art.raster().get(1024);
        return Feed.still(f.width(), f.height(), true, MipChain.of(f.rgba(), f.width(), f.height()));
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }
}
