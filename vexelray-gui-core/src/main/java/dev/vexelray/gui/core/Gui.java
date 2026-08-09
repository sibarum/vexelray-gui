package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.FlexLayout;
import dev.vexelray.gui.core.layout.LayoutContext;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.Mutation;
import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.Reconciler;
import dev.vexelray.gui.core.model.RetainedNode;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The framework facade and the boundary between worker threads and the GUI thread. Workers build UI and mutate it
 * through {@link Node} handles minted here (each posts a {@code Create}); the GUI thread calls {@link #frame} once
 * per frame to drain the mutation queue, reconcile the retained tree (single writer), and lay it out. Application
 * event handlers submit to the worker executor (used once input/events land).
 *
 * <p>Tree construction is just the first batch of mutations: node factories return handles immediately with no
 * round-trip to the GUI thread, so a worker can assemble a whole subtree off-thread.
 */
public final class Gui implements AutoCloseable {

    private final AtomicLong ids = new AtomicLong(1);
    private final MutationSink sink = new MutationSink();
    private final Reconciler reconciler;
    private final Node root;
    private float lastViewportW = -1f;
    private float lastViewportH = -1f;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "vexelray-gui-worker");
        t.setDaemon(true);
        return t;
    });

    public Gui() {
        long rootId = ids.getAndIncrement();
        this.reconciler = new Reconciler(rootId);
        Map<PropKey, Object> init = new EnumMap<>(PropKey.class);
        init.put(PropKey.DIRECTION, Direction.COLUMN);
        init.put(PropKey.WIDTH, Length.FILL);
        init.put(PropKey.HEIGHT, Length.FILL);
        sink.post(new Mutation.Create(rootId, NodeKind.BOX, init));
        this.root = new Node(rootId, sink);
    }

    /** The root node (fills the viewport). Append the UI to it. */
    public Node root() {
        return root;
    }

    /** A generic box (defaults to a row). */
    public Node box() {
        return create(NodeKind.BOX, null);
    }

    /** A horizontal box. */
    public Node row() {
        return create(NodeKind.BOX, Direction.ROW);
    }

    /** A vertical box. */
    public Node column() {
        return create(NodeKind.BOX, Direction.COLUMN);
    }

    /** A text node carrying {@code s}. */
    public Node text(String s) {
        long id = ids.getAndIncrement();
        Map<PropKey, Object> init = new EnumMap<>(PropKey.class);
        init.put(PropKey.TEXT, s);
        sink.post(new Mutation.Create(id, NodeKind.TEXT, init));
        return new Node(id, sink);
    }

    private Node create(NodeKind kind, Direction dir) {
        long id = ids.getAndIncrement();
        Map<PropKey, Object> init = new EnumMap<>(PropKey.class);
        if (dir != null) {
            init.put(PropKey.DIRECTION, dir);
        }
        sink.post(new Mutation.Create(id, kind, init));
        return new Node(id, sink);
    }

    /** Run {@code work} on a worker thread (app logic stays off the GUI thread). */
    public void async(Runnable work) {
        workers.submit(work);
    }

    /** Buffer a group of edits and post them as one atomic {@link Mutation.Batch}. */
    public void batch(Runnable edits) {
        // For now edits post individually (already atomic per-frame within a drain); a true buffering Batch is a
        // small follow-up. Kept as the API seam so call sites are stable.
        edits.run();
    }

    /**
     * Drain + reconcile + (re)layout for one frame; returns the retained root to render (may be null on the very
     * first call before the root's Create is drained — it won't be, since we drain first). {@code tm} supplies
     * text intrinsic sizes.
     */
    public RetainedNode frame(float viewportW, float viewportH, TextMeasurer tm) {
        List<Mutation> batch = sink.drain();
        if (!batch.isEmpty()) {
            reconciler.applyAll(batch);
        }
        RetainedNode r = reconciler.root();
        boolean viewportChanged = viewportW != lastViewportW || viewportH != lastViewportH;
        if (r != null && (reconciler.layoutDirty() || viewportChanged)) {
            FlexLayout.layout(r, viewportW, viewportH, LayoutContext.of(viewportW, viewportH), tm);
            reconciler.clearDirty();
            lastViewportW = viewportW;
            lastViewportH = viewportH;
        }
        return r;
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
