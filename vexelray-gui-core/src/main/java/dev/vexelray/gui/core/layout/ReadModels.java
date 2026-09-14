package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.LayoutReader;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.model.SemanticNode;
import dev.vexelray.gui.core.text.TextGeometry;
import sibarum.atchung.Committer;
import sibarum.probe.Lane;
import sibarum.probe.Probe;
import sibarum.atchung.State;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * The two published views of the laid-out tree: where every node <b>is</b> ({@link LayoutSnapshot}) and what
 * every node <b>is</b> ({@link SemanticSnapshot}), at one version, from one walk.
 *
 * <p><b>Named for what it owns rather than what it does</b>, because it does two things the architecture is
 * careful to keep apart. {@link #resolve} is the compute phase: derived geometry written onto the tree, which is
 * the only stage allowed to work anything out. {@link #publish} is the publish phase, which copies and works
 * nothing out (docs/reference/layout-read-model.md §9). A name like {@code SnapshotPublisher} would have said
 * only the second and housed the first, blurring the distinction the read-model exists to hold.
 *
 * <p><b>The two halves cannot be separated.</b> They are joined by node id and by version, so they have to be
 * built from one walk of one state or the join silently describes two different frames — which is why the
 * semantic snapshot lives here rather than somewhere of its own.
 *
 * <p>What the tree cannot say about itself — who can take focus, who has it, what a landmark is called — arrives
 * as {@link Meanings}, by value rather than by reaching back into the composition root.
 */
public final class ReadModels {

    /** How far below a node its name may be gathered from. See {@code accessibleName}. */
    private static final int NAME_DEPTH = 2;

    /**
     * What the semantic half needs that the tree does not hold: focus, and the names landmarks were given.
     *
     * <p>An interface rather than the objects that answer it, so this component can be built and exercised with
     * neither an input dispatcher nor a navigator in the room.
     */
    public interface Meanings {

        /** Whether the node with this id can take focus. */
        boolean focusable(long id);

        /** The id that currently holds focus, or a value no node has. */
        long focusedId();

        /** The landmark name this node was registered under, or {@code ""}. */
        String landmarkName(long id);

        /** Nothing is focusable, nothing is focused, nothing is named — enough to publish geometry against. */
        Meanings NONE = new Meanings() {
            @Override
            public boolean focusable(long id) {
                return false;
            }

            @Override
            public long focusedId() {
                return -1L;
            }

            @Override
            public String landmarkName(long id) {
                return "";
            }
        };
    }

    private final Meanings meanings;
    private final java.util.concurrent.Executor handlers;

    private final State<LayoutSnapshot> layoutState;
    private final Committer<LayoutSnapshot, LayoutSnapshot> setLayout;
    private volatile LayoutSnapshot latestLayout = LayoutSnapshot.EMPTY;
    private long version;

    private final State<SemanticSnapshot> semanticState;
    private final Committer<SemanticSnapshot, SemanticSnapshot> setSemantics;
    private volatile SemanticSnapshot latestSemantics = SemanticSnapshot.EMPTY;

    /**
     * Geometry observers by node id (see {@link #onResize}). Registered from any thread — a tree is often built
     * off the GUI thread — and delivered from {@link #publish}, so the map is concurrent while each watch's
     * remembered box is read and written on the GUI thread alone.
     */
    private final ConcurrentMap<Long, ResizeWatch> resizeWatches = new ConcurrentHashMap<>();

    /**
     * @param meanings what the semantic half needs beyond the tree; {@link Meanings#NONE} is enough for geometry
     * @param handlers where an unordered resize observer is run — the worker pool, so a handler that computes
     *                 does not sit on the frame
     */
    public ReadModels(Meanings meanings, java.util.concurrent.Executor handlers) {
        this.meanings = meanings == null ? Meanings.NONE : meanings;
        this.handlers = handlers == null ? Runnable::run : handlers;

        State.Builder<LayoutSnapshot> lb = State.of(LayoutSnapshot.EMPTY);
        this.setLayout = lb.mutation("set", (current, next) -> next);
        this.layoutState = lb.build();

        State.Builder<SemanticSnapshot> sb = State.of(SemanticSnapshot.EMPTY);
        this.setSemantics = sb.mutation("set", (current, next) -> next);
        this.semanticState = sb.build();
    }

    // --- the compute phase --------------------------------------------------------------------------------

    /**
     * Walk the laid-out tree and write each node's derived geometry onto it. Runs on the GUI thread, after
     * layout and before {@link #publish}, and is the <b>only</b> stage allowed to compute it — publish copies,
     * renderers and widgets read (docs/reference/layout-read-model.md §2.1).
     */
    public void resolve(RetainedNode n, TextMeasurer tm) {
        if (!n.visible()) {
            // A hidden subtree is not laid out, so baking metrics from its stale rect would publish geometry
            // describing a position it does not occupy -- and a text node draws from those metrics, not from
            // its rect, so that geometry would be drawn if anything ever read it.
            n.textMetrics = null;
            return;
        }
        if (n.hasText()) {
            TextGeometry.resolve(n, tm);
        }
        for (RetainedNode c : n.children) {
            resolve(c, tm);
        }
    }

    // --- the publish phase --------------------------------------------------------------------------------

    /** Copy the resolved tree into both snapshots and publish them, at one version and from one walk. */
    public void publish(RetainedNode root) {
        Map<Long, NodeLayout> nodes = new HashMap<>();
        collectLayout(root, nodes);
        long v = ++version;
        LayoutSnapshot snap = new LayoutSnapshot(v, nodes);
        latestLayout = snap;             // volatile: Node.layout() reads this lock-free from any thread

        // The semantic half, at the same version and from the same tree — the join between the two is by id and
        // by version, so they must be built from one walk of one state or the join silently describes two frames.
        Map<Long, SemanticNode> meaningsById = new HashMap<>();
        collectSemantics(root, -1L, meaningsById);
        SemanticSnapshot sem = new SemanticSnapshot(v, root.id, meaningsById);
        latestSemantics = sem;

        // The causality key (docs/reference/automation.md §4). Timestamps answer "when", and across threads they
        // can even answer it in the wrong order; this answers "which frame", which is the question actually being
        // asked when a click and the layout that was supposed to reflect it disagree. Recorded with the node
        // count because a version that republishes with a tree that did not change is its own kind of finding.
        if (Probe.ON) {
            Probe.mark(Lane.LAYOUT, "layout.publish", "v" + v + " nodes=" + nodes.size());
        }

        deliverResizes(snap);            // before the State commit, so a handler's edits ride the same drain
        layoutState.commit(setLayout, snap);
        semanticState.commit(setSemantics, sem);
    }

    /** The version the next {@link #publish} will carry. Exposed for a host that marks its own frame causality. */
    public long nextVersion() {
        return version + 1;
    }

    // --- what readers see ---------------------------------------------------------------------------------

    /** The computed-layout read-model as a coalesced {@code State}. */
    public State<LayoutSnapshot> layout() {
        return layoutState;
    }

    /** The latest layout snapshot, read lock-free from any thread. */
    public LayoutSnapshot layoutSnapshot() {
        return latestLayout;
    }

    /** The semantic read-model as a coalesced {@code State}. */
    public State<SemanticSnapshot> semantics() {
        return semanticState;
    }

    /** The latest semantic snapshot, read lock-free from any thread. */
    public SemanticSnapshot semanticSnapshot() {
        return latestSemantics;
    }

    /** The reader a {@code Node} resolves {@code Node.layout()} through. */
    public LayoutReader reader() {
        return this::layoutSnapshot;
    }

    // --- geometry observers -------------------------------------------------------------------------------

    /**
     * Watch a node's box. {@code ordered} delivers on the GUI thread inside the layout pass that produced the
     * change — for a handler that only mutates the tree; anything that computes goes on the worker pool.
     * A {@code null} handler forgets the watch.
     */
    public void onResize(long id, Consumer<NodeLayout> handler, boolean ordered) {
        if (handler == null) {
            resizeWatches.remove(id);
            return;
        }
        resizeWatches.put(id, new ResizeWatch(handler, ordered));
    }

    /** Drop the watch on a node that has left the tree. */
    public void forget(long id) {
        resizeWatches.remove(id);
    }

    /**
     * Tell each geometry observer whose box moved. Runs on the GUI thread, once per published layout, and does
     * nothing at all when nobody is watching — the common case, and the reason this can sit on the frame path.
     */
    private void deliverResizes(LayoutSnapshot snap) {
        if (resizeWatches.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, ResizeWatch> entry : resizeWatches.entrySet()) {
            NodeLayout computed = snap.node(entry.getKey());
            if (!computed.present()) {
                continue;                // not in the tree (or not laid out yet): there is no box to report
            }
            ResizeWatch watch = entry.getValue();
            if (computed.rect().equals(watch.box) && computed.content().equals(watch.content)) {
                continue;
            }
            // Recorded before delivery, not after: an ordered handler may mutate, and a mutation that lands in
            // this same frame must not be able to make the next pass look like a change we already announced.
            watch.box = computed.rect();
            watch.content = computed.content();
            if (watch.ordered) {
                watch.handler.accept(computed);
            } else {
                handlers.execute(() -> watch.handler.accept(computed));
            }
        }
    }

    /**
     * One geometry observer: where to send it, and the box it was last told about. The two rects are the
     * comparison, so a scroll — which moves contents inside an unchanged viewport — is not a resize.
     */
    private static final class ResizeWatch {
        private final Consumer<NodeLayout> handler;
        private final boolean ordered;
        private Rect box;
        private Rect content;

        ResizeWatch(Consumer<NodeLayout> handler, boolean ordered) {
            this.handler = handler;
            this.ordered = ordered;
        }
    }

    // --- the two walks ------------------------------------------------------------------------------------

    private static void collectLayout(RetainedNode n, Map<Long, NodeLayout> out) {
        out.put(n.id, new NodeLayout(true,
                new Rect(n.x, n.y, n.w, n.h),
                new Rect(n.viewX, n.viewY, n.viewW, n.viewH),
                // Copied, not computed: {@link Clip} resolved it in the compute phase, where the tree can be
                // seen. Publish projects the model and works nothing out (docs/reference/layout-read-model.md §9).
                new Rect(n.clipX, n.clipY, n.clipW, n.clipH),
                n.cornerPx, n.cornerBottomPx,
                n.scrollX, n.scrollY, n.contentW, n.contentH, n.overflowX, n.overflowY, n.textSizePx,
                n.textMetrics));
        for (RetainedNode c : n.children) {
            collectLayout(c, out);
        }
    }

    /**
     * Collect what each node is, in tree order. Runs on the GUI thread inside {@link #publish}, off the same
     * tree the geometry was read from.
     *
     * <p>Hidden subtrees are described rather than skipped, unlike geometry: a reader asking "why can I not see
     * the Save button" is asking about a node that exists and is not visible, and a snapshot that omitted it
     * could only answer "no such node". Their {@code visible} flag says which case it is, and their geometry is
     * correctly absent from the layout snapshot, so nothing can read a position for one.
     */
    private void collectSemantics(RetainedNode n, long parentId, Map<Long, SemanticNode> out) {
        List<Long> childIds = new ArrayList<>(n.children.size());
        for (RetainedNode c : n.children) {
            childIds.add(c.id);
        }
        boolean text = n.hasText();
        out.put(n.id, new SemanticNode(
                n.id, parentId, childIds, n.kind(),
                n.role(), accessibleName(n), meanings.landmarkName(n.id),
                n.visible(), n.hitInert(), n.floating(),
                meanings.focusable(n.id), meanings.focusedId() == n.id, n.editable(),
                text ? n.textString() : null,
                text && n.editable() ? n.caret() : -1,
                text ? n.selectStart() : -1,
                text ? n.selectEnd() : -1));
        for (RetainedNode c : n.children) {
            collectSemantics(c, n.id, out);
        }
    }

    /**
     * The name a reader would call {@code n} by: its own text if it has any, else the text of its descendants
     * joined in tree order. A {@code Button} is a box with a label inside it, so asking the box for its text
     * gets nothing — the name has to come from the composition, which is exactly what a person reads off it.
     *
     * <p>A name <b>identifies</b> a node among its siblings; it is not a transcript of everything inside it. Two
     * bounds keep the derivation from becoming one:
     *
     * <ul>
     *   <li><b>Stop at a declared {@link RetainedNode#role() role}.</b> A toolbar's name is not every button on
     *       it, and a tab strip's name is not its tabs — those are structure, addressable in their own right.</li>
     *   <li><b>Stop at {@value #NAME_DEPTH} levels.</b> Roles alone are not enough, because the content a
     *       container holds is ordinary application boxes that declare nothing: a {@code tabs} node would
     *       otherwise be named after the entire text of every page inside it. A widget's own label is one or two
     *       levels down — deeper than that and it belongs to something else, whether or not that something has
     *       said so yet.</li>
     * </ul>
     *
     * <p>A reader that wants the full text of a subtree walks the snapshot for it; that is a different question,
     * and one the structure already answers.
     */
    private static String accessibleName(RetainedNode n) {
        String own = n.textString();
        if (own != null && !own.isEmpty()) {
            return own;
        }
        StringBuilder sb = new StringBuilder();
        appendName(n, sb, NAME_DEPTH);
        return sb.toString();
    }

    private static void appendName(RetainedNode n, StringBuilder sb, int depth) {
        if (depth <= 0) {
            return;
        }
        for (RetainedNode c : n.children) {
            if (!c.role().isEmpty()) {
                continue;               // a named thing of its own: part of the structure, not of this name
            }
            String t = c.textString();
            if (t != null && !t.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(t);
            }
            appendName(c, sb, depth - 1);
        }
    }
}
