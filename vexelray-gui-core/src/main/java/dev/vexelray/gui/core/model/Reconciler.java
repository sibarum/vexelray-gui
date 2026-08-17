package dev.vexelray.gui.core.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies mutations to the retained tree — the single writer (GUI thread only). Holds the id→node index and the
 * root, and tracks whether a layout is needed. Structural edits and layout-affecting props set {@code layoutDirty};
 * purely visual props (colour) don't, so a recolour re-renders without re-laying-out.
 */
public final class Reconciler {

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
    // move republishes the read-model without paying for a relayout (docs/layout-read-model.md §2.3).
    private boolean geometryDirty = true;

    public Reconciler(long rootId) {
        this(rootId, id -> { });
    }

    /** As {@link #Reconciler(long)}, notifying {@code onRemoved} for every node that leaves the tree. */
    public Reconciler(long rootId, java.util.function.LongConsumer onRemoved) {
        this.rootId = rootId;
        this.onRemoved = onRemoved == null ? id -> { } : onRemoved;
    }

    public RetainedNode root() {
        return root;
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

    public void apply(Mutation m) {
        switch (m) {
            case Mutation.Create c -> {
                RetainedNode n = new RetainedNode(c.id(), c.kind());
                c.initial().forEach(n::set);
                index.put(c.id(), n);
                if (c.id() == rootId) {
                    root = n;
                }
                layoutDirty = true;
            }
            case Mutation.Insert i -> {
                RetainedNode p = index.get(i.parent());
                RetainedNode ch = index.get(i.child());
                if (p != null && ch != null) {
                    ch.parent = p;
                    if (i.index() < 0 || i.index() >= p.children.size()) {
                        p.children.add(ch);
                    } else {
                        p.children.add(i.index(), ch);
                    }
                }
                layoutDirty = true;
            }
            case Mutation.Remove r -> {
                RetainedNode n = index.get(r.id());
                if (n != null) {
                    if (n.parent != null) {
                        n.parent.children.remove(n);
                    }
                    removeSubtree(n);
                }
                layoutDirty = true;
            }
            case Mutation.SetProp s -> {
                RetainedNode n = index.get(s.id());
                if (n != null) {
                    n.set(s.key(), s.value());
                    if (s.key().layoutAffecting()) {
                        layoutDirty = true;
                    } else if (s.key().geometryAffecting()) {
                        geometryDirty = true;
                    }
                }
            }
            case Mutation.SetText t -> {
                RetainedNode n = index.get(t.id());
                if (n != null) {
                    n.set(PropKey.TEXT, t.text());
                    layoutDirty = true;
                }
            }
            case Mutation.Batch b -> applyAll(b.ops());
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
