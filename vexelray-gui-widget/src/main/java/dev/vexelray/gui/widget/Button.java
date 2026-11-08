package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Addressed;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.Menu;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.Key;

import java.util.function.Consumer;

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
 * user would and publishes {@link #TOGGLED}; {@link #show(boolean)} displays a value the model chose and tells
 * nobody, for the reason {@link Toggle#show} gives — a panel re-reading its model must not report the sync as an
 * edit.
 *
 * <p>Nothing here changes size on hover or press. The label, padding and border width are the same in every
 * state; only colours move.
 *
 * <p><b>What it does is a message.</b> A press is published on {@link #PRESSED} and a chip's state on {@link #TOGGLED},
 * each carrying the button's {@link #address() address}; a view hears them in the frame they happened
 * ({@code gui.mailbox(Button.PRESSED, save.address(), p -> ...)}), and logic hears them like any other message
 * (vexelray-framework's {@code docs/threading.md}, T3.8).
 *
 * <p><b>A menu button</b> ({@link #menu}) drops a declared menu instead: <em>Open with</em>, <em>Sort by</em>, an
 * overflow. Each line sends what it was declared with, as a context menu's does.
 */
public final class Button {

    /** A plain button pressed, by a click, Enter or Space: every press, in order. An {@link IconButton}'s too. */
    public static final Topic<Pressed> PRESSED = Addressed.edge(Pressed.class);

    /** Whether a chip is pressed, when the user (or {@link #pressed(boolean)}) changes it. The last in a frame. */
    public static final Topic<Toggled> TOGGLED =
            Addressed.state(Toggled.class, Toggled::button);

    /** The button at {@code button} was pressed. */
    public record Pressed(String button) implements Addressed {
        @Override
        public Object address() {
            return button;
        }
    }

    /** The chip at {@code button} is now {@code pressed}, or not. */
    public record Toggled(String button, boolean pressed) implements Addressed {
        @Override
        public Object address() {
            return button;
        }
    }

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
    /** The words, inside {@link #node}. */
    private final Node label;
    /** The badge at the far end, hidden while empty. */
    private final Node badge;
    private volatile Kind kind = Kind.SECONDARY;
    private volatile boolean enabled = true;
    private volatile boolean toggle;
    private volatile boolean pressed;
    /** What a press drops, or {@link Menu#NONE} for a button that publishes {@link #PRESSED}. */
    private volatile Menu menu = Menu.NONE;
    private volatile InteractionState state = InteractionState.NORMAL;
    private final WidgetName name;
    /** The widget library's own hook on a press, for a widget built from buttons; see {@link #onPress}. */
    private volatile Runnable onPress = () -> { };
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
        // A row holding the words and the badge, so the badge is inside what is pressed, lit and focused. The words
        // grow, so a button wider than its words keeps its badge at the far end. Both are the button's own: nothing
        // outside styles their text, and how big a button's words are is the theme's.
        this.label = gui.text(label).textSize(Length.rem(0.8125f)).wordWrap(false).hitInert(true)
                .width(Length.grow(1f));
        this.badge = gui.text("").textSize(Length.rem(0.6875f)).wordWrap(false).hitInert(true)
                .padding(Length.rem(0.1f), Length.rem(0.45f)).corner(Length.rem(0.6f)).visible(false);
        this.node = gui.row().role("button")
                .alignItems(AlignItems.CENTER)
                .gap(Length.rem(0.5f))
                .scroll(false, false)
                .padding(Length.rem(0.35f), Length.rem(0.8f))
                .corner(Length.rem(0.4f))
                .children(this.label, this.badge);
        this.name = new WidgetName(gui, "button", node);
        gui.focusable(node, true);
        gui.cursor(node, CursorShape.POINTER);
        Seams.of(gui).onClick(node, (ClickEvent e) -> {
            NodeLayout at = node.layout();
            if (at.present()) {
                inkFrom(e.x() - at.rect().x(), e.y() - at.rect().y());
            }
            activate(e);
        });
        // A second quick press is a second press, and the double click it also makes is the button's own: left to
        // bubble, it reached whatever the button sits in (a viewer's stage, which a double click puts full screen).
        Seams.of(gui).onDoubleClick(node, () -> { });
        Seams.of(gui).onState(node, s -> {
            this.state = s;
            paint();
        });
        Seams.of(gui).claim(node, ENTER, ClaimScope.FOCUSED, this::pressFromKeys);
        Seams.of(gui).claim(node, SPACE, ClaimScope.FOCUSED, this::pressFromKeys);
        paint();
        this.undo = new ValueUndo<>(gui, node, () -> pressed, this::set, false);
    }

    /** The node to place in a layout. */
    public Node node() {
        return node;
    }

    /** Call this button {@code name} in its tree ("save"): its {@link #address()} is the tree's name and this. */
    public Button name(String name) {
        this.name.name(name);
        return this;
    }

    /** Where this button is on the bus: what its {@link Pressed} and {@link Toggled} carry. */
    public String address() {
        return name.address();
    }

    /** Set how much this button asks for. */
    public Button kind(Kind kind) {
        this.kind = kind;
        paint();
        return this;
    }

    /** Change the label. */
    public Button label(String label) {
        this.label.text(label);
        return this;
    }

    /**
     * Show {@code badge} at the button's far end: a count, a number, a short state, on a small pill that is part of
     * the button, pressed, lit and focused with it, and accent while a chip is pressed. Empty takes it away.
     */
    public Button badge(String badge) {
        String text = badge == null ? "" : badge;
        this.badge.text(text).visible(!text.isEmpty());
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

    /**
     * Make this a menu button: a click, Enter or Space drops {@code menu} from the button's lower left, as a menu bar's
     * menu drops, and a second press closes it. The menu is declared as a context menu is ({@link Gui#menu}): each
     * line sends its own message, so a press publishes no {@link #PRESSED}. A key opens it with its first line under
     * the keyboard. Declare it again when what it holds changes; {@link Menu#NONE} makes it a plain button again.
     * Ignored on a chip, whose press is its value.
     */
    public Button menu(Menu menu) {
        this.menu = java.util.Objects.requireNonNull(menu, "menu");
        return this;
    }

    /** Make this a chip: a button that stays pressed, and says so on {@link #TOGGLED}. */
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

    /** Press or release a chip as the user would, publishing {@link #TOGGLED}. No effect on a plain button. */
    public Button pressed(boolean value) {
        if (toggle && enabled) {
            flip(value);
        }
        return this;
    }

    /**
     * Show a pressed state the model chose, <b>without</b> publishing {@link #TOGGLED}. A sync is not an edit —
     * see {@link Toggle#show}.
     */
    public Button show(boolean value) {
        this.pressed = toggle && value;
        paint();
        return this;
    }

    /**
     * The widget library's hook on a press, for a widget built from buttons: run in the dispatch, in order with the
     * input around it, and as well as {@link #PRESSED} rather than instead. Not called for a chip, whose
     * state is {@link #TOGGLED}. Package-private, because an application hears a button by its message.
     */
    Button onPress(Runnable handler) {
        this.onPress = handler == null ? () -> { } : handler;
        return this;
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
        activate(null);
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

    /** A press: {@code at} is the click, or null for a key. */
    private void activate(ClickEvent at) {
        if (!enabled) {
            return;
        }
        Menu drops = menu;
        if (toggle) {
            flip(!pressed);
        } else if (!drops.isEmpty()) {
            ContextMenu.drop(gui, node, at, drops);
        } else {
            onPress.run();
            gui.bus().publish(PRESSED, new Pressed(address()));
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
        gui.bus().publish(TOGGLED, new Toggled(address(), value));
    }

    private void paint() {
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
        node.background(fill.at(s));
        label.textColor(ink);
        boolean picked = enabled && toggle && pressed;
        badge.background(picked ? Role.ACCENT : Role.LINE);
        badge.textColor(!enabled ? Role.FAINT : picked ? Role.PAGE : Role.DIM);
        this.inkRole = ink;
        node.border(Length.dp(1), edge);
    }
}
