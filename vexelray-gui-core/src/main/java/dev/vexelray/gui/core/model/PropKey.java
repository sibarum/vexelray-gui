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
    BORDER_WIDTH(false),
    BORDER_COLOR(false),
    TEXT_COLOR(false),
    // Text (size affects layout via intrinsic measure)
    TEXT(true),
    TEXT_SIZE(true),
    H_ALIGN(false),
    V_ALIGN(false),
    // Layout
    DIRECTION(true),
    JUSTIFY(true),
    ALIGN_ITEMS(true),
    WIDTH(true),
    HEIGHT(true),
    PADDING(true),
    GAP(true);

    private final boolean layoutAffecting;

    PropKey(boolean layoutAffecting) {
        this.layoutAffecting = layoutAffecting;
    }

    public boolean layoutAffecting() {
        return layoutAffecting;
    }
}
