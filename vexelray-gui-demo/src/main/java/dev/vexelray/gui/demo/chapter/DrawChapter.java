package dev.vexelray.gui.demo.chapter;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Chart;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Svg;
import dev.vexelray.gui.nfd.FileDialog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drawing: one authored figure, two targets.
 *
 * <p>A picture is a flat list of marks in the box's own pixels, and it is the answer to a figure that a node tree
 * cannot express. The same chart built out of nodes is some two hundred laid-out, hit-testable, reconciled boxes
 * for a figure that participates in no layout and is replaced wholesale whenever the panel resizes — and it still
 * could not draw the curve, because a node tree is axis-aligned and a curve has diagonals in it.
 *
 * <p><b>Copy and Save hand the same picture to a second target.</b> The SVG is the picture rather than a
 * redrawing of it, which is the property that makes exported output trustworthy: there is no second authoring to
 * fall out of step with the first.
 */
public final class DrawChapter implements Chapter {

    private final AtomicReference<Picture> drawn = new AtomicReference<>(Picture.EMPTY);
    private final AtomicReference<float[]> size = new AtomicReference<>(new float[]{0f, 0f});
    private final AtomicReference<GuiApp> app = new AtomicReference<>();

    @Override
    public String title() {
        return "Drawing";
    }

    @Override
    public String blurb() {
        return "A figure authored as marks in pixels, rebuilt when its box changes, and exported to SVG from "
                + "the same picture the window is showing.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Console console = stage.console();
        stage.onApp(app::set);

        Node figure = gui.box().width(Length.FILL).height(Length.grow(1));
        // On the *Ui* lane, which is the seam for a handler whose work has to land in the same frame as the
        // layout it is reacting to — and a picture authored in pixels is exactly that. The figure is clipped to
        // its box, so a figure one frame behind its panel is a figure that visibly does not fit while a window
        // is being dragged. The lane's caveat ("anything that computes belongs on onResize") is about heavy
        // work; this is a hundred marks and ninety-six sines, and paying it per resize frame is cheaper than
        // looking wrong.
        gui.onResizeUi(figure, layout -> {
            float w = layout.rect().w();
            float h = layout.rect().h();
            Picture p = Chart.of(w, h, gui.theme());
            drawn.set(p);
            size.set(new float[]{w, h});
            figure.picture(p);
        });

        Node tools = Ui.controls(gui,
                Ui.button(gui, "Copy SVG", () -> {
                    String svg = svg();
                    gui.clipboard().set(svg);
                    console.good("copied " + svg.lines().count() + " lines of SVG to the clipboard");
                }),
                Ui.button(gui, "Save SVG…", () -> save(console)),
                Ui.label(gui, "", Length.FILL));

        return Ui.card(gui,
                Ui.heading(gui, "One picture, two targets"),
                figure,
                Ui.rule(gui),
                tools,
                Ui.prose(gui, "Resize the window and the figure is rebuilt at the new size rather than "
                        + "scaled — a picture is authored in the pixels of the box it was measured against, and "
                        + "nothing stretches it behind the author's back."));
    }

    private String svg() {
        float[] wh = size.get();
        return Svg.document(drawn.get(), wh[0], wh[1]);
    }

    /**
     * The native save dialog, parented to the application window.
     *
     * <p>Parented, and that is the whole of what the handle is for: an OS file dialog with no owner is a window
     * the window manager is free to put behind the one that asked for it, which reads to the user as the
     * application having hung.
     */
    private void save(Console console) {
        GuiApp live = app.get();
        long parent = live == null ? 0L : live.windowHandle();
        FileDialog.save(parent, List.of(new FileDialog.Filter("SVG drawing", List.of("svg"))),
                        Path.of(System.getProperty("user.home")), "chart.svg")
                .ifPresentOrElse(path -> {
                    try {
                        Files.writeString(path, svg());
                        console.good("wrote " + path);
                    } catch (IOException e) {
                        console.refused("could not write " + path + ": " + e.getMessage());
                    }
                }, () -> console.note("save cancelled"));
    }
}
