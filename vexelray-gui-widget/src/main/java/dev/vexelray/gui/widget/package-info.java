/**
 * Widgets built on {@code vexelray-gui-core}: the <em>interaction protocols</em> an application composes a UI
 * from — tabs, a tree, a text field with a find bar, sliders, context menus, modals, tooltips, cues, reordering
 * and popped-out panels, plus the window chrome a client-decorated window draws for itself.
 *
 * <p>Painted controls are deliberately <em>not</em> here. A button, a toggle, a card, a heading — those are
 * application code, and they work as application code because a {@link dev.vexelray.gui.core.style.Role} already
 * knows its own hover and pressed shades, so nothing that draws one has to write a colour down. A component
 * earns a place in this package only when it carries an invariant an application cannot be trusted to
 * re-derive: a selection anchor, a commit-or-revert, a virtualised row window, a claim on a chord.
 *
 * <p>What is still missing, and in what order, is {@code docs/todo.md} §4.
 */
package dev.vexelray.gui.widget;
