package dev.vexelray.gui.core.layout;

/**
 * The small enums + context the flex layout reads. Kept in one file since they are trivial and always used
 * together.
 */
public final class LayoutEnums {
    private LayoutEnums() {
    }

    /** A physical axis. A box's main axis is {@link Direction#ROW}=HORIZONTAL, {@link Direction#COLUMN}=VERTICAL. */
    public enum Axis { HORIZONTAL, VERTICAL }

    /** Main-axis direction of a box's children. */
    public enum Direction { ROW, COLUMN }

    /** Main-axis distribution of children within the content box. */
    public enum Justify { START, CENTER, END, SPACE_BETWEEN }

    /** Cross-axis placement of each child (STRETCH fills the cross extent). */
    public enum AlignItems { START, CENTER, END, STRETCH }
}
