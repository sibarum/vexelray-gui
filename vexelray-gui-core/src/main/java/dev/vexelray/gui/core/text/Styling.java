package dev.vexelray.gui.core.text;

import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.text.FontSet;

import java.util.List;

/**
 * Which faces a run of text is in: the node's family, weight and slope, and the spans that change weight or slope
 * over part of it. Everything measurement needs to know about style, and nothing it does not — colour, background
 * and underline never move a glyph, so a measurer handed a styling can ignore them.
 *
 * <p>Passed explicitly, rather than read off the node by whoever measures, because the same node is measured in
 * two ways: its text, under its spans, and on occasion a string that is not its text — the gutter's "0" — which
 * the spans' offsets do not describe. {@link #of} and {@link #base} say which.
 *
 * @param font   the family as the node requested it: a key, an index, or null for the set's first
 * @param weight 100–1000
 * @param spans  the spans over the text being measured; only the {@link Span#styled() styled} ones matter here
 */
public record Styling(Object font, int weight, FontSet.Slope slope, List<Span> spans) {

    /** The set's first family, regular, upright, unstyled — what a node that says nothing about type gets. */
    public static final Styling DEFAULT = new Styling(null, 400, FontSet.Slope.NORMAL, List.of());

    public Styling {
        spans = spans == null ? List.of() : spans;
    }

    /** The node's text as it is drawn: its family and style, and its spans. */
    public static Styling of(RetainedNode n) {
        return new Styling(n.font(), n.fontWeight(), n.fontSlope(), n.spans());
    }

    /** The node's family and style without its spans — for measuring a string that is not the node's text. */
    public static Styling base(RetainedNode n) {
        return new Styling(n.font(), n.fontWeight(), n.fontSlope(), List.of());
    }

    /** This styling without its spans: the node's own face. */
    public Styling base() {
        return spans.isEmpty() ? this : new Styling(font, weight, slope, List.of());
    }

    /** Whether every character is in the node's own face: no span chooses a different one. */
    public boolean uniform() {
        return !Span.styles(spans);
    }

    /** The weight at char {@code index}: the last styled span over it that sets one, else the node's. */
    public int weightAt(int index) {
        int w = weight;
        for (Span s : spans) {
            if (s.weight() != 0 && s.covers(index)) {
                w = s.weight();
            }
        }
        return w;
    }

    /** The slope at char {@code index}: the last styled span over it that sets one, else the node's. */
    public FontSet.Slope slopeAt(int index) {
        FontSet.Slope sl = slope;
        for (Span s : spans) {
            if (s.slope() != null && s.covers(index)) {
                sl = s.slope();
            }
        }
        return sl;
    }
}
