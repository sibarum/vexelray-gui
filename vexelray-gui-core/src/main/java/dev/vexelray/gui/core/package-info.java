/**
 * vexelray-gui framework core: the retained {@code Node} tree with stable identity, the MPSC
 * mutation + event channels (single-writer, {@code PENDING_WAKE} wake CAS), {@code Length} + flex
 * layout, the lifecycle FSM + animation transform layer, framework-owned input dispatch, the
 * {@code RichText} model, and the {@code GuiApp} frame loop.
 *
 * <p>Renders exclusively through VexelRay's native {@code Canvas}; owns no rendering, windowing, or
 * native code. See {@code docs/architecture.md}.
 */
package dev.vexelray.gui.core;
