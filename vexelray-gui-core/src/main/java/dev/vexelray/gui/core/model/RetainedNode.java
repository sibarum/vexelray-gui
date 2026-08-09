package dev.vexelray.gui.core.model;

import dev.vexelray.canvas.Color;
import dev.vexelray.text.TextLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A node in the retained UI tree — the model the GUI thread owns and the {@code TreeRenderer} walks. In this
 * first milestone (architecture.md §12 step 4) a tree is hard-coded with absolute pixel {@code bounds} and plain
 * visual props; the mutation queue, reconciler, and flex layout that will <em>own</em> and <em>compute</em> these
 * arrive in the next step. Colours are VexelRay's native {@link Color}; alignment is VexelRay's {@link TextLayout}
 * enums — the GUI defines no drawing types of its own.
 */
public final class RetainedNode {

    private static final AtomicLong IDS = new AtomicLong(1);

    /** Client-stable identity (auto-assigned for now; client-assigned once the mutation channel lands). */
    public final long id;

    // Layout rect in absolute screen pixels (top-left origin, Y-down). Hard-coded for now; computed by layout later.
    public float x;
    public float y;
    public float w;
    public float h;

    // Visual props.
    public Color background;               // null = no fill
    public float cornerRadius;             // px
    public float borderWidth;              // px, 0 = no border
    public Color borderColor;              // used when borderWidth > 0

    // Text (single run for now; RichText model arrives later).
    public String text;                    // null/empty = no text
    public float textSize = 16f;           // px per em
    public Color textColor = Color.WHITE;
    public TextLayout.HAlign hAlign = TextLayout.HAlign.LEFT;
    public TextLayout.VAlign vAlign = TextLayout.VAlign.MIDDLE;

    public final List<RetainedNode> children = new ArrayList<>();

    public RetainedNode() {
        this.id = IDS.getAndIncrement();
    }

    /** A fresh node with an auto-assigned id. */
    public static RetainedNode node() {
        return new RetainedNode();
    }

    // --- fluent builders (ergonomic hard-coding of a tree; superseded by the mutation API later) ---

    public RetainedNode bounds(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        return this;
    }

    public RetainedNode background(Color c) {
        this.background = c;
        return this;
    }

    public RetainedNode corner(float radius) {
        this.cornerRadius = radius;
        return this;
    }

    public RetainedNode border(float width, Color color) {
        this.borderWidth = width;
        this.borderColor = color;
        return this;
    }

    public RetainedNode text(String s, float size, Color color) {
        this.text = s;
        this.textSize = size;
        this.textColor = color;
        return this;
    }

    public RetainedNode align(TextLayout.HAlign h, TextLayout.VAlign v) {
        this.hAlign = h;
        this.vAlign = v;
        return this;
    }

    public RetainedNode add(RetainedNode... kids) {
        for (RetainedNode k : kids) {
            children.add(k);
        }
        return this;
    }
}
