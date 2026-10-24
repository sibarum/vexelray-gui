package dev.vexelray.gui.core.model;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.layout.EnterMotion;
import dev.vexelray.gui.core.layout.ExitMotion;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.style.PaintMotion;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import sibarum.probe.Lane;
import sibarum.probe.Probe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies mutations to the retained tree — the single writer (GUI thread only). Holds the id→node index and the
 * root, and tracks whether a layout is needed. Structural edits and layout-affecting props set {@code layoutDirty};
 * purely visual props (colour) don't, so a recolour re-renders without re-laying-out.
 *
 * <p><b>The one implementation of {@link Mutation.Sink}</b>, which is how it stays the single writer while
 * {@link Mutation} itself is open. A mutation says what it means by calling a sink method; the write lives here,
 * in a class the model-writer guard already names. Letting mutations apply themselves would have been the shorter
 * change and would have widened that guard to eight more classes, which is the guarantee it exists to hold.
 */
public final class Reconciler implements Mutation.Sink {

    private final long rootId;
    private final Map<Long, RetainedNode> index = new HashMap<>();
    /**
     * Notified with each node id as it leaves the tree, so state hung off a node elsewhere can be dropped with it.
     *
     * <p>Removal was previously a private index deletion that nothing could observe, and everything keyed by node
     * id therefore outlived the node: input handlers, claims, focusability, and focus itself. A removed node that
     * held focus kept holding it, so its claims kept applying — deleting a focused multiline editor left its
     * FOCUSED claim on Tab preempting traversal for the rest of the process. This is the seam the §7 lifecycle FSM
     * will publish from; for now it is a direct callback, because the fix should not wait on the FSM.
     */
    private final java.util.function.LongConsumer onRemoved;
    private RetainedNode root;
    private boolean layoutDirty = true;
    // Derived geometry (caret-follow scroll, text metrics) can go stale without the flex layout changing — moving
    // the caret is the standing example. layoutDirty implies geometryDirty; the converse doesn't hold, so a caret
    // move republishes the read-model without paying for a relayout (docs/reference/layout-read-model.md §2.3).
    private boolean geometryDirty = true;
    // Whether anything a frame would draw differently has changed since a frame was last drawn — the "something
    // changed" of render-on-change (docs/plans/frame-loop.md). Set only by an edit that actually changed the tree:
    // writing a prop the value it already has leaves it alone. Cleared by whoever draws, which is not the layout:
    // a frame can lay out and then not draw, and the change it laid out is still owed a draw.
    private boolean drawDirty = true;

    public Reconciler(long rootId) {
        this(rootId, id -> { });
    }

    /** As {@link #Reconciler(long)}, notifying {@code onRemoved} for every node that leaves the tree. */
    public Reconciler(long rootId, java.util.function.LongConsumer onRemoved) {
        this.rootId = rootId;
        this.onRemoved = onRemoved == null ? id -> { } : onRemoved;
    }

    private volatile PaintMotion repaints = PaintMotion.NONE;

    /**
     * What every paint in the tree resolves against — see {@link #retheme}. Volatile because the renderer reads it,
     * though only ever between applies: it is written on the GUI thread inside the frame, like everything here.
     */
    private volatile Theme theme = Theme.DARK;

    /**
     * Report every colour change (background, border colour, text colour) to {@code sink}'s
     * {@link dev.vexelray.gui.core.style.PaintMotion#repainted repainted}, on the thread applying the change. Only a
     * change from one colour to another: a first appearance has nowhere to come from, and a colour cleared has
     * nothing to go to.
     */
    public void repaints(dev.vexelray.gui.core.style.PaintMotion sink) {
        this.repaints = sink == null ? dev.vexelray.gui.core.style.PaintMotion.NONE : sink;
    }

    private volatile EnterMotion enters = EnterMotion.NONE;
    private volatile ExitMotion exits = ExitMotion.NONE;

    /** Whether something arrived or began to leave since {@link #takePresenceDirty} last asked. */
    private boolean presenceDirty;

    /**
     * Nodes whose departure finished in the frame {@link #depart} last ran: detached, and kept in the index for one
     * more drain so an edit that was already on its way when they went can still put them back. See {@link #depart}.
     */
    private final List<RetainedNode> departed = new java.util.ArrayList<>();

