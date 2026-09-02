package dev.vexelray.gui.demo.chapter;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Modals;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Windows: a second real one, a dialog that is a value, and a native file picker.
 *
 * <p>Everything the framework opens is an OS window with the same chrome, drawn by the same shared device and
 * SDF pipeline, presented by the same frame loop — and given its own input backend when it is created, which is
 * the whole of what an application says about input for every window it does not create itself.
 *
 * <p><b>A named window is one window however many times it is asked for.</b> Clicking the button while the popup
 * is already up raises the one that exists instead of making a second, and closing it does not lose its tree,
 * which is sitting there waiting for the next show. That is the difference between a window and a window
 * factory, and it is the reason the key is a string rather than a handle the application has to keep.
 */
public final class WindowChapter implements Chapter {

    private final AtomicReference<AppWindow> popup = new AtomicReference<>();

    @Override
    public String title() {
        return "Windows";
    }

    @Override
    public String blurb() {
        return "A named popup, modal dialogs that queue, a close request the application may refuse, and the "
                + "OS file picker parented to the window that asked.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Console console = stage.console();

        // Registered now, opened later. The tree is built before the window exists, so a chapter that wants a
        // second window says what it would do and the shell runs it once there is something to run it against.
        stage.onApp(app -> {
            AppWindow window = app.window("popup", () -> {
                // A second window of one application is not a second opinion about what the application looks
                // like, so it gets the same chrome the main window and every dialog get: the frame handed to the
                // GUI with Decorations.CLIENT, and a TitleBar drawn where the system caption was.
                Gui popupGui = new Gui();
                popupGui.theme(gui.theme());
                TitleBar bar = new TitleBar(popupGui, WindowControls.NONE, "VexelRay popup");
                popupContent(popupGui, bar);
                // The bar exists before the window does, so it is bound to the window's controls when there is
                // one and unbound when it closes — which is the whole of what a window-chrome widget needs from
                // a window it does not create.
                return bar.commands(WindowSpec.of(
                        WindowConfig.of("VexelRay popup", 460, 320).decorations(Decorations.CLIENT), popupGui));
            });
            popup.set(window);
            gui.shortcut(Key.GRAVE_ACCENT, () -> {
                window.toggle();
                console.note("popup toggled by Ctrl+`");
            }, Modifier.CONTROL);
        });

        Node windows = Ui.strip(gui,
                Ui.heading(gui, "A second window"),
                Ui.controls(gui,
                        Ui.action(gui, "Open the popup", () -> {
                            AppWindow window = popup.get();
                            if (window == null) {
                                console.refused("no window yet — the popup needs the application to be running");
                                return;
                            }
                            window.show();
                            console.good("popup shown (or raised, if it was already open)");
                        }),
                        Ui.button(gui, "Hide it", () -> {
                            AppWindow window = popup.get();
                            if (window != null) {
                                window.hide();
                                console.note("popup hidden — its tree is intact, waiting for the next show");
                            }
                        })),
                Ui.prose(gui, "Ctrl+` toggles it. Its tree is a Gui of its own, laid out against its own "
                        + "viewport, and its handles are as thread-safe as any other — a worker could mutate it "
                        + "live exactly like the main window's."));

        Node dialogs = Ui.strip(gui,
                Ui.heading(gui, "Dialogs"),
                Ui.controls(gui,
                        // A dialog is a value, from any thread, with no class behind it. While it is up, every
                        // other window of this application is disabled by the window manager and dimmed.
                        Ui.action(gui, "Ask a question", () -> Modals.show(
                                Modal.of("A real window",
                                                "This dialog is an OS window of its own, owned by the main "
                                                        + "window and drawn with the same chrome. The "
                                                        + "application behind it is disabled and dimmed until "
                                                        + "you answer.\n\nAsk twice and the second question "
                                                        + "waits its turn.")
                                        .defaultButton("Ask again", () -> Modals.info("Second question",
                                                "Queued behind the first, and presented when it closed."))
                                        .cancelButton("Close", () -> console.note("dialog closed")))),
                        Ui.button(gui, "Two at once", () -> {
                            Modals.info("First", "Raised first, answered first.");
                            Modals.info("Second", "Was queued while the first was up.");
                            console.note("two dialogs asked for at once; they queue");
                        }),
                        Ui.button(gui, "Confirm", () -> Modals.confirm("Confirm something",
                                "Nothing is going to happen either way.", "Do it",
                                () -> console.good("confirmed"),
                                // The cancel arm is not optional, and that is the point of the shape: taking
                                // Cancel, pressing Escape and closing the dialog all land here, so "the user
                                // did not answer" is answered too.
                                () -> console.note("declined")))));

        Node files = Ui.strip(gui,
                Ui.heading(gui, "The OS file picker"),
                Ui.controls(gui,
                        Ui.button(gui, "Open a file…", () -> pick(stage, console, false)),
                        Ui.button(gui, "Pick a folder…", () -> pick(stage, console, true))),
                Ui.prose(gui, "Parented to the application window, which is the whole of what the handle is "
                        + "for: an unowned file dialog is one the window manager may put behind the window that "
                        + "asked for it, and that reads to the user as the application having hung."));

        Node closing = Ui.strip(gui,
                Ui.heading(gui, "Closing is a request"),
                Ui.prose(gui, "Press the window's close button. Closing is a request the application may "
                        + "refuse, and this one answers with a dialog — installed only where there is input to "
                        + "answer it with, because a dialog nobody can click is a window that cannot be closed "
                        + "at all. Placement is written 700ms after the window stops moving, and that deadline "
                        + "goes into the frame budget beside every other one the application holds, so a loop "
                        + "that parks when nothing is happening still wakes up to keep the promise."));

        return gui.column().width(Length.FILL).height(Length.FILL).gap(Ui.GAP).scroll(false, true)
                .children(windows, dialogs, files, closing);
    }

