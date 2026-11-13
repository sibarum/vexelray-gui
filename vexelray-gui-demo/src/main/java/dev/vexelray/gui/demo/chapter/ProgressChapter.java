package dev.vexelray.gui.demo.chapter;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.Timing;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Progress;
import dev.vexelray.text.TextLayout;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Progress: a bar that is shown how far some work has got, and the work that shows it.
 *
 * <p>The top card is the bar's states held still: nothing done, part done, all done, and a size not known yet. The
 * second is a conversion of twelve files, simulated on a thread of its own: it counts them first, which has no size and
 * so sweeps, then reports every block of every file. The bar is shown each report and draws only those that move its
 * fill half a pixel, so a job that reports thousands of times costs a few hundred frames. Cancel is the page's own
 * button beside the bar, not the bar's, because what cancelling means is the job's to say.
 */
public final class ProgressChapter implements Chapter {

    private static final int FILES = 12;
    private static final long BYTES_PER_FILE = 4L << 20;
    private static final long BLOCK = 16L << 10;

    /** The still indeterminate bar: swept only while the page is on screen, so the gallery is quiet otherwise. */
    private Progress unknown;
    /** The still running bar: glinting only while the page is on screen, for the same reason. */
    private Progress running;

    @Override
    public String title() {
        return "Progress";
    }

    @Override
    public String blurb() {
        return "How far a long job has got: a fill for work of known size, a sweep for work that has not been "
                + "measured yet, and a Cancel beside it that belongs to the job, not to the bar.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Console console = stage.console();

        unknown = new Progress(gui);
        Node states = Ui.strip(gui,
                Ui.heading(gui, "Held still"),
                row(gui, "Nothing yet", new Progress(gui).node(), "0%"),
                row(gui, "Part done", new Progress(gui).show(0.4).node(), "40%"),
                row(gui, "All done", new Progress(gui).show(1.0).node(), "100%"),
                row(gui, "Not known", unknown.node(), "…"),
                Ui.prose(gui, "The fill is the track's full width, slid left by what is not done inside a track "
                        + "that clips to its own corners, so a new value moves the fill and lays nothing out. Not "
                        + "known is a segment crossing the track at about sixty frames a second; under reduced "
                        + "motion it is a faint, still wash over the whole track instead."));

        running = new Progress(gui).show(0.55);
        Node states2 = Ui.strip(gui,
                Ui.heading(gui, "What the work is doing"),
                row(gui, "Idle", new Progress(gui).show(0.55).node(), "55%"),
                row(gui, "Running", running.node(), "55%"),
                row(gui, "Paused", new Progress(gui).show(0.55).state(Progress.State.PAUSED).node(), "55%"),
                row(gui, "Failed", new Progress(gui).show(0.55).state(Progress.State.FAILED).node(), "55%"),
                row(gui, "Paused sweep",
                        new Progress(gui).indeterminate().state(Progress.State.PAUSED).node(), "…"),
                Ui.prose(gui, "How far the work has got and what it is doing are two things, so they are two "
                        + "calls. Running sends a soft glint across the part done every three seconds, so a bar "
                        + "stuck on one large file still shows nothing has hung; it rests between glints, which "
                        + "keeps a long job to a fraction of the frames. Paused and failed are still, and keep "
                        + "the value they reached. There is no disabled bar: nothing on it takes input."));

        Progress bar = new Progress(gui).transition(Timing.millis(120, Timing.Curve.OUT_CUBIC));
        Node readout = gui.text("Ready").width(Length.rem(9)).height(Length.rem(1.5f))
                .textSize(Length.rem(0.875f)).textColor(Role.DIM)
                .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE);
        Button start = new Button(gui, "Convert 12 files").kind(Button.Kind.PRIMARY);
        Button hold = new Button(gui, "Pause").enabled(false);
        Button cancel = new Button(gui, "Cancel").enabled(false);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean paused = new AtomicBoolean();

