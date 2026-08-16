package dev.vexelray.gui.core.text;

import java.util.ArrayList;
import java.util.List;

/**
 * An editable text document: the content, the caret and selection anchor, and the formatting spans, as one
 * immutable value. Held by a widget in an Atchung {@code State<Document>} and changed by committing an
 * {@link Edit}, which {@link #apply} resolves against the current value.
 *
 * <p><b>Why these four fields travel together.</b> They are not independent: a caret offset is only meaningful
 * against a particular text, and a span's range is only meaningful against the same one. Published as separate
 * properties they can be observed mid-update — the previous design mirrored text, caret and selection onto the
 * retained node as three separate mutations, which a frame boundary is free to split, so a frame could render a
 * caret positioned against text that no longer existed. One value, one version, no such state.
 *
 * <p>{@link #lastEdit} is the diff this value's version applied, or {@code null} when the change moved the caret
 * or the spans without touching the text. A subscriber therefore receives what changed alongside the value that
 * changed, which is what lets undo history and span remapping be driven from the commit rather than reconstructed
 * by diffing snapshots.
 *
 * @param text     the content
 * @param caret    the moving end of the selection, and where insertion happens
 * @param anchor   the fixed end; {@code anchor == caret} means no selection
 * @param spans    formatting spans, already remapped through {@link #lastEdit}
 * @param lastEdit the diff that produced this value, or {@code null} if the text is unchanged
 */
public record Document(String text, int caret, int anchor, List<Span> spans, TextEdit lastEdit) {

    /** An empty document with the caret at the start. */
    public static final Document EMPTY = new Document("", 0, 0, List.of(), null);

    public Document {
        text = text == null ? "" : text;
        spans = spans == null ? List.of() : List.copyOf(spans);
        caret = clamp(caret, 0, text.length());
        anchor = clamp(anchor, 0, text.length());
    }

    /** A document holding {@code text}, caret at the end, no selection, no spans. */
    public static Document of(String text) {
        String s = text == null ? "" : text;
        return new Document(s, s.length(), s.length(), List.of(), null);
    }

    public boolean hasSelection() {
        return anchor != caret;
    }

    /** Low end of the selection (== caret when there is none). */
    public int selectionStart() {
        return Math.min(anchor, caret);
    }

    /** High end of the selection (== caret when there is none). */
    public int selectionEnd() {
        return Math.max(anchor, caret);
    }

    /** The selected text, or {@code ""} when there is no selection. */
    public String selectedText() {
        return hasSelection() ? text.substring(selectionStart(), selectionEnd()) : "";
    }

    public int length() {
        return text.length();
    }

    /** Clamp {@code offset} into this document. */
    public int clampOffset(int offset) {
        return clamp(offset, 0, text.length());
    }

    // --- the reducer ---

    /**
     * Resolve {@code edit} against this document and return the result, or {@code this} when nothing changed
     * (so a no-op commit does not burn a version). This is the body of the {@code State} committer: it runs
     * against whatever the current value is at commit time, and may run more than once if a CAS retries, so it
     * is pure and must stay that way.
     */
    public Document apply(Edit edit) {
        return switch (edit) {
            case Edit.Insert i -> replace(selectionStart(), selectionEnd() - selectionStart(), i.text());
            case Edit.DeleteBack d -> {
                if (hasSelection()) {
                    yield replace(selectionStart(), selectionEnd() - selectionStart(), "");
                }
                int from = d.word() ? previousWord(caret) : previousBoundary(caret);
                yield from < caret ? replace(from, caret - from, "") : this;
            }
            case Edit.DeleteForward d -> {
                if (hasSelection()) {
                    yield replace(selectionStart(), selectionEnd() - selectionStart(), "");
                }
                int to = d.word() ? nextWord(caret) : nextBoundary(caret);
                yield to > caret ? replace(caret, to - caret, "") : this;
            }
            case Edit.Replace r -> {
                int at = clampOffset(r.at());
                int removeLen = clamp(r.removeLen(), 0, text.length() - at);
                yield replace(at, removeLen, r.text());
            }
            case Edit.Caret c -> {
                int to = clampOffset(c.to());
                int newAnchor = c.extend() ? anchor : to;
                yield to == caret && newAnchor == anchor ? this
                        : new Document(text, to, newAnchor, spans, null);
            }
            case Edit.SelectAll ignored -> anchor == 0 && caret == text.length() ? this
                    : new Document(text, text.length(), 0, spans, null);
            case Edit.SetText s -> {
                String next = s.text() == null ? "" : s.text();
                yield new Document(next, next.length(), next.length(), List.of(), null);
            }
            case Edit.SetSpans s -> new Document(text, caret, anchor,
                    s.spans() == null ? List.of() : s.spans(), null);
        };
    }

