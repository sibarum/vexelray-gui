package dev.vexelray.gui.keymap;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Gesture;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.input.Trigger;
import sibarum.atchung.Committer;
import sibarum.atchung.State;
import sibarum.atchung.Subscription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * The action mapper: an application's keyboard commands, by name, with chords the user can change.
 *
 * {@snippet :
 * Keymap keys = new Keymap(gui).persist(AppHome.of("editor").settings());
 * keys.action("file.save", "Save", Shortcut.of(Key.S, Modifier.CONTROL)).on(this::save);
 * keys.action("file.save-as", "Save As", Shortcut.of(Key.S, Modifier.CONTROL, Modifier.SHIFT)).on(this::saveAs);
 * }
 *
 * That is the whole of the integration. Each line replaces a {@code gui.shortcut(...)}: the action is claimed on
 * the bus exactly as that shortcut was, at {@link ClaimScope#GLOBAL} unless {@link Action#within} narrows it, so
 * a focused field still outranks it and Ctrl+S still means save. What changes is that the chord is no longer the
 * code's to fix. A {@link KeymapEditor} rebinds it, the claim moves, and the handler never hears about it.
 *
 * <h2>One chord, one action, per place</h2>
 * Within one place — application-wide, or one node at one scope — a chord belongs to at most one action. That
 * is the invariant, and the operations keep it rather than the user: {@link #bind} <em>takes</em> a chord from
 * whichever action in the same place held it, and says which ({@link Bound#displaced}), so the editor can tell
 * the person what moved. Two defaults in one place sharing a chord is a mistake in the code and is refused when
 * declared. Across places chords may repeat, and the narrower place wins while it applies — Tab can indent in an
 * editor and move focus everywhere else, both rebindable.
 *
 * <h2>What is stored</h2>
 * Only what the user changed ({@link Overrides}), so a default fixed in a later version still arrives. It is
 * bus state ({@link #overrides()}), a line-per-action text form ({@link #text()} / {@link #load(String)}) for an
 * application that keeps profiles of its own, and {@link #persist(Settings)} for one that just wants it kept.
 *
 * <h2>Windows</h2>
 * Keys are focal: a chord reaches only the focused window's {@link Gui}. An application that wants its commands
 * from every window {@link #attach}es each one, as it would have bound its shortcuts on each. One keymap, one set
 * of chords, every window.
 *
 * <p>Thread-safe. Writes are serialised on the keymap; handlers run on workers, as claims always do.
 */
public final class Keymap {

    /** Where {@link #persist(Settings)} keeps the overrides unless told otherwise. */
    public static final String SETTINGS_KEY = "keymap";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]+");

    /** The outcome of a {@link #bind}: the actions that lost a chord to it, in declaration order. */
    public record Bound(List<Action> displaced) {
        public Bound {
            displaced = List.copyOf(displaced);
        }
    }

    /**
     * Where an action applies. {@link #APPLICATION} is every attached window, claimed on a node of the keymap's
     * own so its claims never disturb anybody else's on the root; anything else is one node in one tree.
     */
    record Context(Gui gui, Node node, ClaimScope scope) {

        static final Context APPLICATION = new Context(null, null, ClaimScope.GLOBAL);

        @Override
        public boolean equals(Object o) {
            // A tree by identity, a node by id: Node is a handle, and two handles to one node are one place.
            return o instanceof Context c && c.gui == gui && c.scope == scope
                    && (node == null ? c.node == null : c.node != null && c.node.id() == node.id());
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(gui) * 31 + Long.hashCode(node == null ? -1L : node.id())
                    + scope.ordinal();
        }
    }

    /** One claim this keymap holds on the bus. */
    private record Claimed(Gui gui, Node node, ClaimScope scope, Trigger chord, Action action) {
    }

    private final List<Action> actions = new CopyOnWriteArrayList<>();
    private final Map<String, Action> byId = new HashMap<>();
    /** Each attached tree, and the node the application-wide claims are made on in it. */
    private final Map<Gui, Node> anchors = new IdentityHashMap<>();
    private final State<Overrides> overrides;
    private final Committer<Overrides, Overrides> replace;
    private Set<Claimed> claimed = Set.of();
    private Subscription persisting;

    /** A keymap whose application-wide actions apply in {@code gui}. {@link #attach} adds more windows. */
    public Keymap(Gui gui) {
        State.Builder<Overrides> builder = State.of(Overrides.NONE);
        // Absolute rather than relative, unlike most mutations here, and safely so: every write is computed and
        // committed under this keymap's lock, because a rebind is a decision about the whole map (what it takes,
        // and from whom), not an edit that composes with a concurrent one.
        this.replace = builder.mutation("replace", (current, next) -> next);
        this.overrides = builder.build();
        attach(gui);
    }

    // ------------------------------------------------------------------ declaring

    /**
     * Declare an action: its stable id, what a person calls it, and the chords it has until the user says
     * otherwise (none is fine — an action can start unbound and be given a chord by the user). Give it a handler
     * with {@link Action#on}.
     *
     * @throws IllegalArgumentException if the id is taken or is not dotted words ({@code [A-Za-z0-9._-]})
     * @throws IllegalStateException    if an application-wide action already defaults to one of these chords
     */
    public synchronized Action action(String id, String label, Trigger... defaults) {
        if (!validId(id)) {
            throw new IllegalArgumentException("an action id is dotted words ([A-Za-z0-9._-]+); got '" + id + "'");
        }
        if (byId.containsKey(id)) {
            throw new IllegalArgumentException("action '" + id + "' is already declared");
        }
        Action a = new Action(this, id, label == null || label.isBlank() ? id : label,
                List.copyOf(new LinkedHashSet<>(List.of(defaults))));
        refuseSharedDefaults(a, Context.APPLICATION);
        actions.add(a);
        byId.put(id, a);
        sync();
        return a;
    }

    /** The action declared as {@code id}, if any. */
    public synchronized Optional<Action> action(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Every action, in the order declared — the order an editor lists them in. */
    public List<Action> actions() {
        return List.copyOf(actions);
    }

    /**
     * Have application-wide actions apply in {@code gui} too — another window of the same application. Keys go
     * only to the focused window, so a command meant from everywhere has to be claimed everywhere.
     */
    public synchronized Keymap attach(Gui gui) {
        if (!anchors.containsKey(gui)) {
            // A node of the keymap's own, never placed in the tree. GLOBAL claims apply whether or not their node
            // is present, and owning the node is what lets a rebind release exactly this keymap's claim on a
            // chord — the root's claims include the application's undo chords, and releasing Ctrl+Z there would
            // take those with it.
            anchors.put(gui, gui.box().role("keymap"));
            sync();
        }
        return this;
    }

    synchronized void relocate(Action a, Context context) {
        refuseSharedDefaults(a, context);
        a.context(context);
        sync();
    }

    private void refuseSharedDefaults(Action a, Context context) {
        for (Action b : actions) {
            if (b == a || !b.context().equals(context)) {
                continue;
            }
            for (Trigger s : a.defaults()) {
                if (b.defaults().contains(s)) {
                    throw new IllegalStateException(s.text() + " is the default of both '" + b.id() + "' and '"
                            + a.id() + "' in one place; a chord reaches one action there, so one of them needs "
                            + "another default (or none)");
                }
            }
        }
    }

    // ------------------------------------------------------------------ reading

    /** What the user changed, as bus state — {@code keymap.overrides().onCommitLatest(...)} to follow it. */
    public State<Overrides> overrides() {
        return overrides;
    }

    /** The chords that reach {@code action} now. */
    public List<Trigger> chords(Action action) {
        List<Trigger> chords = effective(overrides.value()).get(action);
        return chords == null ? List.of() : chords;
    }

    /** The action {@code chord} reaches in the place {@code near} applies, other than {@code near} itself. */
    public Optional<Action> holder(Trigger chord, Action near) {
        Map<Action, List<Trigger>> now = effective(overrides.value());
        for (Action b : actions) {
            if (b != near && b.context().equals(near.context()) && now.get(b).contains(chord)) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }

    /**
     * Each action's chords under {@code o}: its own where the user set them, otherwise its defaults less any that
     * a set action in the same place has taken. Derived every time, never stored, so there is no second copy of
     * the map to fall out of step with the first.
     *
     * <p>The invariant holds here whatever {@code o} says. {@link #bind} never writes two set actions sharing a
     * chord in one place, but a loaded file is anybody's, so where it does, the action declared first keeps it.
     */
    private Map<Action, List<Trigger>> effective(Overrides o) {
        Map<Context, Set<Trigger>> taken = new HashMap<>();
        Map<Action, List<Trigger>> out = new IdentityHashMap<>();
        for (Action a : actions) {
            if (o.has(a.id())) {
                Set<Trigger> place = taken.computeIfAbsent(a.context(), c -> new HashSet<>());
                out.put(a, o.get(a.id()).stream().filter(place::add).toList());
            }
        }
        for (Action a : actions) {
            if (!o.has(a.id())) {
                Set<Trigger> gone = taken.getOrDefault(a.context(), Set.of());
                out.put(a, a.defaults().stream().filter(s -> !gone.contains(s)).toList());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ changing

    /**
     * Give {@code action} exactly {@code chords} (empty: unbound), taking each from whichever action in the same
     * place held it. Binding an action back to precisely its defaults forgets that it was ever changed.
     */
    public synchronized Bound bind(Action action, List<Trigger> chords) {
        Overrides o = overrides.value();
        Map<Action, List<Trigger>> now = effective(o);
        List<Trigger> list = List.copyOf(new LinkedHashSet<>(chords));
        Overrides next = settle(o.with(action.id(), list), action, list);
        List<Action> displaced = new ArrayList<>();
        for (Action b : actions) {
            if (b == action || !b.context().equals(action.context())) {
                continue;
            }
            List<Trigger> had = now.get(b);
            List<Trigger> kept = had.stream().filter(s -> !list.contains(s)).toList();
            if (kept.size() != had.size()) {
                next = settle(next.with(b.id(), kept), b, kept);
                displaced.add(b);
            }
        }
        commit(next);
        return new Bound(displaced);
    }

    /** A chord list equal to the defaults is no change at all, and is stored as none. */
    private static Overrides settle(Overrides o, Action a, List<Trigger> chords) {
        return chords.equals(a.defaults()) ? o.without(a.id()) : o;
    }

    /** Take every chord from {@code action}. */
    public Bound unbind(Action action) {
        return bind(action, List.of());
    }

    /** Put {@code action} back on its defaults — taking them back from anything that has them now. */
    public Bound reset(Action action) {
        return bind(action, action.defaults());
    }

    /**
     * Every declared action back on its defaults. Entries for actions this run does not know are kept: they are
     * some other build's, and resetting this one is not a decision about those.
     */
    public synchronized Keymap resetAll() {
        Overrides next = overrides.value();
        for (Action a : actions) {
            next = next.without(a.id());
        }
        commit(next);
        return this;
    }

    // ------------------------------------------------------------------ text and settings

    /** What the user changed, one line per action ({@link Overrides#text()}). */
    public String text() {
        return overrides.value().text();
    }

    /** Replace what the user changed with {@code text}, as {@link #text()} wrote it. Unreadable lines are skipped. */
    public Keymap load(String text) {
        return load(Overrides.parse(text));
    }

    /** Replace what the user changed with {@code o} — a profile switch. */
    public synchronized Keymap load(Overrides o) {
        commit(o == null ? Overrides.NONE : o);
        return this;
    }

    /** Load what {@code settings} holds under {@link #SETTINGS_KEY}. */
    public Keymap load(Settings settings) {
        return load(settings, SETTINGS_KEY);
    }

    /** Load what {@code settings} holds under {@code key}. A missing key is nothing changed. */
    public Keymap load(Settings settings, String key) {
        return load(Overrides.parse(settings.getList(key)));
    }

    /** Write what the user changed into {@code settings} under {@link #SETTINGS_KEY}, and save it. */
    public Keymap save(Settings settings) {
        return save(settings, SETTINGS_KEY);
    }

    /** Write what the user changed into {@code settings} under {@code key}, and save it. */
    public Keymap save(Settings settings, String key) {
        settings.putList(key, overrides.value().lines()).save();
        return this;
    }

    /** {@link #persist(Settings, String)} under {@link #SETTINGS_KEY}. */
    public Keymap persist(Settings settings) {
        return persist(settings, SETTINGS_KEY);
    }

    /**
     * Load from {@code settings} now, and save into it on every change from here on — the one call an
     * application that just wants the user's keys kept makes. A rebind is rare and small, and a key map lost to a
     * crash is a person redoing their setup, so it is written as it happens rather than at exit. Calling it again
     * moves the keymap to the new store.
     */
    public synchronized Keymap persist(Settings settings, String key) {
        if (persisting != null) {
            persisting.close();
        }
        load(settings, key);
        persisting = overrides.onCommitLatest(committed -> save(settings, key));
        return this;
    }

    // ------------------------------------------------------------------ claims

    private void commit(Overrides next) {
        overrides.commitIfChanged(replace, next);
        sync();
    }

    /**
     * Make the bus agree with the map: claim what should be claimed and release what should not, touching only
     * the difference so an unchanged binding is never released and re-claimed under a key the user is holding.
     */
    synchronized void sync() {
        Map<Action, List<Trigger>> now = effective(overrides.value());
        Set<Claimed> want = new LinkedHashSet<>();
        for (Action a : actions) {
            if (!a.handled()) {
                continue;
            }
            Context c = a.context();
            for (Trigger chord : now.get(a)) {
                if (c.equals(Context.APPLICATION)) {
                    anchors.forEach((gui, anchor) -> want.add(
                            new Claimed(gui, anchor, ClaimScope.GLOBAL, chord, a)));
                } else {
                    want.add(new Claimed(c.gui(), c.node(), c.scope(), chord, a));
                }
            }
        }
        // Release before claiming: a chord that moved from one action to another in the same place is one
        // release and one claim on the same node, and the other order would release the new claim.
        for (Claimed old : claimed) {
            if (!want.contains(old)) {
                old.gui().releaseClaim(old.node(), old.chord());
            }
        }
        for (Claimed w : want) {
            if (!claimed.contains(w)) {
                Action a = w.action();
                w.gui().claim(w.node(), w.chord(), w.scope(), a::run);
            }
        }
        claimed = want;
    }

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }
}
