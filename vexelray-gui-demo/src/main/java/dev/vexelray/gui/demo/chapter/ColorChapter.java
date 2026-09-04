package dev.vexelray.gui.demo.chapter;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Hex;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.widget.ColorHistory;
import dev.vexelray.gui.widget.ColorPicker;
import dev.vexelray.text.TextLayout;

import java.util.List;

/**
 * Colour: two pickers over one history, and the difference between previewing a colour and choosing it.
 *
 * <p>The claim to watch is the one the console makes visible. Dragging in either picker prints a stream of
 * <em>previews</em> and adds nothing to the strip along the bottom; releasing prints one <em>choice</em> and the
 * strip gains an entry. That is not a nicety — it is what stops a recents strip from filling with the four hundred
 * colours a single drag passed through, and what lets an application put its undo entry somewhere sane.
 *
 * <p>The second claim is that the history is a value the application holds, not something a picker owns. Both
 * pickers here were handed the same {@link ColorHistory}; choose in one and the other's strip fills too, with
 * neither of them knowing the other exists.
 */
public final class ColorChapter implements Chapter {

    private final ColorHistory shared = new ColorHistory(10,
            List.of(Color.rgb(0x4C9AFF), Color.rgb(0xFF8B00), Color.rgb(0x36B37E)));

    @Override
    public String title() {
        return "Colour";
    }

    @Override
    public String blurb() {
        return "A saturation/value square, a hue and an alpha ramp, a hex box and a shared strip of recents — "
                + "and the line between a preview and a choice.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Theme theme = stage.theme();
        Console console = stage.console();

        Node fillSample = sample(gui, "Fill", Color.rgb(0x4C9AFF));
        Node strokeSample = sample(gui, "Stroke", Color.rgb(0xFF8B00));

        ColorPicker fill = new ColorPicker(gui, Color.rgb(0x4C9AFF), shared)
                .onChange(fillSample::background)
                .onCommit(c -> {
                    fillSample.background(c);
                    console.good("fill chosen: " + Hex.format(c));
                });

        // The second picker is the whole point of the history being a parameter: it shares one, and it shows
        // alpha off, because plenty of things an application colours cannot be transparent.
        ColorPicker stroke = new ColorPicker(gui, Color.rgb(0xFF8B00), shared)
                .onChange(strokeSample::background)
                .onCommit(c -> {
                    strokeSample.background(c);
                    console.good("stroke chosen: " + Hex.format(c));
                });
        stroke.alpha(false);

        Node pickers = gui.row().width(Length.FILL).height(Length.AUTO).gap(Ui.GAP)
                .alignItems(AlignItems.START)
                .scroll(false, false)
                .children(
                        gui.column().height(Length.AUTO).gap(Ui.GAP).scroll(false, false)
                                .children(fill.node(), fillSample),
                        gui.column().height(Length.AUTO).gap(Ui.GAP).scroll(false, false)
                                .children(stroke.node(), strokeSample));

        Node notes = Ui.strip(gui,
                Ui.prose(gui, "Drag the square, the hue ramp or the alpha ramp and watch the samples follow — "
                        + "that is onChange, once per pointer move. Let go and the console says a colour was "
                        + "chosen, once, and it appears at the front of both strips. Type a hex and press Enter "
                        + "for the same thing without a drag; type something that is not a colour and the box "
                        + "puts the colour back rather than complaining."),
                Ui.heading(gui, "Why the square is drawn the way it is"),
                Ui.prose(gui, "There is no gradient in the engine's alphabet, so a ramp is bands. The square "
                        + "is banded across saturation only: value in HSV is a multiply, and black at coverage "
                        + "1-v over the full-value colour is exactly the colour at v, so the vertical axis is "
                        + "exact and costs one strip per row instead of a grid of cells."),
                Ui.prose(gui, "HSV rather than Oklab, and deliberately. Oklab is the space the theme is "
                        + "authored in, because it is the one where 'a step lighter' means the same thing "
                        + "everywhere. It is the wrong space to steer in: much of its cube is outside sRGB, so "
                        + "a picker drawn on it has whole regions where the pointer moves and the colour does "
                        + "not. Every point of this square is a different colour you can actually have."),
                Ui.controls(gui,
                        Ui.button(gui, "Clear the samples", () -> {
                            fillSample.background(theme.color(Role.WELL));
                            strokeSample.background(theme.color(Role.WELL));
                        }),
                        Ui.button(gui, "What is in the strip?", () ->
                                console.note("recents: " + shared.current().stream()
                                        .map(Hex::format).toList()))));

        return gui.column().width(Length.FILL).height(Length.FILL).gap(Ui.GAP)
                .children(Ui.strip(gui, Ui.heading(gui, "Two pickers, one history"), pickers), notes);
    }

    private static Node sample(Gui gui, String label, Color initial) {
        Theme theme = gui.theme();
        return gui.text(label)
                .width(Length.rem(16)).height(Length.rem(3))
                .background(initial)
                .corner(Length.rem(0.5f))
                .textSize(Length.rem(0.8125f))
                .textColor(theme.color(Role.ON_ACTION))
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .elevation(theme.elevation(Relief.RAISED));
    }
}
