package dev.vexelray.gui.harness;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Two captures compared pixel for pixel: how many differ, by how much, and where.
 *
 * <p>The proof a rendering change needs is that nothing moved. A capture taken before and one taken after are
 * compared channel by channel, alpha included; a pixel differs when any channel is further apart than the
 * tolerance. Two images of different sizes do not compare at all, and say so rather than cropping.
 *
 * <p>As a command, {@code CaptureDiff <baselineDir> <candidateDir> [tolerance]} compares every {@code *.png} in
 * the baseline with the file of the same name in the candidate, prints a line for each, writes
 * {@code <name>.diff.png} beside every candidate that differs, and exits non-zero if any differed or was missing.
 */
public final class CaptureDiff {

    /** Magenta: a colour no theme paints, so a difference never hides in the picture it marks. */
    private static final int MARK = 0xFFFF00FF;

    private CaptureDiff() {
    }

    /**
     * What a comparison found. {@code differing} counts pixels beyond tolerance; {@code maxDelta} is the largest
     * channel difference seen anywhere, beyond tolerance or not; the box is inclusive and meaningless when nothing
     * differs. A size mismatch has no pixels compared and says why in {@code sizeMismatch}.
     */
    public record Result(int width, int height, long differing, int maxDelta,
                         int minX, int minY, int maxX, int maxY, String sizeMismatch) {

        /** Whether the two images are the same within tolerance, size included. */
        public boolean same() {
            return sizeMismatch == null && differing == 0;
        }

        /** One line: same, a size mismatch, or the count, the worst channel and the box. */
        public String describe() {
            if (sizeMismatch != null) {
                return "size mismatch: " + sizeMismatch;
            }
            if (differing == 0) {
                return maxDelta == 0 ? "same" : "same within tolerance (max delta " + maxDelta + ")";
            }
            return differing + " px differ, max delta " + maxDelta
                    + ", bbox " + minX + "," + minY + " .. " + maxX + "," + maxY
                    + " (" + (maxX - minX + 1) + "x" + (maxY - minY + 1) + ")";
        }
    }

    /** Compares {@code baseline} with {@code candidate}, a pixel differing when any channel exceeds {@code tolerance}. */
    public static Result compare(BufferedImage baseline, BufferedImage candidate, int tolerance) {
        int w = baseline.getWidth();
        int h = baseline.getHeight();
        if (w != candidate.getWidth() || h != candidate.getHeight()) {
            return new Result(w, h, 0, 0, 0, 0, -1, -1,
                    w + "x" + h + " baseline, " + candidate.getWidth() + "x" + candidate.getHeight() + " candidate");
        }
        long differing = 0;
        int maxDelta = 0;
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int delta = delta(baseline.getRGB(x, y), candidate.getRGB(x, y));
                maxDelta = Math.max(maxDelta, delta);
                if (delta > tolerance) {
                    differing++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return new Result(w, h, differing, maxDelta, minX, minY, maxX, maxY, null);
    }

    /** The largest per-channel difference between two ARGB pixels. */
    static int delta(int a, int b) {
        int max = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            max = Math.max(max, Math.abs(((a >>> shift) & 0xFF) - ((b >>> shift) & 0xFF)));
        }
        return max;
    }

    /**
     * The baseline at a third of its brightness with every pixel beyond tolerance in magenta: where the change
     * is, against enough of the picture to say what it touched. Same-sized images only.
     */
    public static BufferedImage diffImage(BufferedImage baseline, BufferedImage candidate, int tolerance) {
        int w = baseline.getWidth();
        int h = baseline.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int base = baseline.getRGB(x, y);
                if (delta(base, candidate.getRGB(x, y)) > tolerance) {
                    out.setRGB(x, y, MARK);
                } else {
                    int r = ((base >> 16) & 0xFF) / 3;
                    int g = ((base >> 8) & 0xFF) / 3;
                    int b = (base & 0xFF) / 3;
                    out.setRGB(x, y, 0xFF000000 | r << 16 | g << 8 | b);
                }
            }
        }
        return out;
    }

    /**
     * Compares two PNG files, writing {@code diffOut} when they differ beyond tolerance and are the same size.
     */
    public static Result compare(Path baseline, Path candidate, int tolerance, Path diffOut) throws IOException {
        BufferedImage a = read(baseline);
        BufferedImage b = read(candidate);
        Result r = compare(a, b, tolerance);
        if (r.sizeMismatch() == null && r.differing() > 0) {
            ImageIO.write(diffImage(a, b, tolerance), "png", diffOut.toFile());
        }
        return r;
    }

    private static BufferedImage read(Path png) throws IOException {
        BufferedImage img = ImageIO.read(png.toFile());
        if (img == null) {
            throw new IOException("not an image: " + png);
        }
        return img;
    }

    /** {@code CaptureDiff <baselineDir> <candidateDir> [tolerance]}; exits 1 when anything differs or is missing. */
    public static void main(String... args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            System.err.println("usage: CaptureDiff <baselineDir> <candidateDir> [tolerance]");
            System.exit(2);
        }
        Path baselineDir = Path.of(args[0]);
        Path candidateDir = Path.of(args[1]);
        int tolerance = args.length == 3 ? Integer.parseInt(args[2]) : 0;

        List<Path> baselines;
        try (Stream<Path> files = Files.list(baselineDir)) {
            baselines = files.filter(p -> p.getFileName().toString().endsWith(".png"))
                    .filter(p -> !p.getFileName().toString().endsWith(".diff.png"))
                    .sorted((p, q) -> natural(p.getFileName().toString(), q.getFileName().toString()))
                    .toList();
        }
        if (baselines.isEmpty()) {
            System.err.println("no *.png in " + baselineDir);
            System.exit(2);
        }

        int failed = 0;
        for (Path base : baselines) {
            String name = base.getFileName().toString();
            Path cand = candidateDir.resolve(name);
            if (!Files.isRegularFile(cand)) {
                System.out.println(name + ": MISSING from " + candidateDir);
                failed++;
                continue;
            }
            Path diff = candidateDir.resolve(name.substring(0, name.length() - ".png".length()) + ".diff.png");
            Files.deleteIfExists(diff);
            Result r;
            try {
                r = compare(base, cand, tolerance, diff);
            } catch (IOException | UncheckedIOException e) {
                System.out.println(name + ": unreadable: " + e.getMessage());
                failed++;
                continue;
            }
            String line = name + ": " + r.describe();
            if (!r.same()) {
                failed++;
                if (r.sizeMismatch() == null) {
                    line += " -> " + diff;
                }
            }
            System.out.println(line);
        }
        System.out.println(failed == 0
                ? "all " + baselines.size() + " same (tolerance " + tolerance + ")"
                : failed + " of " + baselines.size() + " differ or are missing (tolerance " + tolerance + ")");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Names compared with their digit runs as numbers, so chapter-2 comes before chapter-10. */
    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                int c = Long.compare(Long.parseLong(a.substring(si, i)), Long.parseLong(b.substring(sj, j)));
                if (c != 0) {
                    return c;
                }
            } else {
                if (ca != cb) {
                    return Character.compare(ca, cb);
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
