package dev.vexelray.gui.architecture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * No GUI class reaches AWT. A GraalVM native image on Windows can only ship AWT's native libraries (awt.dll,
 * fontmanager.dll and seven more) as files beside the executable, so one reference to ImageIO or a BufferedImage
 * anywhere in the GUI modules ({@link Bytecode#INSPECTED}) is what stops an application from being a single
 * executable. The font atlas is loaded as RGBA pixels ({@code dev.vexelray.text.AtlasPixels}), and captures are
 * written by {@code PngWriter}, for this reason.
 *
 * <p>Tests may use ImageIO; they never go into an image.
 */
class NoAwtGuardTest {

    private static final List<String> FORBIDDEN = List.of("java/awt", "javax/imageio");

    @Test
    void theGuiNeverReferencesAwt() {
        List<String> violations = new ArrayList<>();
        for (String module : Bytecode.INSPECTED) {
            Path classes = Bytecode.classesOf(module);
            for (Path classFile : Bytecode.classFiles(classes)) {
                byte[] bytes = Bytecode.read(classFile);
                for (String forbidden : FORBIDDEN) {
                    if (Bytecode.contains(bytes, forbidden.getBytes(StandardCharsets.UTF_8))) {
                        violations.add(classes.relativize(classFile) + " references " + forbidden);
                    }
                }
            }
        }
        assertEquals(List.of(), violations, "The GUI modules must not reach AWT: a native image would then"
                + " need AWT's DLLs beside it. Load pixels as RGBA and write PNGs with PngWriter.");
    }
}
