package dev.vexelray.gui.core.text;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.TreeRenderer;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.Reconciler;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.text.FontSet;
import dev.vexelray.text.TextLayout;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bold span is measured at bold's advances (docs/plans/font-families.md, the done criterion): the caret positions,
 * the line breaks and the alignment indent of styled text all come from the face each character is drawn in.
 *
 * <p>Over a family built by hand, so every number is exact: in face "ui", regular 'x' is 0.5 em and bold 'x' is
 * 0.7 em, and a space is 0.25 em in both. At 20 px that is 10 px against 14 px a letter.
 */
class StyledTextTest {

    private static final FontSet SET = handMade();
    private static final TextFaces FACES = new TextFaces(SET);
    private static final float PX = 20f;
    private static final float X = 10f;
    private static final float BOLD_X = 14f;
    private static final float SPACE = 5f;

    // --- through the whole frame: model, layout, compute, read-model ------------------------------------

    @Test
    void caretPositionsAreAtEachFacesOwnAdvance() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node label = label(gui, "xxxx").spans(List.of(Span.bold(1, 3)));
            gui.root().children(label);
            gui.frame(400, 100, FACES);

            float[] xs = relative(label);
            assertEquals(List.of(0f, X, X + BOLD_X, X + 2 * BOLD_X, 2 * X + 2 * BOLD_X), boxed(xs),
                    "the two bold letters push everything after them by bold's advance");
        }
    }

    @Test
    void theAlignmentIndentCountsTheBoldWidth() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node plain = label(gui, "xxxx").width(Length.dp(200)).align(TextLayout.HAlign.CENTER,
                    TextLayout.VAlign.TOP);
            Node styled = label(gui, "xxxx").width(Length.dp(200)).align(TextLayout.HAlign.CENTER,
                    TextLayout.VAlign.TOP).spans(List.of(Span.bold(1, 3)));
            gui.root().children(plain, styled);
            gui.frame(400, 200, FACES);

            float plainLeft = line(plain).xs()[0] - plain.layout().rect().x();
            float styledLeft = line(styled).xs()[0] - styled.layout().rect().x();
            float extra = 2 * (BOLD_X - X);
            assertEquals(plainLeft - extra / 2, styledLeft, 1e-3f,
                    "a centred line wider by the bold letters starts half that much further left");
        }
    }

    @Test
    void linesBreakAtTheWidthsTheyDrawAt() {
        // "xx xx" is 45 px regular and 63 px bold; at 50 px wide the regular line fits and the bold one does not.
        try (Gui gui = new Gui(Atchung.create())) {
            Node plain = label(gui, "xx xx").width(Length.dp(50)).wordWrap(true);
            Node bold = label(gui, "xx xx").width(Length.dp(50)).wordWrap(true)
                    .spans(List.of(Span.bold(0, 5)));
            gui.root().children(plain, bold);
            gui.frame(400, 200, FACES);

            assertEquals(1, plain.layout().text().lines().size());
            assertEquals(2, bold.layout().text().lines().size(), "the bold text wraps where its own width says");
        }
    }

    @Test
    void aNodeCanBeBoldWithoutASpan() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node label = label(gui, "xx").bold();
            gui.root().children(label);
            gui.frame(400, 100, FACES);
            assertEquals(List.of(0f, BOLD_X, 2 * BOLD_X), boxed(relative(label)));
        }
    }

    /** A weight the family was not built with gets its nearest face, as CSS would: 600 is bold here. */
    @Test
    void anAbsentWeightIsTheNearestFace() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node label = label(gui, "xx").spans(List.of(Span.weight(0, 2, 600)));
            gui.root().children(label);
            gui.frame(400, 100, FACES);
            assertEquals(List.of(0f, BOLD_X, 2 * BOLD_X), boxed(relative(label)));
        }
    }

    // --- what changes when ----------------------------------------------------------------------------

    /** Recolouring a span moves nothing and stays a draw; making it bold moves glyphs, so it relays out. */
    @Test
    void onlyAStyledSpanReflows() {
        Reconciler r = new Reconciler(1L);
        r.create(1L, Map.of(PropKey.TEXT, "xxxx"));
        r.clearDirty();

        r.setProp(1L, PropKey.SPANS, List.of(Span.foreground(0, 2, dev.vexelray.canvas.Color.rgb(0xff0000))));
        assertFalse(r.layoutDirty(), "a colour is not a width");

        r.setProp(1L, PropKey.SPANS, List.of(Span.bold(0, 2)));
        assertTrue(r.layoutDirty(), "a bold span is");

        r.clearDirty();
        r.setProp(1L, PropKey.SPANS, List.of());
        assertTrue(r.layoutDirty(), "and so is taking one away");
    }

    // --- drawing --------------------------------------------------------------------------------------

    /** The renderer cuts runs where the face changes, and draws each run in its face: regular, bold, regular. */
    @Test
    void eachRunIsDrawnInItsOwnFace() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node label = label(gui, "xxxx").spans(List.of(Span.bold(1, 3)));
            gui.root().children(label);
            RetainedNode root = gui.frame(400, 100, FACES);

            Canvas canvas = new Canvas(400, 100).begin();
            TreeRenderer.emit(root, canvas, FACES);
            int regular = SET.face("ui").id();
            int bold = SET.face("ui", 700, FontSet.Slope.NORMAL).id();
            List<Integer> glyphFaces = canvas.runs().stream().map(Canvas.Run::face).toList();
            assertEquals(List.of(regular, bold, regular), glyphFaces);
        }
    }

    // --- the old index form ---------------------------------------------------------------------------

    /** {@code Node.font(int)} still means what it did: in the standard set 0 is sans and 1 is mono. */
    @Test
    void aFamilyIndexCountsTheManifestsFamilies() {
        TextFaces standard = TextFaces.standard();
        assertEquals("sans", standard.family(null));
        assertEquals("sans", standard.family(0));
        assertEquals("mono", standard.family(1));
        assertEquals("mono", standard.family(7), "past the last is the last, as an atlas face index degraded");
        assertEquals("mono", standard.family("mono"));
    }

    // --- helpers --------------------------------------------------------------------------------------

    private static Node label(Gui gui, String text) {
        return gui.text(text).font("ui").textSize(Length.dp(PX));
    }

    private static TextMetrics.VisualLine line(Node n) {
        return n.layout().text().lines().get(0);
    }

    /** The first line's caret xs, measured from its first. */
    private static float[] relative(Node n) {
        float[] xs = line(n).xs();
        float[] out = new float[xs.length];
        for (int i = 0; i < xs.length; i++) {
            out[i] = xs[i] - xs[0];
        }
        return out;
    }

    private static List<Float> boxed(float[] xs) {
        List<Float> out = new java.util.ArrayList<>();
        for (float x : xs) {
            out.add(x);
        }
        return out;
    }

    /** Family "ui": regular and bold. */
    private static FontSet handMade() {
        Map<String, String> files = new HashMap<>();
        files.put("", """
                {"version": 1, "bytes": 0, "families": [{"name": "ui", "fontFamily": "UI", "faces": [
                  {"style": "regular", "weight": 400, "stretch": 5, "slope": "normal", "metrics": "r.json", "pixels": "r.rgba"},
                  {"style": "bold", "weight": 700, "stretch": 5, "slope": "normal", "metrics": "b.json", "pixels": "b.rgba"}
                ]}]}""");
        files.put("r.json", atlas(0.5f));
        files.put("b.json", atlas(0.7f));
        return FontSet.read(path -> {
            String s = files.get(path);
            return s == null ? null : (InputStream) new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
        });
    }

    private static String atlas(float advance) {
        return "{\"atlas\":{\"type\":\"msdf\",\"distanceRange\":4,\"size\":32,\"width\":64,\"height\":64,"
                + "\"yOrigin\":\"bottom\"},\"metrics\":{\"emSize\":1,\"lineHeight\":1.2,\"ascender\":0.9,"
                + "\"descender\":-0.3,\"underlineY\":-0.1,\"underlineThickness\":0.05},\"glyphs\":["
                + "{\"unicode\":32,\"advance\":0.25},"
                + "{\"unicode\":120,\"advance\":" + advance
                + ",\"planeBounds\":{\"left\":0,\"bottom\":0,\"right\":0.5,\"top\":0.5}"
                + ",\"atlasBounds\":{\"left\":0,\"bottom\":0,\"right\":8,\"top\":8}}],\"kerning\":[]}";
    }
}
