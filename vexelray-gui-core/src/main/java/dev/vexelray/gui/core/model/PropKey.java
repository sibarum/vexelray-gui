package dev.vexelray.gui.core.model;

/**
 * The typed-by-convention property keys a node carries. Values are stored untyped in a {@code RetainedNode}'s
 * prop map and read back through {@link RetainedNode}'s typed accessors. {@code layoutAffecting} marks the props
 * whose change must retrigger layout (as opposed to purely visual props like colour).
 */
public enum PropKey {
    // Visual
    BACKGROUND(false),
    CORNER(false),
    BORDER_COLOR(false),
    TEXT_COLOR(false),
    // Text (size affects layout via intrinsic measure)
    TEXT(true),
    TEXT_SIZE(true),
    H_ALIGN(false),
    V_ALIGN(false),
    // Text editing (visual/state only — caret position and blink don't reflow the line):
    EDITABLE(false),
    CARET(false),    // caret offset into the text (Integer), or absent/-1 for no caret
    CARET_ON(false), // caret blink phase: true = currently shown
    // Layout (border-box: border + padding inset the content, so border width is layout-affecting)
    DIRECTION(true),
    JUSTIFY(true),
    ALIGN_ITEMS(true),
    WIDTH(true),
    HEIGHT(true),
    PADDING(true),
    PADDING_X(true),
    PADDING_Y(true),
    MARGIN(true),
    BORDER_WIDTH(true),
    GAP(true),
    // Per-axis overflow scrolling: enabled by default (auto scrollbars on overflow); set false to disable an axis.
    SCROLL_X(true),
    SCROLL_Y(true);

    private final boolean layoutAffecting;

    PropKey(boolean layoutAffecting) {
        this.layoutAffecting = layoutAffecting;
    }

    public boolean layoutAffecting() {
        return layoutAffecting;
    }
}
