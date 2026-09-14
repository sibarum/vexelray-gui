/**
 * Widgets built on {@code vexelray-gui-core}: the <em>interaction protocols</em> an application composes a UI
 * from — tabs, a rail, a tree, virtualised lists and tables, a text field with a find bar, sliders, switches,
 * number fields, segments, a colour picker, an inspector, context menus, modals, tooltips, cues, reordering and
 * popped-out panels, plus the window chrome a client-decorated window draws for itself.
 *
 * <p>Painted controls are deliberately <em>not</em> here. A button, a card, a heading — those are application
 * code, and they work as application code because a {@link dev.vexelray.gui.core.style.Role} already knows its
 * own hover and pressed shades, so nothing that draws one has to write a colour down. A component earns a place
 * in this package only when it carries an invariant an application cannot be trusted to re-derive: a selection
 * anchor, a commit-or-revert, a virtualised row window, a claim on a chord, a coordinate that a displayed value
 * cannot recover.
 *
 * <p>That last one is why {@link dev.vexelray.gui.widget.ColorPicker} and
 * {@link dev.vexelray.gui.widget.Toggle} are here after {@code docs/todo.md} predicted they would not be: a
 * picker holding {@code Color} instead of {@code Hsv} loses the user's place at every grey, and a knob placed by
 * a hand-computed inset is correct at one rem size. Both look like painted controls and neither is one.
 *
 * <p><b>A control names its setters after who moved it.</b> {@code show(x)} displays a value and tells nobody;
 * the setter named for what the control <em>is</em> — {@link dev.vexelray.gui.widget.Toggle#on},
 * {@link dev.vexelray.gui.widget.Slider#value}, {@link dev.vexelray.gui.widget.Segment#select} — acts as the
 * user would and fires the callback. A panel re-reading its model calls {@code show}; only something genuinely
 * standing in for the user calls the other. This is not a stylistic preference: reporting a sync as an edit is
 * a loop, because the application writes to the model, the model republishes the panel, and the panel syncs
 * again — without bound, and whatever the value is, since the notification never depended on anything moving.
 *
 * <p>{@code show} is about a <em>value</em>, never about visibility. Whether a transient overlay is up is
 * {@code isOpen()} — the one name {@link dev.vexelray.gui.widget.Select},
 * {@link dev.vexelray.gui.widget.ContextMenu}, {@code FindBar} and {@link dev.vexelray.gui.widget.Tooltip} all
 * answer to; whether a node is in the tree's layout is {@code Node.showing}.
 *
 * <p>What is still missing, and in what order, is {@code docs/todo.md} §4.
 */
package dev.vexelray.gui.widget;
