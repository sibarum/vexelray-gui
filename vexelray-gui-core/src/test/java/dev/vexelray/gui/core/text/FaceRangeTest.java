package dev.vexelray.gui.core.text;

import dev.vexelray.text.FontSet;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Face ids for part of a text are that part of the whole text's face ids: the renderer resolves only the visible
 * range, and must draw exactly what resolving everything would have drawn. Both are checked against the per-char
 * definition — {@link Styling#weightAt} and {@link Styling#slopeAt}, last span wins.
 */
class FaceRangeTest {

    private static final TextFaces FACES = new TextFaces(handMade());

    /** One family with a face for each weight and slope the spans ask for, so every distinction shows in the ids. */
    private static FontSet handMade() {
        Map<String, String> files = new HashMap<>();
        files.put("", """
                {"version": 1, "bytes": 0, "families": [{"name": "ui", "fontFamily": "UI", "faces": [
                  {"style": "light", "weight": 300, "stretch": 5, "slope": "normal", "metrics": "m.json", "pixels": "l.rgba"},
                  {"style": "regular", "weight": 400, "stretch": 5, "slope": "normal", "metrics": "m.json", "pixels": "r.rgba"},
                  {"style": "bold", "weight": 700, "stretch": 5, "slope": "normal", "metrics": "m.json", "pixels": "b.rgba"},
                  {"style": "italic", "weight": 400, "stretch": 5, "slope": "italic", "metrics": "m.json", "pixels": "i.rgba"},
                  {"style": "bold italic", "weight": 700, "stretch": 5, "slope": "italic", "metrics": "m.json", "pixels": "bi.rgba"}
                ]}]}""");
        files.put("m.json", "{\"atlas\":{\"type\":\"msdf\",\"distanceRange\":4,\"size\":32,\"width\":64,"
                + "\"height\":64,\"yOrigin\":\"bottom\"},\"metrics\":{\"emSize\":1,\"lineHeight\":1.2,"
                + "\"ascender\":0.9,\"descender\":-0.3,\"underlineY\":-0.1,\"underlineThickness\":0.05},"
                + "\"glyphs\":[{\"unicode\":120,\"advance\":0.5}],\"kerning\":[]}");
        return FontSet.read(path -> {
            String s = files.get(path);
            return s == null ? null : (InputStream) new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
        });
    }

    /** The definition, one char at a time: what faceIds computed before it resolved by range. */
    private static int[] reference(Styling style, int length) {
        int base = FACES.face(style).id();
        if (style.uniform()) {
            int[] ids = new int[length];
            Arrays.fill(ids, base);
            return ids;
        }
        String family = FACES.family(style.font());
        int[] ids = new int[length];
        for (int i = 0; i < length; i++) {
            int w = style.weightAt(i);
            FontSet.Slope s = style.slopeAt(i);
            ids[i] = w == style.weight() && s == style.slope() ? base : FACES.set().face(family, w, s).id();
        }
        return ids;
    }

    private static Styling styled(List<Span> spans) {
        return new Styling("ui", 400, FontSet.Slope.NORMAL, spans);
    }

    @Test
    void overlappingBoldAndItalicAcrossTheRangeEdges() {
        // Unsorted, overlapping, with a later span overriding an earlier one's weight, and spans that start
        // before and end after the range asked for.
        List<Span> spans = List.of(
                Span.italic(10, 40),
                Span.bold(0, 25),
                Span.weight(20, 30, 300),
                Span.foreground(5, 50, null),
                Span.slope(35, 60, FontSet.Slope.NORMAL),
                Span.bold(45, 80));
        Styling style = styled(spans);
        int length = 100;
        int[] full = FACES.faceIds(style, "x".repeat(length));
        assertArrayEquals(reference(style, length), full);
        assertTrue(Arrays.stream(full).distinct().count() >= 4, "the spans should select several faces");
        for (int[] r : new int[][] {{0, 100}, {15, 35}, {22, 23}, {24, 46}, {39, 41}, {50, 50}, {79, 100}}) {
            assertArrayEquals(Arrays.copyOfRange(full, r[0], r[1]), FACES.faceIds(style, r[0], r[1]),
                    "range [" + r[0] + ", " + r[1] + ")");
        }
    }

    @Test
    void randomSpansMatchTheDefinitionOverEveryRange() {
        Random rnd = new Random(7);
        int[] weights = {0, 100, 300, 400, 700, 900};
        FontSet.Slope[] slopes = {null, FontSet.Slope.NORMAL, FontSet.Slope.ITALIC};
        for (int trial = 0; trial < 40; trial++) {
            int length = 1 + rnd.nextInt(120);
            List<Span> spans = new ArrayList<>();
            for (int k = rnd.nextInt(12); k > 0; k--) {
                int a = rnd.nextInt(length + 1);
                int b = a + rnd.nextInt(length + 1 - a);
                spans.add(new Span(a, b, null, null, false, weights[rnd.nextInt(weights.length)],
                        slopes[rnd.nextInt(slopes.length)]));
            }
            Styling style = styled(spans);
            int[] full = reference(style, length);
            assertArrayEquals(full, FACES.faceIds(style, "x".repeat(length)));
            for (int r = 0; r < 20; r++) {
                int from = rnd.nextInt(length + 1);
                int to = from + rnd.nextInt(length + 1 - from);
                assertArrayEquals(Arrays.copyOfRange(full, from, to), FACES.faceIds(style, from, to));
            }
        }
    }

    @Test
    void unstyledRangeIsTheNodeFace() {
        Styling style = styled(List.of(Span.foreground(0, 10, null)));
        int[] ids = FACES.faceIds(style, 3, 8);
        assertEquals(5, ids.length);
        assertTrue(Arrays.stream(ids).allMatch(id -> id == FACES.face(style).id()));
    }

    /**
     * Not a pass/fail measure — a record of what the change buys. A 100k-char document with 2k styled spans, one
     * screenful (4k chars) visible: the per-char definition over the whole text against the range resolution.
     */
    @Test
    void largeStyledTextCost() {
        int length = 100_000;
        List<Span> spans = new ArrayList<>();
        for (int k = 0; k < 2_000; k++) {
            int a = k * 50;
            spans.add(k % 2 == 0 ? Span.bold(a, a + 10) : Span.italic(a, a + 10));
        }
        Styling style = styled(spans);
        String text = "x".repeat(length);
        int from = 48_000;
        int to = 52_000;

        long t0 = System.nanoTime();
        int[] before = reference(style, length);
        long t1 = System.nanoTime();
        int[] fullNow = FACES.faceIds(style, text);
        long t2 = System.nanoTime();
        int[] visible = null;
        int reps = 100;
        for (int r = 0; r < reps; r++) {
            visible = FACES.faceIds(style, from, to);
        }
        long t3 = System.nanoTime();

        assertArrayEquals(before, fullNow);
        assertArrayEquals(Arrays.copyOfRange(before, from, to), visible);
        System.out.printf("[FaceRangeTest] 100k chars, 2k spans: per-char whole text %.2f ms; "
                        + "painted whole text %.2f ms; painted visible 4k chars %.3f ms%n",
                (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6 / reps);
    }
}
