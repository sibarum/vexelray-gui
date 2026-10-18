package dev.vexelray.gui.keymap;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Trigger;

import java.util.List;

/**
 * One thing the application can do from the keyboard, by name: {@code file.save}, "Save". Declared on a
 * {@link Keymap} with its default chords, handled with {@link #on}, and from then on reached by whatever chords
 * the user has given it — the handler never learns which, and never has to be told when they change.
 *
 * <p>The id is the stable part: it is what a settings file stores, so renaming the label is free and renaming
 * the id forgets the user's choice. Ids are dotted words — {@code [A-Za-z0-9._-]}.
 *
 * <p><b>Where it applies.</b> An action applies application-wide unless {@link #within} says otherwise, which is
 * how a chord is rebindable while still meaning one thing in an editor and another outside it: the claim is made
 * on the node at the scope given, so it outranks and is outranked exactly as the node's own claims are. Two
 * actions in one place never share a chord ({@link Keymap#bind}); in different places they may, and the narrower
 * one wins while it applies, as every claim does.
 */
public final class Action {

    private final Keymap keymap;
    private final String id;
    private final String label;
    private final List<Trigger> defaults;
    private volatile Runnable handler;
    private volatile Keymap.Context context = Keymap.Context.APPLICATION;

    Action(Keymap keymap, String id, String label, List<Trigger> defaults) {
        this.keymap = keymap;
        this.id = id;
        this.label = label;
        this.defaults = defaults;
    }

    /** The stable name, the one a settings file stores. */
    public String id() {
        return id;
    }

    /** What a person calls it. */
    public String label() {
        return label;
    }

    /** The chords the application gave it. */
    public List<Trigger> defaults() {
        return defaults;
    }

    /** The chords that reach it now — the user's, where they changed them, otherwise the defaults. */
    public List<Trigger> chords() {
        return keymap.chords(this);
    }

    /** Whether the user has changed this action's chords, so there is something for a reset to undo. */
    public boolean customized() {
        return keymap.overrides().value().has(id);
    }

    /**
     * What it does. Runs on a worker thread, like every claim. Until an action has a handler its chords are not
     * claimed at all, so a declared but unimplemented action never swallows a key.
     */
    public Action on(Runnable handler) {
        this.handler = handler;
        keymap.sync();
        return this;
    }

    /**
     * Apply only where {@code node} is — while it holds focus ({@link ClaimScope#FOCUSED}), while it is in the
     * tree ({@link ClaimScope#VISIBLE}) — rather than application-wide. The claim is the node's, in {@code gui},
     * so it ranks exactly as that node's own claims do; and rebinding releases the node's claim on the old chord.
     *
     * @throws IllegalStateException if another action already defaults to one of this one's chords there
     */
    public Action within(Gui gui, Node node, ClaimScope scope) {
        Keymap.Context next = new Keymap.Context(gui, node, scope);
        keymap.relocate(this, next);
        return this;
    }

    /** Run the handler now, as its chord would — for the menu item or button that does the same thing. */
    public void run() {
        Runnable h = handler;
        if (h != null) {
            h.run();
        }
    }

    boolean handled() {
        return handler != null;
    }

    Keymap.Context context() {
        return context;
    }

    void context(Keymap.Context context) {
        this.context = context;
    }

    @Override
    public String toString() {
        return id;
    }
}