    /**
     * The one content-mutation path: replace {@code [at, at + removeLen)} with {@code insert}, move the caret to
     * the end of the insertion, and remap every span through the resulting diff so formatting stays attached to
     * its text (auto-diff, keyboard-focus-text.md §4.4).
     */
    private Document replace(int at, int removeLen, String insert) {
        String in = insert == null ? "" : insert;
        String removed = text.substring(at, at + removeLen);
        if (removed.isEmpty() && in.isEmpty()) {
            return this;
        }
        TextEdit diff = new TextEdit(at, removed, in);
        String next = text.substring(0, at) + in + text.substring(at + removeLen);
        int newCaret = at + in.length();
        return new Document(next, newCaret, newCaret, remap(spans, diff), diff);
    }

    private static List<Span> remap(List<Span> spans, TextEdit diff) {
        if (spans.isEmpty()) {
            return spans;
        }
        List<Span> out = new ArrayList<>(spans.size());
        for (Span sp : spans) {
            Span r = sp.remap(diff);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    // --- boundaries (offsets, not glyphs — the document knows nothing about layout) ---

    private int previousBoundary(int from) {
        return from > 0 ? text.offsetByCodePoints(from, -1) : 0;
    }

    private int nextBoundary(int from) {
        return from < text.length() ? text.offsetByCodePoints(from, 1) : from;
    }

    /**
     * A <em>word character</em>: letter, digit, {@code -} or {@code _}. Everything else (whitespace and other
     * punctuation) separates, so an identifier like {@code foo_bar-baz} counts as one word.
     */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    /**
     * Nearest word boundary left of {@code from}: skip whitespace, then consume one run of <em>either</em> word
     * characters <em>or</em> non-space separators — so it stops at a punctuation cluster rather than leaping the
     * whole gap (keyboard-focus-text.md §8.2).
     */
    public int previousWord(int from) {
        int i = clampOffset(from);
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        if (i > 0 && isWordChar(text.charAt(i - 1))) {
            while (i > 0 && isWordChar(text.charAt(i - 1))) {
                i--;
            }
        } else {
            while (i > 0 && !isWordChar(text.charAt(i - 1)) && !Character.isWhitespace(text.charAt(i - 1))) {
                i--;
            }
        }
        return i;
    }

    /** Nearest word boundary right of {@code from}, by the same rule as {@link #previousWord}. */
    public int nextWord(int from) {
        int i = clampOffset(from);
        int n = text.length();
        while (i < n && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i < n && isWordChar(text.charAt(i))) {
            while (i < n && isWordChar(text.charAt(i))) {
                i++;
            }
        } else {
            while (i < n && !isWordChar(text.charAt(i)) && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
        }
        return i;
    }

    /** One code point left of the caret (or the caret, at the start). */
    public int stepLeft(int from) {
        return previousBoundary(clampOffset(from));
    }

    /** One code point right of the caret (or the caret, at the end). */
    public int stepRight(int from) {
        return nextBoundary(clampOffset(from));
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
