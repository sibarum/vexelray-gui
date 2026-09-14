package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.model.Mutation;
import dev.vexelray.gui.core.model.PropKey;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where nodes come from, and how an edit to one reaches the model: the id counter, the publisher seam every
 * {@link Node} handle writes through, and the batch that makes a group of edits one publish.
 *
 * <p><b>A node handle is not the node.</b> {@code box()} mints an id, posts a {@code Create} and hands back a
 * handle; the retained node appears when the GUI thread drains. Everything a handle does is a mutation on the
 * bus, from whatever thread called it, which is what lets a tree be built off the GUI thread without a lock.
 *
 * <p>Constructible with a bus and two functions, so the factory can be exercised without a frame loop: the
 * mutations it produces are observable on the topic it publishes to.
 */
public final class Trees {

    /** Node mutations, published from any thread and drained by the GUI thread once per frame. */
    private final Topic<Mutation> mutations;

    private final Atchung bus;
    private final java.util.function.Consumer<String> wake;
    private final LayoutReader reads;

    private final AtomicLong ids = new AtomicLong(1);

    /**
     * The group this thread's edits are joining, if it is inside {@link #batch}. Thread-local because a batch is
     * a property of the call, not of the tree: two workers batching at once must not collect into one group.
     */
    private final ThreadLocal<List<Mutation>> batching = new ThreadLocal<>();

    /**
     * The publisher seam for {@link Node} handles: every setter publishes a {@link Mutation} from any thread —
     * unless this thread is inside a batch, in which case the op joins the group and the group publishes once.
     */
    private final MutationSink sink;

    private final long rootId;
    private final Node root;

    /**
     * @param bus         where mutations are published
     * @param topic       the topic they are published on, which the caller also drains
     * @param wake        how to ask for a frame; every publish goes through it, because a mutation that does not
     *                    wake the loop produces a window that stops updating rather than one that updates slowly
     * @param reads       how a handle resolves {@code Node.layout()}; consulted lazily, so it may be supplied
     *                    before whatever answers it exists
     */
    public Trees(Atchung bus, Topic<Mutation> topic, java.util.function.Consumer<String> wake, LayoutReader reads) {
        this.bus = bus;
        this.mutations = topic;
        this.wake = wake == null ? why -> { } : wake;
        this.reads = reads;
        this.sink = m -> {
            List<Mutation> group = batching.get();
            if (group != null) {
                group.add(m);
            } else {
                publish(m);
            }
        };
        this.rootId = ids.getAndIncrement();
        this.root = new Node(rootId, sink, reads);
    }

    /**
     * The id the root was minted with, for whoever has to build a reconciler around it.
     *
     * <p>Minted in the constructor, but the root is <b>not created</b> there — see {@link #createRoot}.
     */
    public long rootId() {
        return rootId;
    }

    /**
     * Post the root's {@code Create}. Call it once, and only after whatever drains {@link #mutations} is
     * subscribed.
     *
     * <p>Separate from the constructor because the root is the one node whose id is needed <em>before</em> the
     * subscription exists — a reconciler is built around it — while its {@code Create} must be published
     * <em>after</em>, or it goes onto the bus with nobody listening and the tree starts life without a root.
     * That is not a hypothetical ordering: it is what happened when this component was first extracted, and what
     * the suite caught.
     */
    public Node createRoot() {
        Map<PropKey, Object> init = new EnumMap<>(PropKey.class);
        init.put(PropKey.DIRECTION, Direction.COLUMN);
        init.put(PropKey.WIDTH, Length.FILL);
        init.put(PropKey.HEIGHT, Length.FILL);
        sink.post(new Mutation.Create(rootId, init));
        return root;
    }

    /** The seam a {@link Node} handle writes through. */
    public MutationSink sink() {
        return sink;
    }

    /** The root node (fills the viewport). Append the UI to it. */
    public Node root() {
        return root;
    }

    /** A handle on an existing node id. */
    public Node handle(long id) {
        return new Node(id, sink, reads);
    }

    /** A generic box (defaults to a row). */
    public Node box() {
        return create(null);
    }

    /** A horizontal box. */
    public Node row() {
        return create(Direction.ROW);
    }

    /** A vertical box. */
    public Node column() {
        return create(Direction.COLUMN);
    }

    /**
     * A node carrying {@code s} — which is the whole of what makes it a text node.
     *
     * <p><b>Sugar, not a second kind.</b> This is exactly {@code box().text(s)}, and the two are
     * interchangeable: a node draws text when it has text, so there is no way to make one that holds a string
     * and refuses to show it. That used to be possible and was the quietest bug in the framework — see
     * {@code RetainedNode.hasText}.
     *
     * <p>An empty string is still text: it is a label whose content has not arrived, and it holds its line's
     * height so the row around it does not jump when the content lands.
     */
    public Node text(String s) {
        long id = ids.getAndIncrement();
        Map<PropKey, Object> init = new EnumMap<>(PropKey.class);
        init.put(PropKey.TEXT, s);
        sink.post(new Mutation.Create(id, init));
        return new Node(id, sink, reads);
    }

    private Node create(Direction dir) {
        long id = ids.getAndIncrement();
        Map<PropKey, Object> init = new EnumMap<>(PropKey.class);
        if (dir != null) {
            init.put(PropKey.DIRECTION, dir);
        }
        sink.post(new Mutation.Create(id, init));
        return new Node(id, sink, reads);
    }

    /**
     * Run {@code edits} as one group: every mutation they produce publishes as a single {@link Mutation.Batch}
     * rather than one at a time.
     *
     * <p>Nesting joins the outer group rather than making a second one, so a widget that batches internally
     * composes with an application that batches around it.
     */
    public void batch(Runnable edits) {
        if (batching.get() != null) {
            edits.run();   // nested: the ops join the outer group, which publishes them
            return;
        }
        List<Mutation> group = new ArrayList<>();
        batching.set(group);
        try {
            edits.run();
        } finally {
            batching.remove();
        }
        if (!group.isEmpty()) {
            publish(new Mutation.Batch(List.copyOf(group)));
        }
    }

    /**
     * Put a mutation on the bus and tell whoever is driving frames that there is now something to draw.
     *
     * <p>Every publish goes through here, which is the point: the wake is easy to write once and easy to forget
     * the second time, and forgetting it produces a window that stops updating rather than one that updates
     * slowly.
     */
    private void publish(Mutation m) {
        bus.publish(mutations, m);
        wake.accept("mutation");
    }
}
