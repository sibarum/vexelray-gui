package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A column of icons that chooses which panel is on show, and a panel beside it that shows it.
 *
 * <h2>This is not {@link Tabs} turned on its side</h2>
 *
 * A tab bar and a rail answer different questions. Tabs say <em>which of these documents am I looking at</em>, so
 * one is always selected and the bar is part of the content beneath it. A rail says <em>which tool am I holding</em>,
 * so <b>none</b> is a legitimate answer: clicking the selected icon puts the panel away and leaves the rail, which
 * is the whole reason a rail is worth having over a bar — the work is the canvas, and the panel is on loan from it.
 * That difference is a state a tab bar does not have and should not grow, so this is its own widget rather than an
 * orientation flag on that one.
 *
 * <h2>Pages are built once and hidden</h2>
 *
 * A page is a {@link Page} builder rather than a node, and it is built the first time it is shown and kept
 * thereafter — the same choice {@link Tabs} makes for its pages and {@link Popout} for its two hosts, for the
 * same reason. A {@link Node}'s registrations are keyed by node id inside the tree that minted it and released
 * when it leaves, so a page that was removed and rebuilt would come back drawing correctly and unable to take a
 * keystroke. Building lazily is the affordable half of that: a rail with six panels costs one panel's nodes until
 * the others are asked for.
 *
 * <h2>The rail does not move</h2>
 *
 * The icons keep their places whether the panel is open or shut, and the panel takes its space from the content
 * rather than from the rail. Nothing here appears, grows or shifts under the pointer: the icon under the pointer
 * when the panel opens is the icon under it afterwards.
 *
 * <h2>Closing is what the motion seam is for</h2>
 *
 * A panel on its way out has to still be there while it goes, so <b>hiding it cannot be the first thing that
 * happens</b> — which is a problem a rail has and a tab bar never does, because a tab bar has no way to end up
 * showing nothing. {@link #transition} installs the motion: for its duration the panel is on show and the rail is
 * mid-transaction, and the panel is put away only when the motion reports that it is done. {@link #onSelect} is
 * told at the moment the selection changes rather than after the panel has gone, so a handler with something of
 * its own to move is told while the thing it is moving away from is still on screen.
 *
 * <p>With nothing installed the panel appears and goes in one step, which is what a rail did before there was any
 * motion here and is the whole of the reduced-motion path.
 */
public final class Rail {

    private static final Length TILE = Length.rem(1.9f);

    /** Builds a panel's contents, once, into the node it is given. */
    @FunctionalInterface
    public interface Page {
        void build(Gui gui, Node into);
    }

    /**
     * The motion of one change of panel. Three changes are possible, and a transition can tell them apart from
     * {@link Change} alone: the panel <b>opens</b> (there was nothing on show), it <b>closes</b> (there is
     * nothing to put on show), or two pages <b>trade places</b> inside a panel that stays where it is.
     *
     * <p>Which gives the rule the built-ins here follow, and the one an application writing its own should:
     * <b>the panel is what moves when it appears or goes; the pages are what move when they trade places.</b>
     * A panel that faded while its pages faded too would compose the two, and a panel that held still while its
     * contents arrived from nowhere would read as having been there all along.
     *
     * <p>Call {@code done} when the change is over. Until then the rail is mid-transaction; after it, the rail
     * restores the resting state — one page visible, the panel shown or hidden to match the selection — and a
     * second call is ignored, so a transition that finishes after it was superseded is harmless rather than a
     * panel that never comes back. The surface is the <b>visual-transform layer</b> (architecture.md §7):
     * {@code opacity} and {@code translate}, every property of which has an identity value the rail can restore
     * without being told. A transition that reaches outside it owns putting that back.
     *
     * <p>Nothing here mentions time, which is what keeps this module free of any clock — see {@link Ramp}.
     */
    @FunctionalInterface
    public interface PanelTransition {

        /** Move the panel, or its pages. Safe to call {@code done} inline, from a frame loop, or from a thread. */
        void run(Change change, Runnable done);

        /**
         * Change panels instantly — the default, and the shape the reduced-motion setting collapses to. A rail
         * that never installs a transition does exactly what a rail did before there were any.
         */
        PanelTransition NONE = (change, done) -> done.run();
    }

    /**
     * The nodes one change of panel has to work with: the panel itself, the page leaving, and the page arriving.
     *
     * <p>{@code outgoing} is null when the panel was shut and {@code incoming} is null when it is being shut;
     * both are never null, because a change from nothing to nothing is not one. {@link #opening()} and
     * {@link #closing()} say which case this is, so that a transition does not have to remember what the nulls
     * mean.
     *
     * <p>Unlike {@link Tabs.Change} there is no paint order here, and that is not an omission. Tabs carries it
     * because its pages have an order the content is expected to agree with — moving right along the bar sends
     * the arriving page in from the right. A rail's icons are <em>tools</em>, not a sequence, so a swap between
     * two of them has no direction; the direction that does exist is which edge the rail is against, and only the
     * application knows that (see {@link #slide}).
     */
    public record Change(Node panel, Node outgoing, Node incoming) {

        /** Whether this change is a panel appearing rather than one page giving way to another. */
        public boolean opening() {
            return outgoing == null;
        }

        /** Whether this change is the panel being put away. */
        public boolean closing() {
            return incoming == null;
        }
    }

    /**
     * How much faster the leaving page clears than the arriving one comes up, when two pages trade places. It is
     * {@link Tabs}'s rate, and the note on that constant is where the case for it being other than 1 is made.
     */
    private static final float LEAVE_RATE = 2f;

    /**
     * A fade, timed by {@code ramp}: the panel fades up as it opens and down as it closes, and two pages trading
     * places dissolve into each other. Direction-free, so it is correct whichever edge the rail is against and
     * asks nothing of the layout around it — which is why it is the one to reach for first.
     *
     * <p><b>What shows through a closing panel is the application's own canvas</b>, and here that is right.
     * Alpha applies per primitive rather than to the subtree as a composited group (see {@link Node#opacity}),
     * so the panel's surface fades along with the rows on it rather than carrying them. A rail panel is on loan
     * from the work underneath, and the work is what should be coming back. {@link Tabs} had to go the other way
     * and paint its own backdrop, because a tab page is <em>inside</em> a panel that stays.
     *
     * <p><b>Give this ramp a linear ease.</b> Easing is for something arriving at a place; opacity has none, and
     * the eye reads it about as it is given — an {@code OUT_CUBIC} fade is 87% done at its own halfway point, so
     * it reads as a jump followed by a stall rather than as a slow fade.
     */
    public static PanelTransition fade(Ramp ramp) {
        return (change, done) -> {
            dissolve(change, 0f);
            ramp.run(p -> dissolve(change, clamp(p)), done);
        };
    }

    /**
     * The fade, with the panel travelling a short distance as it comes and goes — out from under the rail as it
     * opens and back under it as it closes. Two pages trading places still only dissolve: a rail's tools have no
     * order, so a swap between two of them has no direction to travel in.
     *
     * <p>{@code travel} is in multiples of the panel's own em, and <b>the sign is the application's</b> because
     * only it knows which edge the rail is against: negative when the rail is to the panel's left, so the panel
     * comes out from beneath it, and positive when the rail is on its right. A rem or two is plenty — far enough
     * to have a direction, near enough that nothing appears to fly.
     *
     * <p><b>The panel's parent has to clip</b>, or a panel mid-travel draws over the rail beside it: a
     * translation is a fact about drawing and adds no overflow, so nothing else will notice the panel leaving its
     * box. That parent is the application's layout rather than this widget's, which is the other reason the
     * travel is opt-in.
     *
     * <p><b>Two curves.</b> The fade stays linear. The travel is eased — out on the way in, because a panel
     * arriving has a place to arrive at and decelerating into it is what reads as weight; in on the way out,
     * because a panel leaving has nowhere to be and accelerating away is what reads as leaving rather than as
     * drifting.
     */
    public static PanelTransition slide(Ramp ramp, float travel) {
        return (change, done) -> {
            dissolve(change, 0f);
            if (change.opening()) {
                change.panel().translate(travel, 0f);
            }
            ramp.run(p -> {
                float t = clamp(p);
                dissolve(change, t);
                if (change.opening()) {
                    change.panel().translate(travel * (1f - arrive(t)), 0f);
                } else if (change.closing()) {
                    change.panel().translate(travel * leave(t), 0f);
                }
            }, done);
        };
    }

    /**
     * Set the opacities for progress {@code t}: the panel's when it is appearing or going, the two pages' when
     * they are trading places — see {@link PanelTransition} for why those are not the same nodes.
     */
    private static void dissolve(Change change, float t) {
        if (change.opening()) {
            change.panel().opacity(t);
        } else if (change.closing()) {
            change.panel().opacity(1f - t);
        } else {
            change.incoming().opacity(t);
            change.outgoing().opacity(Math.max(0f, 1f - t * LEAVE_RATE));
        }
    }

    /** Out-cubic: most of the distance covered early, settling into place rather than stopping. */
    private static float arrive(float t) {
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    /** In-cubic, the mirror of {@link #arrive}: away slowly at first, and then gone. */
    private static float leave(float t) {
        return t * t * t;
    }

    private static float clamp(double p) {
        return (float) Math.max(0d, Math.min(1d, p));
    }

    private final Gui gui;
    private final Node bar;
    private final Node panel;
    private final Node panelTitle;
    private final Node panelHint;
    private final Node panelBody;
    private final List<Item> items = new ArrayList<>();
    private volatile String selected;
    private volatile Consumer<String> onSelect = k -> { };
    private volatile PanelTransition transition = PanelTransition.NONE;
    /** The pages a transition has hold of, null where the change it is running has only one. Guarded by {@code this}. */
    private Node fadingOut;
    private Node fadingIn;

    private record Item(String key, String title, Node tile, Node body, Page page, boolean[] built) {
    }

    /** Build an empty rail on {@code gui}; add panels with {@link #item}. */
    public Rail(Gui gui) {
        this.gui = gui;
        Theme theme = gui.theme();
        this.bar = gui.column().role("rail")
                .gap(Length.rem(0.2f))
                .padding(Length.rem(0.35f))
                .corner(Length.rem(0.5f))
                .background(theme.color(Role.CHROME))
                .border(Length.dp(1), theme.color(Role.EDGE))
                .lit(theme.lit()).elevation(theme.elevation(Relief.FLOATING))
                .alignItems(AlignItems.CENTER)
                .scroll(false, false);

        this.panelTitle = gui.text("").width(Length.grow(1f))
                .textSize(Length.rem(0.62f)).wordWrap(false).textColor(theme.color(Role.DIM));
        this.panelHint = gui.text("")
                .textSize(Length.rem(0.6f)).wordWrap(false).textColor(theme.color(Role.FAINT));
        Node close = gui.text("×").size(Length.rem(1.2f), Length.rem(1.2f))
                .corner(Length.rem(0.25f))
                .textColor(theme.color(Role.FAINT))
                .align(dev.vexelray.text.TextLayout.HAlign.CENTER, dev.vexelray.text.TextLayout.VAlign.MIDDLE);
        gui.focusable(close, true);
        gui.cursor(close, CursorShape.POINTER);
        gui.onClick(close, this::close);
        gui.onState(close, s -> close.background(
                theme.color(s == InteractionState.NORMAL ? Role.NONE : Role.SELECTION)));
        Node head = gui.row().role("rail-panel-head")
                .width(Length.grow(1f)).gap(Length.rem(0.5f))
                .padding(Length.rem(0.4f), Length.rem(0.55f))
                .alignItems(AlignItems.CENTER)
                .children(panelTitle, panelHint, close);
        Node headRule = gui.box().width(Length.percent(100)).height(Length.dp(1))
                .background(theme.color(Role.LINE));
        this.panelBody = gui.column().width(Length.grow(1f)).height(Length.grow(1f)).scroll(false, true);
        this.panel = gui.column().role("rail-panel")
                .corner(Length.rem(0.5f))
                .background(theme.color(Role.PANEL))
                .border(Length.dp(1), theme.color(Role.EDGE))
                .lit(theme.lit()).elevation(theme.elevation(Relief.FLOATING))
                .clip(true)
                .visible(false)
                .children(head, headRule, panelBody);
    }

    /**
     * Add a panel.
     *
     * @param key   how the application names this panel — what {@link #select} takes and {@link #onSelect} hands back
     * @param icon  the tile's contents; the application's, because this module has no icon set and should not grow one
     * @param title shown in the panel's heading
     * @param page  builds the panel's contents, the first time it is shown
     */
    public Rail item(String key, Node icon, String title, Page page) {
        Node tile = gui.box().role("rail-item")
                .size(TILE, TILE)
                .corner(Length.rem(0.35f))
                .alignItems(AlignItems.CENTER)
                .children(icon.width(Length.percent(100)).height(Length.percent(100))
                        .align(dev.vexelray.text.TextLayout.HAlign.CENTER,
                                dev.vexelray.text.TextLayout.VAlign.MIDDLE));
        Node body = gui.column().width(Length.grow(1f)).visible(false);
        Item item = new Item(key, title, tile, body, page, new boolean[1]);
        items.add(item);
        gui.focusable(tile, true);
        gui.cursor(tile, CursorShape.POINTER);
        gui.onClick(tile, () -> toggle(key));
        gui.onState(tile, s -> paintTile(item, s == InteractionState.HOVER));
        bar.append(tile);
        panelBody.append(body);
        paintTile(item, false);
        return this;
    }

    /**
     * Add a divider, and below it an icon that runs an action rather than opening a panel — a reset, a fit, a
     * home. It never becomes the selection, so opening it does not put away whatever panel is on show.
     */
    public Rail action(Node icon, Runnable act) {
        Theme theme = gui.theme();
        Node rule = gui.box().width(Length.percent(70)).height(Length.dp(1))
                .margin(Length.rem(0.15f))
                .background(theme.color(Role.LINE));
        Node tile = gui.box().role("rail-action")
                .size(TILE, TILE)
                .corner(Length.rem(0.35f))
                .alignItems(AlignItems.CENTER)
                .children(icon.width(Length.percent(100)).height(Length.percent(100))
                        .align(dev.vexelray.text.TextLayout.HAlign.CENTER,
                                dev.vexelray.text.TextLayout.VAlign.MIDDLE));
        gui.focusable(tile, true);
        gui.cursor(tile, CursorShape.POINTER);
        gui.onClick(tile, act);
        gui.onState(tile, s -> {
            tile.background(theme.color(s == InteractionState.NORMAL ? Role.NONE : Role.SELECTION));
            tile.textColor(theme.color(s == InteractionState.NORMAL ? Role.FAINT : Role.INK));
        });
        tile.textColor(theme.color(Role.FAINT));
        bar.append(rule);
        bar.append(tile);
        return this;
    }

    /** The icon column. Place it against the edge it belongs to. */
    public Node node() {
        return bar;
    }

    /** The panel. Place it beside {@link #node()}; it is hidden while nothing is selected. */
    public Node panel() {
        return panel;
    }

    /** The selected panel's key, or null when the panel is shut. */
    public String selected() {
        return selected;
    }

    /**
     * Install the motion for opening, closing and swapping panels. The default is {@link PanelTransition#NONE} —
     * instant, and identical to how a rail behaved before transitions existed, so this is opt-in and the
     * reduced-motion path is simply not calling it.
     */
    public Rail transition(PanelTransition transition) {
        this.transition = transition == null ? PanelTransition.NONE : transition;
        return this;
    }

    /**
     * Show a panel, building it if this is the first time. A key with no item shuts the panel.
     *
     * <p>With a transition installed the rail is briefly mid-transaction, and the rule that keeps that from
     * turning into a mess is the one {@link Tabs#select} follows: <b>a new selection settles the previous one
     * first</b>. Letting a stale transition finish on its own terms loses a page — open A, swap to B, then close,
     * quickly, and the callback for the swap arrives with no honest thing left to do. Settling first means the
     * only page an in-flight transition can still be holding is one this call is about to take charge of anyway.
     *
     * <p>What is <em>not</em> deferred is the selection itself: {@link #selected()} reports the new answer and
     * {@link #onSelect} has been told before the motion starts. Only what is on screen is ever mid-flight.
     */
    public synchronized Rail select(String key) {
        Item found = item(key);
        // Before anything else, and in particular before the panel is touched: see above.
        settle();
        Item previous = item(selected);
        if (found == previous) {
            // Nothing to move. This is the path a repeated select takes, and a transition there would be a fade
            // from a page to itself.
            for (Item i : items) {
                paintTile(i, false);
            }
            onSelect.accept(selected);
            return this;
        }

        this.selected = found == null ? null : found.key();
        if (found != null) {
            if (!found.built()[0]) {
                found.built()[0] = true;
                found.page().build(gui, found.body());
            }
            // The arriving page and the panel are shown now. What is leaving is left exactly as it is and put
            // away by settle(), which is the whole of what makes a close something that can be watched.
            found.body().visible(true);
            panelTitle.text(found.title().toUpperCase(java.util.Locale.ROOT));
            panel.visible(true);
        }
        for (Item i : items) {
            paintTile(i, false);
        }

        Node outgoing = previous == null ? null : previous.body();
        Node incoming = found == null ? null : found.body();
        fadingOut = outgoing;
        fadingIn = incoming;
        if (outgoing != null && incoming != null) {
            // Two pages in one body: lift the leaving one over the arriving one so the column does not split the
            // space between them, and make both pointer-transparent. A page on its way out must not be what a
            // click lands on however solid it still looks, and a page in motion is a page whose pointer target
            // disagrees with where it is drawn — a moving target is not something to ask anyone to click.
            outgoing.floatAt(Length.ZERO, Length.ZERO).hitInert(true);
            incoming.hitInert(true);
        } else {
            // The panel itself is what is moving, so the panel is what must not take the pointer while it does.
            panel.hitInert(true);
        }
        // Told before the motion starts, and so while the panel it is being told about is still on show.
        onSelect.accept(this.selected);
        transition.run(new Change(panel, outgoing, incoming), this::settle);
        return this;
    }

    /** Shut the panel, leaving the rail. */
    public Rail close() {
        return select(null);
    }

    /** Show a panel, or shut it if it is already the one on show. */
    public Rail toggle(String key) {
        return select(key.equals(selected) ? null : key);
    }

    /** A short word beside the panel's title — a count, a mode, a unit. */
    public Rail hint(String text) {
        panelHint.text(text);
        return this;
    }

    /** How wide the panel is. Its height is the layout's to decide. */
    public Rail panelWidth(Length width) {
        panel.width(width);
        return this;
    }

    /**
     * React to the selection, including to null when the panel shuts. Runs on a worker thread.
     *
     * <p>Called at the moment the selection changes: {@link #selected()} already reports the new answer and the
     * panel is <b>still on show</b>, whether it is arriving or leaving, because the motion has not started yet.
     * That ordering is what a handler with something of its own to move needs — a handler told after the panel
     * had already been hidden could not have animated a close at all.
     */
    public Rail onSelect(Consumer<String> handler) {
        this.onSelect = handler == null ? k -> { } : handler;
        return this;
    }

    /** Attach hover titles for every item added so far. */
    public Rail titles(Tooltip tooltip) {
        for (Item i : items) {
            tooltip.attach(i.tile(), i.title());
        }
        return this;
    }

    /** The item {@code key} names, or null — which is what a null key and an unknown one both come to. */
    private Item item(String key) {
        for (Item i : items) {
            if (i.key().equals(key)) {
                return i;
            }
        }
        return null;
    }

    /**
     * Put the panel and its pages back at rest for whatever the selection now is: exactly one page visible when
     * there is a selection and none when there is not, everything in flow, opaque, untranslated and hittable
     * again, and the panel shown or hidden to match.
     *
     * <p>Idempotent, and stated in terms of {@link #selected} rather than of what any transition was doing — so
     * a settle that arrives late describes the rail as it is rather than as it was. That is the whole
     * interruption story, and it is why there is no generation token: the worst a superseded {@code done} can do
     * is cut the change that replaced it short, to the state that change was heading for anyway. A fade that
     * jumps the last of the way is the failure mode; a panel that never comes back is not reachable.
     */
    private synchronized void settle() {
        Node out = fadingOut;
        Node in = fadingIn;
        fadingOut = null;
        fadingIn = null;
        if (out == null && in == null) {
            return;
        }
        if (out != null) {
            out.unfloat().hitInert(false).opacity(1f).translate(0f, 0f);
        }
        if (in != null) {
            in.hitInert(false).opacity(1f).translate(0f, 0f);
        }
        panel.hitInert(false).opacity(1f).translate(0f, 0f);
        for (Item i : items) {
            i.body().visible(i.key().equals(selected));
        }
        panel.visible(selected != null);
    }

    private void paintTile(Item item, boolean hover) {
        Theme theme = gui.theme();
        boolean isSelected = item.key().equals(selected);
        item.tile().background(theme.color(
                isSelected ? Role.SELECTION : hover ? Role.RAISED : Role.NONE));
        item.tile().textColor(theme.color(isSelected ? Role.ACCENT : hover ? Role.INK : Role.DIM));
    }
}
