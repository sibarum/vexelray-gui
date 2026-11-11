package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.input.Trigger;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.IconView;

/**
 * A chord drawn as keys: {@code [⇧][↵]}, {@code [Ctrl][S]}, the caps {@link KeyCaps} gives each key side by side, with
 * no {@code +} between them. Where a menu line says its chord, and where the key map shows what an action is bound to.
 *
 * <p><b>Its name is the chord's spelling.</b> The caps are pictures, so the row carries {@link Trigger#text()} as a
 * hidden label, as {@link IconButton} carries its name: a screen reader, an automation agent and a test all read
 * {@code Ctrl+Shift+K}, on every platform, whatever the caps look like.
 *
 * <p><b>Sized by its text.</b> A cap is drawn {@value #CAP} times the text size, so the cap's body (46 of its 64
 * units) stands about as tall as a line of the text beside it. A gesture has no keys, and is its name in text.
 */
public final class ChordView {

    /** The role of each key drawn, and of a gesture's name. */
    static final String KEYCAP = "keycap";
    /** A cap's side, in text sizes. */
    static final float CAP = 1.5f;
    // A word cap, in units of the 64 grid the drawn caps are on (KeyboardKeys on the canvas), so it stands beside them
    // at the same height, weight and corner: an outline 3.5 thick round a 43 x 44 rect of radius 8.5, a solid lip
    // along the bottom from 44.5 down. Only its width is its own: the word's, and never narrower than a drawn cap.
    /** The outline's thickness. */
    private static final float STROKE = 3.5f / 64f;
    /** The outline's outer corner: the rect's radius plus half the stroke. */
    private static final float RADIUS = 10.25f / 64f;
    /** The lip inside the outline: from 44.5 to the outline's inner edge at 52.25. */
    private static final float LIP = 7.75f / 64f;
    /** The face the word sits on, between the top of the outline and the lip. */
    private static final float FACE = 32.75f / 64f;
    /** The least the inside of the outline is wide: a drawn cap's, 46.5 outer less two strokes. */
    private static final float INSIDE = 39.5f / 64f;
    /** The clear space round a cap, as a drawn cap has inside its 64-unit box. */
    private static final float GUTTER = 8.5f / 64f;
    /** The room either side of the word, inside the outline. */
    private static final float SIDE_ROOM = 5f / 64f;
    /** The word's size: as large as the face lets a capital and a descender-free word sit with air above and below. */
    private static final float WORD = 0.44f;

    private ChordView() {
    }

    /**
     * {@code chord} as caps beside text {@code textRem} rem high, in {@code tint}. The node is a row; place it as any
     * other. Its role is {@code shortcut}.
     */
    public static Node of(Gui gui, Trigger chord, float textRem, Role tint) {
        return of(gui, chord, textRem, tint, KeyCaps.host());
    }

    /** As {@link #of(Gui, Trigger, float, Role)}, with {@code caps}' faces rather than the host's. */
    public static Node of(Gui gui, Trigger chord, float textRem, Role tint, KeyCaps caps) {
        Node row = gui.row().role("shortcut").alignItems(AlignItems.CENTER).gap(Length.rem(textRem * 0.15f))
                .scroll(false, false);
        row.append(gui.text(chord.text()).hitInert(true).visible(false));
        if (!(chord instanceof Shortcut s)) {
            row.append(plain(gui, chord.text(), textRem, tint));
            return row;
        }
        for (KeyCaps.Face f : caps.faces(s)) {
            row.append(f.icon() != null ? cap(gui, f, textRem, tint)
                    : wordCap(gui, f.word(), textRem, tint));
        }
        return row;
    }

    // Every part is inert to the pointer, so a click anywhere on the chord is a click on the row: the row is what a
    // menu counts as its own and what a key-map chip listens on, and neither has to know the parts. And every part
    // declares a role, KEYCAP, which is where a name stops being gathered: the row is called by its chord's spelling
    // alone, not by that and then the words on its caps.
    private static Node plain(Gui gui, String word, float textRem, Role tint) {
        return gui.text(word).role(KEYCAP).textSize(Length.rem(textRem)).wordWrap(false).textColor(tint)
                .hitInert(true);
    }

    /** A drawn cap, its symbol and all: {@code ⇧}. */
    private static Node cap(Gui gui, KeyCaps.Face f, float textRem, Role tint) {
        float side = textRem * CAP;
        return IconView.of(gui, f.icon()).size(Length.rem(side)).tint(tint).node().role(KEYCAP).hitInert(true);
    }

    /**
     * A cap with a word on it, {@code Ctrl}, {@code F12}: drawn here rather than baked, as an outline and a lip in the
     * drawn caps' proportions, so it can be as wide as its word, as a Ctrl key is wider than a letter's. The word is
     * in the GUI's own font, so it is sharp at any size and the same on every build machine.
     */
    private static Node wordCap(Gui gui, String word, float textRem, Role tint) {
        float side = textRem * CAP;
        Node face = gui.row().height(rem(side * FACE)).padding(rem(0), rem(side * SIDE_ROOM))
                .justify(Justify.CENTER).alignItems(AlignItems.CENTER).scroll(false, false)
                .children(gui.text(word).textSize(rem(side * WORD)).bold().wordWrap(false).textColor(tint)
                        .hitInert(true));
        // The lip fills the bottom of the outline, its lower corners following the outline's inner curve.
        Node lip = gui.box().width(Length.FILL).height(rem(side * LIP)).background(tint)
                .corner(rem(0), rem(side * (RADIUS - STROKE)));
        // Zero high and a drawn cap's inside wide: a word shorter than that (Alt) still makes a cap no narrower.
        Node least = gui.box().size(rem(side * INSIDE), rem(0));
        return gui.column().role(KEYCAP).hitInert(true).alignItems(AlignItems.CENTER)
                .margin(rem(side * GUTTER)).border(rem(side * STROKE), tint).corner(rem(side * RADIUS))
                .scroll(false, false).children(face, lip, least);
    }

    private static Length rem(float r) {
        return Length.rem(r);
    }
}
