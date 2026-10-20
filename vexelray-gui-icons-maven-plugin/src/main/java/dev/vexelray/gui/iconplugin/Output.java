package dev.vexelray.gui.iconplugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Writing what a bake makes: the atlas in the font atlas's layout, sources, and a picture of the atlas to look at. */
final class Output {

    /** "VXPX", which {@code dev.vexelray.text.AtlasPixels} reads. */
    static final int MAGIC = 0x56585058;

    private Output() {
    }

    /** VXPX, width, height, then the RGBA8 deflated: what the run time inflates with {@code java.util.zip} alone. */
    static byte[] pixels(byte[] rgba, int w, int h) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(w);
        out.writeInt(h);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream z = new DeflaterOutputStream(out, deflater)) {
            z.write(rgba);
        }
        deflater.end();
        return bytes.toByteArray();
    }

    /** Writes only if the bytes differ, so a bake that changed nothing leaves every file's time alone too. */
    static boolean write(Path path, byte[] bytes) throws IOException {
        if (Files.exists(path) && Arrays.equals(Files.readAllBytes(path), bytes)) {
            return false;
        }
        Files.createDirectories(path.getParent());
        Path part = path.resolveSibling(path.getFileName() + ".part");
        Files.write(part, bytes);
        Files.move(part, path, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    static boolean write(Path path, String text) throws IOException {
        return write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    /** The atlas's red channel as grey: the fields as they are, to find an icon or a bad edge by sight. */
    static void picture(Path png, byte[] rgba, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = rgba[(y * w + x) * 4] & 0xFF;
                img.setRGB(x, y, v << 16 | v << 8 | v);
            }
        }
        Files.createDirectories(png.getParent());
        ImageIO.write(img, "png", png.toFile());
    }

    /** A digest that changes when any of what a bake reads does. */
    static final class Stamp {

        private final MessageDigest digest;

        /** Begun with the plugin's own build, so a plugin that bakes or generates differently bakes again. */
        Stamp() {
            this(true);
        }

        private Stamp(boolean withPlugin) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            if (withPlugin) {
                add(plugin());
            }
        }

        /** The plugin's own code, by the bytes of what makes the fields and writes the sources, jar or folder alike. */
        private static String plugin() {
            Stamp s = new Stamp(false);
            for (Class<?> c : new Class<?>[]{Output.class, Sources.class, Mask.class, Atlas.class, Gather.class,
                    SetManifest.class, BakeMojo.class, FileFamiliesMojo.class}) {
                try (InputStream in = c.getResourceAsStream(c.getSimpleName() + ".class")) {
                    s.add(c.getName());
                    if (in != null) {
                        s.digest.update(in.readAllBytes());
                    }
                } catch (IOException e) {
                    s.add("unreadable");
                }
            }
            return s.hex();
        }

        Stamp add(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            digest.update(length(b.length));
            digest.update(b);
            return this;
        }

        Stamp add(Path file) throws IOException {
            add(file.toString());
            byte[] b = Files.readAllBytes(file);
            digest.update(length(b.length));
            digest.update(b);
            return this;
        }

        /** A length as four bytes, so that two inputs run together can never read as one. */
        private static byte[] length(int n) {
            return new byte[]{(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n};
        }

        String hex() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
