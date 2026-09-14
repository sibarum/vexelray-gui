package dev.vexelray.gui.core.nav;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.model.RetainedNode;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Named places in a tree, and taking the user to one — landmarks, revealers, and the walks in flight
 * (docs/reference/navigation.md).
 *
 * <p><b>A name for a node, not for a route.</b> Nothing about an address says which tab, which branch or how far
 * down the page the node is: the route is derived, every time, from the tree as it stands. Move the node and
 * every link to it still works.
 *
 * <p>Everything a walk does, it does through the commands the widgets already expose — the same {@code select},
 * {@code expand} and {@code scrollIntoView} a click ends up calling — so a page arrived at this way is in
 * exactly the state it would be in had the user clicked their way there, handlers and all. It is not synthesised
 * input: input needs a place on screen to aim at, and half of what navigation does is make the target have one.
 *
 * <p>What it needs of the tree it asks for through {@link Tree}, so it holds no reconciler, no mutation sink and
 * no {@code Gui} — four methods, stubbable in a test.
 */
public final class Navigator {

    /** How many frames a walk may take before it gives up. A walk that cannot finish must not run for ever. */
    private static final int MAX_NAV_FRAMES = 240;

    /**
     * What a walk needs of the tree it is walking. An interface rather than the objects behind it, so a
     * navigator can be built without a reconciler or a mutation sink.
     *
     * <p>The two commands are named here rather than reached through a node handle. A handle would have been the
     * shorter seam and the wrong one: {@code Node}'s constructor is package-private to {@code core}, so a seam
     * shaped that way can only be implemented by {@code Gui} — which is precisely the coupling the extraction is
     * for. Saying what a walk <em>does</em> leaves the seam implementable by anyone, a test included.
     */
    public interface Tree {

        /** The retained node with this id, or {@code null} if the tree does not have it (yet). */
        RetainedNode node(long id);

        /** Bring the node inside every scrolling ancestor's viewport. */
        void scrollIntoView(long id);

        /** Give the node the keyboard. */
        void focus(long id);

        /** A handle on the node, to hand to whoever was waiting for the arrival. */
        Node arrived(long id);
    }

    /**
     * Named places in this tree, and the reverse map so a node leaving takes its name with it. A landmark is a
     * name for a node, not for a position: the node may be reparented, restyled or rebuilt around and the name
     * still means it.
     */
    private final ConcurrentMap<String, Long> landmarks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> landmarkNames = new ConcurrentHashMap<>();

    /** How each concealing container un-conceals a descendant (see {@link #reveals}). */
    private final ConcurrentMap<Long, Reveal> revealers = new ConcurrentHashMap<>();

    /** Navigations in flight, advanced one step per frame. Added from any thread, stepped on the GUI thread. */
    private final Queue<Walk> walks = new ConcurrentLinkedQueue<>();

    /**
     * The name this window is registered under ({@code GuiApp.window(key, ...)}), so an {@link Address} that
     * names a window can be told from one meant for somebody else. Blank until an application says.
     */
    private volatile String windowKey = Address.ANY_WINDOW;

    private final Tree tree;
    private final Atchung bus;
    private final Consumer<String> wake;

    /**
     * @param tree what a walk asks of the tree it is walking
     * @param bus  where an address for another window is published
     * @param wake how to ask for a frame, since a walk advances one step per frame and nothing else may be
     *             happening
     */
    public Navigator(Tree tree, Atchung bus, Consumer<String> wake) {
        this.tree = tree;
        this.bus = bus;
        this.wake = wake == null ? why -> { } : wake;
    }

    // --- naming a place -----------------------------------------------------------------------------------

