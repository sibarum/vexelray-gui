package dev.vexelray.gui.core.app;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link PngWriter} judged by a decoder it does not share code with: ImageIO, which tests may use. */
class PngWriterTest {

    @Test
    void imageIoReadsBackEveryPixel() throws IOException {
        int w = 37;
        int h = 23;
        byte[] rgba = new byte[w * h * 4];
        new Random(5).nextBytes(rgba);
        // A flat band too, where the "Up" filter's zeros are the common case.
        for (int i = 0; i < w * 4 * 5; i++) {
            rgba[w * 4 * 10 + i] = (byte) 0x7F;
        }
        assertRoundTrip(rgba, w, h);
    }

    @Test
    void aSinglePixel() throws IOException {
        assertRoundTrip(new byte[] {1, 2, 3, 4}, 1, 1);
    }

    @Test
    void theWrongNumberOfBytesIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PngWriter.write(new byte[15], 2, 2, new ByteArrayOutputStream()));
    }

    private static void assertRoundTrip(byte[] rgba, int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PngWriter.write(rgba, w, h, out);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
        assertNotNull(img, "ImageIO did not recognise the PNG");
        assertEquals(w, img.getWidth());
        assertEquals(h, img.getHeight());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                int expected = (rgba[i + 3] & 0xFF) << 24 | (rgba[i] & 0xFF) << 16 | (rgba[i + 1] & 0xFF) << 8
                        | (rgba[i + 2] & 0xFF);
                assertEquals(Integer.toHexString(expected), Integer.toHexString(img.getRGB(x, y)),
                        "pixel " + x + ", " + y);
            }
        }
    }
}
