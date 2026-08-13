/**
 * vexelray-gui framework core: the retained {@code Node} tree with stable identity, the Atchung-backed
 * mutation channel (single-writer reconciler, pumped once per frame on the GUI thread) plus event/state
 * publishing, {@code Length} + flex layout, the lifecycle FSM + animation transform layer,
 * framework-owned input dispatch, the {@code RichText} model, and the {@code GuiApp} frame loop.
 *
 * <p>Renders exclusively through VexelRay's native {@code Canvas} and passes messages exclusively through
 * Atchung; owns no rendering, windowing, input, or messaging machinery. See {@code docs/architecture.md}.
 */
package dev.vexelray.gui.core;