    /**
     * Name a place in this tree. From then on that name is an address — {@link #navigate} brings the user to it,
     * a hyperlink in a document can point at it, a macro can step through it, and a test can ask to be taken
     * there rather than knowing how to get there.
     *
     * <p>Names are the application's to organise; dotted paths read well and sort well, and nothing here parses
     * them. A name may not contain {@code '/'} — that separates the window from the landmark in an
     * {@link Address} — and re-using a name rebinds it, so a rebuilt panel naming its parts again is not an
     * error. The binding is released with the node, like every other registration keyed by node id.
     */
    public void landmark(String name, long id) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("landmark name must not be blank");
        }
        if (name.indexOf(Address.SEPARATOR) >= 0) {
            throw new IllegalArgumentException("landmark name must not contain '" + Address.SEPARATOR + "': " + name);
        }
        Long previous = landmarks.put(name, id);
        if (previous != null && previous != id) {
            landmarkNames.remove(previous, name);
        }
        landmarkNames.put(id, name);
    }

    /** Forget the landmark called {@code name}, if there is one. */
    public void clearLandmark(String name) {
        Long id = landmarks.remove(name);
        if (id != null) {
            landmarkNames.remove(id, name);
        }
    }

    /** The node named by {@code name}, if this tree has that landmark. */
    public Optional<Node> landmarkNode(String name) {
        Long id = landmarks.get(name);
        return id == null ? Optional.empty() : Optional.of(tree.arrived(id));
    }

    /** The landmark name this node was registered under, or {@code ""}. Read by the semantic read-model. */
    public String landmarkName(long id) {
        return landmarkNames.getOrDefault(id, "");
    }

    /**
     * Declare how a node makes a descendant of it reachable — the seam that lets navigation cross a tab panel, a
     * collapsed branch or a drawer without knowing what any of those are. See {@link Reveal}, which is where the
     * reasoning lives; the widgets that conceal things register their own.
     */
    public void reveals(long id, Reveal reveal) {
        if (reveal == null) {
            revealers.remove(id);
        } else {
            revealers.put(id, reveal);
        }
    }

    /** Everything keyed by a node id is dropped when the node leaves the tree. */
    public void forget(long id) {
        revealers.remove(id);
        String name = landmarkNames.remove(id);
        if (name != null) {
            landmarks.remove(name, id);
        }
    }

    // --- going there --------------------------------------------------------------------------------------

    /**
     * Navigate to {@code address}. An address naming a different window is <b>published</b> rather than walked,
     * so it reaches that window (and, through {@code GuiApp}, opens it if it is closed); one naming this window
     * or no window at all is walked here.
     *
     * <p>Takes frames rather than returning done; see {@link Navigation} for why, and for the arrival.
     */
    public Navigation navigate(Address address) {
        if (address.windowNamed() && !address.window().equals(windowKey)) {
            Navigation forwarded = new Navigation(address);
            bus.publish(NavTopics.GO, address);
            // Somebody else's destination: this tree cannot say when it arrives, and pretending otherwise would
            // hand back a future that never completes. The request is on the bus, which is the whole promise.
            forwarded.arrival().completeExceptionally(new Navigation.Unreachable(
                    "forwarded to window '" + address.window() + "'; that window reports its own arrival"));
            return forwarded;
        }
        Navigation nav = new Navigation(address);
        walks.add(new Walk(nav));
        wake.accept("navigation requested");
        return nav;
    }

    /** A navigation request off the bus. Accepted only if it is for this window and this tree has the landmark. */
    public void accept(Address address) {
        if (address.windowNamed() && !address.window().equals(windowKey)) {
            return;
        }
        if (!landmarks.containsKey(address.landmark())) {
            return;   // not ours; another window on this bus owns that name, or nobody does
        }
        walks.add(new Walk(new Navigation(address)));
    }

    /**
     * Advance every navigation in flight by one step, dropping the ones that finished.
     *
     * @return whether any walk is still working, so the caller knows to ask for another frame
     */
    public boolean step() {
        if (walks.isEmpty()) {
            return false;
        }
        boolean working = false;
        for (Iterator<Walk> it = walks.iterator(); it.hasNext(); ) {
            if (it.next().step()) {
                it.remove();
            } else {
                working = true;
            }
        }
        return working;
    }

    /** Whether any navigation is in flight. */
    public boolean walking() {
        return !walks.isEmpty();
    }

    /** The name this window is known by, so addresses that name a window can be routed. */
    public void windowKey(String key) {
        this.windowKey = key == null ? Address.ANY_WINDOW : key;
    }

    /** The name this window is known by, or blank if nothing has said. */
    public String windowKey() {
        return windowKey;
    }

    /**
     * One navigation, advanced a step per frame.
     *
     * <p>The steps are: ask each concealing ancestor to reveal the target, outermost first and one per frame
     * (each reveal changes what the next one is looking at); then reveal it to the scrollers and focus it; then,
     * one frame later, report arrival — so "arrived" means the user can see it, not that the last command has
     * been issued.
     */
    private final class Walk {

        private final Navigation nav;
        /** Ancestors already asked, so a container that declined is not asked again every frame. */
        private final Set<Long> asked = new HashSet<>();
        private int frames;
        /** Set once the target has been scrolled to and focused; the next step reports arrival. */
        private boolean settling;

        Walk(Navigation nav) {
            this.nav = nav;
        }

        /** @return whether this walk is finished and should be dropped. */
        boolean step() {
            if (nav.done()) {
                return true;
            }
            if (++frames > MAX_NAV_FRAMES) {
                return fail("gave up after " + MAX_NAV_FRAMES + " frames");
            }
            Long id = landmarks.get(nav.address().landmark());
            if (id == null) {
                return false;   // the tree may not have been built yet; the frame budget ends this if it never is
            }
            RetainedNode target = tree.node(id);
            if (target == null) {
                return false;   // named, but not in the tree yet
            }
            if (settling) {
                nav.arrival().complete(tree.arrived(id));
                return true;
            }
            for (RetainedNode a : ancestorsOutermostFirst(target)) {
                Reveal reveal = revealers.get(a.id);
                if (reveal == null || !asked.add(a.id)) {
                    continue;
                }
                if (reveal.reveal(id)) {
                    return false;   // it acted: let the frame apply and lay out what it did before going on
                }
            }
            if (concealed(target)) {
                return fail("still hidden after every container that could reveal it was asked — the container "
                        + "concealing it declares no Reveal (see Reveal's class note)");
            }
            tree.scrollIntoView(id);
            tree.focus(id);
            settling = true;
            wake.accept("navigation arriving");
            return false;
        }

        private boolean fail(String why) {
            nav.arrival().completeExceptionally(
                    new Navigation.Unreachable("cannot navigate to " + nav.address() + ": " + why));
            return true;
        }
    }

    /** {@code target}'s ancestors, root first — the order reveals must run in. */
    private static List<RetainedNode> ancestorsOutermostFirst(RetainedNode target) {
        List<RetainedNode> chain = new ArrayList<>();
        for (RetainedNode a = target.parent; a != null; a = a.parent) {
            chain.add(a);
        }
        Collections.reverse(chain);
        return chain;
    }

    private static boolean concealed(RetainedNode target) {
        for (RetainedNode n = target; n != null; n = n.parent) {
            if (!n.visible()) {
                return true;
            }
        }
        return false;
    }
}
