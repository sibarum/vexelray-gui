package dev.vexelray.gui.core.text;

import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.text.FontSet;
import dev.vexelray.text.GlyphLayout;
import dev.vexelray.text.TextLayout;

import java.util.List;

/**
 * The application's fonts as the GUI uses them: a {@link FontSet}, and the answer to "which face is this
 * character in" for a node's {@link Styling}. It is also the {@link TextMeasurer} — measuring is that answer
 * summed — so the layout, the compute phase and the renderer resolve faces in exactly one place and cannot
 * disagree about which face a glyph came from (docs/plans/font-families.md §3.3).
 *
 * <p>Per character, not per node: a bold span in a regular line is measured at bold's advances. Caret positions,
 * line breaks and the alignment indent are all derived from the advances this returns, so they follow the span
 * with nothing of their own to update. Vertical metrics are the node's own face's — a bold word sits on the
 * line's baseline, in the line's height.
 *
 * <p>Immutable, and every face's layout is built at construction, so one instance serves every window and every
 * thread that measures.
 */
public final class TextFaces implements TextMeasurer {

    private final FontSet set;
    private final TextLayout[] layouts;

    public TextFaces(FontSet set) {
        this.set = set;
        this.layouts = new TextLayout[set.faces().size()];
        for (FontSet.Face f : set.faces()) {
            layouts[f.id()] = new TextLayout(set.layout(f));
        }
    }

    /**
     * The fonts vexelray-text ships — {@code sans} and {@code mono} — each falling back to the other: mono borrows
     * what it lacks from sans, as the old single atlas's mono face did, and sans borrows mono's arrows and box
     * drawing rather than drawing the missing-glyph box.
     */
    public static TextFaces standard() {
        return Standard.INSTANCE;
    }

    /** Holder: parsed on first use, once. */
    private static final class Standard {
        static final TextFaces INSTANCE = new TextFaces(FontSet.standard().withFallback("sans", "mono"));
    }

    public FontSet set() {
        return set;
    }

    // --- faces ---------------------------------------------------------------------------------------------

    /**
     * The family a node asked for: a key is taken as it is (and must exist), an index counts into the families in
     * manifest order and stops at the last, and nothing is the first.
     */
    public String family(Object font) {
        List<String> families = set.families();
        if (font instanceof String key) {
            set.family(key);   // throws, naming the families there are, for a key the set does not have
            return key;
        }
        if (font instanceof Integer i) {
            return families.get(Math.max(0, Math.min(i, families.size() - 1)));
        }
        return families.get(0);
    }

    /** The face the styling's own family, weight and slope select — the node's face, before any span. */
    public FontSet.Face face(Styling style) {
        return set.face(family(style.font()), style.weight(), style.slope());
    }

    /**
     * The face of every char of {@code text} under {@code style}, by {@link FontSet.Face#id() id}. A surrogate pair's
     * two chars share one. Unstyled text is the node's face throughout.
     */
    public int[] faceIds(Styling style, String text) {
        int n = text.length();
        int[] ids = new int[n];
        FontSet.Face base = face(style);
        if (style.uniform()) {
            java.util.Arrays.fill(ids, base.id());
            return ids;
        }
        String family = family(style.font());
        for (int i = 0; i < n; i++) {
            int w = style.weightAt(i);
            FontSet.Slope s = style.slopeAt(i);
            ids[i] = w == style.weight() && s == style.slope() ? base.id() : set.face(family, w, s).id();
        }
        return ids;
    }

    /** The layout that draws face {@code id}, falling back along its chain. */
    public TextLayout layout(int id) {
        return layouts[id];
    }

    /** The layout of the styling's own face. */
    public TextLayout layout(Styling style) {
        return layouts[face(style).id()];
    }

    // --- measuring -----------------------------------------------------------------------------------------

    @Override
    public float intrinsic(RetainedNode node, Axis axis, float textSizePx) {
        Styling style = Styling.of(node);
        if (axis == Axis.HORIZONTAL) {
            float[] adv = caretAdvances(style, node.textString() == null ? "" : node.textString(), textSizePx);
            return adv[adv.length - 1];
        }
        GlyphLayout gl = layout(style).glyphLayout();
        return gl.ascent(textSizePx) + gl.descent(textSizePx);
    }

    @Override
    public float[] caretAdvances(String text, float textSizePx) {
        return caretAdvances(Styling.DEFAULT, text, textSizePx);
    }

    /**
     * Cumulative advance at each char boundary ({@code [0] == 0}), each code point at its own face's advance — the
     * one array caret x, line widths and the alignment indent are all computed from.
     */
    @Override
    public float[] caretAdvances(Styling style, String text, float textSizePx) {
        if (text == null) {
            return new float[] {0f};
        }
        int[] ids = faceIds(style, text);
        float[] xs = new float[text.length() + 1];
        float x = 0f;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            x += layouts[ids[i]].glyphLayout().advance(cp, textSizePx);
            for (int j = i + 1; j <= next; j++) {
                xs[j] = x;   // a surrogate pair's two boundaries share the advance
            }
            i = next;
        }
        return xs;
    }

    @Override
    public List<TextLayout.LineSpan> lineSpans(String text, float wrapWidth, float textSizePx) {
        return lineSpans(Styling.DEFAULT, text, wrapWidth, textSizePx);
    }

    /** Lines broken by the widths the text will draw at: each code point's own face's advance. */
    @Override
    public List<TextLayout.LineSpan> lineSpans(Styling style, String text, float wrapWidth, float textSizePx) {
        String s = text == null ? "" : text;
        int[] ids = faceIds(style, s);
        return TextLayout.breakLineSpans(s, (index, cp) -> layouts[ids[index]].glyphLayout().advance(cp, textSizePx),
                wrapWidth, TextLayout.WrapMode.WORD_CHAR);
    }

    @Override
    public int offsetAt(String text, float localX, float textSizePx) {
        if (text == null || text.isEmpty() || localX <= 0f) {
            return 0;
        }
        float[] xs = caretAdvances(Styling.DEFAULT, text, textSizePx);
        for (int i = 1; i < xs.length; i++) {
            if (localX < (xs[i - 1] + xs[i]) * 0.5f) {
                return i - 1;
            }
        }
        return text.length();
    }
}
