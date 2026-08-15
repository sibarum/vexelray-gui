package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.FocusEvent;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.core.text.TextEdit;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * An editable text field — single-line by default, {@link #multiline} on request — built on the framework's
 * input seams: typed text arrives through {@link Gui#onChar} (the layout-resolved {@code CharTyped} channel),
 * edit commands (caret motion, backspace, delete, Home/End, selection, clipboard, submit) through
 * {@link Gui#onKey}, and click-to-position plus drag-to-select through the general {@link Gui#onDrag} seam.
 *
 * <p>Everything geometric — where a click lands, where the line above is, how far a page scrolls — is a pure
 * lookup on this node's published layout read-model ({@code node.layout().text()}, docs/layout-read-model.md).
 * The widget never sees a measurer or a glyph atlas, which is what lets the identical code drive a field on
 * screen, headless in a test, or on a remote client with no fonts of its own.
 *
 * <p>The field owns the authoritative content, caret and selection anchor; it mirrors them onto the retained
 * node ({@link Node#text},
 * {@link Node#caret}, {@link Node#selection}) so the renderer draws the text, the selection highlight and the
 * blinking caret.
 *
 * <p>Selection spans {@code [min(anchor,caret), max(anchor,caret))}; {@code anchor == caret} means no selection.
 * Clipboard cut/copy/paste go through {@link Gui#clipboard()} (an OS clipboard when the app installs one, else a
 * process-local one). All input handlers run on worker threads, so every edit is serialised on this instance's
 * monitor and every tree change goes through the thread-safe {@link Node} handle; clipboard I/O happens outside
 * the lock. Reads flow out through {@link #onChange} (content edits) and {@link #onSubmit} (Enter).
 */
public final class TextField {

    private static final Color FIELD_BG = Color.rgb(0x0e1220);
    private static final Color FIELD_BORDER = Color.rgb(0x2b3346);
    private static final Color FIELD_BORDER_FOCUS = Color.rgb(0x3aa0ff);
    private static final Color INK = Color.rgb(0xeef2f8);
    private static final long BLINK_MILLIS = 530L;

    private final Gui gui;
    private final Node node;

    /** Cap on retained undo history, so a long editing session can't grow the stacks without bound. */
    private static final int UNDO_LIMIT = 1000;

    // Authoritative edit state, guarded by `this`. caret is the moving end; anchor the fixed end of a selection.
    private final StringBuilder content = new StringBuilder();
    private int caret;
    private int anchor;

    // Undo/redo over the edit-diff (§4.3). Each user edit pushes a TextEdit; a caret move or non-typing op sets
    // a coalescing barrier so a fresh typing/deletion run starts its own undo entry instead of merging.
    private final Deque<TextEdit> undo = new ArrayDeque<>();
    private final Deque<TextEdit> redo = new ArrayDeque<>();
    private boolean coalesceBarrier;

    // Formatting spans (fg/bg/underline). Auto-diff: every edit and undo/redo remaps them through the TextEdit
    // so they stay attached to their text. Guarded by `this`.
    private List<Span> spans = new ArrayList<>();

    // Vertical navigation's sticky desired column (absolute px). NaN means "recompute from the caret": every
    // horizontal move invalidates it, and Up/Down deliberately does not, so a run of Up/Down through short lines
    // returns to the original column instead of walking left. Guarded by `this`.
    private float desiredX = Float.NaN;

    private volatile boolean multiline;
    private volatile boolean focused;
    private volatile boolean blinkOn;
    private volatile Consumer<String> onChange = s -> { };
    private volatile Consumer<String> onSubmit = s -> { };

    /** Build an empty text field on {@code gui}. Size it via {@link #node()} (e.g. {@code .width(...)}). */
    public TextField(Gui gui) {
        this(gui, "");
    }

    /** Build a text field on {@code gui} pre-filled with {@code initial}; the caret starts at the end. */
    public TextField(Gui gui, String initial) {
        this.gui = gui;
        this.content.append(initial == null ? "" : initial);
        this.caret = content.length();
        this.anchor = caret;

        this.node = gui.text(content.toString())
                .editable(true)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE)
                .textColor(INK)
                .height(Length.rem(2.5f))
                .background(FIELD_BG)
                .corner(Length.rem(0.5f))
                .border(Length.rem(0.1f), FIELD_BORDER)
                .caret(-1);

        gui.onChar(node, this::onCodePoint);
        gui.onKey(node, this::onKey);
        // Pointer caret placement + drag-select, computed from this node's published layout read-model
        // (docs/layout-read-model.md) — press sets the caret, drag extends the selection. Uses the general
        // onDrag seam; no special caret plumbing in core.
        gui.onDrag(node, this::onPointer);
        gui.bus().subscribe(gui.focusEvents(), this::onFocus);

        startBlink();
    }

    /** The node to place in a layout (style/size it further through this handle). */
    public Node node() {
        return node;
    }

    /** Current text content. */
    public synchronized String text() {
        return content.toString();
    }

    /** Replace the content programmatically; the caret moves to the end, selection clears, and history resets. */
    public synchronized TextField text(String s) {
        content.setLength(0);
        content.append(s == null ? "" : s);
        caret = content.length();
        anchor = caret;
        undo.clear();
        redo.clear();
        coalesceBarrier = true;
        syncNode();
        onChange.accept(content.toString());
        return this;
    }

    /** React to content edits (typing, deletion, paste, programmatic set). Runs on a worker thread. */
    public TextField onChange(Consumer<String> handler) {
        this.onChange = handler == null ? s -> { } : handler;
        return this;
    }

    /** React to Enter being pressed in the field. Runs on a worker thread. Never fires on a multiline field. */
    public TextField onSubmit(Consumer<String> handler) {
        this.onSubmit = handler == null ? s -> { } : handler;
        return this;
    }

    /**
     * Let the field hold multiple lines: Enter inserts a newline instead of submitting, pasted newlines survive,
     * and the field scrolls vertically to keep the caret in view. Size it with {@code node().height(...)} — a
     * multiline field does not grow to fit its content.
     */
    public TextField multiline(boolean multiline) {
        this.multiline = multiline;
        node.multiline(multiline);
        return this;
    }

    /**
     * Wrap long lines at the field's content width instead of scrolling horizontally. Only meaningful together
     * with {@link #multiline}; a wrapped field never scrolls horizontally.
     */
    public TextField wordWrap(boolean wrap) {
        node.wordWrap(wrap);
        return this;
    }

    // --- formatting spans (§4.4) ---

    /** Replace the whole span set (bulk refresh — e.g. re-running a highlighter). Empty/null clears them. */
    public synchronized TextField setSpans(List<Span> newSpans) {
        spans = newSpans == null ? new ArrayList<>() : new ArrayList<>(newSpans);
        node.spans(spans);
        return this;
    }

    /** Add a single span, keeping the rest (§4.4 single-span update). */
    public synchronized TextField addSpan(Span span) {
        if (span != null) {
            spans.add(span);
            node.spans(spans);
        }
        return this;
    }

    /** Remove all spans. */
    public synchronized TextField clearSpans() {
        spans.clear();
        node.spans(List.of());
        return this;
    }

    /** A snapshot of the current spans. */
    public synchronized List<Span> spans() {
        return List.copyOf(spans);
    }

    // --- input handlers (worker threads) ---

    private void onCodePoint(int cp) {
        if (cp < 0x20 || cp == 0x7F) {
            return; // control characters ride the key channel, never the text channel
        }
        String s = new String(Character.toChars(cp));
        String changed;
        synchronized (this) {
            int at = hasSelection() ? selLo() : caret;
            int removeLen = hasSelection() ? selHi() - selLo() : 0;
            changed = applyEdit(at, removeLen, s);
        }
        wake();
        if (changed != null) {
            onChange.accept(changed);
        }
    }

    private void onKey(KeyEvent e) {
        boolean ctrl = e.has(Modifier.CONTROL);
        boolean shift = e.has(Modifier.SHIFT);
        Key k = e.key();

        if (ctrl) {
            switch (k) {
                case A -> { selectAll(); return; }
                case C -> { copy(); return; }
                case X -> { cut(); return; }
                case V -> { paste(); return; }
                case Z -> { if (shift) { redo(); } else { undo(); } return; } // Ctrl+Z undo, Ctrl+Shift+Z redo
                case Y -> { redo(); return; }                                  // Ctrl+Y redo
                default -> { /* Ctrl+other falls through to motion (word-jump) below */ }
            }
        }

        String changed = null;
        synchronized (this) {
            switch (k) {
                case LEFT -> { moveCaret(ctrl ? prevWord(caret) : stepLeft(caret), shift); syncNode(); }
                case RIGHT -> { moveCaret(ctrl ? nextWord(caret) : stepRight(caret), shift); syncNode(); }
                // Home/End are *visual* line ends on the read-model, so they stop at a wrap, not at a newline.
                case HOME -> { moveCaret(visualLineStart(), shift); syncNode(); }
                case END -> { moveCaret(visualLineEnd(), shift); syncNode(); }
                case UP -> moveByLines(-1, shift);
                case DOWN -> moveByLines(1, shift);
                case PAGE_UP -> moveByLines(-pageLines(), shift);
                case PAGE_DOWN -> moveByLines(pageLines(), shift);
                case BACKSPACE -> changed = ctrl ? deleteWordBack() : backspace();
                case DELETE -> changed = ctrl ? deleteWordForward() : deleteForward();
                case ENTER -> {
                    if (multiline) {
                        int at = hasSelection() ? selLo() : caret;
                        int removeLen = hasSelection() ? selHi() - selLo() : 0;
                        changed = applyEdit(at, removeLen, "\n");
                    }
                    // single-line: submitted outside the lock
                }
                default -> {
                    return; // not an edit command; typed text arrives via onChar
                }
            }
        }
        wake();
        if (k == Key.ENTER && !multiline) {
            onSubmit.accept(text());
        } else if (changed != null) {
            onChange.accept(changed);
        }
    }

    // --- vertical navigation, as pure widget code over the layout read-model (docs/layout-read-model.md §11.5) ---

    /**
     * Move the caret {@code lines} visual lines (negative = up), keeping the sticky desired column. Call while
     * holding the lock. Does nothing when the node has no published metrics yet.
     */
    private void moveByLines(int lines, boolean extend) {
        dev.vexelray.gui.core.text.TextMetrics m = node.layout().text();
        if (m == null || lines == 0) {
            return;
        }
        if (Float.isNaN(desiredX)) {
            desiredX = m.caretX(clamp(caret));
        }
        float column = desiredX;   // moveCaret clears it; vertical motion must keep it
        int target = clamp(caret);
        for (int i = 0; i < Math.abs(lines); i++) {
            int next = lines < 0 ? m.offsetAbove(target, column) : m.offsetBelow(target, column);
            if (next == target) {
                break;   // already at the first/last visual line
            }
            target = next;
        }
        moveCaret(target, extend);
        syncNode();
        desiredX = column;   // re-assert after syncNode cleared it: this move was vertical
    }

    /** How many visual lines fit in the field, for PageUp/PageDown. At least one, so a tiny field still moves. */
    private int pageLines() {
        dev.vexelray.gui.core.text.TextMetrics m = node.layout().text();
        if (m == null) {
            return 1;
        }
        float lineH = m.caretHeight(clamp(caret));
        // viewH is already the text area — inset by the padding, less whatever the scrollbars reserved.
        float viewH = node.layout().viewH();
        return lineH > 0f ? Math.max(1, (int) Math.floor(viewH / lineH)) : 1;
    }

    /** Start of the caret's visual line, falling back to the string start when metrics aren't published yet. */
    private int visualLineStart() {
        dev.vexelray.gui.core.text.TextMetrics m = node.layout().text();
        return m == null ? 0 : m.lineStart(clamp(caret));
    }

    /** End of the caret's visual line, falling back to the string end when metrics aren't published yet. */
    private int visualLineEnd() {
        dev.vexelray.gui.core.text.TextMetrics m = node.layout().text();
        return m == null ? content.length() : m.lineEnd(clamp(caret));
    }

    /** Map a pointer press/drag to a caret offset via this node's published text metrics, then place/extend. */
    private void onPointer(dev.vexelray.gui.core.input.DragEvent e) {
        dev.vexelray.gui.core.text.TextMetrics m = node.layout().text();
        if (m == null) {
            return; // not laid out yet, or no glyph metrics available
        }
        int offset = m.offsetAt(e.x(), e.y());
        switch (e.phase()) {
            case START -> placeCaret(offset);   // press positions the caret, collapsing any selection
            case MOVE -> extendTo(offset);       // drag extends the selection to the pointer
            case END -> { }
        }
    }

    /** Click: caret to the offset, selection collapsed there. */
    private void placeCaret(int offset) {
        synchronized (this) {
            caret = clamp(offset);
            anchor = caret;
            coalesceBarrier = true;
            syncNode();
        }
        wake();
    }

    /** Drag: caret to the offset, anchor kept — so the drag paints a selection. */
    private void extendTo(int offset) {
        synchronized (this) {
            caret = clamp(offset);
            coalesceBarrier = true;
            syncNode();
        }
        wake();
    }

    private void onFocus(FocusEvent e) {
        if (e.nodeId() != node.id()) {
            return;
        }
        focused = e.gained();
        if (focused) {
            blinkOn = true;
            node.caret(caretSnapshot());
            node.caretOn(true);
            node.border(Length.rem(0.1f), FIELD_BORDER_FOCUS);
        } else {
            node.caret(-1);
            node.caretOn(false);
            node.border(Length.rem(0.1f), FIELD_BORDER);
        }
    }

    // --- clipboard (I/O kept off the edit lock) ---

    private void copy() {
        String sel;
        synchronized (this) {
            sel = selectionText();
        }
        if (!sel.isEmpty()) {
            gui.clipboard().set(sel);
        }
    }

    private void cut() {
        String sel;
        String changed = null;
        synchronized (this) {
            sel = selectionText();
            if (!sel.isEmpty()) {
                coalesceBarrier = true;
                changed = applyEdit(selLo(), selHi() - selLo(), "");
            }
        }
        if (!sel.isEmpty()) {
            gui.clipboard().set(sel);
            wake();
            if (changed != null) {
                onChange.accept(changed);
            }
        }
    }

    private void paste() {
        String clip = gui.clipboard().get();
        if (clip == null || clip.isEmpty()) {
            return;
        }
        // A single-line field flattens embedded newlines; a multiline one keeps them (normalising CRLF first).
        String insert = multiline
                ? clip.replace("\r\n", "\n").replace('\r', '\n')
                : clip.replace('\n', ' ').replace('\r', ' ');
        String changed;
        synchronized (this) {
            int at = hasSelection() ? selLo() : caret;
            int removeLen = hasSelection() ? selHi() - selLo() : 0;
            coalesceBarrier = true; // a paste is its own undo entry, never merged with adjacent typing
            changed = applyEdit(at, removeLen, insert);
        }
        wake();
        if (changed != null) {
            onChange.accept(changed);
        }
    }

    private void selectAll() {
        synchronized (this) {
            anchor = 0;
            caret = content.length();
            coalesceBarrier = true;
            syncNode();
        }
        wake();
    }

    // --- edit primitives (call while holding the lock) ---

    private boolean hasSelection() {
        return anchor != caret;
    }

    private int selLo() {
        return Math.min(anchor, caret);
    }

    private int selHi() {
        return Math.max(anchor, caret);
    }

    private String selectionText() {
        return hasSelection() ? content.substring(selLo(), selHi()) : "";
    }

    /**
     * The one content-mutation path: replace {@code [at, at+removeLen)} with {@code insert}, move the caret to the
     * end of the insertion, record the resulting {@link TextEdit} for undo (coalescing typing/deletion runs), and
     * mirror to the node. Returns the new content, or {@code null} if the edit was a no-op.
     */
    private String applyEdit(int at, int removeLen, String insert) {
        String removed = content.substring(at, at + removeLen);
        if (removed.isEmpty() && insert.isEmpty()) {
            return null;
        }
        TextEdit edit = new TextEdit(at, removed, insert);
        content.replace(at, at + removeLen, insert);
        caret = at + insert.length();
        anchor = caret;
        recordEdit(edit);
        remapSpans(edit);
        syncNode();
        return content.toString();
    }

    /** Auto-diff (§4.4): remap every span through {@code edit}, dropping any that collapsed, and push to the node. */
    private void remapSpans(TextEdit edit) {
        if (spans.isEmpty()) {
            return;
        }
        List<Span> next = new ArrayList<>(spans.size());
        for (Span sp : spans) {
            Span r = sp.remap(edit);
            if (r != null) {
                next.add(r);
            }
        }
        spans = next;
        node.spans(next);
    }

    /** Push {@code e} onto the undo stack, merging into the previous entry when it continues a run (§4.3). */
    private void recordEdit(TextEdit e) {
        redo.clear();
        TextEdit top = undo.peek();
        if (!coalesceBarrier && top != null && canCoalesce(top, e)) {
            undo.pop();
            undo.push(merge(top, e));
        } else {
            undo.push(e);
            while (undo.size() > UNDO_LIMIT) {
                undo.removeLast();
            }
        }
        coalesceBarrier = false;
    }

    private static boolean isInsert(TextEdit t) {
        return t.removed().isEmpty() && !t.inserted().isEmpty();
    }

    private static boolean isDelete(TextEdit t) {
        return t.inserted().isEmpty() && !t.removed().isEmpty();
    }

    /** Whether {@code e} continues {@code top}'s run: contiguous single-char typing, or backspace/forward-delete. */
    private static boolean canCoalesce(TextEdit top, TextEdit e) {
        if (isInsert(top) && isInsert(e) && e.inserted().length() == 1 && e.at() == top.insertedEnd()) {
            return true; // typing run: "a" then "b" at the caret
        }
        if (isDelete(top) && isDelete(e) && e.removed().length() == 1 && e.removedEnd() == top.at()) {
            return true; // backspace run: deletions extending leftward
        }
        return isDelete(top) && isDelete(e) && e.removed().length() == 1 && e.at() == top.at();
        // forward-delete run: repeated deletions at the same caret
    }

    private static TextEdit merge(TextEdit top, TextEdit e) {
        if (isInsert(top)) {
            return new TextEdit(top.at(), "", top.inserted() + e.inserted());
        }
        if (e.removedEnd() == top.at()) {
            return new TextEdit(e.at(), e.removed() + top.removed(), ""); // backspace: e is left of top
        }
        return new TextEdit(top.at(), top.removed() + e.removed(), "");    // forward-delete: e at the caret
    }

    private void undo() {
        String changed = null;
        synchronized (this) {
            TextEdit e = undo.poll();
            if (e == null) {
                return;
            }
            content.replace(e.at(), e.insertedEnd(), e.removed());
            caret = e.at() + e.removed().length();
            anchor = caret;
            redo.push(e);
            coalesceBarrier = true;
            remapSpans(e.inverse()); // undo maps spans from post-edit coords back to pre-edit
            syncNode();
            changed = content.toString();
        }
        wake();
        onChange.accept(changed);
    }

    private void redo() {
        String changed = null;
        synchronized (this) {
            TextEdit e = redo.poll();
            if (e == null) {
                return;
            }
            content.replace(e.at(), e.removedEnd(), e.inserted());
            caret = e.insertedEnd();
            anchor = caret;
            undo.push(e);
            coalesceBarrier = true;
            remapSpans(e); // redo re-applies the edit, so spans map forward through it
            syncNode();
            changed = content.toString();
        }
        wake();
        onChange.accept(changed);
    }

    private void moveCaret(int newPos, boolean extend) {
        caret = clamp(newPos);
        if (!extend) {
            anchor = caret;
        }
        coalesceBarrier = true; // a caret move ends the current typing/deletion run for undo grouping
    }

    /** @return the new content after the edit, or {@code null} if nothing changed. */
    private String backspace() {
        if (hasSelection()) {
            return applyEdit(selLo(), selHi() - selLo(), "");
        }
        if (caret > 0) {
            int start = content.offsetByCodePoints(caret, -1);
            return applyEdit(start, caret - start, "");
        }
        return null;
    }

    private String deleteForward() {
        if (hasSelection()) {
            return applyEdit(selLo(), selHi() - selLo(), "");
        }
        if (caret < content.length()) {
            int end = content.offsetByCodePoints(caret, 1);
            return applyEdit(caret, end - caret, "");
        }
        return null;
    }

    /** Ctrl+Backspace: delete from the previous word boundary to the caret (or the selection, if any). */
    private String deleteWordBack() {
        if (hasSelection()) {
            return applyEdit(selLo(), selHi() - selLo(), "");
        }
        int start = prevWord(caret);
        coalesceBarrier = true; // word-delete is its own undo entry
        return start < caret ? applyEdit(start, caret - start, "") : null;
    }

    /** Ctrl+Delete: delete from the caret to the next word boundary (or the selection, if any). */
    private String deleteWordForward() {
        if (hasSelection()) {
            return applyEdit(selLo(), selHi() - selLo(), "");
        }
        int end = nextWord(caret);
        coalesceBarrier = true;
        return end > caret ? applyEdit(caret, end - caret, "") : null;
    }

    private int stepLeft(int c) {
        return c > 0 ? content.offsetByCodePoints(c, -1) : 0;
    }

    private int stepRight(int c) {
        return c < content.length() ? content.offsetByCodePoints(c, 1) : c;
    }

    private int clamp(int offset) {
        return Math.max(0, Math.min(offset, content.length()));
    }

    // --- helpers ---

    private synchronized int caretSnapshot() {
        return caret;
    }

    /**
     * Mirror content + caret + selection onto the retained node (call while holding the lock).
     *
     * <p>This is also the one place the sticky desired column is dropped. Every caret or content change funnels
     * through here, so invalidating once here cannot be forgotten at a new call site — and {@link #moveByLines}
     * simply re-asserts the column immediately afterwards, which is exactly what makes it sticky across a run of
     * Up/Down and not across anything else.
     */
    private void syncNode() {
        desiredX = Float.NaN;
        node.text(content.toString());
        node.caret(caret);
        node.selection(anchor, caret);
    }

    /** Show the caret solid right after an action, so motion/typing feels responsive. */
    private void wake() {
        blinkOn = true;
        if (focused) {
            node.caretOn(true);
        }
    }

    /**
     * A <em>word character</em>: letter, digit, {@code -} or {@code _}. Everything else (whitespace and other
     * punctuation) is a separator, so identifiers like {@code foo_bar-baz} count as a single word.
     */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    /**
     * Nearest word boundary to the left of {@code from}: skip leading whitespace, then consume one run of
     * <em>either</em> word chars <em>or</em> non-space separators — so it stops at punctuation clusters rather
     * than leaping the whole gap (§8.2).
     */
    private int prevWord(int from) {
        int i = from;
        while (i > 0 && Character.isWhitespace(content.charAt(i - 1))) {
            i--;
        }
        if (i > 0 && isWordChar(content.charAt(i - 1))) {
            while (i > 0 && isWordChar(content.charAt(i - 1))) {
                i--;
            }
        } else {
            while (i > 0 && !isWordChar(content.charAt(i - 1)) && !Character.isWhitespace(content.charAt(i - 1))) {
                i--;
            }
        }
        return i;
    }

    /**
     * Nearest word boundary to the right of {@code from}: skip leading whitespace, then consume one run of
     * <em>either</em> word chars <em>or</em> non-space separators (§8.2).
     */
    private int nextWord(int from) {
        int i = from;
        int n = content.length();
        while (i < n && Character.isWhitespace(content.charAt(i))) {
            i++;
        }
        if (i < n && isWordChar(content.charAt(i))) {
            while (i < n && isWordChar(content.charAt(i))) {
                i++;
            }
        } else {
            while (i < n && !isWordChar(content.charAt(i)) && !Character.isWhitespace(content.charAt(i))) {
                i++;
            }
        }
        return i;
    }

    private void startBlink() {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(BLINK_MILLIS);
                    if (focused) {
                        blinkOn = !blinkOn;
                        node.caretOn(blinkOn);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "vexelray-textfield-blink");
        t.setDaemon(true);
        t.start();
    }
}
