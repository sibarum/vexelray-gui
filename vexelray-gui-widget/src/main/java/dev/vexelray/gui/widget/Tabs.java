package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.input.MenuSink;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * A tabbed panel: a row of headers over a stack of pages, one shown at a time.
 *
 * <p><b>Pages are hidden, never removed.</b> That is the load-bearing choice. Rebuilding the content by removing
 * and inserting children would look equivalent and is not: registrations are keyed by node id and released when a
 * node leaves the tree, so a {@link TextField} on a page would come back inert — still drawn, no longer able to
 * receive a keystroke. Hiding keeps identity, and with it the handlers, the focusability and the widget state, so
 * switching away and back returns a page exactly as it was, caret and all.
 *
 * <p>Selection is ordinary framework machinery rather than anything new: headers are clickable, so they take the
 * pointer cursor by inference (§8.3); they are focusable, so Tab reaches them; and each claims Left/Right at
 * {@link ClaimScope#FOCUSED} while it holds focus, so the arrow keys walk the bar without core knowing what a tab
 * is. Nothing here reaches past a {@link Node} handle.
 */
public final class Tabs {

    private final Gui gui;
    private final Node root;
    private final Node bar;
    private final Node pages;
    private final List<Node> headers = new ArrayList<>();
    private final List<Node> bodies = new ArrayList<>();

    private volatile int selected = -1;
    private volatile IntConsumer onSelect = i -> { };
    private volatile TabMenu contextMenu = (index, menu) -> { };
    private volatile TabTransition transition = TabTransition.NONE;

    /** The two pages a transition currently has hold of, or null when the panel is at rest. Guarded by {@code this}. */
    private Node fadingOut;
    private Node fadingIn;


    /**
     * What an application adds to a tab's context menu, told which tab was right-clicked. A separate type rather
     * than a {@code BiConsumer<Integer, MenuSink>} because the index is an {@code int} and the pair reads better
     * named: {@code (index, menu) -> menu.item("Duplicate", () -> duplicate(index))}.
     */
    @FunctionalInterface
    public interface TabMenu {
        /** Add to the menu for tab {@code index}. Runs on a worker thread, at the moment of the click. */
        void build(int index, MenuSink menu);
    }

    /**
     * The motion of one tab change: the outgoing page is on its way out, the incoming one is already visible, and
     * for the duration both occupy the whole content area — the outgoing one floats over the incoming one, so
     * nothing reflows while they overlap and the pointer passes straight through the one that is leaving.
     *
     * <p>Call {@code done} when the outgoing page may be hidden. Until then the panel is mid-transaction; after
     * it, {@code Tabs} restores the resting state and a second call is ignored, so a transition that finishes
     * after it was superseded is harmless rather than a page that never comes back.
     *
     * <p><b>The transition surface is the visual-transform layer</b> (architecture.md §7) — today, {@code opacity}.
     * {@code Tabs} resets those, along with the float and hit-inertness it applied itself, whenever it returns to
     * rest, including when a fast second click cuts a transition short. That is the whole reason the surface is
     * named: every property on it has an identity value the framework can restore without being told. A
     * transition that reaches outside it — animating a page's own background, say — owns putting that back.
     *
     * <p>Nothing here mentions time, which is what keeps this module free of any clock: a {@code Tabs} in a test
     * with no frame loop behaves exactly like one in a running application that never installed a transition.
     */
    @FunctionalInterface
    public interface TabTransition {

        /** Move the pages. Safe to call {@code done} inline, from a frame loop, or from another thread. */
        void run(Change change, Runnable done);

        /**
         * Change tabs instantly — the default, and the shape the reduced-motion setting collapses to. A panel
         * that never installs a transition does exactly what it did before there were any.
         */
        TabTransition NONE = (change, done) -> done.run();
    }

    /**
     * The two pages of one tab change, and — the part that is not obvious and that only the panel knows — which
     * of them the renderer paints last.
     *
     * <p>Paint order is child order, decided when the pages were added and never changed afterwards, because
     * reordering means removing and re-inserting and that releases every registration on the page (see
     * {@link #remove}). There is no z-index to reach for either: overlays in this framework are ordinary
     * last-children, which is what buys "no second tree and no z-order bookkeeping". So the arriving page is on
     * top when the selection moves <em>right</em> along the bar and underneath when it moves left, and a
     * transition that assumed one of those would be compositing backwards half the time.
     *
     * <p>Which matters because of how alpha composites. With the top layer at {@code a} over the bottom at
     * {@code b}, what reaches the screen is {@code a·top + (1-a)·b·bottom + (1-a)(1-b)·backdrop}. That last term
     * is the whole design problem, and it admits no clean solution: it vanishes only when {@code a} or {@code b}
     * is exactly 1, so <b>a transition cannot both fade the two pages and keep them covering the box</b>.
     *
     * <p>Neither horn is free. Holding the bottom page opaque gives a perfect {@code p·In + (1-p)·Out} blend —
     * but only if the top page actually covers it, and a page that is just text covers nothing, so the page
     * underneath stays at full strength for the whole transition and then vanishes the instant it is hidden.
     * Fading both never snaps, but lets the backdrop through in between. What makes the second the better trade
     * here is that the backdrop is no longer an accident: the panel paints its own content surface, so the term
     * resolves to a deliberate, stationary colour a shade off the pages themselves rather than to whatever the
     * application happened to put behind the widget.
     *
     * <p>{@link #crossfade} therefore fades both and does not consult the paint order at all — which is what
     * makes the two directions the same transition. What {@code incomingOnTop} is still for is
     * <em>orientation</em>: it is exactly "the selection moved right along the bar", which is the direction
     * {@link #slide} sends the arriving page in from.
     */
    public record Change(Node outgoing, Node incoming, boolean incomingOnTop) { }

    /**
     * Where the time comes from: drive {@code progress} from 0 to 1 and then call {@code done}. Sole purpose is
     * to keep this module clock-free — {@code vexelray-gui-krono} can satisfy it with
     * {@code (p, done) -> krono.ramp(ms(160), Ease.OUT_CUBIC, p, done)} without either module naming the other,
     * because the only types crossing the seam are a {@link DoubleConsumer} and a {@link Runnable}.
     */
    @FunctionalInterface
    public interface Ramp {
        /**
         * Drive {@code progress} over [0,1] and call {@code done} once at the end. Deliver both endpoints
         * exactly: a ramp sampled only at frame boundaries is already a frame into its duration by the first
         * one, so a consumer that never sees 0 starts by jumping to wherever that first sample landed.
         */
        void run(DoubleConsumer progress, Runnable done);
    }

    /**
     * How much faster the leaving page goes than the arriving one comes: it is gone by {@code 1/LEAVE_RATE} of
     * the transition, while the arriving page takes all of it.
     *
     * <p>Not symmetric, and deliberately. A page leaving and a page arriving are not the same event to watch —
     * the one you are done with should get out of the way, and the one you asked for should take its time
     * appearing. Fading them at matching rates is the obvious thing and it reads as the old page loitering,
     * because for the whole first half it is still the more solid of the two.
     *
     * <p>What it costs is the backdrop. Src-over leaves {@code (1-a)(1-b)} of the panel's content surface on
     * screen, and clearing the leaving page early means neither is near opaque around the crossing point: the
     * leak peaks at {@code 1 - 1/(2·LEAVE_RATE) - ...} — 50% here at the halfway mark, against 10% for a leaving
     * page that holds on and drops late. That is the whole trade, and it is a judgement about what reads better
     * rather than a number to minimise. It is bounded, symmetric in both directions, and this is the dial.
     */
    private static final float LEAVE_RATE = 2f;

    /**
     * A dissolve from one page to the other, timed by {@code ramp}: the arriving page fades up across the whole
     * transition while the leaving one clears in a fraction of it. No layout runs for the duration, and nothing
     * is created or destroyed.
     *
     * <p>Both endpoints are exact: at 0 the outgoing page is untouched, and at 1 the incoming one is — and the
     * outgoing one reached zero long before it is hidden, so hiding it is the end of a fade rather than a cut.
     * That last part is not free; see the clock seam on why the end value has to be presented before the
     * consumer tears down, and {@link Change} for the compositing that decides everything in between.
     *
     * <p>Neither rate depends on which page the renderer paints last, so the transition looks the same in both
     * directions. It did not always: holding the page underneath opaque made the timing depend on paint order,
     * which meant moving left and moving right along the bar were visibly different transitions for no reason
     * anyone could have named.
     *
     * <p><b>Give this ramp a linear ease.</b> Easing exists for something arriving at a <em>place</em>, where
     * decelerating into it reads as weight; a dissolve has no place to arrive at, and the eye reads opacity
     * about as it is given. An {@code OUT_CUBIC} ramp is 87% faded at the halfway point of its own duration, so
     * the visible part of the transition finishes in the first third and the remainder is a stall with nothing
     * moving — which does not read as a slow fade, it reads as a delay followed by a jump. The failure is
     * invisible to a test that only checks the endpoints, because the endpoints are perfect either way.
     */
    public static TabTransition crossfade(Ramp ramp) {
        return (change, done) -> {
            dissolve(change, 0f);
            ramp.run(p -> dissolve(change, clamp(p)), done);
        };
    }

    /** How far the arriving page travels, in multiples of its own em. */
    private static final float TRAVEL = 1.25f;

    /**
     * The dissolve, with the arriving page travelling a short distance into place — from the side you moved
     * toward, so the bar's left-to-right order is something the content agrees with rather than merely labels.
     *
     * <p><b>Why motion and not more fade.</b> A dissolve alone has nothing in it that moves, and things in the
     * world do not dissolve, they travel — which is most of what makes a pure crossfade read as mechanical
     * however well it is timed. A short displacement is the cheapest thing that reads as physical, and 1.25em is
     * deliberately small: far enough to have a direction, near enough that nothing appears to fly.
     *
     * <p><b>Two curves, and that is the point.</b> The fade stays linear, because opacity has no place to arrive
     * at and the eye reads it about as it is given. The travel is eased out, because displacement <em>does</em>
     * have a place to arrive at and decelerating into it is what reads as weight. Easing was the wrong answer for
     * the fade and is the right one here; running both off one ramp with one curve is what makes a transition
     * feel like a slideshow.
     *
     * <p><b>Only the arriving page moves.</b> The leaving one holds still and dissolves underneath. Sliding it
     * out as well is the more obvious "swipe", and it would drag a strip of bare panel across the content area
     * behind its trailing edge — the leaving page is what covers the box while the arriving one is still faint,
     * so it is the one thing here that cannot go anywhere.
     */
    public static TabTransition slide(Ramp ramp) {
        return (change, done) -> {
            // The arriving page enters from the side you moved toward; leftward selections come from the left.
            float from = change.incomingOnTop() ? TRAVEL : -TRAVEL;
            dissolve(change, 0f);
            change.incoming().translate(from, 0f);
            ramp.run(p -> {
                float t = clamp(p);
                dissolve(change, t);
                // Out-cubic: most of the distance is covered early and it settles into place rather than
                // stopping. The fade underneath stays linear — see above.
                float eased = 1f - (1f - t) * (1f - t) * (1f - t);
                change.incoming().translate(from * (1f - eased), 0f);
            }, done);
        };
    }

    private static float clamp(double p) {
        return (float) Math.max(0d, Math.min(1d, p));
    }

    /**
     * Set both pages' opacity for progress {@code t}: the arriving page fades up across the whole duration, the
     * leaving one clears {@link #LEAVE_RATE} times faster. Stated in terms of arriving and leaving rather than
     * top and bottom, so the transition does not depend on paint order and looks the same both ways along the
     * bar.
     */
    private static void dissolve(Change change, float t) {
        change.incoming().opacity(t);
        change.outgoing().opacity(Math.max(0f, 1f - t * LEAVE_RATE));
    }

    /** Build an empty tab panel; add pages with {@link #add}. */
    public Tabs(Gui gui) {
        this.gui = gui;
        ContextMenu.presentOn(gui);   // every header gets a Close menu; something has to be able to show it
        this.bar = gui.row().width(Length.FILL).height(Length.rem(2.25f)).background(gui.theme().color(Role.CHROME))
                .gap(Length.dp(2)).alignItems(AlignItems.STRETCH).scroll(false, false);
        // The content area is a surface, not a hole. Two reasons, and they arrived independently.
        //
        // It looks right: the bar and the area under it are one piece of chrome framing the content, so a page
        // that is just text reads as sitting in the panel rather than floating on whatever happens to be behind
        // the whole widget. CHROME is the role for exactly this — "the frame around the content: a title bar, a
        // tab bar" — so the two halves of the frame name the same surface instead of matching by coincidence.
        //
        // And it is what makes a transition well defined. Blending two pages is only meaningful against a known
        // backdrop; with none, a page that does not paint its own background has nothing behind it but whatever
        // the application put there, and a dissolve either bleeds through to it or refuses to fade at all. The
        // wrapper is that backdrop, and it is opaque, and it never moves.
        this.pages = gui.column().width(Length.FILL).height(Length.FILL)
                .background(gui.theme().color(Role.CHROME))
                .corner(Length.ZERO, Length.rem(0.5f))    // flat where it meets the bar, rounded where it ends
                // And it is where the content area ends, which a page sliding through it has to be told. A
                // translated child adds no overflow, so the automatic clipping that comes with scrolling would
                // never notice one leaving.
                .clip(true);
        this.root = gui.column().width(Length.FILL).height(Length.FILL).children(bar, pages);
    }

    /** The node to place in a layout. */
    public Node node() {
        return root;
    }

    /** The index of the selected tab, or -1 when there are none. */
    public int selected() {
        return selected;
    }

    /** How many tabs there are. */
    public int count() {
        return headers.size();
    }

    /** React to selection changes (including the first one). Runs on the handler executor. */
    public Tabs onSelect(IntConsumer handler) {
        this.onSelect = handler == null ? i -> { } : handler;
        return this;
    }

    /**
     * Add a page under {@code title}. {@code body} is placed in the content area and hidden until selected; the
     * first page added is selected automatically, so a freshly built panel is never blank.
     */
    public Tabs add(String title, Node body) {
        int index = headers.size();
        Node header = gui.text(title)
                .height(Length.FILL)
                .padding(Length.dp(6), Length.dp(14))
                .textSize(Length.rem(1))
                .textColor(gui.theme().color(Role.DIM))
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .corner(Length.rem(0.5f), Length.ZERO)   // tab silhouette: rounded shoulders, flat seat
                .background(gui.theme().color(Role.PANEL));

        // Handlers resolve the header to its index at event time, not add time: tabs can be removed, so a
        // baked-in index would aim every surviving closure one tab off. The node's identity is the stable key.
        gui.onClick(header, () -> select(headers.indexOf(header)));
        gui.focusable(header, true);
        // Arrow keys walk the bar while a header holds focus. A claim rather than a key handler, so it preempts
        // anything else bound to those chords for exactly as long as this header is focused.
        gui.claim(header, Shortcut.of(Key.LEFT), ClaimScope.FOCUSED, () -> select(headers.indexOf(header) - 1));
        gui.claim(header, Shortcut.of(Key.RIGHT), ClaimScope.FOCUSED, () -> select(headers.indexOf(header) + 1));
        // Hover shading, except on the selected tab, which keeps its active colour.
        gui.onState(header, state -> header.background(background(headers.indexOf(header), state)));
        // The one thing every tab bar's menu has, and the same index-at-event-time rule as the handlers above:
        // Close aims at wherever this header sits when the item is chosen, not where it sat when it was built.
        gui.onContextMenu(header, menu -> {
            int at = headers.indexOf(header);
            menu.item("Close", () -> remove(at));
            contextMenu.build(at, menu);
        });

        bar.append(header);
        pages.append(body.width(Length.FILL).height(Length.FILL).visible(false));
        headers.add(header);
        bodies.add(body);

        if (selected < 0) {
            select(0);
        }
        return this;
    }

    /**
     * Install the motion for a tab change. The default is {@link TabTransition#NONE} — instant, and identical to
     * how the panel behaved before transitions existed, so this is opt-in and the reduced-motion path is simply
     * not calling it.
     */
    public Tabs transition(TabTransition transition) {
        this.transition = transition == null ? TabTransition.NONE : transition;
        return this;
    }

    /**
     * Show tab {@code index}, clamped into range so an arrow key at either end simply stays put. No node is
     * created, destroyed, or re-registered: the change is prop writes, and with no transition installed it is the
     * same two it always was.
     *
     * <p>With one installed the panel is briefly mid-transaction, and the rule that keeps that from turning into
     * a mess is that <b>a new selection settles the previous one first</b>. The alternative — letting a stale
     * transition finish on its own terms — loses a page: click A→B→C quickly and the callback for A→B arrives
     * after C is showing, with no honest thing left for it to do. Settling first means the only page any
     * in-flight transition can still be holding is one this call is about to take charge of anyway.
     */
    public synchronized Tabs select(int index) {
        int next = Math.max(0, Math.min(headers.size() - 1, index));
        if (headers.isEmpty() || next == selected) {
            return this;
        }
        // Before anything else, and in particular before the incoming page is shown: settling hides the page that
        // was on its way out, and going back to it (A→B then B→A mid-fade) would otherwise hide the one arriving.
        settle();

        int previous = selected;
        selected = next;
        if (previous >= 0) {
            headers.get(previous).background(gui.theme().color(Role.PANEL))
                    .textColor(gui.theme().color(Role.DIM)).lit(false).elevation(Length.ZERO);
        }
        Node incoming = bodies.get(next).visible(true);
        // The active tab is physically forward: lit and floating a little above the bar the idle tabs sit flush in.
        // The bar restyles instantly either way — the transition is the pages moving, and a header that faded
        // along with them would leave the click the user just made unacknowledged for the length of the motion.
        headers.get(next).background(gui.theme().color(Role.SELECTION))
                .textColor(gui.theme().color(Role.ACCENT)).lit(gui.theme().lit()).elevation(Length.rem(0.25f));

        if (previous >= 0) {
            Node outgoing = bodies.get(previous);
            fadingOut = outgoing;
            fadingIn = incoming;
            // Lift the outgoing page over the incoming one so both fill the content area at once without the
            // column splitting it between them, and make it pointer-transparent: a page on its way out must not
            // be what a click lands on, however solid it still looks. Its size is left alone — a floated FILL
            // fills its parent, so the page keeps the size the panel gave it when it was added.
            outgoing.floatAt(Length.ZERO, Length.ZERO).hitInert(true);
            // Both pages go pointer-transparent for the duration, not just the one leaving. A translated node is
            // still hit where layout put it, so a page in motion is a page whose pointer target disagrees with
            // where it is drawn — and rather than let the two differ, neither takes the pointer until they stop.
            // Which is also the better behaviour: a moving target is not something to ask anyone to click.
            incoming.hitInert(true);
            // Paint order is child order, and `bodies` is in that order, so the arriving page is on top exactly
            // when the selection moved right along the bar. Floating does not change this — a floating node is an
            // ordinary child for painting, which is the whole reason overlays here are last-children.
            transition.run(new Change(outgoing, incoming, next > previous), this::settle);
        }
        int delivered = next;
        gui.handlers().execute(() -> onSelect.accept(delivered));
        return this;
    }

    /**
     * Return both pages of a transition to rest: the outgoing one hidden, back in flow, opaque and hittable
     * again; the incoming one opaque. Idempotent, and that is the whole interruption story — a {@code done} from
     * a transition that has already been superseded finds nothing to settle and does nothing, so it needs no
     * generation token and a transition never has to learn that it lost.
     */
    private synchronized void settle() {
        Node out = fadingOut;
        Node in = fadingIn;
        fadingOut = null;
        fadingIn = null;
        if (out != null) {
            out.unfloat().hitInert(false).opacity(1f).translate(0f, 0f).visible(false);
        }
        if (in != null) {
            in.hitInert(false).opacity(1f).translate(0f, 0f);
        }
    }

    /** Rename tab {@code index}'s header (e.g. an editor tab following a save-as). */
    public Tabs title(int index, String title) {
        if (index >= 0 && index < headers.size()) {
            headers.get(index).text(title);
        }
        return this;
    }

    /**
     * Remove tab {@code index}: header and body leave the tree via {@link Node#remove()} — the one real removal
     * primitive. (Re-setting the parents' child lists with {@code children(...)} would be wrong twice over: that
     * method only <em>appends</em>, and an insert of an already-parented node leaves it in both parents, so the
     * survivors would render duplicated.) Removal releases every input registration in both subtrees, so the
     * page's widgets come back dead, not dormant — the caller closes them, it cannot re-home them. Selection
     * moves to the nearest surviving tab.
     */
    public synchronized Tabs remove(int index) {
        if (index < 0 || index >= headers.size()) {
            return this;
        }
        // A page about to leave the tree must not still be a transition's outgoing half: settling first puts both
        // pages back in a state the removal can reason about, whichever of them is the one going.
        settle();
        headers.remove(index).remove();
        bodies.remove(index).remove();

        int previous = selected;
        selected = -1;   // force select() to restyle: surviving indices shifted under the old value
        if (!headers.isEmpty()) {
            select(Math.min(previous > index ? previous - 1 : previous, headers.size() - 1));
        }
        return this;
    }

    /**
     * Add to the context menu of every tab. Close comes first, then this — sources accumulate, so an application
     * contributes "Close others", "Duplicate", "Pin" without restating the one the bar already knows how to do.
     */
    public Tabs onContextMenu(TabMenu source) {
        this.contextMenu = source == null ? (index, menu) -> { } : source;
        return this;
    }

    /** The header node for tab {@code index} -- package-private, so a test can aim at it rather than guess. */
    Node header(int index) {
        return headers.get(index);
    }

    /** Move focus to the selected header — the entry point for driving the bar from the keyboard. */
    public Tabs focus() {
        if (selected >= 0) {
            gui.focus(headers.get(selected));
        }
        return this;
    }

    private Color background(int index, InteractionState state) {
        // The selected tab keeps its active fill whatever the pointer does; every other tab is a panel, shaded by
        // the theme rather than by a second constant per state.
        return index == selected
                ? gui.theme().color(Role.SELECTION)
                : gui.theme().color(Role.PANEL, state);
    }
}
