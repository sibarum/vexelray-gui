package dev.vexelray.gui.demo.chapter;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.widget.Slider;
import dev.vexelray.text.TextLayout;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layout: the flex engine, driven live, and the unit system underneath it.
 *
 * <p>Nothing in this window is a hard-coded rect, including this page. The interesting part of that claim is not
 * that boxes are arranged for you — it is <b>the unit system</b>. A {@code rem} is content and grows with zoom;
 * a {@code dp} is frame and does not, which is why the gutters around these cards stay the same while the type
 * inside them grows; {@code grow} is a share of what is left after the fixed children have taken theirs.
 *
 * <p>Zoom is the check that catches a length that lied. Every length here resolves through it, so each step of
 * the ladder should be the previous picture scaled — and any element that holds its pixel size while the rest
 * grow is one still pinned to device pixels. That is what the {@code --capture-zoom} run captures as a strip of
 * images to be compared.
 */
public final class LayoutChapter implements Chapter {

    private static final Justify[] JUSTIFY = Justify.values();
    private static final AlignItems[] ALIGN = AlignItems.values();

    private final AtomicInteger justify = new AtomicInteger();
    private final AtomicInteger align = new AtomicInteger(AlignItems.CENTER.ordinal());

    @Override
    public String title() {
        return "Layout";
    }

    @Override
    public String blurb() {
        return "Flex, driven live: direction, justification, alignment, gap and grow — and the difference "
                + "between a length that is content and one that is frame.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Theme theme = stage.theme();
        Console console = stage.console();

        Node a = swatch(gui, "fixed 6rem", Length.rem(6), Role.ACCENT);
        Node b = swatch(gui, "grow 1", Length.grow(1), Role.ACTION);
        Node c = swatch(gui, "grow 2", Length.grow(2), Role.SELECTION);
        Node d = swatch(gui, "fixed 4rem", Length.rem(4), Role.ACCENT);

        Node arena = gui.row().width(Length.FILL).height(Length.grow(1))
                .background(theme.color(Role.WELL)).corner(Length.rem(0.5f))
                .border(Length.rem(0.1f), theme.color(Role.LINE))
                .padding(Length.dp(12)).gap(Length.rem(0.625f))
                .alignItems(ALIGN[align.get()])
                .children(a, b, c, d);

        Node readout = Ui.label(gui, "", Length.FILL);
        Runnable state = () -> readout.text(String.format(
                "justify %s  ·  align %s  ·  the two grow children split what the fixed ones left, 1 to 2",
                JUSTIFY[justify.get()], ALIGN[align.get()]));
        state.run();

        Slider gap = new Slider(gui, 0.2f).onChange(v -> arena.gap(Length.rem((float) v * 3f)));
        gap.node().width(Length.rem(10));
        Slider pad = new Slider(gui, 0.25f).onChange(v -> arena.padding(Length.dp((float) v * 48f)));
        pad.node().width(Length.rem(10));

        Node controls = Ui.strip(gui,
                Ui.controls(gui,
                        Ui.button(gui, "Justify »", () -> {
                            arena.justify(JUSTIFY[justify.updateAndGet(i -> (i + 1) % JUSTIFY.length)]);
                            state.run();
                        }),
                        Ui.button(gui, "Align »", () -> {
                            arena.alignItems(ALIGN[align.updateAndGet(i -> (i + 1) % ALIGN.length)]);
                            state.run();
                        }),
                        Ui.toggle(gui, "Row", "Column", true,
                                on -> arena.direction(on ? Direction.ROW : Direction.COLUMN))),
                Ui.controls(gui,
                        Ui.label(gui, "Gap (rem):", Length.rem(6.5f)), gap.node(),
                        Ui.label(gui, "Padding (dp):", Length.rem(7.5f)), pad.node()),
                readout);

        Node zoom = Ui.strip(gui,
                Ui.heading(gui, "Zoom is the check"),
                Ui.controls(gui,
                        Ui.button(gui, "Zoom in", gui::zoomIn),
                        Ui.button(gui, "Zoom out", gui::zoomOut),
                        Ui.button(gui, "Reset", gui::resetZoom),
                        Ui.button(gui, "What zoom am I at?", () ->
                                console.note(String.format("zoom %.2fx, root em %.1f px",
                                        gui.zoom().value(), gui.rootEmPx())))),
                Ui.prose(gui, "The gap slider is in rem and the padding slider in dp. Zoom in and watch which "
                        + "of the two changes: content grows, frame does not. That is the whole distinction, "
                        + "and getting it backwards is what makes a zoomed interface feel like a screenshot "
                        + "that was enlarged."));

        return gui.column().width(Length.FILL).height(Length.FILL).gap(Ui.GAP)
                .children(
                        Ui.card(gui, Ui.heading(gui, "A row, live"), arena),
                        controls,
                        zoom);
    }

    private static Node swatch(Gui gui, String label, Length width, Role fill) {
        Theme theme = gui.theme();
        return gui.text(label).width(width).height(Length.rem(4))
                .background(theme.color(fill)).corner(Length.rem(0.375f))
                .textSize(Length.rem(0.8125f)).textColor(theme.color(Role.ON_ACTION))
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .lit(theme.lit()).elevation(Length.rem(0.5f));
    }
}
