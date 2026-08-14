package dev.vexelray.gui.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.model.Mutation;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.text.TextLayout;

/**
 * A write-only, identity-stable handle to a node — safe to hold and call from any thread. Every setter enqueues a
 * mutation on the shared {@link MutationSink}; the GUI thread applies them to the retained model. Editing a node
 * never mints a new identity, so state stays attached (architecture.md §3). Fluent: setters return {@code this}.
 */
public final class Node {

    private final long id;
    private final MutationSink sink;

    Node(long id, MutationSink sink) {
        this.id = id;
        this.sink = sink;
    }

    public long id() {
        return id;
    }

    // --- visual ---

    public Node background(Color c) {
        return prop(PropKey.BACKGROUND, c);
    }

    public Node corner(Length radius) {
        return prop(PropKey.CORNER, radius);
    }

    public Node border(Length width, Color color) {
        prop(PropKey.BORDER_WIDTH, width);
        return prop(PropKey.BORDER_COLOR, color);
    }

    // --- text ---

    public Node text(String s) {
        sink.post(new Mutation.SetText(id, s));
        return this;
    }

    public Node textSize(Length size) {
        return prop(PropKey.TEXT_SIZE, size);
    }

    public Node textColor(Color c) {
        return prop(PropKey.TEXT_COLOR, c);
    }

    public Node align(TextLayout.HAlign h, TextLayout.VAlign v) {
        prop(PropKey.H_ALIGN, h);
        return prop(PropKey.V_ALIGN, v);
    }

    /** Mark this text node editable (a text field). Editing state (caret) is carried by {@link #caret}. */
    public Node editable(boolean editable) {
        return prop(PropKey.EDITABLE, editable);
    }

    /** Set the caret offset into the text (character index), or {@code -1} to hide the caret. */
    public Node caret(int offset) {
        return prop(PropKey.CARET, offset);
    }

    /** Set the caret blink phase — {@code true} shows the caret this instant, {@code false} hides it. */
    public Node caretOn(boolean on) {
        return prop(PropKey.CARET_ON, on);
    }

    /** Set the selection range (character offsets); {@code start == end} clears the selection. */
    public Node selection(int start, int end) {
        prop(PropKey.SELECT_START, start);
        return prop(PropKey.SELECT_END, end);
    }

    // --- layout ---

    public Node direction(Direction d) {
        return prop(PropKey.DIRECTION, d);
    }

    public Node justify(Justify j) {
        return prop(PropKey.JUSTIFY, j);
    }

    public Node alignItems(AlignItems a) {
        return prop(PropKey.ALIGN_ITEMS, a);
    }

    public Node width(Length w) {
        return prop(PropKey.WIDTH, w);
    }

    public Node height(Length h) {
        return prop(PropKey.HEIGHT, h);
    }

    public Node size(Length w, Length h) {
        width(w);
        return height(h);
    }

    public Node padding(Length p) {
        return prop(PropKey.PADDING, p);
    }

    /** CSS-style shorthand: {@code vertical} padding top+bottom, {@code horizontal} padding left+right. */
    public Node padding(Length vertical, Length horizontal) {
        prop(PropKey.PADDING_Y, vertical);
        return prop(PropKey.PADDING_X, horizontal);
    }

    public Node margin(Length m) {
        return prop(PropKey.MARGIN, m);
    }

    public Node gap(Length g) {
        return prop(PropKey.GAP, g);
    }

    /** Enable/disable overflow scrolling per axis (both default on — auto scrollbars appear on overflow). */
    public Node scroll(boolean allowX, boolean allowY) {
        prop(PropKey.SCROLL_X, allowX);
        return prop(PropKey.SCROLL_Y, allowY);
    }

    // --- structure ---

    public Node append(Node child) {
        sink.post(new Mutation.Insert(id, child.id, Mutation.Insert.END));
        return this;
    }

    public Node children(Node... kids) {
        for (Node k : kids) {
            append(k);
        }
        return this;
    }

    public void remove() {
        sink.post(new Mutation.Remove(id));
    }

    private Node prop(PropKey key, Object value) {
        sink.post(new Mutation.SetProp(id, key, value));
        return this;
    }
}