    /** Nodes that began to leave since {@link #liftReplaced} last ran, and the parents that gained an arrival. */
    private final List<RetainedNode> beganLeaving = new java.util.ArrayList<>();
    private final java.util.Set<RetainedNode> gainedArrival =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Lift every node that began to leave a parent that also gained an arrival in the same drain: that is a
     * replacement, not a removal, so the leaving node lets go of its place and fades out where it stood, over what
     * replaces it, rather than holding its place and pushing the newcomer aside. GUI thread, once per frame, after the
     * drain and before the layout.
     *
     * <p><b>Why it is decided here and not left to the application.</b> Rebuilding a panel by removing everything and
     * appending new nodes is the most common shape in every application on the stack, and in vexelray-gui's own
     * {@code Breadcrumb}. Under an exit motion that keeps a leaving node's place, it doubles: the old rows hold their
     * space while the new ones arrive below or beside them (MainFrame's Open dialog, the Inspector's picker,
     * Vexplore's rail, dock and breadcrumb, 2026-10-24). {@code Node.keyed} is the better shape for a list, and this is
     * what makes the common one right anyway. Only a parent that kept nothing (every child leaving or new) counts as
     * replaced; a single removal, or a keyed list's edit, is untouched: what leaves keeps its place, and then the gap
     * closes.
     */
    public void liftReplaced() {
        if (!lifted.isEmpty()) {
            lifted.removeIf(n -> !n.leaving || n.parent == null);   // gone, or put back: nothing left to let go of
        }
        if (beganLeaving.isEmpty()) {
            gainedArrival.clear();
            arrivedNow.clear();
            return;
        }
        for (RetainedNode n : beganLeaving) {
            RetainedNode p = n.parent;
            if (n.leaving && !n.lifted && p != null && gainedArrival.contains(p) && n.layoutCtx != null
                    && keptNothing(p)) {
                n.lifted = true;
                n.liftedX = n.x - p.x;
                n.liftedY = n.y - p.y;
                n.liftedW = n.w;
                n.liftedH = n.h;
                lifted.add(n);
                layoutDirty = true;
                exits.replaced(n);
                replacedParents.add(p);
            }
        }
        // What arrived in a replaced parent waits on what it replaces, so the two are never seen through each other.
        for (RetainedNode a : arrivedNow) {
            if (a.parent != null && replacedParents.contains(a.parent)) {
                enters.replacing(a);
            }
        }
        replacedParents.clear();
        beganLeaving.clear();
        gainedArrival.clear();
        arrivedNow.clear();
    }

    /** Parents found replaced in this {@link #liftReplaced}; scratch, empty between calls. */
    private final java.util.Set<RetainedNode> replacedParents =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Lifted nodes still fading, which a change of size or scale lets go of at once (see {@link #dropLifted}). */
    private final List<RetainedNode> lifted = new java.util.ArrayList<>();

    /**
     * Let go of every lifted node at once: the window's size or scale changed, and the rectangle each was lifted at,
     * in px, no longer describes anything (Vexplore's rail, rebuilt on a zoom step, faded out as a ghost at its
     * pre-zoom size over the zoomed one). A frame that re-lays the whole window is already a change everywhere, so
     * a fading leftover going with it is not seen as a jump of its own. GUI thread, before the layout.
     */
    public void dropLifted() {
        if (lifted.isEmpty()) {
            return;
        }
        for (RetainedNode n : lifted) {
            if (n.leaving && n.parent != null) {
                n.parent.children.remove(n);
                n.parent = null;
                departed.add(n);
                layoutDirty = true;
                drawDirty = true;
            }
        }
        lifted.clear();
    }

    /** Nodes that arrived since {@link #liftReplaced} last ran. */
    private final java.util.Set<RetainedNode> arrivedNow =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Whether everything {@code p} now holds either is leaving or has just arrived: it was rebuilt, not edited. A keyed
     * list that drops one row and adds another keeps the rest, and its dropped row keeps its place while the gap
     * closes, as any single removal does.
     */
    private boolean keptNothing(RetainedNode p) {
        for (RetainedNode c : p.children) {
            if (!c.leaving && !arrivedNow.contains(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Report arrivals to {@code enter} and offer removals to {@code exit} from the next edit on — see
     * {@link EnterMotion} and {@link ExitMotion}. Null is {@code NONE}.
     */
    public void presences(EnterMotion enter, ExitMotion exit) {
        this.enters = enter == null ? EnterMotion.NONE : enter;
        this.exits = exit == null ? ExitMotion.NONE : exit;
    }

    /**
     * Whether a node arrived, began to leave or came back since the last call, and clear it: the frame's cue to
     * walk the tree for presence even though nothing was in motion a frame ago.
     */
    public boolean takePresenceDirty() {
        boolean was = presenceDirty;
        presenceDirty = false;
        return was;
    }

    /**
     * Detach {@code gone} — leaving nodes whose presence has reached zero — and release whatever an earlier call
     * detached that nothing has put back since. Returns whether anything is still owed a frame: a node detached now
     * is released by the next call, so the caller must make sure there is one.
     *
     * <p><b>Why released a frame late.</b> A reader decides to put a leaving node back from the published snapshot,
     * which is a frame old, and its edit lands at the next drain. A node detached and released in one go could be
     * released in the gap between the reader looking and the edit arriving, and the edit would then name a node that
     * no longer exists — the row the user asked for back simply missing. Held for one drain, every such edit finds
     * it. Detached is not drawn, not laid out and not hit, so the extra frame is invisible.
     *
     * <p>Unlike every other edit here this one is the frame's, not a mutation's: it is a consequence of the motion
     * stage having drawn the node at nothing, and the frame is what knows that.
     */
    public boolean depart(List<RetainedNode> gone) {
        if (!departed.isEmpty()) {
            for (RetainedNode d : departed) {
                if (d.parent == null && index.get(d.id) == d) {
                    removeSubtree(d);
                }
            }
            departed.clear();
        }
        for (RetainedNode g : gone) {
            if (g.parent != null) {
                g.parent.children.remove(g);
                g.parent = null;
            }
            departed.add(g);
            layoutDirty = true;
            drawDirty = true;
        }
        return !departed.isEmpty();
    }

    /** Nodes asked to be brought into view since the frame last took them (see {@link Mutation.Reveal}). */
    private final List<RetainedNode> reveals = new java.util.ArrayList<>();

    public RetainedNode root() {
        return root;
    }

    /**
     * The node with this id, or {@code null} if nothing in the tree has it — a read of the index that removal
     * and reparenting already maintain. GUI thread, like every other read of the model.
     */
    public RetainedNode node(long id) {
        return index.get(id);
    }

    /**
     * Take the reveal requests made since the last call, emptying them — one-shot, like the ask itself. A request
     * for a node that has since left the tree comes back too; the caller sees it is detached and skips it, which
     * is cheaper than pruning this list on every removal for a case that is one frame wide.
     */
    public List<RetainedNode> takeReveals() {
        if (reveals.isEmpty()) {
            return List.of();
        }
        List<RetainedNode> asked = List.copyOf(reveals);
        reveals.clear();
        return asked;
    }

    public boolean layoutDirty() {
        return layoutDirty;
    }

    /** Whether derived geometry must be recomputed and republished this frame (implied by {@link #layoutDirty}). */
    public boolean geometryDirty() {
        return geometryDirty || layoutDirty;
    }

    public void clearDirty() {
        layoutDirty = false;
        geometryDirty = false;
    }

    /** Force a relayout next frame — used when scroll offsets change (which reposition without a tree mutation). */
    public void markLayoutDirty() {
        layoutDirty = true;
        drawDirty = true;
    }

    /**
     * Whether the tree changed since the last {@link #takeDrawDirty}, and clear it. Read once per frame by the
     * host deciding whether to draw; GUI thread only, like everything here.
     */
    public boolean takeDrawDirty() {
        boolean was = drawDirty;
        drawDirty = false;
        return was;
    }

    /**
     * Whether writing {@code value} over {@code old} changes nothing. Equal values change nothing, with one
     * exception: a collection posted again <em>as the same object</em> may have been edited in place since it was
     * last posted, and the reference cannot say whether it was, so it counts as a change. Every other prop value is
     * an immutable record, a boxed primitive, an enum or a string, where equality is the whole story.
     */
    private static boolean unchanged(Object old, Object value) {
        if (old == value) {
            return !(value instanceof java.util.Collection<?> || value instanceof Map<?, ?>);
        }
        return old != null && old.equals(value);
    }

    public void applyAll(List<Mutation> mutations) {
        for (Mutation m : mutations) {
            apply(m);
        }
    }

    /**
     * Apply one mutation, by asking it what it is.
     *
     * <p>Was a switch naming every mutation kind, which put the meaning of an edit in the writer rather than in
     * the edit, and kept {@link Mutation} sealed because the switch would not have had a case for anything else.
     */
    public void apply(Mutation m) {
        m.emitTo(Probe.ON && Probe.tracing(Lane.LAYOUT) ? traced : this);
    }

    /** The sink {@link #apply} hands a mutation while the layout lane is traced: this one, saying what each did. */
    private final Traced traced = new Traced();

    // --- Mutation.Sink: the only place a RetainedNode field is written ------------------------------------

    @Override
    public void create(long id, Map<PropKey, Object> initial) {
        RetainedNode n = new RetainedNode(id);
        initial.forEach(n::set);
        index.put(id, n);
        if (id == rootId) {
            root = n;
        }
        layoutDirty = true;
        drawDirty = true;
    }

    @Override
    public void insert(long parent, long child, int index) {
        RetainedNode p = this.index.get(parent);
        RetainedNode ch = this.index.get(child);
        if (p != null && ch != null) {
            // Detach first. An insert says where the child is *now*, and a node is in exactly one place,
            // so leaving it in its previous parent list would put it in two — laid out twice, drawn
            // twice, with `parent` naming only one of them. No caller can want that, which is why this
            // is a correction rather than a new behaviour to opt into.
            //
            // It is also the whole of what "move a node" needs. Before it, the only way to get a node
            // under a different parent was to remove it and build a new one — and Remove releases the
            // subtree, so that loses the handle, its registrations, and any widget state hanging off it.
            // A reorderable list, a reorderable tab bar and a tree that can be dragged into all needed
            // this one line.
            RetainedNode was = ch.parent;
            if (ch.parent != null) {
                ch.parent.children.remove(ch);
            }
            ch.parent = p;
            if (index < 0 || index >= p.children.size()) {
                p.children.add(ch);
            } else {
                p.children.add(index, ch);
            }
            attached(ch, was, p);
        }
        layoutDirty = true;
        drawDirty = true;
    }

    /**
     * Append {@code children} to {@code parent}, in order: exactly as an insert at the end of each. On a parent that
     * already had children it also says, once in the process, that this added rather than replaced, because
     * {@code Node.children(...)} reads like a setter. A row rebuilt by it shows everything twice, and looks right in any
     * test that builds it only once (Maistro's FN-6).
     */
    @Override
    public void children(long parent, long[] children) {
        RetainedNode p = index.get(parent);
        int had = 0;
        if (p != null) {
            for (RetainedNode c : p.children) {
                if (!c.leaving) {
                    had++;
                }
            }
        }
        for (long child : children) {
            insert(parent, child, Mutation.Insert.END);
        }
        if (had > 0 && p != null) {
            dev.vexelray.diag.Diagnostics.dropped("Node/children-appends",
                    "children(...) replacing what was there",
                    "it appends: " + p.kind().name().toLowerCase(java.util.Locale.ROOT) + " " + p.id + " had " + had + (had == 1 ? " child" : " children")
                            + " and was given " + children.length + " more, so it holds both. To replace its children,"
                            + " call arrange(...), or keyed(...) for a list drawn from data; to add to them, append(...)."
                            + " (ids are ottermate tree's)");
        }
    }

    /**
     * Make {@code children} {@code parent}'s children, in order. A child not named is removed — through the exit
     * motion, like any removal — and one already leaving that is not named keeps its place, after whatever preceded it
     * that is still there, rather than jumping about as its neighbours reorder while it fades. A named node is moved
     * here from wherever it was, which is what lets a {@code LayoutMotion} see a reordered row travel; a named node
     * that was leaving comes back. Changes nothing, and owes no frame, when the order is the one already there.
     */
    @Override
    public void arrange(long parent, long[] children) {
        RetainedNode p = index.get(parent);
        if (p == null) {
            return;
        }
        java.util.Set<RetainedNode> named = new java.util.LinkedHashSet<>();
        for (long id : children) {
            RetainedNode n = index.get(id);
            if (n != null && n != p) {
                named.add(n);   // a repeat names nothing new: a node is in one place
            }
        }
        if (named.stream().noneMatch(n -> n.leaving) && p.children.equals(new ArrayList<>(named))) {
            return;
        }
        for (RetainedNode c : List.copyOf(p.children)) {
            if (!named.contains(c) && !c.leaving) {
                remove(c.id);   // leaves, or goes at once, exactly as Node.remove() would have it
            }
        }
        List<RetainedNode> old = List.copyOf(p.children);   // the named still here, and the leaving
        List<RetainedNode> order = new ArrayList<>(named);
        for (int i = 0; i < old.size(); i++) {
            RetainedNode c = old.get(i);
            if (named.contains(c)) {
                continue;
            }
            int at = 0;
            for (int j = i - 1; j >= 0; j--) {
                int k = order.indexOf(old.get(j));
                if (k >= 0) {
                    at = k + 1;
                    break;
                }
            }
            order.add(at, c);
        }
        Map<RetainedNode, RetainedNode> was = new HashMap<>();
        for (RetainedNode n : named) {
            was.put(n, n.parent);
            if (n.parent != null && n.parent != p) {
                n.parent.children.remove(n);
            }
            n.parent = p;
        }
        p.children.clear();
        p.children.addAll(order);
        for (RetainedNode n : named) {
            RetainedNode from = was.get(n);
            if (from != p || n.leaving) {
                attached(n, from == p ? p : from, p);
            }
        }
        layoutDirty = true;
        drawDirty = true;
    }

    /**
     * {@code ch} has just been put under {@code p}, having been under {@code was}: report it to the enter motion if
     * that is an arrival. A leaving node put back is one, wherever it was put; a node that had no parent is one when
     * the parent it joins has been laid out, so a subtree built off-screen arrives once, at its top, and nothing in
     * the first frame's tree arrives at all. A move between two parents is not one.
     */
    private void attached(RetainedNode ch, RetainedNode was, RetainedNode p) {
        boolean returning = ch.leaving;
        if (returning) {
            ch.leaving = false;
            ch.lifted = false;   // back in the flow, wherever it was put
        }
        if (was == null) {
            departed.remove(ch);
        }
        // Into a container that owns its children's motion nothing arrives (PropKey.OWN_CHILD_MOTION).
        if (returning || (was == null && p.layoutCtx != null && !p.ownsChildMotion())) {
            enters.entered(ch);
            if (!returning) {
                gainedArrival.add(p);
                arrivedNow.add(ch);
            }
            // A walk is owed only when presence will be anything but 1: an arrival under EnterMotion.NONE is already
            // fully present, and walking the whole tree to write that again costs O(n) per added node. A node coming
            // back from leaving is drawn below 1, so it is owed one whatever the enter motion.
            if (returning || enters != EnterMotion.NONE) {
                presenceDirty = true;
            }
        }
    }

    @Override
    public void remove(long id) {
        RetainedNode n = index.get(id);
        if (n != null) {
            // Offered to the exit motion first, if it is on screen to be seen going: one that takes it keeps it in
            // place, drawn leaving, until the frame detaches it (depart). A second remove of a leaving node is "now".
            // A child of a container that owns its children's motion is not offered (PropKey.OWN_CHILD_MOTION). Nor
            // is a node already faded to nothing by the application's own hand: it has gone, and holding its place for one more
            // fade is a dead pause before its neighbours close up (the Scheduler's Motion.leave, under Presence).
            if (!n.leaving && n.parent != null && n.layoutCtx != null && n.visible() && !n.parent.ownsChildMotion()
                    && n.opacity() > 0f && exits.leave(n)) {
                n.leaving = true;
                beganLeaving.add(n);
                presenceDirty = true;
                drawDirty = true;
                return;
            }
            if (n.parent != null) {
                n.parent.children.remove(n);
            } else {
                departed.remove(n);
            }
            removeSubtree(n);
        }
        layoutDirty = true;
        drawDirty = true;
    }

    @Override
    public void setProp(long id, PropKey key, Object value) {
        RetainedNode n = index.get(id);
        if (n != null) {
            // The change itself is what says a frame is owed, so a write that changes nothing says nothing: no
            // relayout, no draw. Before this, setting a colour to the colour it already was cost a frame.
            Object before = n.raw(key);
            if (unchanged(before, value)) {
                return;
            }
            n.set(key, value);
            drawDirty = true;
            // A paint is reported as the colours it is in the theme being drawn: a hover from ACTION to ACTION-at-HOVER
            // is a change of colour, and a write of another role that happens to resolve the same is not.
            if (before instanceof Paint from && value instanceof Paint to && !n.paintCuts()) {
                if (key == PropKey.BACKGROUND) {
                    report(id, PaintMotion.Channel.BACKGROUND, from.in(theme), to.in(theme));
                } else if (key == PropKey.BORDER_COLOR) {
                    report(id, PaintMotion.Channel.BORDER, from.in(theme), to.in(theme));
                } else if (key == PropKey.TEXT_COLOR) {
                    report(id, PaintMotion.Channel.TEXT, from.in(theme), to.in(theme));
                }
            }
            if (key.affectsLayout(before, value)) {
                layoutDirty = true;
            } else if (key.geometryAffecting()) {
                geometryDirty = true;
            }
        }
    }

    @Override
    public void setText(long id, String text) {
        RetainedNode n = index.get(id);
        if (n != null) {
            if (unchanged(n.raw(PropKey.TEXT), text)) {
                return;
            }
            n.set(PropKey.TEXT, text);
            layoutDirty = true;
            drawDirty = true;
        }
    }

    @Override
    public void scrollToEdge(long id) {
        RetainedNode n = index.get(id);
        // Attachment alone is the whole edit: the layout pins a locked container offset to its edge on
        // every pass while attached, so the next one puts it there. Writing the offset here as well would
        // be guessing at a content height that has not been measured yet.
        if (n != null && n.scrollLock() != dev.vexelray.gui.core.layout.LayoutEnums.ScrollLock.NONE) {
            n.scrollAttached = true;
            layoutDirty = true;
            drawDirty = true;
        }
    }

    @Override
    public void scrollTo(long id, float x, float y) {
        RetainedNode n = index.get(id);
        if (n == null) {
            return;
        }
        // Written as asked; the layout pass clamps it to what the content allows, against the content measured
        // in the same pass rather than the one before.
        n.scrollX = Math.max(0f, x);
        n.scrollY = Math.max(0f, y);
        // An offset chosen by the application is a position away from a locked edge, and the lock would otherwise
        // pin it straight back. Put at the edge, the next scroll re-attaches it as the user's own would.
        n.scrollAttached = false;
        layoutDirty = true;
        drawDirty = true;
    }

    @Override
    public void reveal(long id) {
        RetainedNode n = index.get(id);
        // Recorded, not answered: where the node is has not been decided yet this frame. The frame reads
        // these back after it lays out (Gui.frame), which is the first moment the question has an answer.
        if (n != null) {
            reveals.add(n);
        }
    }

    /**
     * Every node now resolves against {@code next}. Nothing is written to a node — a paint is the same paint in any
     * theme — but every colour that comes out differently is reported as if it had been, so a source that fades a
     * hover fades the whole tree from one look to the other. Text that never stated a colour is drawn in the
     * theme's ink, and is reported as that.
     */
    @Override
    public void retheme(Theme next) {
        Theme was = theme;
        theme = next == null ? Theme.DARK : next;
        if (was.equals(theme)) {
            return;
        }
        drawDirty = true;
        for (RetainedNode n : index.values()) {
            Paint bg = n.background();
            if (bg != null) {
                report(n.id, PaintMotion.Channel.BACKGROUND, bg.in(was), bg.in(theme));
            }
            Paint border = n.borderColor();
            if (border != null) {
                report(n.id, PaintMotion.Channel.BORDER, border.in(was), border.in(theme));
            }
            Paint ink = n.textColor() != null ? n.textColor() : n.textString() != null ? Role.INK : null;
            if (ink != null) {
                report(n.id, PaintMotion.Channel.TEXT, ink.in(was), ink.in(theme));
            }
        }
    }

    /** The theme the tree is drawn in: the last one {@link #retheme} applied, {@link Theme#DARK} before any. */
    public Theme theme() {
        return theme;
    }

    /** Tell the paint motion source, for a change from one colour to another that is a change. */
    private void report(long id, PaintMotion.Channel channel, Color from, Color to) {
        if (from != null && to != null && !from.equals(to)) {
            repaints.repainted(id, channel, from, to);
        }
    }

    /**
     * This reconciler, with a row for every edit: what it was, and what it did to the frame. One row per sink call,
     * so a {@link Mutation.Batch} is reported edit by edit.
     *
     * <p>What an edit did is read off the dirty flags. They are cleared before it, read after it and put back as they
     * were, so each edit is judged on its own even when an earlier one this frame already set them: a frame that
     * lays out once may have had one edit that asked for it and twenty that would not have. The outcome is the
     * strongest flag set ({@code layout}, {@code geometry}, {@code draw}); a reveal is {@code queued}, being answered
     * after the layout; an edit naming a node not in the tree is {@code missing}; anything else is {@code unchanged},
     * which is nearly always a write of the value already there. The outcome is in the row's kind, so the rollup
     * counts each, and the detail names the edit. Text is reported by its length, never its content.
     */
    private final class Traced implements Mutation.Sink {

        private static final long NONE = Long.MIN_VALUE;

        private boolean wasLayout;
        private boolean wasGeometry;
        private boolean wasDraw;
        private int wasReveals;
        private boolean present;

        private void begin(long target) {
            wasLayout = layoutDirty;
            wasGeometry = geometryDirty;
            wasDraw = drawDirty;
            wasReveals = reveals.size();
            present = target == NONE || index.containsKey(target);
            layoutDirty = false;
            geometryDirty = false;
            drawDirty = false;
        }

        private void end(String detail) {
            String outcome = layoutDirty ? "mutation layout"
                    : geometryDirty ? "mutation geometry"
                    : drawDirty ? "mutation draw"
                    : reveals.size() > wasReveals ? "mutation queued"
                    : !present ? "mutation missing"
                    : "mutation unchanged";
            layoutDirty |= wasLayout;
            geometryDirty |= wasGeometry;
            drawDirty |= wasDraw;
            Probe.mark(Lane.LAYOUT, outcome, detail);
        }

        @Override
        public void create(long id, Map<PropKey, Object> initial) {
            begin(NONE);
            try {
                Reconciler.this.create(id, initial);
            } finally {
                end("Create node=" + id + " props=" + initial.size());
            }
        }

        @Override
        public void insert(long parent, long child, int at) {
            begin(parent);
            present &= index.containsKey(child);
            try {
                Reconciler.this.insert(parent, child, at);
            } finally {
                end("Insert node=" + parent + " child=" + child + " at=" + at);
            }
        }

        @Override
        public void remove(long id) {
            begin(id);
            try {
                Reconciler.this.remove(id);
            } finally {
                end("Remove node=" + id);
            }
        }

        @Override
        public void arrange(long parent, long[] children) {
            begin(parent);
            try {
                Reconciler.this.arrange(parent, children);
            } finally {
                end("Arrange node=" + parent + " children=" + children.length);
            }
        }

        @Override
        public void children(long parent, long[] children) {
            begin(parent);
            try {
                Reconciler.this.children(parent, children);
            } finally {
                end("Children node=" + parent + " children=" + children.length);
            }
        }

        @Override
        public void setProp(long id, PropKey key, Object value) {
            begin(id);
            try {
                Reconciler.this.setProp(id, key, value);
            } finally {
                // A flag, a number or a choice says itself in a word; anything bigger (a paint, a length, a
                // picture) is named by its key alone.
                boolean small = value instanceof Boolean || value instanceof Number || value instanceof Enum<?>;
                end("SetProp node=" + id + " " + key + (small ? "=" + value : value == null ? "=null" : ""));
            }
        }

        @Override
        public void setText(long id, String text) {
            begin(id);
            try {
                Reconciler.this.setText(id, text);
            } finally {
                end("SetText node=" + id + " chars=" + (text == null ? 0 : text.length()));
            }
        }

        @Override
        public void scrollToEdge(long id) {
            begin(id);
            try {
                Reconciler.this.scrollToEdge(id);
            } finally {
                end("ScrollToEdge node=" + id);
            }
        }

        @Override
        public void scrollTo(long id, float x, float y) {
            begin(id);
            try {
                Reconciler.this.scrollTo(id, x, y);
            } finally {
                end("ScrollTo node=" + id);
            }
        }

        @Override
        public void reveal(long id) {
            begin(id);
            try {
                Reconciler.this.reveal(id);
            } finally {
                end("Reveal node=" + id);
            }
        }

        @Override
        public void retheme(Theme next) {
            begin(NONE);
            try {
                Reconciler.this.retheme(next);
            } finally {
                end("Retheme");
            }
        }
    }

    /** Drop {@code n} and everything under it from the index, announcing each id as it goes. */
    private void removeSubtree(RetainedNode n) {
        index.remove(n.id);
        n.parent = null;
        for (RetainedNode c : n.children) {
            removeSubtree(c);
        }
        exits.left(n);
        onRemoved.accept(n.id);   // after the descendants, so a listener sees children released before parents
    }
}
