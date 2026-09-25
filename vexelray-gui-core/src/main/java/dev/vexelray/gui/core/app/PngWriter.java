package dev.vexelray.gui.core.app;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Captures to PNG, with nothing but {@code java.util.zip}: an 8-bit RGBA, non-interlaced image, each row filtered
 * "Up" against the one above, in one IDAT. The one image this framework writes is its own pixels, going out.
 * It still decodes nothing.
 *
 * <p>Not ImageIO, because ImageIO is AWT. A GraalVM native image on Windows can only ship AWT's native libraries
 * as DLLs beside the executable, and a capture was the last thing here that reached it.
 */
final class PngWriter {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PngWriter() {
    }

    /** {@code rgba}, straight RGBA8, tightly packed, top row first, as a PNG at {@code path}. */
    static void write(byte[] rgba, int width, int height, Path path) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path))) {
            write(rgba, width, height, out);
        }
    }

    static void write(byte[] rgba, int width, int height, OutputStream out) throws IOException {
        if (width < 1 || height < 1 || rgba.length != (long) width * height * 4) {
            throw new IllegalArgumentException(width + " x " + height + " needs " + (long) width * height * 4
                    + " bytes of RGBA, got " + rgba.length);
        }
        out.write(SIGNATURE);

        ByteArrayOutputStream header = new ByteArrayOutputStream(13);
        DataOutputStream ihdr = new DataOutputStream(header);
        ihdr.writeInt(width);
        ihdr.writeInt(height);
        ihdr.writeByte(8);   // bits per channel
        ihdr.writeByte(6);   // colour type: RGBA
        ihdr.writeByte(0);   // deflate
        ihdr.writeByte(0);   // adaptive filtering, a filter byte per row
        ihdr.writeByte(0);   // not interlaced
        chunk(out, "IHDR", header.toByteArray());

        int stride = width * 4;
        ByteArrayOutputStream data = new ByteArrayOutputStream(rgba.length / 4);
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try (DeflaterOutputStream z = new DeflaterOutputStream(data, deflater, 1 << 16)) {
            byte[] row = new byte[1 + stride];
            for (int y = 0; y < height; y++) {
                int at = y * stride;
                // "Up": each byte less the one above it. A capture is mostly flat colour, where that is zero.
                row[0] = (byte) (y == 0 ? 0 : 2);
                for (int i = 0; i < stride; i++) {
                    row[1 + i] = (byte) (rgba[at + i] - (y == 0 ? 0 : rgba[at - stride + i]));
                }
                z.write(row);
            }
        } finally {
            deflater.end();
        }
        chunk(out, "IDAT", data.toByteArray());
        chunk(out, "IEND", new byte[0]);
    }

    private static void chunk(OutputStream out, String type, byte[] data) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(data.length);
        d.write(name);
        d.write(data);
        d.writeInt((int) crc.getValue());
    }
}
