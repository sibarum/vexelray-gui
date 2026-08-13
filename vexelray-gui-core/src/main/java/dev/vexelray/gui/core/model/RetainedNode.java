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

    /** Default text size when none is set: one root em (≈16px at the default context). */
    private static final Length DEFAULT_TEXT_SIZE = Length.rem(1f);

    public final long id;
    public final NodeKind kind;
    private final Map<PropKey, Object> props = new EnumMap<>(PropKey.class);
    public final List<RetainedNode> children = new ArrayList<>();
    public RetainedNode parent;

    // Layout-computed border-box rect, absolute screen px (Y-down). w/h include border + padding (border-box).
    public float x;
    public float y;
    public float w;
    public float h;

    // Layout-computed, resolved-to-px render inputs (filled each layout pass so the renderer needs no units/ctx).
    public float borderPx;
    public float cornerPx;
    public float textSizePx = 16f;

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

    public Length corner() {
        return len(PropKey.CORNER, Length.ZERO);
    }

    public Length borderWidth() {
        return len(PropKey.BORDER_WIDTH, Length.ZERO);
    }

    public Color borderColor() {
        return (Color) props.get(PropKey.BORDER_COLOR);
    }

    public String textString() {
        return (String) props.get(PropKey.TEXT);
    }

    public Length textSize() {
        return len(PropKey.TEXT_SIZE, DEFAULT_TEXT_SIZE);
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
        return len(PropKey.WIDTH, Length.AUTO);
    }

    public Length height() {
        return len(PropKey.HEIGHT, Length.AUTO);
    }

    public Length padding() {
        return len(PropKey.PADDING, Length.ZERO);
    }

    public Length margin() {
        return len(PropKey.MARGIN, Length.ZERO);
    }

    public Length gap() {
        return len(PropKey.GAP, Length.ZERO);
    }

    private Length len(PropKey key, Length dflt) {
        Object v = props.get(key);
        return v instanceof Length l ? l : dflt;
    }
}
