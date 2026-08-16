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
 */
public sealed interface Edit {

    /** Insert {@code text} at the caret, replacing the selection if there is one. */
    record Insert(String text) implements Edit {
    }

    /** Delete backwards from the caret — the selection, else one word or one code point. */
    record DeleteBack(boolean word) implements Edit {
    }

    /** Delete forwards from the caret — the selection, else one word or one code point. */
    record DeleteForward(boolean word) implements Edit {
    }

    /**
     * Replace {@code [at, at + removeLen)} with {@code text}, in absolute coordinates. Undo and redo use this:
     * they replay a {@link TextEdit} that was recorded against a specific document state, so re-resolving it
     * would be wrong. Offsets are clamped to the current text rather than throwing.
     */
    record Replace(int at, int removeLen, String text) implements Edit {
    }

    /** Move the caret to {@code to}; {@code extend} keeps the anchor, so the selection grows. */
    record Caret(int to, boolean extend) implements Edit {
    }

    /** Select the whole document. */
    record SelectAll() implements Edit {
    }

    /** Replace the entire text (a programmatic set): caret to the end, selection cleared, spans dropped. */
    record SetText(String text) implements Edit {
    }

    /** Replace the formatting span set, leaving the text and caret alone. */
    record SetSpans(List<Span> spans) implements Edit {
    }
}