    private void pick(Stage stage, Console console, boolean folder) {
        stage.onApp(app -> {
            long parent = app.windowHandle();
            Path home = Path.of(System.getProperty("user.home"));
            var picked = folder
                    ? FileDialog.pickFolder(parent, home)
                    : FileDialog.open(parent, List.of(
                            new FileDialog.Filter("Java source", List.of("java")),
                            new FileDialog.Filter("Any file", List.of("*"))), home);
            picked.ifPresentOrElse(
                    path -> console.good("picked " + path),
                    () -> console.note("picker cancelled"));
        });
    }

    /**
     * The popup's tree: its own {@link Gui}, laid out against the popup's own viewport, under its own title bar.
     *
     * <p>The handles are as thread-safe as any other, so a worker could mutate this tree live exactly as it
     * mutates the main window's.
     */
    private static void popupContent(Gui p, TitleBar bar) {
        Theme theme = p.theme();
        Node card = p.column().width(Length.FILL).height(Length.FILL)
                .background(theme.color(Role.PANEL)).corner(Length.rem(1))
                .border(Length.rem(0.1f), theme.color(Role.LINE))
                .lit(theme.lit()).elevation(theme.elevation(Relief.OVERLAY))
                .padding(Length.dp(16)).gap(Length.rem(0.5f))
                .children(
                        p.text("A true OS window").height(Length.rem(2)).textSize(Length.rem(1.375f))
                                .textColor(theme.color(Role.ACCENT)),
                        p.text("Created on the main thread, presented by the same frame loop as the main "
                                        + "window, drawn by the same shared device and SDF pipeline, and given "
                                        + "its own input backend when it was created. Close it with the "
                                        + "title-bar button; the application keeps running, and this tree is "
                                        + "still here when you open it again.")
                                .width(Length.FILL)
                                .textSize(Length.rem(0.9375f)).textColor(theme.color(Role.DIM))
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP));
        // The bar is a child of the root and the padding is on the body below it, not on the root: a title bar
        // inset from the window edge is a title bar with a gap the window manager still treats as caption.
        Node body = p.column().width(Length.FILL).height(Length.FILL).padding(Length.dp(16)).children(card);
        p.root().background(theme.color(Role.PAGE)).children(bar.node(), body);
    }
}
