package dev.vexelray.gui.demo.chapter;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.krono.Transitions;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Ramp;
import dev.vexelray.text.TextLayout;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Motion: three kinds of it, each entering the framework at a different seam, and none of them known to the
 * widget that moves.
 *
 * <p><b>A cue</b> is a one-shot the application authors and the framework plays once and takes back off. It is
 * {@code (progress, box) -> Picture} painted into a node's overlay slot, so it costs the node's own layout
 * nothing and cannot be left behind.
 *
 * <p><b>A transition</b> animates a node <em>arriving where the model already says it is</em>. The model change
 * is instantaneous and has happened; only the picture is behind. That is why undo animates for free — a change
 * that puts a row back is a layout change like any other, and nothing in the motion layer knows or cares which
 * direction the user was going.
 *
 * <p><b>An animation</b> drives a property from one value to another. It is the blunt instrument, for a property
 * with no resting value worth naming.
 *
 * <p>What they share is where the clock is: outside. {@code -widget} declares its seams as a {@code DoubleConsumer}
 * and a {@code Runnable}, this module satisfies them, and a widget animates without ever naming Kronometer.
 * Leave the one line out that connects them and every one of these falls back to an instant change.
 */
public final class MotionChapter implements Chapter {

    private final AtomicBoolean expanded = new AtomicBoolean();

    @Override
    public String title() {
        return "Motion";
    }

    @Override
    public String blurb() {
        return "Cues that play once, transitions that carry a node to where it already is, and an easing "
                + "curve chosen for what the motion means.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Theme theme = stage.theme();
        Console console = stage.console();
        KronoGui krono = stage.krono();

        // --- cues ---------------------------------------------------------------------------------------
        Node target = gui.text("play a cue on me").width(Length.FILL).height(Length.rem(4))
                .background(theme.color(Role.WELL)).corner(Length.rem(0.5f))
                .border(Length.rem(0.1f), theme.color(Role.LINE))
                .textSize(Length.rem(0.9375f)).textColor(theme.color(Role.DIM))
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE);

        Ramp insistent = (progress, done) -> krono.ramp(Dur.ms(650), Ease.LINEAR, progress, done);
        Node cues = Ui.strip(gui,
                Ui.heading(gui, "Cues — a moment, played once"),
                target,
                Ui.controls(gui,
                        Ui.button(gui, "Accepted", () -> {
                            stage.cues().play(target, Cue.scanline(theme.color(Role.ACCENT)));
                            console.good("scanline: the field took the command");
                        }),
                        Ui.button(gui, "Written to", () -> {
                            stage.cues().play(target,
                                    Cue.wash(Color.withAlpha(theme.color(Role.ACCENT), 0.5f)));
                            console.note("wash: something changed that the user did not cause");
                        }),
                        Ui.button(gui, "Refused", () -> {
                            stage.cues().play(target, Cue.ring(theme.color(Role.DANGER))
                                    .with(Cue.wash(Color.withAlpha(theme.color(Role.DANGER), 0.45f))),
                                    insistent);
                            console.refused("ring: refusal, and it runs longer because it asks to be read");
                        })),
                Ui.prose(gui, "All three are linear, and that is not laziness. An easing curve is for something "
                        + "arriving at a place, where decelerating into it reads as weight; a cue has nowhere to "
                        + "arrive, so easing it only produces a stall. 420ms rather than something snappier, "
                        + "because a cue competes with whatever the user is actually looking at — under about a "
                        + "third of a second it reads as a rendering glitch if it registers at all."));

        // --- transitions --------------------------------------------------------------------------------
        // Enrolment is opt-in, per node: a container's direct children, which is what a list or a tree wants,
        // since its rows come and go and enrolling each one as it appeared would be bookkeeping the container
        // already does. Dur.ZERO here would be the honest reduced-motion setting rather than a special case.
        Transitions moves = Transitions.on(krono);
        Node stack = gui.column().width(Length.FILL).height(Length.AUTO).gap(Length.rem(0.375f));
        Node detail = gui.text("this panel took the room, and the rows below covered the distance")
                .width(Length.FILL).height(Length.ZERO)
                .background(theme.color(Role.SELECTION)).corner(Length.rem(0.375f))
                .textSize(Length.rem(0.875f)).textColor(theme.color(Role.INK))
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .clip(true)
                .visible(false);
        stack.children(
                bar(gui, "Row one"),
                detail,
                bar(gui, "Row two"),
                bar(gui, "Row three"),
                bar(gui, "Row four"));
        moves.followChildren(stack, Dur.ms(220), Ease.OUT_CUBIC);
        gui.motion(moves);

        Node transitions = Ui.strip(gui,
                Ui.heading(gui, "Transitions — the picture catching up"),
                stack,
                Ui.controls(gui,
                        Ui.button(gui, "Open the panel", () -> {
                            boolean open = !expanded.get();
                            expanded.set(open);
                            // The height changes at once. Nothing animates the panel; the rows below it are
                            // enrolled, so they are drawn where they were and move to where they now are.
                            // Height *and* visibility. A zero-height box still has a label in it, and a label
                            // is drawn from its baseline rather than clipped to a box it overflows — so height
                            // alone leaves the text lying across the row below it.
                            detail.height(open ? Length.rem(3.5f) : Length.ZERO).visible(open);
                            console.note(open ? "panel opened — the rows below slid" : "panel closed");
                        })),
                Ui.prose(gui, "OUT_CUBIC here, where the page crossfade is linear: this is a distance being "
                        + "covered, and decelerating into the place it stops is what reads as weight. Interrupt "
                        + "it — click twice quickly — and it continues from where it is drawn rather than "
                        + "snapping back to where it was."));

        // --- animation ----------------------------------------------------------------------------------
        Node meter = gui.box().width(Length.rem(4)).height(Length.rem(1.5f))
                .background(theme.color(Role.ACCENT)).corner(Length.rem(0.375f));
        Node track = gui.row().width(Length.FILL).height(Length.rem(1.5f))
                .background(theme.color(Role.TRACK)).corner(Length.rem(0.375f))
                .children(meter);
        AtomicBoolean wide = new AtomicBoolean();
        Node animation = Ui.strip(gui,
                Ui.heading(gui, "Animation — a property, driven"),
                track,
                Ui.controls(gui,
                        Ui.button(gui, "Drive it", () -> {
                            boolean out = !wide.get();
                            wide.set(out);
                            krono.animate(meter, Node::width,
                                    Length.rem(out ? 4 : 22), Length.rem(out ? 22 : 4),
                                    Dur.ms(420), Ease.IN_OUT_CUBIC);
                            console.note("width driven from one value to another over 420ms");
                        }),
                        Ui.button(gui, "Recolour", () -> krono.animate(meter, Node::background,
                                theme.color(Role.ACCENT), theme.color(Role.DANGER),
                                Dur.ms(420), Ease.LINEAR))),
                Ui.prose(gui, "Colour interpolates through Oklab rather than sRGB, so a blend between two "
                        + "saturated colours does not dip through grey on the way."));

        return gui.column().width(Length.FILL).height(Length.FILL).gap(Ui.GAP).scroll(false, true)
                .children(cues, transitions, animation);
    }

    private static Node bar(Gui gui, String text) {
        Theme theme = gui.theme();
        return gui.text(text).width(Length.FILL).height(Length.rem(2.25f))
                .background(theme.color(Role.WELL)).corner(Length.rem(0.375f))
                .padding(Length.ZERO, Length.em(0.75f))
                .textSize(Length.rem(0.9375f)).textColor(theme.color(Role.DIM))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
    }
}
