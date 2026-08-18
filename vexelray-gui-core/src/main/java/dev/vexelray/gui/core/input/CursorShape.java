package dev.vexelray.gui.core.input;

/**
 * The pointer cursor shape the GUI requests from the host window (§8.3). The dispatcher derives it from what is
 * under the pointer and what the pointer is currently doing; the application maps it onto its window's cursor
 * API. Appearance only — a cursor change never moves or resizes anything, so it cannot violate the
 * pointer-target rule the way a hover-triggered scrollbar would.
 *
 * <p><b>Precedence, most specific first.</b> The order matters more than the shapes: an affordance the pointer is
 * <em>using</em> outranks one it is merely over, and an explicit declaration outranks an inferred one.
 *
 * <ol>
 *   <li>{@link #GRABBING} — a grab is in progress (scrollbar thumb, slider). Holds until release, wherever the
 *       pointer wanders, because the pointer is captured and still driving that control.</li>
 *   <li>{@link #GRAB} — over something draggable: a scrollbar, or a node that declared it.</li>
 *   <li>{@link #POINTER} — over something clickable, i.e. it or an ancestor has a click handler. Same
 *       ancestor-or-self rule clicks themselves bubble by, so a button's label is part of the button.</li>
 *   <li>{@link #TEXT} — over editable text. Deliberately the weakest of the affordances: an editable field
 *       inside a clickable row is a control first, and clicking it does something more than place a caret.</li>
 *   <li>{@link #DEFAULT} — everything else.</li>
 * </ol>
 */
public enum CursorShape {

    /** The ordinary arrow. */
    DEFAULT,

    /** The I-beam, over editable or selectable text. */
    TEXT,

    /** The hand, over anything clickable. */
    POINTER,

    /** The open hand, over something that can be grabbed and dragged. */
    GRAB,

    /** The closed hand, while a grab is actually in progress. */
    GRABBING
}
