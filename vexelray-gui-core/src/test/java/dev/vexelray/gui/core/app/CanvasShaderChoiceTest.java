package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.gui.core.text.TextFaces;
import dev.vexelray.text.FontSet;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The canvas shader follows the fonts' atlas type: one text source per shader, so one type per set. */
class CanvasShaderChoiceTest {

    @Test
    void withNoShaderAskedForTheCoreSetForTheFontsIsChosen() {
        assertInstanceOf(CanvasShader.Fragment.class, GuiApp.canvasShader(TextFaces.standard(), null));
        assertInstanceOf(CanvasShader.SdfFragment.class, GuiApp.canvasShader(faces("sdf", "sdf"), null));
    }

    @Test
    void aShaderForTheFontsTypeIsKept() {
        CanvasShader.Composed asked = new CanvasShader.SdfFragment();
        assertSame(asked, GuiApp.canvasShader(faces("sdf", "sdf"), asked));
    }

    @Test
    void aShaderForTheOtherTypeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> GuiApp.canvasShader(faces("sdf", "sdf"), new CanvasShader.Fragment()));
        assertThrows(IllegalArgumentException.class,
                () -> GuiApp.canvasShader(TextFaces.standard(), new CanvasShader.SdfFragment()));
    }

    @Test
    void aSetThatMixesTypesIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> GuiApp.canvasShader(faces("sdf", "msdf"), null));
    }

    /**
     * Families {@code a} and {@code b} of the given types, each with the standard sans's metrics; the pixels are never
     * read.
     */
    private static TextFaces faces(String a, String b) {
        String manifest = """
                {"version": 1, "bytes": 0, "families": [
                  {"name": "a", "fontFamily": "A", "faces": [{"style": "regular", "weight": 400, "stretch": 5,
                    "slope": "normal", "type": "%s", "metrics": "m.json", "pixels": "a.px"}]},
                  {"name": "b", "fontFamily": "B", "faces": [{"style": "regular", "weight": 400, "stretch": 5,
                    "slope": "normal", "type": "%s", "metrics": "m.json", "pixels": "b.px"}]}
                ]}""".formatted(a, b);
        return new TextFaces(FontSet.read(path -> switch (path) {
            case "" -> new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8));
            case "m.json" -> FontSet.class.getResourceAsStream("/dev/vexelray/text/atlas/sans/regular.json");
            default -> (InputStream) null;
        }));
    }
}
