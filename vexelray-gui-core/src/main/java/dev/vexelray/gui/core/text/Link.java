package dev.vexelray.gui.core.text;

/**
 * A hyperlink: the half-open character range {@code [start, end)} stands for {@code target}, and activating it
 * (Ctrl+click, or Ctrl+Enter with the caret inside it) asks for that target.
 *
 * <p><b>A link is not a span.</b> {@link Span} says how text is <em>drawn</em>; this says what a piece of text
 * <em>means</em>. Keeping them apart is what makes the behaviour possible at all: the underline under a link
 * appears only while the modifier is held, so it is derived from this and the keyboard together, several times a
 * second, and it would be nonsense to store it. A link that carried its own underline could not stop being
 * underlined.
 *
 * <p>{@code target} is uninterpreted here. A {@code window/landmark} address navigates
 * ({@link dev.vexelray.gui.core.nav.Address}), and anything else is the application's to make sense of — a file,
 * a URL, an identifier in its own model. Nothing in the framework opens anything on the strength of a string
 * found in a document.
 *
 * <p>Links remap through an edit exactly as spans do ({@link #remap(TextEdit)}), so typing before a link moves
 * it and typing inside it grows it, and no bookkeeping is asked of the code that made it.
 */
public record Link(int start, int end, String target) {

    public Link {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("invalid link range [" + start + ", " + end + ")");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("a link must have a target");
        }
    }

    /** Whether {@code offset} falls within {@code [start, end)}. */
    public boolean covers(int offset) {
        return offset >= start && offset < end;
    }

    /** The underline this link is drawn with while the modifier is held. */
    public Span underline() {
        return Span.underline(start, end);
    }

    /**
     * Remap this link's range through {@code edit}, as {@link Span#remap} does. Returns the shifted link, or
     * {@code null} if the edit deleted the whole of its text — a link whose words are gone is not a link with an
     * empty range, it is no longer there.
     */
    public Link remap(TextEdit edit) {
        int s = edit.mapForward(start);
        int e = edit.mapForward(end);
        return e > s ? new Link(s, e, target) : null;
    }
}
