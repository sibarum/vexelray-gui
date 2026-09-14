package dev.vexelray.gui.core.text;

import java.util.List;

/**
 * An intent to change a {@link Document}, resolved against the current value rather than against coordinates the
 * caller measured earlier.
 *
 * <p>That relativity is the point. An absolute instruction ("the text is now {@code abc}") computed from a stale
 * read silently discards whatever landed in between — the update is lost and the result still looks coherent, so
 * nothing reports it. A relative intent ("insert {@code c} at the caret") is re-resolved against whatever the
 * document actually is when it commits, so a concurrent commit costs a CAS retry instead of an edit.
 *
 * <p>Ordering is a separate property and this does not supply it: two intents committed concurrently both apply,
 * but which lands first is decided by the race. Intents that must be sequenced are committed on the GUI thread's
 * drain, where a total order already exists (architecture.md §5, §8).
 *
 * <p><b>Each edit resolves itself.</b> {@code Document.apply} was a switch over every kind, which is dispatch
 * written by hand and the reason this interface had to stay sealed — an edit an application defined could not be
 * applied, because the switch would have had no case for it. No sink is needed the way {@code Mutation} needed
 * one: a document is immutable and {@link #apply} returns a new one, so resolving an edit writes nothing and
 * there is no single-writer guarantee to preserve.
 */
public interface Edit {

    /**
     * Resolve this intent against {@code document} and return the result, or {@code document} itself when
     * nothing changed — so a no-op commit does not burn a version.
     *
     * <p><b>Must be pure.</b> This runs as the body of a {@code State} committer, against whatever the current
     * value is at commit time, and may run more than once if a CAS retries. An implementation that recorded
     * something, or read a clock, would do it a number of times nobody chose.
     */
    Document apply(Document document);

    /** Insert {@code text} at the caret, replacing the selection if there is one. */
    record Insert(String text) implements Edit {
        @Override
        public Document apply(Document d) {
            return d.replace(d.selectionStart(), d.selectionEnd() - d.selectionStart(), text);
        }
    }

    /** Delete backwards from the caret — the selection, else one word or one code point. */
    record DeleteBack(boolean word) implements Edit {
        @Override
        public Document apply(Document d) {
            if (d.hasSelection()) {
                return d.replace(d.selectionStart(), d.selectionEnd() - d.selectionStart(), "");
            }
            int from = word ? d.previousWord(d.caret()) : d.previousBoundary(d.caret());
            return from < d.caret() ? d.replace(from, d.caret() - from, "") : d;
        }
    }

    /** Delete forwards from the caret — the selection, else one word or one code point. */
    record DeleteForward(boolean word) implements Edit {
        @Override
        public Document apply(Document d) {
            if (d.hasSelection()) {
                return d.replace(d.selectionStart(), d.selectionEnd() - d.selectionStart(), "");
            }
            int to = word ? d.nextWord(d.caret()) : d.nextBoundary(d.caret());
            return to > d.caret() ? d.replace(d.caret(), to - d.caret(), "") : d;
        }
    }

    /**
     * Replace {@code [at, at + removeLen)} with {@code text}, in absolute coordinates. Undo and redo use this:
     * they replay a {@link TextEdit} that was recorded against a specific document state, so re-resolving it
     * would be wrong. Offsets are clamped to the current text rather than throwing.
     */
    record Replace(int at, int removeLen, String text) implements Edit {
        @Override
        public Document apply(Document d) {
            int from = d.clampOffset(at);
            return d.replace(from, Document.clamp(removeLen, 0, d.length() - from), text);
        }
    }

    /** Move the caret to {@code to}; {@code extend} keeps the anchor, so the selection grows. */
    record Caret(int to, boolean extend) implements Edit {
        @Override
        public Document apply(Document d) {
            int moved = d.clampOffset(to);
            int newAnchor = extend ? d.anchor() : moved;
            return moved == d.caret() && newAnchor == d.anchor()
                    ? d
                    : new Document(d.text(), moved, newAnchor, d.spans(), null);
        }
    }

    /** Select the whole document. */
    record SelectAll() implements Edit {
        @Override
        public Document apply(Document d) {
            return d.anchor() == 0 && d.caret() == d.length()
                    ? d
                    : new Document(d.text(), d.length(), 0, d.spans(), null);
        }
    }

    /**
     * Select {@code [start, end)} — anchor at {@code start}, caret at {@code end}, both clamped into the text.
     *
     * <p>Absolute, and deliberately so: unlike {@link Caret} this names a range someone already located in the
     * text — the match a search landed on, the word a completion is replacing — and re-resolving it against the
     * caret would move it somewhere else. {@link SelectAll} stays the relative form of the same idea (whatever
     * the text is at commit time, all of it), which is why both exist.
     */
    record Select(int start, int end) implements Edit {
        @Override
        public Document apply(Document d) {
            int from = d.clampOffset(start);
            int to = d.clampOffset(end);
            return to == d.caret() && from == d.anchor() ? d : new Document(d.text(), to, from, d.spans(), null);
        }
    }

    /** Replace the entire text (a programmatic set): caret to the end, selection cleared, spans dropped. */
    record SetText(String text) implements Edit {
        @Override
        public Document apply(Document d) {
            String next = text == null ? "" : text;
            return new Document(next, next.length(), next.length(), List.of(), null);
        }
    }

    /**
     * Replace all of the text with {@code text}, as an <b>edit</b>: it produces a diff, so spans are remapped
     * through it and a history has something to record. The relative form of {@link Replace} over the whole
     * document — whatever the content is at commit time, all of it — which is why no length is a parameter:
     * measuring one first is exactly the stale read this interface exists to avoid.
     *
     * <p>{@link SetText} is the other half of the pair and stays the right answer for content the document has
     * no past with — a file loaded over the top, a field reset. This one is for content the user just made
     * happen: a completion, a template, a keypad key that rewrites the line. The difference is not the pixels,
     * it is whether what was there is a state anyone should be able to get back to.
     *
     * <p>Replacing the text with what it already says is <b>not</b> an edit: the document is yielded unchanged,
     * so there is no diff, no history entry and no caret thrown to the end. A command that happens to be a fixed
     * point must not cost a Ctrl+Z that appears to do nothing.
     */
    record ReplaceAll(String text) implements Edit {
        @Override
        public Document apply(Document d) {
            String next = text == null ? "" : text;
            return next.equals(d.text()) ? d : d.replace(0, d.length(), next);
        }
    }

    /** Replace the formatting span set, leaving the text and caret alone. */
    record SetSpans(List<Span> spans) implements Edit {
        @Override
        public Document apply(Document d) {
            return new Document(d.text(), d.caret(), d.anchor(), spans == null ? List.of() : spans, null);
        }
    }
}
