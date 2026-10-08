package dev.vexelray.gui.core.text;

import dev.vexelray.canvas.Color;
import dev.vexelray.text.FontSet;

import java.util.List;

/**
 * A formatting span: the half-open character range {@code [start, end)} carries visual attributes — a text
 * (foreground) colour, a background highlight colour, an underline, a weight and a slope (architecture,
 * keyboard-focus-text.md §4.4). Any attribute may be absent ({@code null} colour, {@code false} underline,
 * weight {@code 0}, {@code null} slope), so spans compose: a fg span and a bold span can overlap the same text.
 *
 * <p>Weight and slope choose a different <b>face</b> of the node's family (docs/plans/font-families.md §3.3), and a
 * face has its own advances — so a span that carries either is part of measurement, not only of drawing: caret
 * positions, line breaks and the alignment indent all move with it. A span with neither changes nothing but
 * colour and lines, and stays as cheap to change as it always was ({@link #styles}).
 *
 * <p>Spans are <em>auto-diff</em>: after an edit they remap their offsets through the edit's {@link TextEdit}
 * (via {@link #remap(TextEdit)}), so an insertion before a span shifts it and an edit inside it grows/shrinks it,
 * keeping every span attached to its text with no manual bookkeeping.
 *
 * @param weight 100–1000, or 0 to leave the weight as the node has it
 * @param slope  upright, italic or oblique, or null to leave the slope as the node has it
 */
public record Span(int start, int end, Color fg, Color bg, boolean underline, int weight, FontSet.Slope slope) {

    public Span {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid span range [" + start + ", " + end + ")");
        }
        if (weight != 0 && (weight < 1 || weight > 1000)) {
            throw new IllegalArgumentException("a weight is 1-1000, or 0 for the node's own: " + weight);
        }
    }

    /** A span of colour and underline only — the shape every span had before weight and slope. */
    public Span(int start, int end, Color fg, Color bg, boolean underline) {
        this(start, end, fg, bg, underline, 0, null);
    }

    /** A foreground (text) colour span. */
    public static Span foreground(int start, int end, Color color) {
        return new Span(start, end, color, null, false);
    }

    /** A background (highlight) colour span. */
    public static Span background(int start, int end, Color color) {
        return new Span(start, end, null, color, false);
    }

    /** An underline span. */
    public static Span underline(int start, int end) {
        return new Span(start, end, null, null, true);
    }

    /** A weight span: 700 is bold. The text is drawn, and measured, in the family's face nearest that weight. */
    public static Span weight(int start, int end, int weight) {
        return new Span(start, end, null, null, false, weight, null);
    }

    /** A bold span — weight 700. */
    public static Span bold(int start, int end) {
        return weight(start, end, 700);
    }

    /** A slope span: italic or oblique (or upright, inside text the node sets slanted). */
    public static Span slope(int start, int end, FontSet.Slope slope) {
        return new Span(start, end, null, null, false, 0, slope);
    }

    /** An italic span. */
    public static Span italic(int start, int end) {
        return slope(start, end, FontSet.Slope.ITALIC);
    }

    /** Whether this span chooses a face — and so moves what is measured, not only what is drawn. */
    public boolean styled() {
        return weight != 0 || slope != null;
    }

    /** Whether any span in {@code spans} chooses a face. A null or non-list value has none. */
    public static boolean styles(Object spans) {
        if (spans instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Span s && s.styled()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether {@code offset} falls within {@code [start, end)}. */
    public boolean covers(int offset) {
        return offset >= start && offset < end;
    }

    /**
     * Remap this span's range through {@code edit} (auto-diff, §4.4). Returns the shifted span, or {@code null} if
     * the edit collapsed it to nothing (e.g. its whole range was deleted) — the caller drops such spans.
     */
    public Span remap(TextEdit edit) {
        int s = edit.mapForward(start);
        int e = edit.mapForward(end);
        return e > s ? new Span(s, e, fg, bg, underline, weight, slope) : null;
    }
}
