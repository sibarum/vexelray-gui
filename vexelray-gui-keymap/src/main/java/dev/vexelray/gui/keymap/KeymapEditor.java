package dev.vexelray.gui.keymap;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.FocusEvent;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.input.Trigger;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.IconButton;
import dev.vexelray.gui.widget.TextField;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The control that changes a {@link Keymap}: every action, its chords as chips, and a way to change each one by
 * pressing the new chord. A node like any other, to put wherever the application keeps its preferences —
 * {@link KeymapWindow} is the same control in a window of its own, behind a title-bar instrument.
 *
 * <p><b>Recording takes every key.</b> Clicking a chip (or Enter on it) arms a recorder that holds a
 * {@link Gui#claimAny wildcard claim} while it has focus, so the next chord is <em>recorded</em> rather than
 * performed: Ctrl+S records Ctrl+S instead of saving, and Tab records Tab instead of moving on. Escape alone
 * cancels, and so does focus going anywhere else — a recorder nobody is looking at must not eat the next key.
 * The modifiers being held show as they are pressed, so the person can see the chord forming.
 *
 * <p><b>Taking, not refusing.</b> A chord another action in the same place holds is taken from it, and the line
 * under the list says what moved; the reset beside each action undoes it. A conflict dialog would ask the person
 * to resolve something they have just resolved by pressing the key.
 *
 * <p>Keyboard-complete: Tab walks the chips, Enter or Space records, Delete or Backspace removes. With the
 * pointer, the {@code ×} on a chip removes it and {@code +} adds another. Nothing appears on hover.
 */
public final class KeymapEditor implements AutoCloseable {

    private static final Length TEXT = Length.rem(0.8125f);
    private static final String HINT =
            "Click a chord to change it, × to remove it, + to add another. Esc cancels a recording.";

    private static final Shortcut ENTER = Shortcut.of(Key.ENTER);
    private static final Shortcut SPACE = Shortcut.of(Key.SPACE);
    private static final Shortcut DELETE = Shortcut.of(Key.DELETE);
    private static final Shortcut BACKSPACE = Shortcut.of(Key.BACKSPACE);
    private static final Shortcut ESCAPE = Shortcut.of(Key.ESCAPE);

    /** The keys that type something: letters, digits, space, punctuation, and the keypad's digits and operators. */
    private static final Set<Key> TYPING = typing();

    private static Set<Key> typing() {
        java.util.EnumSet<Key> keys = java.util.EnumSet.range(Key.A, Key.SPACE);
        keys.addAll(java.util.EnumSet.range(Key.NUMPAD_0, Key.NUMPAD_DECIMAL));
        keys.addAll(java.util.EnumSet.range(Key.MINUS, Key.GRAVE_ACCENT));
        return java.util.Collections.unmodifiableSet(keys);
    }

    /** A chip's place: the {@code index}th chord of {@code action}, or its {@code +} when the index is -1. */
    private record Slot(Action action, int index) {
    }

    private final Gui gui;
    private final Keymap keymap;
    private final Node node;
    private final Node list;
    private final Node status;
    private final TextField filter;
    private final List<Node> rows = new ArrayList<>();
    private final Map<Slot, Node> chips = new HashMap<>();
    private final Subscription mapSub;
    private final Subscription heldSub;
    private final Subscription focusSub;

    private volatile String query = "";
    /** The slot being recorded, and the node recording it; both null while nothing is. */
    private Slot recording;
    private Node capture;

    public KeymapEditor(Gui gui, Keymap keymap) {
        this.gui = gui;
        this.keymap = keymap;
        Theme theme = gui.theme();
        this.filter = new TextField(gui).onChange(text -> {
            query = text.strip().toLowerCase(Locale.ROOT);
            render();
        });
        filter.node().role("keymap-filter").width(Length.FILL);
        Button resetAll = new Button(gui, "Reset all").onPress(() -> {
            keymap.resetAll();
            say("Every action is back on its default chords.");
        });
        Node header = gui.row().width(Length.FILL).gap(Length.rem(0.5f)).alignItems(AlignItems.CENTER)
                .children(gui.text("Filter").textSize(TEXT).wordWrap(false).textColor(theme.color(Role.DIM)),
                        filter.node(), resetAll.node());
        this.list = gui.column().role("keymap-actions").width(Length.FILL).height(Length.FILL)
                .gap(Length.rem(0.15f)).scroll(false, true);
        // The status line has its height reserved whether or not it has anything to say, so a message arriving
        // never moves the list above it.
        this.status = gui.text(HINT).role("keymap-status").width(Length.FILL).height(Length.rem(1.5f))
                .textSize(Length.rem(0.75f)).textColor(theme.color(Role.DIM));
        this.node = gui.column().role("keymap-editor").width(Length.FILL).height(Length.FILL)
                .padding(Length.rem(0.75f)).gap(Length.rem(0.5f))
                .children(header, list, status);
        this.mapSub = keymap.overrides().onCommitLatest(committed -> render());
        this.heldSub = gui.modifiers().onCommit(committed -> showHeld(committed.value()));
        this.focusSub = gui.bus().subscribe(gui.focusEvents(), this::focusMoved);
        render();
    }

    /** The node to place in a layout. */
    public Node node() {
        return node;
    }

    /**
     * Rebuild the list from the keymap. Changes to the bindings arrive by themselves; this is for actions
     * declared after the editor was built.
     */
    public KeymapEditor refresh() {
        render();
        return this;
    }

    /** The chip for {@code action}'s {@code index}th chord ({@code -1}: its {@code +}), as last rendered. */
    synchronized Node chip(Action action, int index) {
        return chips.get(new Slot(action, index));
    }

    /** The line under the list. */
    Node status() {
        return status;
    }

    @Override
    public void close() {
        mapSub.close();
        heldSub.close();
        focusSub.close();
        filter.close();
        synchronized (this) {
            disarm();
        }
    }

    // ------------------------------------------------------------------ the list

    private synchronized void render() {
        for (Node row : rows) {
            row.remove();
        }
        rows.clear();
        chips.clear();
        capture = null;
        for (Action a : keymap.actions()) {
            if (matches(a)) {
                rows.add(row(a));
            }
        }
        if (rows.isEmpty()) {
            rows.add(gui.text(keymap.actions().isEmpty() ? "This application has no rebindable actions."
                            : "No action matches “" + query + "”.")
                    .textSize(TEXT).textColor(gui.theme().color(Role.DIM)));
        }
        list.children(rows.toArray(Node[]::new));
        if (capture != null) {
            gui.focus(capture);
        }
    }

    /**
     * Rebuild, and give the keyboard back to the chip at {@code back} — after the person's own command, so the
     * keyboard stays where they were. Only then: a rebuild because the map changed elsewhere, or because they
     * typed in the filter, must not pull focus out of wherever they are.
     *
     * <p>Rendered here even though the change already rendered once on commit, and focused only after this
     * render: focusing during the commit's render would focus a chip this one is about to replace.
     */
    private synchronized void renderFocusing(Slot back) {
        render();
        // A chip that was removed has no successor at its index; the action's + is the next best place.
        Node chip = chips.getOrDefault(back, chips.get(new Slot(back.action(), -1)));
        if (chip != null) {
            gui.focus(chip);
        }
    }

    private boolean matches(Action a) {
        String q = query;
        if (q.isEmpty()) {
            return true;
        }
        if (a.label().toLowerCase(Locale.ROOT).contains(q) || a.id().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        for (Trigger s : a.chords()) {
            if (s.text().toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    private Node row(Action a) {
        Theme theme = gui.theme();
        Node label = gui.text(a.label()).role("keymap-label").width(Length.rem(12)).wordWrap(false)
                .textSize(TEXT).textColor(theme.color(Role.INK));
        Node chords = gui.row().width(Length.FILL).gap(Length.rem(0.3f)).alignItems(AlignItems.CENTER);
        List<Trigger> cs = a.chords();
        for (int i = 0; i < cs.size(); i++) {
            chords.append(slot(new Slot(a, i), cs.get(i)));
        }
        chords.append(slot(new Slot(a, -1), null));
        // Always present and only enabled when there is something to reset, so the column does not shift as
        // actions are changed and changed back.
        IconButton reset = new IconButton(gui, Icon.RESET, "Reset " + a.label())
                .tip("Back to " + (a.defaults().isEmpty() ? "unbound" : texts(a.defaults())))
                .enabled(a.customized())
                .onPress(() -> {
                    Keymap.Bound b = keymap.reset(a);
                    say(a.label() + " is back on " + (a.defaults().isEmpty() ? "no chord" : texts(a.defaults()))
                            + "." + moved(b));
                    renderFocusing(new Slot(a, -1));
                });
        return gui.row().role("keymap-row").width(Length.FILL).alignItems(AlignItems.CENTER)
                .gap(Length.rem(0.5f)).padding(Length.rem(0.2f), Length.rem(0.3f))
                .children(label, chords, reset.node());
    }

    private Node slot(Slot s, Trigger chord) {
        Theme theme = gui.theme();
        if (s.equals(recording)) {
            // A node of its own rather than the chip in another state: the chip claims Enter and Delete by name,
            // and a named claim outranks a wildcard at one scope — so recording on the chip could never record
            // Enter. A fresh node has no claims but the one that records.
            capture = gui.text(Shortcut.prefix(gui.modifiers().value()) + "…").role("keymap-recording")
                    .wordWrap(false).textSize(TEXT).padding(Length.rem(0.15f), Length.rem(0.5f))
                    .corner(Length.rem(0.3f)).background(theme.color(Role.SELECTION))
                    .border(Length.rem(0.1f), theme.color(Role.ACCENT)).textColor(theme.color(Role.INK));
            gui.focusable(capture, true);
            gui.claimAny(capture, ClaimScope.FOCUSED, this::recorded);
            return capture;
        }
        Node chip;
        if (chord == null) {
            chip = gui.text("+").role("keymap-add").wordWrap(false).textSize(TEXT)
                    .padding(Length.rem(0.15f), Length.rem(0.5f)).corner(Length.rem(0.3f))
                    .border(Length.rem(0.1f), theme.color(Role.LINE)).textColor(theme.color(Role.DIM));
        } else {
            Node remove = gui.text("×").role("keymap-remove").wordWrap(false).textSize(TEXT)
                    .textColor(theme.color(Role.DIM));
            gui.onClick(remove, () -> remove(s));
            gui.cursor(remove, CursorShape.POINTER);
            chip = gui.row().role("keymap-chord").alignItems(AlignItems.CENTER).gap(Length.rem(0.4f))
                    .padding(Length.rem(0.15f), Length.rem(0.5f)).corner(Length.rem(0.3f))
                    .background(theme.color(Role.RAISED)).border(Length.rem(0.1f), theme.color(Role.LINE))
                    .children(gui.text(chord.text()).wordWrap(false).textSize(TEXT)
                            .textColor(theme.color(Role.INK)), remove);
            gui.claim(chip, DELETE, ClaimScope.FOCUSED, () -> remove(s));
            gui.claim(chip, BACKSPACE, ClaimScope.FOCUSED, () -> remove(s));
        }
        gui.focusable(chip, true);
        gui.cursor(chip, CursorShape.POINTER);
        gui.onClick(chip, () -> record(s));
        gui.claim(chip, ENTER, ClaimScope.FOCUSED, () -> record(s));
        gui.claim(chip, SPACE, ClaimScope.FOCUSED, () -> record(s));
        chips.put(s, chip);
        return chip;
    }

    // ------------------------------------------------------------------ recording

    private synchronized void record(Slot s) {
        disarm();
        recording = s;
        say("Press the new chord for " + s.action().label() + ", or make the gesture on the touchpad. Esc cancels.");
        render();
    }

    /** The wildcard claim's command: the chord pressed while the recorder held focus. */
    private void recorded(Trigger chord) {
        Slot s;
        synchronized (this) {
            s = recording;
            if (s == null) {
                return;
            }
            disarm();
        }
        if (chord.equals(ESCAPE)) {
            say(HINT);
            renderFocusing(s);
            return;
        }
        Action a = s.action();
        List<Trigger> next = new ArrayList<>(a.chords());
        if (s.index() >= 0 && s.index() < next.size()) {
            next.set(s.index(), chord);
        } else {
            next.add(chord);
        }
        Keymap.Bound b = keymap.bind(a, next);
        say(a.label() + " is now " + (next.isEmpty() ? "unbound" : texts(a.chords())) + "." + moved(b)
                + typingWarning(chord, a));
        // Back to the chip that now says the new chord — for a + that is a new chip, not the + itself.
        renderFocusing(new Slot(a, a.chords().indexOf(chord)));
    }

    private void remove(Slot s) {
        Action a = s.action();
        List<Trigger> next = new ArrayList<>(a.chords());
        if (s.index() < 0 || s.index() >= next.size()) {
            return;
        }
        Trigger gone = next.remove(s.index());
        keymap.bind(a, next);
        say(gone.text() + " no longer reaches " + a.label() + ".");
        renderFocusing(s);
    }

    /** Cancel a recording the user walked away from: focus left the recorder for anywhere else. */
    private void focusMoved(FocusEvent e) {
        synchronized (this) {
            if (e.gained() || capture == null || e.nodeId() != capture.id() || recording == null) {
                return;
            }
            disarm();
        }
        say(HINT);
        render();
    }

    /** Stop recording. The capture node goes with the next render, and its claim with it. */
    private void disarm() {
        if (capture != null) {
            gui.releaseClaimAny(capture);
        }
        recording = null;
    }

    private synchronized void showHeld(Set<Modifier> held) {
        Node c = capture;
        if (c != null && recording != null) {
            c.text(Shortcut.prefix(held) + "…");
        }
    }

    // ------------------------------------------------------------------ the status line

    private void say(String message) {
        status.text(message);
    }

    private static String moved(Keymap.Bound b) {
        if (b.displaced().isEmpty()) {
            return "";
        }
        List<String> names = b.displaced().stream().map(Action::label).toList();
        return " Taken from " + String.join(", ", names) + ".";
    }

    /**
     * A chord with no modifier but Shift is a key that also types. Bound application-wide it is claimed before
     * any field sees it, so the person should hear that once, at the moment they make it — not discover it by
     * failing to type the letter.
     */
    private static String typingWarning(Trigger trigger, Action a) {
        if (!(trigger instanceof Shortcut chord)) {
            return "";   // a gesture types nothing
        }
        Set<Modifier> m = chord.modifiers();
        boolean bare = m.isEmpty() || m.equals(Set.of(Modifier.SHIFT));
        return bare && TYPING.contains(chord.key()) && a.context().equals(Keymap.Context.APPLICATION)
                ? " It has no Ctrl or Alt, so it will fire while typing too." : "";
    }

    private static String texts(List<Trigger> chords) {
        return String.join(" or ", chords.stream().map(Trigger::text).toList());
    }
}
