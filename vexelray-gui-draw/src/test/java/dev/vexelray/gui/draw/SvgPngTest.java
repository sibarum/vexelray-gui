package dev.vexelray.gui.draw;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The PNG an exported drawing is embedded as, written without AWT, reads back as it was written. */
class SvgPngTest {

    @Test
    void aPngReadsBackPixelForPixel() throws IOException {
        int w = 7, h = 5;
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < rgba.length; i++) {
            rgba[i] = (byte) (i * 37 + 11);
        }
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(SvgSink.png(rgba, w, h)));
        assertEquals(w, img.getWidth());
        assertEquals(h, img.getHeight());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int o = (y * w + x) * 4;
                int want = (rgba[o + 3] & 0xFF) << 24 | (rgba[o] & 0xFF) << 16 | (rgba[o + 1] & 0xFF) << 8
                        | rgba[o + 2] & 0xFF;
                assertEquals(want, img.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
    }
}
