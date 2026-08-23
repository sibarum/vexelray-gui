package dev.vexelray.gui.core.style;

import dev.vexelray.canvas.Color;

/**
 * What a thing <b>is</b> in the interface, resolved against a {@link Palette}. Widgets name roles; only a palette
 * names colours.
 *
 * <p>A role is a function, not a key — which is what keeps this open. The constants below are the vocabulary the
 * framework's own widgets need, but an application that wants "the colour of an unsaved buffer's tab" writes
 * {@code Role tainted = p -> p.surface(3)} (or anything else derived from the same anchors) and it themes with
 * everything else, with nothing to register and no table to extend. It is also why resolution has no switch in it:
 * the role carries its own answer, so no one has to enumerate the cases.
 *
 * <p>Several roles resolve to the same value in {@link Palette#DARK} — a border, a slider groove and a selected
 * row are all level 4 there. They stay separate roles because they are separate decisions: a palette is free to
 * tint the selection with its accent without turning every border blue.
 */
@FunctionalInterface
public interface Role {

    /** Resolve this role against {@code palette}. */
    Color of(Palette palette);

    // ---------------------------------------------------------------- surfaces

    /** No paint at all — for a control that only shows a surface once the pointer is on it. */
    Role NONE = p -> Color.TRANSPARENT;

    /** A sunken input surface: a text field, a tree, anything the user types or picks into. */
    Role WELL = p -> p.surface(-1);

    /** The window background. */
    Role PAGE = p -> p.surface(0);

    /** The frame around the content: a title bar, a tab bar, a scrollbar trough. */
    Role CHROME = p -> p.surface(1);

    /** A block of content standing off the page: a card, a menu, a button, an idle tab. */
    Role PANEL = p -> p.surface(2);

    /** A surface floating above the panels: a tooltip bubble, a popover. */
    Role RAISED = p -> p.surface(3);

    /** Borders and separators. */
    Role LINE = p -> p.surface(4);

    /** The groove a control runs in: a slider track, a progress rail. */
    Role TRACK = p -> p.surface(4);

    /** The fill behind a selected row or the active tab. */
    Role SELECTION = p -> p.surface(4);

    /** A border that has to read against any surface, not just the page — a tooltip edge, a scrollbar track. */
    Role EDGE = p -> p.surface(6);

    /** Chrome that must be obvious rather than quiet: a scrollbar thumb. */
    Role GRIP = p -> p.surface(10);

    // -------------------------------------------------------------------- ink

    /** Primary text. */
    Role INK = p -> p.text(0);

    /** Secondary text: labels, an unselected tab, a caption. */
    Role DIM = p -> p.text(1);

    /** Text that is present but not to be read yet: a placeholder, a line-number gutter, a disabled control. */
    Role FAINT = p -> p.text(2);

    // ------------------------------------------------------------- chromatic

    /** Focus rings, links, a slider thumb, the label of the selected thing. */
    Role ACCENT = p -> p.accent().toColor();

    /** The fill of a filled control. */
    Role ACTION = p -> p.action().toColor();

    /** The label on a filled control — near-white in every palette, for the reason in {@link Palette#contrastTo}. */
    Role ON_ACTION = p -> p.contrastTo(p.action());

    /** Destructive. */
    Role DANGER = p -> p.danger().toColor();

    /** The label on a destructive fill. */
    Role ON_DANGER = p -> p.contrastTo(p.danger());

    /** The wash over selected text — the accent, thin enough to read the glyphs through. */
    Role HIGHLIGHT = p -> p.accent().toColor(0.35f);

    // ----------------------------------------------------------------- depth

    /** The ink an elevation shadow is drawn in, at the opacity this palette gives depth. */
    Role SHADOW = p -> p.depth().toColor((float) p.shadowAlpha());

    /** The dim over a window a dialog has taken priority from. Dark in every palette — a veil is not a surface. */
    Role SCRIM = p -> p.depth().toColor(0.45f);
}
