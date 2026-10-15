package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import sibarum.tactroller.api.Key;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A button — and, with {@link #toggle}, a chip: a button that stays pressed.
 *
 * <h2>Why a button is a widget after all</h2>
 * {@code docs/plans/todo.md} §4 keeps painted controls out of this package, and a button <em>looks</em> like the
 * most painted control there is. What it carries is the part every hand-rolled one leaves out: a button that is
 * a text node with a click handler cannot be pressed from the keyboard. Enter and Space have to be claimed at
 * {@link ClaimScope#FOCUSED}, so that a button holding focus takes the key and a text field beside it does not
 * lose one; a disabled button has to leave the focus order altogether, not merely ignore a click; and a chip's
 * pressed state has to be one value that the label, the colour and the callback agree about. Four applications
 * writing four calls each wrote the first two wrong. This is the found-in-use case the package doc names for
 * {@link Toggle} and {@link ColorPicker}, and it is here for the same reason.
 *
 * <h2>Kinds are hierarchy, not decoration</h2>
 * A screen has at most one {@link Kind#PRIMARY}: the thing that will happen if the user does nothing but agree.
 * It is the {@code ACTION} fill, and an application chooses what colour that is once, in its palette. A
 * {@link Kind#SECONDARY} is an outlined alternative and {@link Kind#GHOST} is chrome with no outline at all. No
 * kind names a colour, so a look change is an edit to the theme and not to every button.
 *
 * <h2>A chip is a button that holds a value</h2>
 * {@link #toggle(boolean)} makes the button remember whether it is pressed. {@link #pressed(boolean)} acts as the
 * user would and tells {@link #onToggle}; {@link #show(boolean)} displays a value the model chose and tells
 * nobody, for the reason {@link Toggle#show} gives — a panel re-reading its model must not report the sync as an
 * edit.
 *
 * <p>Nothing here changes size on hover or press. The label, padding and border width are the same in every
 * state; only colours move.
 *
 * <p>Handlers run on a worker thread, like every other widget's.
 */
public final class Button {

    /** How much a button is asking for. */
    public enum Kind {
        /** The one thing to do: a filled control. */
        PRIMARY,
        /** An alternative, outlined. */
        SECONDARY,
        /** Chrome: no fill and no outline until it is touched. */
        GHOST
    }

    private static final Shortcut ENTER = Shortcut.of(Key.ENTER);
    private static final Shortcut SPACE = Shortcut.of(Key.SPACE);

    private final Gui gui;
    private final Node node;
    private volatile Kind kind = Kind.SECONDARY;
    private volatile boolean enabled = true;
    private volatile boolean toggle;
    private volatile boolean pressed;
    private volatile InteractionState state = InteractionState.NORMAL;
    private volatile Runnable onPress = () -> { };
    private volatile Consumer<Boolean> onToggle = v -> { };
    /** What plays a ripple on each press, or null for none. */
    private volatile Cues ripples;
    /** The label's colour as last painted: a ripple is drawn in it, so it reads on whatever the fill is. */
    private volatile Role inkRole = Role.INK;
    /** How much of the label's colour a ripple starts at: enough to see, little enough to read the label through. */
    private static final float RIPPLE_STRENGTH = 0.22f;
    /** The way back from each press of a chip — a chip holds a value, so it undoes as a {@link Toggle} does. */
    private final ValueUndo<Boolean> undo;

    /** A {@link Kind#SECONDARY} button saying {@code label}. */
    public Button(Gui gui, String label) {
        this.gui = gui;
        // Padding and a hairline border are on every kind, filled or not, so switching kind or pressing a chip
        // never moves the label by the width of a border that appeared.
        this.node = gui.text(label).role("button")
                .textSize(Length.rem(0.8125f))
                .wordWrap(false)
                .padding(Length.rem(0.35f), Length.rem(0.8f))
                .corner(Length.rem(0.4f));
        gui.focusable(node, true);
        gui.cursor(node, CursorShape.POINTER);
        gui.onClick(node, (ClickEvent e) -> {
            NodeLayout at = node.layout();
            if (at.present()) {
                inkFrom(e.x() - at.rect().x(), e.y() - at.rect().y());
            }
            activate();
        });
        gui.onState(node, s -> {
            this.state = s;
            paint();
        });
        gui.claim(node, ENTER, ClaimScope.FOCUSED, this::pressFromKeys);
        gui.claim(node, SPACE, ClaimScope.FOCUSED, this::pressFromKeys);
        paint();
        this.undo = new ValueUndo<>(gui, node, () -> pressed, this::set, false);
    }

    /** The node to place in a layout. */
    public Node node() {
        return node;
    }

    /** Set how much this button asks for. */
    public Button kind(Kind kind) {
        this.kind = kind;
        paint();
        return this;
    }

    /** Change the label. */
    public Button label(String label) {
        node.text(label);
        return this;
    }

    /**
     * Enable or disable. A disabled button ignores a click, ignores Enter and Space, and — the part a click
     * handler's early return does not give — leaves the focus order, so Tab does not stop on something that
     * cannot be used. All of that is the node's {@code disabled}, which the dispatcher enforces for every
     * control alike; what is the button's own is how it looks disabled.
     */
    public Button enabled(boolean enabled) {
        this.enabled = enabled;
        node.disabled(!enabled);
        paint();
        return this;
    }

    /** Whether the button can be used. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Acknowledge each press with a {@link Cue#ripple} played through {@code cues}: from the point the pointer
     * pressed, or from the middle for Enter or Space. The ink is the label's own colour, faint, so it reads on every
     * kind of button the theme can make. {@code null}, or {@link Cues#none()} for reduced motion, presses with no
     * ripple, which is the default; the button stays clock-free either way, since the timing is the cues'.
     */
    public Button ripple(Cues cues) {
        this.ripples = cues;
        return this;
    }

    /** Make this a chip: a button that stays pressed, and reports through {@link #onToggle}. */
    public Button toggle(boolean toggle) {
        this.toggle = toggle;
        node.role(toggle ? "togglebutton" : "button");
        paint();
        return this;
    }

    /** Whether a chip is pressed. Always false for a plain button. */
    public boolean pressed() {
        return pressed;
    }

    /** Press or release a chip as the user would, telling {@link #onToggle}. No effect on a plain button. */
    public Button pressed(boolean value) {
        if (toggle && enabled) {
            flip(value);
        }
        return this;
    }

    /**
     * Show a pressed state the model chose, <b>without</b> notifying {@link #onToggle}. A sync is not an edit —
     * see {@link Toggle#show}.
     */
    public Button show(boolean value) {
        this.pressed = toggle && value;
        paint();
        return this;
    }

    /** React to a click, Enter or Space. Runs on a worker thread. Not called for a chip; see {@link #onToggle}. */
    public Button onPress(Runnable handler) {
        this.onPress = handler == null ? () -> { } : handler;
        return this;
    }

    /** React to a chip flipping. Runs on a worker thread. */
    public Button onToggle(Consumer<Boolean> handler) {
        this.onToggle = handler == null ? v -> { } : handler;
        return this;
    }

    /**
     * Make this button's command an <b>edit</b>: each press asks {@code command} for the {@link Change} it makes —
     * built then, against whatever is selected and open at that moment — and performs it through the history the
     * tree binds above the button, so Ctrl+Z takes it back (docs/plans/undo.md, step 7). A null answer does nothing:
     * the command has nothing to do this time.
     *
     * <p>Replaces {@link #onPress}, and is the form to reach for whenever the press changes the user's data. The
     * framework cannot invert a {@link Runnable}, so a command written as one is a command that cannot be undone;
     * written as a change, it states the doing and the undoing once, and they cannot disagree. {@code onPress} stays
     * for what is not an edit — opening a window, starting a search. The change applies on the handler thread,
     * under the history's lock, so keep it to model work, as {@link Change} asks.
     */
    public Button perform(Supplier<? extends Change> command) {
        java.util.Objects.requireNonNull(command, "command");
        return onPress(() -> {
            Change change = command.get();
            if (change == null) {
                return;
            }
            History history = gui.historyFor(node);
            if (history != null) {
                history.perform(change);
            } else {
                change.apply();   // a tree that binds no history: still done, only not undoable
            }
        });
    }

    /** Whether a chip's presses are undoable: on by default. Off for a chip that is not an edit, a filter or a view. */
    public Button undoable(boolean undoable) {
        undo.enabled(undoable);
        return this;
    }

    /** Enter or Space: a press with no point, so a ripple from the middle. */
    private void pressFromKeys() {
        NodeLayout at = node.layout();
        if (at.present()) {
            inkFrom(at.rect().w() * 0.5f, at.rect().h() * 0.5f);
        }
        activate();
    }

    /** A ripple from {@code (x, y)} in the button's own frame, if ripples are on and the button can be pressed. */
    private void inkFrom(float x, float y) {
        Cues cues = ripples;
        if (cues == null || !enabled) {
            return;
        }
        Color ink = gui.theme().color(inkRole);
        cues.play(node, Cue.ripple(Color.withAlpha(ink, ink.a() * RIPPLE_STRENGTH), x, y));
    }

    private void activate() {
        if (!enabled) {
            return;
        }
        if (toggle) {
            flip(!pressed);
        } else {
            onPress.run();
        }
    }

    /** A chip pressed or released by the user, or by something acting as the user: a step to undo. */
    private void flip(boolean value) {
        boolean was = pressed;
        set(value);
        undo.edited(was, value);
    }

    private void set(boolean value) {
        this.pressed = value;
        paint();
        onToggle.accept(value);
    }

    private void paint() {
        Theme theme = gui.theme();
        InteractionState s = enabled ? state : InteractionState.NORMAL;
        Role fill;
        Role ink;
        Role edge;
        if (!enabled) {
            fill = Role.NONE;
            ink = Role.FAINT;
            edge = kind == Kind.GHOST ? Role.NONE : Role.LINE;
        } else if (toggle && pressed) {
            // Pressed is the accent — the same colour selection is — over a wash of it, so a row of chips reads by
            // silhouette: the pressed ones are the ones with colour in them.
            fill = Role.HIGHLIGHT;
            ink = Role.ACCENT;
            edge = Role.ACCENT;
        } else if (kind == Kind.PRIMARY) {
            fill = Role.ACTION;
            ink = Role.ON_ACTION;
            edge = Role.NONE;
        } else if (kind == Kind.SECONDARY) {
            fill = Role.RAISED;
            ink = Role.INK;
            edge = Role.EDGE;
        } else {
            fill = Role.NONE;
            ink = Role.DIM;
            edge = Role.NONE;
        }
        // The wash a hover puts on a control comes from the theme's own ladder, so a button needs no colour of its
        // own for it. The ghost has nothing to lift, so it borrows the chrome step.
        boolean lit = s == InteractionState.HOVER || s == InteractionState.PRESSED;
        if (lit && kind == Kind.GHOST && !(toggle && pressed)) {
            fill = Role.CHROME;
            ink = Role.INK;
        }
        node.background(theme.color(fill, s));
        node.textColor(theme.color(ink));
        this.inkRole = ink;
        node.border(Length.dp(1), theme.color(edge));
    }
}
