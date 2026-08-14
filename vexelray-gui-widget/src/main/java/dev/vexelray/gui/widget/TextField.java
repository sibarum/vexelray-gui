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
 * A single-line editable text field built entirely on the framework's input seams: typed text arrives through
 * {@link Gui#onChar} (the layout-resolved {@code CharTyped} channel), edit commands (caret motion, backspace,
 * delete, Home/End, submit) through {@link Gui#onKey}, and click-to-position through {@link Gui#onCaretHit}. The
 * field owns the authoritative content + caret; it mirrors them onto the retained node ({@link Node#text},
 * {@link Node#caret}) so the renderer draws the text and the caret bar, and it blinks the caret while focused.
 *
 * <p>All input handlers run on worker threads, so every edit is serialised on this instance's monitor and every
 * tree change goes through the thread-safe {@link Node} handle. Reads flow out through {@link #onChange} (content
 * edits) and {@link #onSubmit} (Enter); both run on a worker thread.
 */
public final class TextField {

    private static final Color FIELD_BG = Color.rgb(0x0e1220);
    private static final Color FIELD_BORDER = Color.rgb(0x2b3346);
    private static final Color FIELD_BORDER_FOCUS = Color.rgb(0x3aa0ff);
    private static final Color INK = Color.rgb(0xeef2f8);
    private static final long BLINK_MILLIS = 530L;

    private final Gui gui;
    private final Node node;

    // Authoritative edit state, guarded by `this`.
    private final StringBuilder content = new StringBuilder();
    private int caret;

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
        gui.onCaretHit(node, this::setCaret);
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

    /** Replace the content programmatically; the caret moves to the end. Notifies {@link #onChange}. */
    public synchronized TextField text(String s) {
        content.setLength(0);
        content.append(s == null ? "" : s);
        caret = content.length();
        syncNode();
        onChange.accept(content.toString());
        return this;
    }

    /** React to content edits (typing, deletion, programmatic set). Runs on a worker thread. */
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
        // Defensive: control characters ride the key channel, never the text channel.
        if (cp < 0x20 || cp == 0x7F) {
            return;
        }
        String changed;
        synchronized (this) {
            content.insert(caret, new String(Character.toChars(cp)));
            caret += Character.charCount(cp);
            syncNode();
            changed = content.toString();
        }
        wake();
        onChange.accept(changed);
    }

    private void onKey(KeyEvent e) {
        boolean ctrl = e.has(Modifier.CONTROL);
        boolean edited = false;
        String changed = null;
        synchronized (this) {
            switch (e.key()) {
                case LEFT -> caret = ctrl ? prevWord(caret) : (caret > 0 ? content.offsetByCodePoints(caret, -1) : 0);
                case RIGHT -> caret = ctrl ? nextWord(caret)
                        : (caret < content.length() ? content.offsetByCodePoints(caret, 1) : caret);
                case HOME -> caret = 0;
                case END -> caret = content.length();
                case BACKSPACE -> {
                    if (caret > 0) {
                        int start = content.offsetByCodePoints(caret, -1);
                        content.delete(start, caret);
                        caret = start;
                        edited = true;
                    }
                }
                case DELETE -> {
                    if (caret < content.length()) {
                        int end = content.offsetByCodePoints(caret, 1);
                        content.delete(caret, end);
                        edited = true;
                    }
                }
                case ENTER -> { /* handled below, outside the lock */ }
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
        if (e.key() == Key.ENTER) {
            onSubmit.accept(text());
        } else if (changed != null) {
            onChange.accept(changed);
        }
    }

    private void setCaret(int offset) {
        synchronized (this) {
            caret = Math.max(0, Math.min(offset, content.length()));
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

    // --- helpers ---

    private synchronized int caretSnapshot() {
        return caret;
    }

    /** Mirror content + caret onto the retained node (call while holding the lock). */
    private void syncNode() {
        node.text(content.toString());
        node.caret(caret);
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
