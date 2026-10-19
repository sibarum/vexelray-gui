package dev.vexelray.gui.demo;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Finish;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.text.TextLayout;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The gallery's own small vocabulary: a card, a button, a toggle, a heading, a paragraph.
 *
 * <p>None of this is framework. It exists because nine chapters that each hand-rolled a button would be nine
 * chapters about buttons, and what a chapter is supposed to be about is whatever it is about. Everything here is
 * ordinary application code over {@link Node} handles and {@link Role}s — which is the honest demonstration in
 * its own right: an application builds its own controls, and the framework's part is that a role already knows
 * its hover and pressed shades, so nobody here writes a colour down.
 */
public final class Ui {

    /** Standing padding inside a card. dp, not rem: this is frame around the content rather than content. */
    public static final Length CARD_PAD = Length.dp(18);

    /** The gap between things that belong together. */
    public static final Length GAP = Length.rem(0.625f);

    /**
     * A floating panel: lit fill, soft analytic shadow, rounded, bordered. Both the light and the shadow are
     * transfer functions of the same rounded-box SDF the fill already evaluates, so a card costs no texture.
     */
    public static Node card(Gui gui, Node... children) {
        return gui.column().width(Length.FILL).height(Length.FILL)
                .background(Role.PANEL).corner(Length.rem(0.875f))
                .border(Length.rem(0.1f), Role.LINE)
                .lit(Finish.LIT).elevation(gui.theme().elevation(Relief.OVERLAY))
                .padding(CARD_PAD).gap(GAP)
                .children(children);
    }

    /** A card that takes only the height its content needs — a strip of controls under a live one. */
    public static Node strip(Gui gui, Node... children) {
        return card(gui, children).height(Length.AUTO);
    }

    /** The title line of a section. */
    public static Node heading(Gui gui, String text) {
        return gui.text(text).width(Length.FILL).height(Length.rem(1.75f))
                .textSize(Length.rem(1.25f)).textColor(Role.ACCENT)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
    }

    /**
     * A paragraph. No height on purpose: a text node sizes to its <em>wrapped</em> content, so prose that runs
     * to three lines reserves three lines and prose that does not, does not. Pinning a height here is how a
     * paragraph comes to be clipped the first time somebody rewords it.
     */
    public static Node prose(Gui gui, String text) {
        return gui.text(text).width(Length.FILL)
                .textSize(Length.rem(0.9375f)).textColor(Role.DIM)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
    }

    /** A short caption beside a control. */
    public static Node label(Gui gui, String text, Length width) {
        return gui.text(text).width(width).height(Length.rem(2))
                .textSize(Length.rem(0.9375f)).textColor(Role.DIM)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
    }

    /** A quiet button: panel fill, bordered — for anything that is not the main thing on the page. */
    public static Node button(Gui gui, String text, Runnable onClick) {
        Node b = shell(gui, text, Role.DIM, Role.PANEL, true);
        gui.onClick(b, onClick);
        return b;
    }

    /** The loud one: filled with the action colour, its label letterpressed where the theme letterpresses. */
    public static Node action(Gui gui, String text, Runnable onClick) {
        Node b = shell(gui, text, Role.ON_ACTION, Role.ACTION, false)
                .textSunken(Finish.LETTERPRESS);
        gui.onClick(b, onClick);
        return b;
    }

    /**
     * A two-state control. Which <em>role</em> it fills with depends on its own state — a filled control while
     * on, a panel while off — and the theme shades whichever of those is current for hover and press. One
     * handler owns all the restyling and reads the toggle state together with the last interaction state, which
     * is what makes a flip that happens mid-hover repaint as hovered rather than as normal.
     */
    public static Node toggle(Gui gui, String on, String off, boolean initial, Consumer<Boolean> onChange) {
        AtomicBoolean value = new AtomicBoolean(initial);
        AtomicReference<InteractionState> last = new AtomicReference<>(InteractionState.NORMAL);
        Node t = gui.text(initial ? on : off).role("toggle").width(Length.rem(8.5f)).height(Length.rem(2.5f))
                .corner(Length.rem(0.5f)).border(Length.rem(0.1f), Role.LINE)
                .textSize(Length.rem(0.9375f))
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .lit(Finish.LIT);
        Runnable restyle = () -> {
            boolean v = value.get();
            InteractionState state = last.get();
            t.text(v ? on : off)
                    .textColor(v ? Role.ON_ACTION : Role.DIM)
                    // Letterpress only while the label is white-on-fill; the off state is low-contrast already.
                    .textSunken(v ? Finish.LETTERPRESS : Finish.OFF)
                    .background((v ? Role.ACTION : Role.PANEL).at(state))
                    .elevation(gui.theme().elevation(Relief.CONTROL, state));
        };
        restyle.run();
        gui.onState(t, state -> {
            last.set(state);
            restyle.run();
        });
        gui.onClick(t, () -> {
            boolean now = !value.get();
            value.set(now);
            restyle.run();
            onChange.accept(now);
        });
        return t;
    }

    /** A row of controls that does not scroll and centres what is in it. */
    public static Node controls(Gui gui, Node... children) {
        return gui.row().width(Length.FILL).height(Length.AUTO).gap(Length.rem(0.5f))
                .alignItems(AlignItems.CENTER).scroll(false, false)
                .children(children);
    }

    /** A hairline between sections of a card. */
    public static Node rule(Gui gui) {
        return gui.box().width(Length.FILL).height(Length.rem(0.1f))
                .background(Role.LINE);
    }

    /**
     * The button body, without a handler. Depth is part of the feedback, and the theme owns the response: the
     * control names the rung it rests on and hover lifts it, pressing sets it flush, so the shadow reports the
     * gesture as well as the fill does.
     */
    private static Node shell(Gui gui, String text, Role fg, Role fill, boolean bordered) {
        Node b = gui.text(text).role("button").width(Length.AUTO).height(Length.rem(2.5f))
                .padding(Length.ZERO, Length.rem(0.875f))
                .background(fill)
                .corner(Length.rem(0.5f)).textColor(fg)
                .textSize(Length.rem(0.9375f))
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .lit(Finish.LIT).elevation(gui.theme().elevation(Relief.CONTROL));
        if (bordered) {
            b.border(Length.rem(0.1f), Role.LINE);
        }
        gui.onState(b, state -> b.background(fill.at(state))
                .elevation(gui.theme().elevation(Relief.CONTROL, state)));
        return b;
    }

    private Ui() {
    }
}
