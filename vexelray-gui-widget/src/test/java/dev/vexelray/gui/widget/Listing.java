package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.IconSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A hierarchy as the tree tests describe one, answering a {@link TreeView}'s {@link TreeView.ChildrenWanted} with its
 * {@link TreeView.Children}. The shape {@code TreeView.Source} had before a tree asked by message: the tests keep it so
 * that what each one says about the tree did not change when the asking did.
 *
 * <p>It answers inline, on the thread that asks, which is the GUI thread inside a frame. An application never does
 * that with a listing that reads a disk; a test's maps are not a disk. The reply is still a message, published on the
 * bus, so it lands in a frame as an application's does: a request sent before the widgets' tick is answered in that
 * frame, and one sent from inside it (a walk going on from a level that has just landed) in the next.
 */
interface Listing<T> {

    /** The top-level items, in display order. */
    List<T> roots();

    /** The row text for {@code item}. */
    String label(T item);

    /** Whether {@code item} can expand at all. */
    boolean hasChildren(T item);

    /** The children of {@code item}, in display order. */
    List<T> children(T item);

    /** Whether {@code item} takes a drop into it. */
    default boolean acceptsChildren(T item) {
        return hasChildren(item);
    }

    /** The icon drawn before {@code item}'s label, or null. */
    default IconSource icon(T item) {
        return null;
    }

    /** {@code item} as an entry. */
    default TreeView.Entry entry(T item) {
        return new TreeView.Entry(item, label(item), icon(item), hasChildren(item), acceptsChildren(item));
    }

    /** Names for the trees built here, so two in one test never answer to one another's requests. */
    AtomicInteger TREES = new AtomicInteger();

    /** A tree over {@code listing}, answered by it, with its roots landed. */
    static <T> TreeView<T> tree(HeadlessGui h, Listing<T> listing) {
        String name = "tree-" + TREES.incrementAndGet();
        h.bus.subscribe(TreeView.CHILDREN_WANTED, wanted -> {
            if (!wanted.tree().equals(h.gui.address(name))) {
                return;
            }
            @SuppressWarnings("unchecked")
            T parent = (T) wanted.parent();
            List<TreeView.Entry> level = new ArrayList<>();
            for (T item : parent == null ? listing.roots() : listing.children(parent)) {
                level.add(listing.entry(item));
            }
            h.bus.publish(TreeView.CHILDREN, new TreeView.Children(wanted.tree(), parent, level));
        });
        TreeView<T> tree = new TreeView<>(h.gui, name);
        h.frame();   // the tree asks for its roots, and this answers from inside the frame
        h.frame();   // so they land in the next
        return tree;
    }
}
