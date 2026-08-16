package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.FocusEvent;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.text.Document;
import dev.vexelray.gui.core.text.Edit;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.core.text.TextEdit;
import dev.vexelray.gui.core.text.TextMetrics;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Committer;
import sibarum.atchung.State;
import sibarum.atchung.Subscription;
import sibarum.atchung.Versioned;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * An editable text field — single-line by default, {@link #multiline} on request.
 *
 * <p><b>The content is an Atchung {@code State<Document>}, not a private buffer.</b> Text, caret, selection anchor
 * and formatting spans are one immutable value carrying one version, changed by committing a relative
 * {@link Edit} that the reducer resolves against whatever the document is at commit time. Readers on any thread
 * get a coherent snapshot lock-free; a concurrent programmatic change costs a CAS retry rather than a lost edit;
 * and the three-property mirror that could previously be split by a frame boundary is now a single atomic value.
 *
 * <p><b>Edits are committed on the GUI thread's drain; notifications are delivered asynchronously.</b> Those are
 * two different requirements and the field now treats them as such. Applying a keystroke to a document depends on
 * arrival order and is bounded model work, so it runs as an ordered stage ({@link Gui#onCharUi},
 * {@link Gui#onKeyUi}, {@link Gui#onDragUi}, {@link Gui#claimUi}) where a total order already exists. Telling the
 * application what changed has no ordering requirement at all, so it goes to the handler executor carrying its
 * version — a late delivery is detectably stale rather than silently out of sequence. Handing each input event to
 * a worker pool separately, as this widget used to, orders the invocations but not their effects: a typed burst
 * could apply scrambled, and because every individual edit stayed coherent the result read as a dropped keystroke
 * (see {@code HandlerOrderingTest}).
 *
 * <p>Everything geometric — where a click lands, where the line above is, how far a page scrolls — remains a pure
 * lookup on this node's published layout read-model ({@code node.layout().text()}, docs/layout-read-model.md). The
 * widget never sees a measurer or a glyph atlas, which is what lets the identical code drive a field on screen,
 * headless in a test, or on a remote client with no fonts of its own.
 *
 * <p>Selection spans {@code [min(anchor,caret), max(anchor,caret))}; {@code anchor == caret} means no selection.
 * Clipboard cut/copy/paste go through {@link Gui#clipboard()}. Call {@link #close()} to release the field's
 * subscriptions, claims and blink registration when it is removed from the tree.
 */
public final class TextField implements AutoCloseable {

    private static final Color FIELD_BG = Color.rgb(0x0e1220);
    private static final Color FIELD_BORDER = Color.rgb(0x2b3346);
    private static final Color FIELD_BORDER_FOCUS = Color.rgb(0x3aa0ff);
    private static final Color INK = Color.rgb(0xeef2f8);

    /** Cap on retained undo history, so a long editing session can't grow the stacks without bound. */
    private static final int UNDO_LIMIT = 1000;

    /** Spaces inserted by Tab in a multiline field (soft tabs — the document holds no tab characters). */
    private static final int SOFT_TAB_WIDTH = 4;

    private final Gui gui;
    private final Node node;

    /** The document: one versioned value, read lock-free from any thread, written only by committing an Edit. */
    private final State<Document> document;
    private final Committer<Document, Edit> commit;

    private final Subscription focusSub;
    private final CaretBlink.Registration blink;

    /**
     * Undo/redo over the edit-diff (keyboard-focus-text.md §4.3). This is widget-local <em>history</em>, not the
     * document, and it is the only mutable state left needing a monitor — held across commit-and-record so the
     * recorded diff is the one that commit produced. The document itself needs no lock: it is a {@code State}.
     */
    private final Deque<TextEdit> undo = new ArrayDeque<>();
    private final Deque<TextEdit> redo = new ArrayDeque<>();
    private boolean coalesceBarrier;

    /**
     * Vertical navigation's sticky desired column (absolute px). NaN means "recompute from the caret": every
     * horizontal move invalidates it, and Up/Down deliberately does not, so a run of Up/Down through short lines
     * returns to the original column instead of walking left. Touched only on the ordered stage (GUI thread).
     */
    private float desiredX = Float.NaN;

    private volatile boolean multiline;
    private volatile boolean focused;
    private volatile Consumer<String> onChange = s -> { };
    private volatile Consumer<String> onSubmit = s -> { };

    /** Build an empty text field on {@code gui}. Size it via {@link #node()} (e.g. {@code .width(...)}). */
    public TextField(Gui gui) {
        this(gui, "");
    }

    /** Build a text field on {@code gui} pre-filled with {@code initial}; the caret starts at the end. */
    public TextField(Gui gui, String initial) {
        this.gui = gui;

        State.Builder<Document> builder = State.of(Document.of(initial));
        // One reducer for every change: the Edit is relative and Document.apply resolves it against the current
        // value, so a retry under contention re-resolves rather than re-imposing a stale absolute result.
        this.commit = builder.mutation("edit", Document::apply);
        this.document = builder.build();

        this.node = gui.text(text())
                .editable(true)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE)
                .textColor(INK)
                .height(Length.rem(2.5f))
                .background(FIELD_BG)
                .corner(Length.rem(0.5f))
                .border(Length.rem(0.1f), FIELD_BORDER)
                .caret(-1);

        // The edit path, all of it ordered: typed text, edit commands, and pointer caret placement are the three
        // ways this document changes from input, and all three must sequence against each other.
        gui.onCharUi(node, this::onCodePoint);
        gui.onKeyUi(node, this::onKey);
        gui.onDragUi(node, this::onPointer);

        this.focusSub = gui.bus().subscribe(gui.focusEvents(), this::onFocus);
        this.blink = CaretBlink.register(gui, node, () -> focused);
    }

    /** The node to place in a layout (style/size it further through this handle). */
    public Node node() {
        return node;
    }

    /**
     * The document as a versioned bus {@code State} — subscribe with {@code field.document().onCommit(...)} to
     * observe every change with the version and the diff that produced it, or read {@code .value()} for a
     * coherent snapshot from any thread without a lock.
     */
    public State<Document> document() {
        return document;
    }

    /** Current text content — a lock-free read of the latest committed document. */
    public String text() {
        return document.value().text();
    }

    /** Current caret offset. */
    public int caret() {
        return document.value().caret();
    }

    /** Replace the content programmatically; the caret moves to the end, selection clears, and history resets. */
    public TextField text(String s) {
        synchronized (this) {
            undo.clear();
            redo.clear();
            coalesceBarrier = true;
            document.commit(commit, new Edit.SetText(s));
        }
        published();
        return this;
    }

    /** React to content edits (typing, deletion, paste, programmatic set). Runs on the handler executor. */
    public TextField onChange(Consumer<String> handler) {
        this.onChange = handler == null ? s -> { } : handler;
        return this;
    }

    /** React to Enter being pressed in the field. Runs on the handler executor. Never fires on a multiline field. */
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
        // Tab indents inside a multiline editor, and traverses focus everywhere else. Expressed as a claim on the
        // chord while this field has focus, which outranks the framework's global Tab claim — rather than core
        // deciding on the field's behalf. Shift+Tab is deliberately left unclaimed, so it is still the way out.
        // Ordered, because indenting edits the same document typing does.
        Shortcut tab = Shortcut.of(Key.TAB);
        if (multiline) {
            gui.claimUi(node, tab, ClaimScope.FOCUSED, this::insertSoftTab);
        } else {
            gui.releaseClaim(node, tab);
        }
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

    /**
     * Show a gutter of hard-line numbers. Numbers count newlines, not wrapped rows, so a line that wraps onto
     * three rows is numbered once — and the gutter narrows the text area, so turning it on reflows the wrap.
     */
    public TextField lineNumbers(boolean show) {
        node.lineNumbers(show);
        return this;
    }

    /** Release this field's subscriptions, claims and blink registration. Call when removing it from the tree. */
    @Override
    public void close() {
        focusSub.close();
        blink.close();
        gui.releaseNode(node);
    }

    // --- formatting spans (§4.4) ---

    /** Replace the whole span set (bulk refresh — e.g. re-running a highlighter). Empty/null clears them. */
    public TextField setSpans(List<Span> newSpans) {
        document.commit(commit, new Edit.SetSpans(newSpans == null ? List.of() : List.copyOf(newSpans)));
        published();
        return this;
    }

    /** Add a single span, keeping the rest (§4.4 single-span update). */
    public TextField addSpan(Span span) {
        if (span != null) {
            List<Span> next = new ArrayList<>(document.value().spans());
            next.add(span);
            document.commit(commit, new Edit.SetSpans(next));
            published();
        }
        return this;
    }

    /** Remove all spans. */
    public TextField clearSpans() {
        return setSpans(List.of());
    }

    /** A snapshot of the current spans. */
    public List<Span> spans() {
        return document.value().spans();
    }

    // --- ordered stages: these run on the GUI thread during the input drain, in arrival order ---

    private void onCodePoint(int cp) {
        if (cp < 0x20 || cp == 0x7F) {
            return; // control characters ride the key channel, never the text channel
        }
        apply(new Edit.Insert(new String(Character.toChars(cp))), false);
    }

    private void onKey(KeyEvent e) {
        boolean ctrl = e.has(Modifier.CONTROL);
        boolean shift = e.has(Modifier.SHIFT);
        Key k = e.key();

        if (ctrl) {
            switch (k) {
                case A -> { apply(new Edit.SelectAll(), true); return; }
                case C -> { copy(); return; }
                case X -> { cut(); return; }
                case V -> { paste(); return; }
                case Z -> { if (shift) { redo(); } else { undo(); } return; } // Ctrl+Z undo, Ctrl+Shift+Z redo
                case Y -> { redo(); return; }                                  // Ctrl+Y redo
                default -> { /* Ctrl+other falls through to motion (word-jump) below */ }
            }
        }

        Document d = document.value();
        switch (k) {
            case LEFT -> moveCaret(ctrl ? d.previousWord(d.caret()) : d.stepLeft(d.caret()), shift);
            case RIGHT -> moveCaret(ctrl ? d.nextWord(d.caret()) : d.stepRight(d.caret()), shift);
            // Home/End are *visual* line ends on the read-model, so they stop at a wrap, not at a newline.
            // With Ctrl they address the whole document instead, as everywhere else.
            case HOME -> moveCaret(ctrl ? 0 : visualLineStart(d), shift);
            case END -> moveCaret(ctrl ? d.length() : visualLineEnd(d), shift);
            case UP -> moveByLines(-1, shift);
            case DOWN -> moveByLines(1, shift);
            case PAGE_UP -> moveByLines(-pageLines(), shift);
            case PAGE_DOWN -> moveByLines(pageLines(), shift);
            case BACKSPACE -> apply(new Edit.DeleteBack(ctrl), ctrl);
            case DELETE -> apply(new Edit.DeleteForward(ctrl), ctrl);
            case ENTER -> {
                if (multiline) {
                    // A newline is an undo boundary on both sides: without the barriers it is just another
                    // one-character insert continuing the typing run, and a whole multi-line burst collapses
                    // into a single Ctrl+Z. Undo steps read "cd", then the newline, then "ab".
                    apply(new Edit.Insert("\n"), true);
                    barrier();
                } else {
                    String current = d.text();
                    gui.handlers().execute(() -> onSubmit.accept(current));
                }
            }
            default -> { /* not an edit command; typed text arrives via onCodePoint */ }
        }
    }

    /** Map a pointer press/drag to a caret offset via this node's published text metrics, then place/extend. */
    private void onPointer(DragEvent e) {
        TextMetrics m = node.layout().text();
        if (m == null) {
            return; // not laid out yet, or no glyph metrics available
        }
        int offset = m.offsetAt(e.x(), e.y());
        switch (e.phase()) {
            case START -> moveCaret(offset, false);  // press positions the caret, collapsing any selection
            case MOVE -> moveCaret(offset, true);    // drag extends the selection to the pointer
            case END -> { }
        }
    }

    /** Soft tabs: {@value #SOFT_TAB_WIDTH} spaces, so the document contains no tab characters to disagree over. */
    private void insertSoftTab() {
        apply(new Edit.Insert(" ".repeat(SOFT_TAB_WIDTH)), true);
        barrier();
    }

    // --- vertical navigation, as pure widget code over the layout read-model (docs/layout-read-model.md §11.5) ---

    /** Move the caret {@code lines} visual lines (negative = up), keeping the sticky desired column. */
    private void moveByLines(int lines, boolean extend) {
        TextMetrics m = node.layout().text();
        if (m == null || lines == 0) {
            return;
        }
        Document d = document.value();
        if (Float.isNaN(desiredX)) {
            desiredX = m.caretX(d.caret());
        }
        float column = desiredX;   // moveCaret clears it; vertical motion must keep it
        int target = d.caret();
        for (int i = 0; i < Math.abs(lines); i++) {
            int next = lines < 0 ? m.offsetAbove(target, column) : m.offsetBelow(target, column);
            if (next == target) {
                break;   // already at the first/last visual line
            }
            target = next;
        }
        moveCaret(target, extend);
        desiredX = column;   // re-assert after moveCaret cleared it: this move was vertical
    }

    /** How many visual lines fit in the field, for PageUp/PageDown. At least one, so a tiny field still moves. */
    private int pageLines() {
        TextMetrics m = node.layout().text();
        if (m == null) {
            return 1;
        }
        float lineH = m.caretHeight(document.value().caret());
        // viewH is already the text area — inset by the padding, less whatever the scrollbars reserved.
        float viewH = node.layout().viewH();
        return lineH > 0f ? Math.max(1, (int) Math.floor(viewH / lineH)) : 1;
    }

    /** Start of the caret's visual line, falling back to the string start when metrics aren't published yet. */
    private int visualLineStart(Document d) {
        TextMetrics m = node.layout().text();
        return m == null ? 0 : m.lineStart(d.caret());
    }

    /** End of the caret's visual line, falling back to the string end when metrics aren't published yet. */
    private int visualLineEnd(Document d) {
        TextMetrics m = node.layout().text();
        return m == null ? d.length() : m.lineEnd(d.caret());
    }

    // --- clipboard (I/O kept off the commit) ---

    private void copy() {
        String sel = document.value().selectedText();
        if (!sel.isEmpty()) {
            gui.clipboard().set(sel);
        }
    }

    private void cut() {
        String sel = document.value().selectedText();
        if (sel.isEmpty()) {
            return;
        }
        gui.clipboard().set(sel);
        apply(new Edit.Insert(""), true);   // Insert("") over a selection deletes it
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
        apply(new Edit.Insert(insert), true);   // a paste is its own undo entry, never merged with typing
    }

    // --- commit + history ---

    /**
     * Commit {@code edit} and record it for undo. The monitor spans commit-and-record so the diff pushed onto the
     * history is the one this commit produced — it guards the <em>history</em>, not the document, which readers
     * reach lock-free through the {@code State}.
     *
     * @param barrierBefore start a fresh undo entry rather than merging into the run in progress
     */
    private void apply(Edit edit, boolean barrierBefore) {
        synchronized (this) {
            if (barrierBefore) {
                coalesceBarrier = true;
            }
            Document before = document.value();
            document.commit(commit, edit);
            Document after = document.value();
            if (after == before) {
                return;   // a no-op edit burns no version and no history entry
            }
            TextEdit diff = after.lastEdit();
            if (diff != null) {
                record(diff);
            } else {
                coalesceBarrier = true;   // a caret/selection move ends the current typing run
            }
        }
        published();
    }

    /** Move the caret (or extend the selection), which also ends the current undo run. */
    private void moveCaret(int to, boolean extend) {
        desiredX = Float.NaN;
        apply(new Edit.Caret(to, extend), false);
    }

    /** Force the next edit to start its own undo entry. */
    private synchronized void barrier() {
        coalesceBarrier = true;
    }

    /** Push {@code e} onto the undo stack, merging into the previous entry when it continues a run (§4.3). */
    private void record(TextEdit e) {
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
        synchronized (this) {
            TextEdit e = undo.poll();
            if (e == null) {
                return;
            }
            // Replay the inverse in absolute coordinates: an undo restores a specific prior state, so unlike an
            // input edit it must not be re-resolved against the caret.
            document.commit(commit, new Edit.Replace(e.at(), e.inserted().length(), e.removed()));
            redo.push(e);
            coalesceBarrier = true;
        }
        published();
    }

    private void redo() {
        synchronized (this) {
            TextEdit e = redo.poll();
            if (e == null) {
                return;
            }
            document.commit(commit, new Edit.Replace(e.at(), e.removed().length(), e.inserted()));
            undo.push(e);
            coalesceBarrier = true;
        }
        published();
    }

    // --- publication: mirror to the retained node, then notify the application ---

    /**
     * Mirror the document onto the retained node and notify the application.
     *
     * <p>The mirror is one atomic value going onto three node props — the node model still carries them
     * separately, but they are written from a single immutable snapshot, so they can no longer describe different
     * versions of the document. The notification goes to the handler executor: it has no ordering requirement, and
     * putting it there is what keeps a slow application callback from running on the frame loop.
     */
    private void published() {
        Versioned<Document> v = document.current();
        Document d = v.value();
        node.text(d.text());
        node.caret(focused ? d.caret() : -1);
        node.selection(d.anchor(), d.caret());
        node.spans(d.spans());
        blink.wake();
        Consumer<String> handler = onChange;
        gui.handlers().execute(() -> handler.accept(d.text()));
    }

    private void onFocus(FocusEvent e) {
        if (e.nodeId() != node.id()) {
            return;
        }
        focused = e.gained();
        if (focused) {
            node.caret(document.value().caret());
            node.caretOn(true);
            node.border(Length.rem(0.1f), FIELD_BORDER_FOCUS);
        } else {
            node.caret(-1);
            node.caretOn(false);
            node.border(Length.rem(0.1f), FIELD_BORDER);
        }
    }
}
