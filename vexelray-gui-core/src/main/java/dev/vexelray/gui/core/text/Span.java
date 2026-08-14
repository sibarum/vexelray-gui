package dev.vexelray.gui.core.text;

import dev.vexelray.canvas.Color;

/**
 * A formatting span: the half-open character range {@code [start, end)} carries visual attributes — a text
 * (foreground) colour, a background highlight colour, and/or an underline (architecture, keyboard-focus-text.md
 * §4.4). Any attribute may be absent ({@code null} colour / {@code false} underline), so spans compose: a fg span
 * and a bg span can overlap the same text.
 *
 * <p>Spans are <em>auto-diff</em>: after an edit they remap their offsets through the edit's {@link TextEdit}
 * (via {@link #remap(TextEdit)}), so an insertion before a span shifts it and an edit inside it grows/shrinks it,
 * keeping every span attached to its text with no manual bookkeeping.
 */
public record Span(int start, int end, Color fg, Color bg, boolean underline) {

    public Span {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid span range [" + start + ", " + end + ")");
        }
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
        return e > s ? new Span(s, e, fg, bg, underline) : null;
    }
}
