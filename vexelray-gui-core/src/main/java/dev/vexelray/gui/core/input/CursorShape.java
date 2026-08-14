package dev.vexelray.gui.core.input;

/**
 * The pointer cursor shape the GUI requests from the host window (§8.3). The dispatcher derives it from the node
 * under the pointer — {@link #TEXT} (the I-beam) over selectable/editable text, {@link #DEFAULT} elsewhere — and
 * the application maps it onto its window's cursor API. Appearance only; never a geometry change.
 */
public enum CursorShape { DEFAULT, TEXT }
