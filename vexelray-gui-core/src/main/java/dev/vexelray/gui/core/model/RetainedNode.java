package dev.vexelray.gui.core.model;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.text.TextLayout;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The live model node — GUI-thread-only, mutated exclusively by the {@link Reconciler}. Identity is the stable
 * {@code id} (the {@code Node} handle shares it). Props live in an untyped map keyed by {@link PropKey} and are
 * read through the typed accessors below; {@link #x}/{@link #y}/{@link #w}/{@link #h} are the layout-computed
 * rect (screen px), filled by the flex layout each dirty frame.
 */
public final class RetainedNode {

    public final long id;
    public final NodeKind kind;
    private final Map<PropKey, Object> props = new EnumMap<>(PropKey.class);
    public final List<RetainedNode> children = new ArrayList<>();
    public RetainedNode parent;

    // Layout-computed rect, absolute screen px (Y-down).
    public float x;
    public float y;
    public float w;
    public float h;

    public RetainedNode(long id, NodeKind kind) {
        this.id = id;
        this.kind = kind;
    }

    public void set(PropKey key, Object value) {
        if (value == null) {
            props.remove(key);
        } else {
            props.put(key, value);
        }
    }

    public Object raw(PropKey key) {
        return props.get(key);
    }

    // --- typed accessors with defaults ---

    public Color background() {
        return (Color) props.get(PropKey.BACKGROUND);
    }

    public float corner() {
        return f(PropKey.CORNER, 0f);
    }

    public float borderWidth() {
        return f(PropKey.BORDER_WIDTH, 0f);
    }

    public Color borderColor() {
        return (Color) props.get(PropKey.BORDER_COLOR);
    }

    public String textString() {
        return (String) props.get(PropKey.TEXT);
    }

    public float textSize() {
        return f(PropKey.TEXT_SIZE, 16f);
    }

    public Color textColor() {
        Object c = props.get(PropKey.TEXT_COLOR);
        return c != null ? (Color) c : Color.WHITE;
    }

    public TextLayout.HAlign hAlign() {
        Object a = props.get(PropKey.H_ALIGN);
        return a != null ? (TextLayout.HAlign) a : TextLayout.HAlign.LEFT;
    }

    public TextLayout.VAlign vAlign() {
        Object a = props.get(PropKey.V_ALIGN);
        return a != null ? (TextLayout.VAlign) a : TextLayout.VAlign.MIDDLE;
    }

    public Direction direction() {
        Object d = props.get(PropKey.DIRECTION);
        return d != null ? (Direction) d : Direction.ROW;
    }

    public Justify justify() {
        Object j = props.get(PropKey.JUSTIFY);
        return j != null ? (Justify) j : Justify.START;
    }

    public AlignItems alignItems() {
        Object a = props.get(PropKey.ALIGN_ITEMS);
        return a != null ? (AlignItems) a : AlignItems.STRETCH;
    }

    public Length width() {
        return len(PropKey.WIDTH);
    }

    public Length height() {
        return len(PropKey.HEIGHT);
    }

    public float padding() {
        return f(PropKey.PADDING, 0f);
    }

    public float gap() {
        return f(PropKey.GAP, 0f);
    }

    private float f(PropKey key, float dflt) {
        Object v = props.get(key);
        return v != null ? ((Number) v).floatValue() : dflt;
    }

    private Length len(PropKey key) {
        Object v = props.get(key);
        return v != null ? (Length) v : Length.AUTO;
    }
}
