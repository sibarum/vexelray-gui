package dev.vexelray.gui.core.model;

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

    public Reconciler(long rootId) {
        this(rootId, id -> { });
    }

    /** As {@link #Reconciler(long)}, notifying {@code onRemoved} for every node that leaves the tree. */
    public Reconciler(long rootId, java.util.function.LongConsumer onRemoved) {
        this.rootId = rootId;
        this.onRemoved = onRemoved == null ? id -> { } : onRemoved;
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
        m.emitTo(this);
    }

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
            if (ch.parent != null) {
                ch.parent.children.remove(ch);
            }
            ch.parent = p;
            if (index < 0 || index >= p.children.size()) {
                p.children.add(ch);
            } else {
                p.children.add(index, ch);
            }
        }
        layoutDirty = true;
    }

    @Override
    public void remove(long id) {
        RetainedNode n = index.get(id);
        if (n != null) {
            if (n.parent != null) {
                n.parent.children.remove(n);
            }
            removeSubtree(n);
        }
        layoutDirty = true;
    }

    @Override
    public void setProp(long id, PropKey key, Object value) {
        RetainedNode n = index.get(id);
        if (n != null) {
            n.set(key, value);
            if (key.layoutAffecting()) {
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
            n.set(PropKey.TEXT, text);
            layoutDirty = true;
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
        }
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

    /** Drop {@code n} and everything under it from the index, announcing each id as it goes. */
    private void removeSubtree(RetainedNode n) {
        index.remove(n.id);
        n.parent = null;
        for (RetainedNode c : n.children) {
            removeSubtree(c);
        }
        onRemoved.accept(n.id);   // after the descendants, so a listener sees children released before parents
    }
}
