package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A tabbed panel: a row of headers over a stack of pages, one shown at a time.
 *
 * <p><b>Pages are hidden, never removed.</b> That is the load-bearing choice. Rebuilding the content by removing
 * and inserting children would look equivalent and is not: registrations are keyed by node id and released when a
 * node leaves the tree, so a {@link TextField} on a page would come back inert — still drawn, no longer able to
 * receive a keystroke. Hiding keeps identity, and with it the handlers, the focusability and the widget state, so
 * switching away and back returns a page exactly as it was, caret and all.
 *
 * <p>Selection is ordinary framework machinery rather than anything new: headers are clickable, so they take the
 * pointer cursor by inference (§8.3); they are focusable, so Tab reaches them; and each claims Left/Right at
 * {@link ClaimScope#FOCUSED} while it holds focus, so the arrow keys walk the bar without core knowing what a tab
 * is. Nothing here reaches past a {@link Node} handle.
 */
public final class Tabs {

    private static final Color BAR = Color.rgb(0x161b28);
    private static final Color TAB_IDLE = Color.rgb(0x1b2130);
    private static final Color TAB_HOVER = Color.rgb(0x232a3d);
    private static final Color TAB_ACTIVE = Color.rgb(0x2b3346);
    private static final Color INK = Color.rgb(0xeef2f8);
    private static final Color DIM = Color.rgb(0x93a0b4);
    private static final Color ACCENT = Color.rgb(0x3aa0ff);

    private final Gui gui;
    private final Node root;
    private final Node bar;
    private final Node pages;
    private final List<Node> headers = new ArrayList<>();
    private final List<Node> bodies = new ArrayList<>();

    private volatile int selected = -1;
    private volatile IntConsumer onSelect = i -> { };

    /** Build an empty tab panel; add pages with {@link #add}. */
    public Tabs(Gui gui) {
        this.gui = gui;
        this.bar = gui.row().width(Length.FILL).height(Length.rem(2.25f)).background(BAR)
                .gap(Length.dp(2)).alignItems(AlignItems.STRETCH).scroll(false, false);
        this.pages = gui.column().width(Length.FILL).height(Length.FILL);
        this.root = gui.column().width(Length.FILL).height(Length.FILL).children(bar, pages);
    }

    /** The node to place in a layout. */
    public Node node() {
        return root;
    }

    /** The index of the selected tab, or -1 when there are none. */
    public int selected() {
        return selected;
    }

    /** How many tabs there are. */
    public int count() {
        return headers.size();
    }

    /** React to selection changes (including the first one). Runs on the handler executor. */
    public Tabs onSelect(IntConsumer handler) {
        this.onSelect = handler == null ? i -> { } : handler;
        return this;
    }

    /**
     * Add a page under {@code title}. {@code body} is placed in the content area and hidden until selected; the
     * first page added is selected automatically, so a freshly built panel is never blank.
     */
    public Tabs add(String title, Node body) {
        int index = headers.size();
        Node header = gui.text(title)
                .height(Length.FILL)
                .padding(Length.dp(6), Length.dp(14))
                .textSize(Length.rem(1))
                .textColor(DIM)
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .corner(Length.rem(0.5f), Length.ZERO)   // tab silhouette: rounded shoulders, flat seat
                .background(TAB_IDLE);

        // Handlers resolve the header to its index at event time, not add time: tabs can be removed, so a
        // baked-in index would aim every surviving closure one tab off. The node's identity is the stable key.
        gui.onClick(header, () -> select(headers.indexOf(header)));
        gui.focusable(header, true);
        // Arrow keys walk the bar while a header holds focus. A claim rather than a key handler, so it preempts
        // anything else bound to those chords for exactly as long as this header is focused.
        gui.claim(header, Shortcut.of(Key.LEFT), ClaimScope.FOCUSED, () -> select(headers.indexOf(header) - 1));
        gui.claim(header, Shortcut.of(Key.RIGHT), ClaimScope.FOCUSED, () -> select(headers.indexOf(header) + 1));
        // Hover shading, except on the selected tab, which keeps its active colour.
        gui.onState(header, state -> header.background(background(headers.indexOf(header), state)));

        bar.append(header);
        pages.append(body.width(Length.FILL).height(Length.FILL).visible(false));
        headers.add(header);
        bodies.add(body);

        if (selected < 0) {
            select(0);
        }
        return this;
    }

    /**
     * Show tab {@code index}, clamped into range so an arrow key at either end simply stays put. Hiding the old
     * page and showing the new one is two prop changes: no node is created, destroyed, or re-registered.
     */
    public Tabs select(int index) {
        int next = Math.max(0, Math.min(headers.size() - 1, index));
        if (headers.isEmpty() || next == selected) {
            return this;
        }
        int previous = selected;
        selected = next;
        if (previous >= 0) {
            bodies.get(previous).visible(false);
            headers.get(previous).background(TAB_IDLE).textColor(DIM).lit(false).elevation(Length.ZERO);
        }
        bodies.get(next).visible(true);
        // The active tab is physically forward: lit and floating a little above the bar the idle tabs sit flush in.
        headers.get(next).background(TAB_ACTIVE).textColor(ACCENT).lit(true).elevation(Length.rem(0.25f));
        int delivered = next;
        gui.handlers().execute(() -> onSelect.accept(delivered));
        return this;
    }

    /** Rename tab {@code index}'s header (e.g. an editor tab following a save-as). */
    public Tabs title(int index, String title) {
        if (index >= 0 && index < headers.size()) {
            headers.get(index).text(title);
        }
        return this;
    }

    /**
     * Remove tab {@code index}: header and body leave the tree via {@link Node#remove()} — the one real removal
     * primitive. (Re-setting the parents' child lists with {@code children(...)} would be wrong twice over: that
     * method only <em>appends</em>, and an insert of an already-parented node leaves it in both parents, so the
     * survivors would render duplicated.) Removal releases every input registration in both subtrees, so the
     * page's widgets come back dead, not dormant — the caller closes them, it cannot re-home them. Selection
     * moves to the nearest surviving tab.
     */
    public Tabs remove(int index) {
        if (index < 0 || index >= headers.size()) {
            return this;
        }
        headers.remove(index).remove();
        bodies.remove(index).remove();

        int previous = selected;
        selected = -1;   // force select() to restyle: surviving indices shifted under the old value
        if (!headers.isEmpty()) {
            select(Math.min(previous > index ? previous - 1 : previous, headers.size() - 1));
        }
        return this;
    }

    /** The header node for tab {@code index} -- package-private, so a test can aim at it rather than guess. */
    Node header(int index) {
        return headers.get(index);
    }

    /** Move focus to the selected header — the entry point for driving the bar from the keyboard. */
    public Tabs focus() {
        if (selected >= 0) {
            gui.focus(headers.get(selected));
        }
        return this;
    }

    private Color background(int index, InteractionState state) {
        if (index == selected) {
            return TAB_ACTIVE;
        }
        return state == InteractionState.NORMAL ? TAB_IDLE : TAB_HOVER;
    }
}
