package dev.vexelray.gui.core.model;

import java.util.List;

/**
 * What one node <b>is</b>, as opposed to where it is: role, name, structure and interaction state. The
 * per-node element of {@link dev.vexelray.gui.core.layout.SemanticSnapshot}, and the half of the read-model
 * {@link dev.vexelray.gui.core.layout.NodeLayout} deliberately does not carry (docs/automation.md §3).
 *
 * <p>Keyed by the same node id and published at the same {@code version} as the layout snapshot, so the two
 * join by id and by frame. Pure data — no node references, no lengths, nothing to resolve — because a consumer
 * of this may be a remote peer with no atlas, no tree and no layout context.
 *
 * <p><b>Why this exists separately.</b> {@link NodeKind} is {@code BOX} or {@code TEXT}: it says how a node is
 * laid out and drawn, which is all rendering ever needed. It cannot say that a particular box is a button, or
 * which text is that button's label — so a reader that is not the renderer sees an anonymous box tree. Anything
 * that must address a node by what it means (an automation agent, a thin client, a screen reader) needs this.
 *
 * @param id           the node's stable id — the same key {@code LayoutSnapshot} uses
 * @param parentId     the parent's id, or {@code -1} for the root
 * @param children     child ids in tree order (also paint and traversal order)
 * @param kind         how it lays out and draws
 * @param role         what it means, as declared by the widget that built it, or {@code ""} if undeclared
 * @param name         its accessible name: own text, else the text of its descendants (see the collector)
 * @param landmark     the name this node is registered under ({@code Gui.landmark}), or {@code ""}. The
 *                     <b>durable</b> way to say which node this is: an id is minted per run and means nothing
 *                     in the next one, so anything written down — a macro, a script, a recorded session — has
 *                     to name a landmark instead. It is also the half of an {@link
 *                     dev.vexelray.gui.core.nav.Address} that makes a node <em>reachable</em> rather than
 *                     merely locatable, since navigating to one reveals whatever is concealing it
 * @param visible      whether it is in the laid-out tree at all this frame
 * @param hitInert     pointer-transparent: the pointer addresses whatever is behind it
 * @param floating     an overlay placed by {@code Node.floatAt} rather than by its parent's flow
 * @param focusable    registered as able to take keyboard focus
 * @param focused      holds keyboard focus right now
 * @param editable     an editable text node
 * @param text         its own text, or {@code null} for a non-text node
 * @param caret        caret offset for an editable text node, else {@code -1}
 * @param selectStart  selection start offset, or {@code -1}
 * @param selectEnd    selection end offset, or {@code -1}
 */
public record SemanticNode(
        long id,
        long parentId,
        List<Long> children,
        NodeKind kind,
        String role,
        String name,
        String landmark,
        boolean visible,
        boolean hitInert,
        boolean floating,
        boolean focusable,
        boolean focused,
        boolean editable,
        String text,
        int caret,
        int selectStart,
        int selectEnd) {

    /** The value returned for a node this snapshot does not describe. */
    public static final SemanticNode ABSENT = new SemanticNode(
            -1L, -1L, List.of(), NodeKind.BOX, "", "", "",
            false, false, false, false, false, false, null, -1, -1, -1);

    public SemanticNode {
        children = List.copyOf(children);
        role = role == null ? "" : role;
        landmark = landmark == null ? "" : landmark;
        name = name == null ? "" : name;
    }

    /** Whether this node is described by the snapshot at all — the counterpart of {@code NodeLayout.present()}. */
    public boolean present() {
        return id >= 0L;
    }
}
