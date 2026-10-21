package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.NodeLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A container's children kept in step with a list of items by key: rebuild from state without rebuilding the nodes.
 *
 * <pre>{@code
 * Keyed<Task> rows = list.keyed(Task::id, task -> gui.row().children(gui.text("")));
 * rows.reconcile(tasks, (task, row) -> row.background(task.done() ? Role.MUTED : Role.PANEL));   // after every change
 * }</pre>
 *
 * <h2>Why "rebuild from state" fought motion</h2>
 *
 * <p>The simplest honest way to show a list is to throw away its rows and build them again from the model whenever
 * the model changes. Every application here started that way, and every one hit the same wall: a rebuilt row is a
 * <em>new</em> node, so nothing about it moved — {@code LayoutMotion} sees a row vanish and a stranger appear, a
 * focused field loses focus, a hover state resets, and the whole list flashes on every edit. The usual repair was a
 * hand-written map from model id to node, diffed by hand, in every application, each with its own bugs.
 *
 * <p>{@link #reconcile} is that diff, written once. For each item it keeps the node whose key is still present and hands it
 * to {@code update} to be changed in place; builds a node for a key it has not seen; puts them in the items' order
 * with {@link Node#arrange}, so a row whose position changed is <em>moved</em> (and slides, under a
 * {@code LayoutMotion}); and removes the rest (which leave, under an {@code ExitMotion}). New rows arrive under an
 * {@code EnterMotion}. Nothing about a kept row is lost: its handlers, focus and widget state are the node's, and the
 * node is the same one.
 *
 * <h2>A key that comes back</h2>
 *
 * <p>A key removed and shown again before its node has finished leaving gets that node back — the departure turns
 * round where it is drawn instead of a second row fading in beside the first. Once the old node is gone, the key gets
 * a new one from {@code build}.
 *
 * <h2>Threading</h2>
 *
 * <p>Call {@link #reconcile} from any thread, as with every {@link Node} method; calls are serialised here, and what they
 * post lands in order. Keys are compared with {@code equals}, so a record or a boxed id works.
 */
public final class Keyed<T> {

    private final Node parent;
    private final Function<? super T, ?> key;
    private final Function<? super T, Node> build;
    /** The node shown for each key, as of the last {@link #reconcile}. */
    private Map<Object, Node> live = new LinkedHashMap<>();
    /** Nodes removed by an earlier {@link #reconcile}, which may still be on screen leaving. */
    private final Map<Object, Node> leaving = new HashMap<>();

    Keyed(Node parent, Function<? super T, ?> key, Function<? super T, Node> build) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.key = Objects.requireNonNull(key, "key");
        this.build = Objects.requireNonNull(build, "build");
    }

    /** {@link #reconcile(List, BiConsumer)} for nodes that {@code build} completes and nothing later changes. */
    public Keyed<T> reconcile(List<? extends T> items) {
        return reconcile(items, (item, node) -> { });
    }

    /**
     * Make the parent's children one node per item, in the items' order: a key already shown keeps its node, a new
     * key gets one from {@code build}, and a key no longer present is removed. {@code update} is then called for every
     * item with its node — new or kept — to bring it up to date; build only what never changes in {@code build}, and
     * say everything that can in {@code update}, so the two cannot disagree.
     *
     * @throws IllegalArgumentException if two items share a key: one node cannot be in two places
     */
    public synchronized Keyed<T> reconcile(List<? extends T> items, BiConsumer<? super T, Node> update) {
        Objects.requireNonNull(update, "update");
        // Anything that has finished leaving has gone from the snapshot, and is no longer there to come back.
        leaving.values().removeIf(node -> node.layout() == NodeLayout.ABSENT);
        Map<Object, Node> next = new LinkedHashMap<>();
        List<Node> order = new ArrayList<>(items.size());
        for (T item : items) {
            Object k = key.apply(item);
            if (next.containsKey(k)) {
                throw new IllegalArgumentException("two items share the key " + k);
            }
            Node node = live.remove(k);
            if (node == null) {
                node = leaving.remove(k);   // still on screen, going: it turns round instead of being replaced
            }
            if (node == null) {
                node = build.apply(item);
            }
            update.accept(item, node);
            next.put(k, node);
            order.add(node);
        }
        leaving.putAll(live);   // every key not shown this time; arrange removes their nodes
        live = next;
        parent.arrange(order);
        return this;
    }

    /** The node shown for {@code key}, or null if the last {@link #reconcile} had no item with it. */
    public synchronized Node node(Object key) {
        return live.get(key);
    }

    /** How many items the last {@link #reconcile} had. */
    public synchronized int size() {
        return live.size();
    }

    @Override
    public synchronized String toString() {
        return "Keyed[" + live.size() + " shown, " + leaving.size() + " leaving]";
    }
}
