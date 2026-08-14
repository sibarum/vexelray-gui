package dev.vexelray.gui.core.text;

import java.util.Objects;

/**
 * A single text edit expressed as a diff: at character offset {@code at}, {@code removed} was replaced by
 * {@code inserted}. This is the framework's one edit-diff primitive (architecture, keyboard-focus-text.md §4.3):
 * undo/redo replay it (an edit and its {@link #inverse()} are symmetric), and every span remaps its offsets
 * through {@link #mapForward(int)} — so one mechanism drives both undo and auto-diff spans.
 *
 * <p>A pure insertion has {@code removed == ""}; a pure deletion has {@code inserted == ""}. Offsets are
 * character indices (UTF-16 code units), matching {@code String}/{@code StringBuilder}.
 */
public record TextEdit(int at, String removed, String inserted) {

    public TextEdit {
        Objects.requireNonNull(removed, "removed");
        Objects.requireNonNull(inserted, "inserted");
        if (at < 0) {
            throw new IllegalArgumentException("at must be non-negative: " + at);
        }
    }

    /** A pure insertion of {@code text} at {@code at}. */
    public static TextEdit insert(int at, String text) {
        return new TextEdit(at, "", text);
    }

    /** A pure deletion of {@code text} (the removed content) at {@code at}. */
    public static TextEdit delete(int at, String text) {
        return new TextEdit(at, text, "");
    }

    /** Net length change this edit applies to the text (inserted − removed). */
    public int delta() {
        return inserted.length() - removed.length();
    }

    /** The end offset of the removed span in the pre-edit text. */
    public int removedEnd() {
        return at + removed.length();
    }

    /** The end offset of the inserted span in the post-edit text. */
    public int insertedEnd() {
        return at + inserted.length();
    }

    /** The inverse edit — applying this then its inverse is a no-op. Undo replays the inverse. */
    public TextEdit inverse() {
        return new TextEdit(at, inserted, removed);
    }

    /**
     * Map an offset in the <em>pre</em>-edit text to the corresponding offset in the <em>post</em>-edit text:
     * offsets before the edit are unchanged, offsets after it shift by {@link #delta()}, and an offset that fell
     * inside the removed span collapses to the end of the inserted text (the caret/anchor lands where the
     * replacement ends). This is the remap auto-diff spans apply to keep their ranges attached across edits.
     */
    public int mapForward(int offset) {
        if (offset <= at) {
            return offset;
        }
        if (offset >= removedEnd()) {
            return offset + delta();
        }
        return insertedEnd();
    }
}
