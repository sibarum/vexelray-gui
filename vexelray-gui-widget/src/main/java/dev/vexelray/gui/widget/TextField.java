package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.FocusEvent;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.function.Consumer;

/**
 * A single-line editable text field built on the framework's input seams: typed text arrives through
 * {@link Gui#onChar} (the layout-resolved {@code CharTyped} channel), edit commands (caret motion, backspace,
 * delete, Home/End, selection, clipboard, submit) through {@link Gui#onKey}, click-to-position through
 * {@link Gui#onCaretHit}, and drag-to-select through {@link Gui#onCaretDrag}. The field owns the authoritative
 * content, caret and selection anchor; it mirrors them onto the retained node ({@link Node#text},
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

    // Authoritative edit state, guarded by `this`. caret is the moving end; anchor the fixed end of a selection.
    private final StringBuilder content = new StringBuilder();
    private int caret;
    private int anchor;

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
        gui.onCaretHit(node, this::placeCaret);
        gui.onCaretDrag(node, this::extendTo);
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

    /** Replace the content programmatically; the caret moves to the end and any selection clears. */
    public synchronized TextField text(String s) {
        content.setLength(0);
        content.append(s == null ? "" : s);
        caret = content.length();
        anchor = caret;
        syncNode();
        onChange.accept(content.toString());
        return this;
    }

    /** React to content edits (typing, deletion, paste, programmatic set). Runs on a worker thread. */
    public TextField onChange(Consumer<String> handler) {
        this.onChange = handler == null ? s -> { } : handler;
        return this;
    }

    /** React to Enter being pressed in the field. Runs on a worker thread. */
    public TextField onSubmit(Consumer<String> handler) {
        this.onSubmit = handler == null ? s -> { } : handler;
        return this;
    }

    // --- input handlers (worker threads) ---

    private void onCodePoint(int cp) {
        if (cp < 0x20 || cp == 0x7F) {
            return; // control characters ride the key channel, never the text channel
        }
        String changed;
        synchronized (this) {
            if (hasSelection()) {
                deleteSelection();
            }
            content.insert(caret, new String(Character.toChars(cp)));
            caret += Character.charCount(cp);
            anchor = caret;
            syncNode();
            changed = content.toString();
        }
        wake();
        onChange.accept(changed);
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
                default -> { /* Ctrl+other falls through to motion (word-jump) below */ }
            }
        }

        boolean edited = false;
        String changed = null;
        synchronized (this) {
            switch (k) {
                case LEFT -> moveCaret(ctrl ? prevWord(caret) : stepLeft(caret), shift);
                case RIGHT -> moveCaret(ctrl ? nextWord(caret) : stepRight(caret), shift);
                case HOME -> moveCaret(0, shift);
                case END -> moveCaret(content.length(), shift);
                case BACKSPACE -> edited = backspace();
                case DELETE -> edited = deleteForward();
                case ENTER -> { /* handled outside the lock */ }
                default -> {
                    return; // not an edit command; typed text arrives via onChar
                }
            }
            syncNode();
            if (edited) {
                changed = content.toString();
            }
        }
        wake();
        if (k == Key.ENTER) {
            onSubmit.accept(text());
        } else if (changed != null) {
            onChange.accept(changed);
        }
    }

    /** Click: caret to the offset, selection collapsed there. */
    private void placeCaret(int offset) {
        synchronized (this) {
            caret = clamp(offset);
            anchor = caret;
            syncNode();
        }
        wake();
    }

    /** Drag: caret to the offset, anchor kept — so the drag paints a selection. */
    private void extendTo(int offset) {
        synchronized (this) {
            caret = clamp(offset);
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
                deleteSelection();
                syncNode();
                changed = content.toString();
            }
        }
        if (!sel.isEmpty()) {
            gui.clipboard().set(sel);
            wake();
            onChange.accept(changed);
        }
    }

    private void paste() {
        String clip = gui.clipboard().get();
        if (clip == null || clip.isEmpty()) {
            return;
        }
        String oneLine = clip.replace('\n', ' ').replace('\r', ' '); // single-line field: no embedded newlines
        String changed;
        synchronized (this) {
            if (hasSelection()) {
                deleteSelection();
            }
            content.insert(caret, oneLine);
            caret += oneLine.length();
            anchor = caret;
            syncNode();
            changed = content.toString();
        }
        wake();
        onChange.accept(changed);
    }

    private void selectAll() {
        synchronized (this) {
            anchor = 0;
            caret = content.length();
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

    private void deleteSelection() {
        int lo = selLo();
        content.delete(lo, selHi());
        caret = lo;
        anchor = lo;
    }

    private void moveCaret(int newPos, boolean extend) {
        caret = clamp(newPos);
        if (!extend) {
            anchor = caret;
        }
    }

    private boolean backspace() {
        if (hasSelection()) {
            deleteSelection();
            return true;
        }
        if (caret > 0) {
            int start = content.offsetByCodePoints(caret, -1);
            content.delete(start, caret);
            caret = start;
            anchor = caret;
            return true;
        }
        return false;
    }

    private boolean deleteForward() {
        if (hasSelection()) {
            deleteSelection();
            return true;
        }
        if (caret < content.length()) {
            int end = content.offsetByCodePoints(caret, 1);
            content.delete(caret, end);
            anchor = caret;
            return true;
        }
        return false;
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

    /** Mirror content + caret + selection onto the retained node (call while holding the lock). */
    private void syncNode() {
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

    /** Nearest word boundary to the left of {@code from} (skip whitespace, then word chars). */
    private int prevWord(int from) {
        int i = from;
        while (i > 0 && Character.isWhitespace(content.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && !Character.isWhitespace(content.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /** Nearest word boundary to the right of {@code from} (skip word chars, then whitespace). */
    private int nextWord(int from) {
        int i = from;
        int n = content.length();
        while (i < n && !Character.isWhitespace(content.charAt(i))) {
            i++;
        }
        while (i < n && Character.isWhitespace(content.charAt(i))) {
            i++;
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
