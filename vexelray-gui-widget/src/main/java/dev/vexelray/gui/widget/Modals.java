package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The application's dialogs: one entity, asked from anywhere, showing one dialog at a time.
 *
 * {@snippet :
 * Modals.install(app);                                    // once, at the application edge
 * ...
 * Modals.show(Modal.of("Delete file", "This cannot be undone.")
 *         .defaultButton("Delete", this::delete)
 *         .cancelButton("Cancel", () -> { }));            // from any thread, at the point the question arises
 * }
 *
 * <p><b>A real window, because a dialog is a real thing.</b> Each dialog is an OS window owned by the main
 * window, drawn with the same chrome as the rest of the application ({@code Decorations.CLIENT} + {@link
 * TitleBar}), so it looks and behaves identically no matter which window put it up. While it is up, every other
 * window of the application is <b>disabled by the window manager</b> and dimmed
 * ({@link GuiApp#modalWindow}): clicking a blocked window flashes the dialog rather than doing nothing quietly,
 * and the dimming says why. Modality is not the application remembering to ignore events.
 *
 * <p><b>One at a time, in order.</b> Two worker threads that both decide to ask something do not produce two
 * dialogs fighting over the screen: the second waits ({@link ModalQueue}) and is presented when the first
 * closes. Nothing is dropped and nothing is stacked.
 *
 * <p><b>No class per question.</b> A dialog is a {@link Modal} value — title, message, buttons with actions —
 * so asking costs an expression, not a type. Actions run on the dialog's own handler executor after its window
 * has been asked to close, so an action may put up the next dialog immediately.
 *
 * <p>The dialog needs input to be clickable, which means the application must have supplied
 * {@link GuiApp#input} — the seam that attaches a device backend to windows the framework opens. Without it the
 * dialog still draws, and its title-bar close button still works (that is the window manager's), but its own
 * buttons hear nothing.
 */
public final class Modals implements AutoCloseable {

    private static final Color BG = Color.rgb(0x151a26);
    private static final Color INK = Color.rgb(0xeef2f8);
    private static final Color LINE = Color.rgb(0x2b3346);
    private static final Color BTN = Color.rgb(0x1b2130);
    private static final Color BTN_HOVER = Color.rgb(0x232a3d);
    private static final Color BTN_PRESSED = Color.rgb(0x11141b);
    private static final Color ACCENT = Color.rgb(0x2668b3);
    private static final Color ACCENT_HOVER = Color.rgb(0x2f78c9);
    private static final Color ACCENT_PRESSED = Color.rgb(0x1d548f);

    /** Dialog metrics, in the logical pixels {@link WindowConfig} takes. */
    private static final int WIDTH = 460;
    private static final int TITLE_BAR = 32;
    private static final int PADDING = 36;         // the body's padding, top and bottom
    private static final int LINE_HEIGHT = 22;
    private static final int BUTTON_ROW = 60;
    private static final int CHAR_WIDTH = 8;       // rough advance, for guessing where the message wraps

    private static final AtomicReference<Modals> INSTANCE = new AtomicReference<>();

    private final GuiApp app;
    private final ModalQueue queue = new ModalQueue();
    private final AtomicReference<Live> live = new AtomicReference<>();

    // One tree, reused by every dialog. Only one is ever on screen, so a second tree would only ever be a second
    // bus, a second handler executor and a second set of viewport observers — leaked once per question asked.
    // What changes per dialog is the title, the message and the buttons; the window around them is new each time,
    // because a window cannot be resized to fit a message it has not been given yet.
    private final Gui gui = new Gui();
    private final TitleBar bar;
    private final Node message;
    private final Node buttonRow;
    /** The buttons of the dialog on screen, released when the next one takes their place. */
    private final List<Node> buttons = new ArrayList<>();

    /** One dialog while it is on screen: its spec, its window once it exists, and whether it is already going. */
    private record Live(Modal modal, AtomicReference<NativeWindow> window, AtomicBoolean dismissed) {
    }

    private Modals(GuiApp app) {
        this.app = app;
        this.bar = new TitleBar(gui, WindowControls.NONE, "");
        this.message = gui.text("")
                .width(Length.FILL).height(Length.grow(1))
                .textSize(Length.rem(1)).textColor(INK)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
        this.buttonRow = gui.row()
                .width(Length.FILL).height(Length.AUTO)
                .justify(Justify.END).alignItems(AlignItems.CENTER)
                .gap(Length.dp(8))
                .scroll(false, false);
        Node body = gui.column()
                .width(Length.FILL).height(Length.grow(1))
                .padding(Length.dp(18)).gap(Length.dp(16))
                .scroll(false, false)
                .children(message, buttonRow);
        gui.root().background(BG).children(bar.node(), body);

        // Registered once, not per dialog: a global claim has no node to release it with, so a dialog that
        // claimed Escape on the way up would leave that claim behind on the way down. These read the dialog that
        // is up when the key arrives, which is the same thing and stays one registration.
        gui.shortcut(Key.ESCAPE, () -> {
            Live current = live.get();
            dismiss(current == null ? null : current.modal().cancelButton());
        });
        gui.shortcut(Key.ENTER, () -> {
            Live current = live.get();
            dismiss(current == null ? null : current.modal().defaultButton());
        });
    }

    /**
     * Install the application's dialogs on {@code app} and make them reachable from the static methods here.
     * Called once, at the application edge; installing again replaces the instance (the previous one's dialogs
     * are left to close on their own).
     */
    public static Modals install(GuiApp app) {
        if (app == null) {
            throw new IllegalArgumentException("app must not be null");
        }
        Modals modals = new Modals(app);
        INSTANCE.set(modals);
        return modals;
    }

    /** The installed instance, or null if {@link #install} has not been called. */
    public static Modals instance() {
        return INSTANCE.get();
    }

    /** Show {@code modal}, or queue it behind the dialog that is up. Callable from any thread. */
    public static void show(Modal modal) {
        Modals modals = INSTANCE.get();
        if (modals == null) {
            throw new IllegalStateException("Modals.install(app) before showing a dialog");
        }
        modals.present(modal);
    }

    /** A message with one "OK" button — the "your export finished" dialog. */
    public static void info(String title, String message) {
        show(Modal.of(title, message).defaultButton("OK", () -> { }));
    }

    /**
     * A yes/no question. {@code onConfirm} runs if the user takes the affirmative button, {@code onCancel} if
     * they take Cancel, press Escape, or close the dialog — so "the user did not answer" is answered too, which
     * is the case an application that only handles the yes leaves hanging.
     */
    public static void confirm(String title, String message, String confirmLabel,
                               Runnable onConfirm, Runnable onCancel) {
        show(Modal.of(title, message)
                .defaultButton(confirmLabel, onConfirm)
                .cancelButton("Cancel", onCancel));
    }

    /** Show {@code modal} on this instance, or queue it. Callable from any thread. */
    public void present(Modal modal) {
        if (modal == null) {
            throw new IllegalArgumentException("modal must not be null");
        }
        Modal now = queue.offer(modal);
        if (now != null) {
            open(now);
        }
    }

    /** Whether a dialog is on screen right now. */
    public boolean showing() {
        return queue.showing() != null;
    }

    /** How many dialogs are waiting behind the one on screen. */
    public int queued() {
        return queue.queued();
    }

    /**
     * Drop every queued dialog and dismiss the one showing (running nothing). For shutdown: an application that
     * is closing should not be held up by a question nobody is left to answer.
     */
    @Override
    public void close() {
        queue.clear();
        dismiss(null);
        INSTANCE.compareAndSet(this, null);
    }

    // --- one dialog ---

    /** Put {@code modal}'s content into the shared tree and ask the host for a window to show it in. Any thread. */
    private void open(Modal modal) {
        Live current = new Live(modal, new AtomicReference<>(), new AtomicBoolean());
        live.set(current);

        bar.title(modal.title());
        message.text(modal.message());
        for (Node old : buttons) {
            gui.releaseNode(old);   // the previous dialog's handlers go with its buttons
            old.remove();
        }
        buttons.clear();
        for (Modal.Button b : modal.buttons()) {
            Node node = button(b);
            buttons.add(node);
            buttonRow.append(node);
        }

        WindowConfig config = WindowConfig.of(modal.title(), width(modal), height(modal))
                .decorations(Decorations.CLIENT);
        app.requestWindow(WindowSpec.of(config, gui)
                .onCreated(window -> {
                    current.window().set(window);
                    bar.controls(WindowControls.of(window));   // the chrome commands *this* window
                    app.modalWindow(window);                   // and everything else stops taking input
                })
                // The window closing is the one place every route converges: a button, Escape, the title bar's
                // close, Alt+F4, the owner going away. Two things have to happen here or an application can be
                // left stuck: the queue advances (so a question asked behind this one is still asked), and a
                // dialog that went away without a button being chosen answers as Cancel — otherwise closing a
                // "save before quitting?" dialog from its title bar would leave the close request unanswered
                // and the application unable to quit at all.
                .onClosed(() -> {
                    live.compareAndSet(current, null);
                    if (current.dismissed().compareAndSet(false, true)) {
                        run(modal.cancelButton());
                    }
                    Modal next = queue.next();
                    if (next != null) {
                        open(next);
                    }
                }));
    }

    /**
     * Dismiss the dialog that is up, running {@code chosen}'s action if there is one. Idempotent per dialog: the
     * user can press Escape while a button click is already in flight, and only the first answer counts.
     */
    private void dismiss(Modal.Button chosen) {
        Live current = live.get();
        if (current == null || !current.dismissed().compareAndSet(false, true)) {
            return;
        }
        NativeWindow window = current.window().get();
        if (window != null) {
            window.requestClose();   // the ordinary route: the loop tears it down and onClosed advances the queue
        }
        run(chosen);
    }

    /**
     * Run a chosen button's action, off the GUI thread like every other application callback here — and after
     * the dialog's close has been asked for, so an action that puts up the next dialog does not race its own
     * window's teardown.
     */
    private void run(Modal.Button chosen) {
        if (chosen != null) {
            gui.async(chosen.action());
        }
    }

    /** One dialog button: the default one accented, the rest quiet, all of them dismissing the dialog. */
    private Node button(Modal.Button spec) {
        Color base = spec.isDefault() ? ACCENT : BTN;
        Color hover = spec.isDefault() ? ACCENT_HOVER : BTN_HOVER;
        Color pressed = spec.isDefault() ? ACCENT_PRESSED : BTN_PRESSED;
        Node b = gui.text(spec.label())
                .height(Length.dp(32))
                .padding(Length.ZERO, Length.dp(18))
                .background(base)
                .corner(Length.rem(0.5f))
                .border(Length.rem(0.1f), LINE)
                .textSize(Length.rem(0.95f)).textColor(INK)
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .lit(true).elevation(Length.rem(0.35f));
        gui.onState(b, state -> {
            b.background(switch (state) {
                case NORMAL -> base;
                case HOVER -> hover;
                case PRESSED -> pressed;
            });
            b.elevation(state == InteractionState.PRESSED ? Length.ZERO : Length.rem(0.35f));
        });
        gui.onClick(b, () -> dismiss(spec));
        return b;
    }

    /** The dialog's width: what it asked for, or one that reads comfortably for a sentence or two. */
    private static int width(Modal modal) {
        return modal.width() > 0 ? modal.width() : WIDTH;
    }

    /**
     * The dialog's height: what it asked for, or enough for the message it carries. A guess — the text is not
     * measured until the window exists, and a window cannot be created after it is drawn — so it counts explicit
     * line breaks and estimates wrapping from an average advance. Wrong by a line either way is a little empty
     * space or a little scrolling, and {@link Modal#size} is there for the dialog that cannot tolerate either.
     */
    private static int height(Modal modal) {
        if (modal.height() > 0) {
            return modal.height();
        }
        int perLine = Math.max(1, (width(modal) - 2 * 18) / CHAR_WIDTH);
        int lines = 0;
        for (String paragraph : modal.message().split("\n", -1)) {
            lines += Math.max(1, (paragraph.length() + perLine - 1) / perLine);
        }
        return TITLE_BAR + PADDING + Math.max(1, lines) * LINE_HEIGHT + BUTTON_ROW;
    }
}