        gui.mailbox(Button.PRESSED, start.address(), p -> {
            start.enabled(false);
            cancel.enabled(true);
            hold.enabled(true).label("Pause");
            cancelled.set(false);
            paused.set(false);
            console.note("converting " + FILES + " files");
            Thread.ofVirtual().name("demo-convert").start(() -> {
                // The bar and the readout are written from the job's thread: every widget method is safe from any.
                bar.state(Progress.State.RUNNING).indeterminate();
                readout.text("Counting…");
                pause(900);
                long total = FILES * BYTES_PER_FILE;
                long done = 0;
                for (int f = 0; f < FILES && !cancelled.get(); f++) {
                    readout.text((f + 1) + " of " + FILES + " files");
                    for (long b = 0; b < BYTES_PER_FILE && !cancelled.get(); b += BLOCK) {
                        while (paused.get() && !cancelled.get()) {
                            bar.state(Progress.State.PAUSED);
                            pause(50);
                        }
                        bar.state(Progress.State.RUNNING);
                        done += BLOCK;
                        bar.show(done, total);
                        if ((done / BLOCK) % 16 == 0) {
                            pause(15);
                        }
                    }
                }
                boolean stopped = cancelled.get();
                bar.state(Progress.State.IDLE);
                readout.text(stopped ? "Cancelled" : "Done");
                if (stopped) {
                    console.refused("cancelled at " + Math.round(bar.fraction() * 100) + "%");
                } else {
                    console.good("converted " + FILES + " files");
                }
                cancel.enabled(false);
                hold.enabled(false).label("Pause");
                start.enabled(true);
            });
        });
        gui.mailbox(Button.PRESSED, cancel.address(), p -> cancelled.set(true));
        gui.mailbox(Button.PRESSED, hold.address(), p -> {
            boolean now = !paused.get();
            paused.set(now);
            hold.label(now ? "Resume" : "Pause");
        });

        Node job = Ui.strip(gui,
                Ui.heading(gui, "A job that reports"),
                gui.row().width(Length.FILL).height(Length.AUTO).gap(Length.rem(0.75f))
                        .alignItems(AlignItems.CENTER).scroll(false, false)
                        .children(bar.node().width(Length.grow(1)), readout, hold.node(), cancel.node()),
                gui.row().width(Length.FILL).height(Length.AUTO).scroll(false, false).children(start.node()),
                Ui.prose(gui, "The job counts its files first, with no size to show, so the bar sweeps; then it "
                        + "reports every 16 KiB block of every 4 MiB file, about three thousand reports. The bar "
                        + "holds any that would move its fill less than half a pixel, so the frames drawn follow "
                        + "the width of the track, not the number of reports. Pause holds "
                        + "the job between blocks and says so to the bar, which turns amber and stops glinting."));

        return gui.column().width(Length.FILL).height(Length.FILL).gap(Ui.GAP)
                .children(states, states2, job);
    }

    @Override
    public void shown(boolean showing) {
        if (unknown == null) {
            return;
        }
        if (showing) {
            unknown.indeterminate();
            running.state(Progress.State.RUNNING);
        } else {
            unknown.show(0.0);
            running.state(Progress.State.IDLE);
        }
    }

    /** A captioned bar with a word for its value beside it. */
    private static Node row(Gui gui, String caption, Node bar, String value) {
        return gui.row().width(Length.FILL).height(Length.AUTO).gap(Length.rem(0.75f))
                .alignItems(AlignItems.CENTER).scroll(false, false)
                .children(
                        gui.text(caption).width(Length.rem(7)).height(Length.rem(1.5f))
                                .textSize(Length.rem(0.875f)).textColor(Role.DIM)
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE),
                        bar.width(Length.grow(1)),
                        gui.text(value).width(Length.rem(3)).height(Length.rem(1.5f))
                                .textSize(Length.rem(0.875f)).textColor(Role.FAINT)
                                .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE));
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
