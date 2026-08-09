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

    public Node corner(float radiusPx) {
        return prop(PropKey.CORNER, radiusPx);
    }

    public Node border(float widthPx, Color color) {
        prop(PropKey.BORDER_WIDTH, widthPx);
        return prop(PropKey.BORDER_COLOR, color);
    }

    // --- text ---

    public Node text(String s) {
        sink.post(new Mutation.SetText(id, s));
        return this;
    }

    public Node textSize(float px) {
        return prop(PropKey.TEXT_SIZE, px);
    }

    public Node textColor(Color c) {
        return prop(PropKey.TEXT_COLOR, c);
    }

    public Node align(TextLayout.HAlign h, TextLayout.VAlign v) {
        prop(PropKey.H_ALIGN, h);
        return prop(PropKey.V_ALIGN, v);
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

    public Node padding(float px) {
        return prop(PropKey.PADDING, px);
    }

    public Node gap(float px) {
        return prop(PropKey.GAP, px);
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
