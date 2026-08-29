package dev.vexelray.gui.core.drop;

/**
 * A node's answer to "what would happen if this landed here".
 *
 * <pre>{@code
 * gui.onDrop(treeBody, (payload, x, y) -> payload.as(ROW)
 *         .map(row -> seamAt(y).accept(row))     // every y resolves to some seam
 *         .orElse(Drop.NONE));                   // ...but only rows are accepted at all
 * }</pre>
 *
 * <h2>Answer for every point, not for the good ones</h2>
 *
 * <p>A target is asked about <em>any</em> point inside its box and must answer for all of them. That is a
 * stronger obligation than it sounds, and it is deliberate: the failure it rules out is the one where a drop
 * works in some places and silently does nothing in others, so the user learns to hunt for the spot that
 * "takes". {@link Drop#NONE} is a legitimate answer — the point is that it is an <em>answer</em>, chosen, rather
 * than a gap nobody considered.
 *
 * <p>The practical shape of this for a list or a tree is a partition: every pixel of every row belongs to some
 * seam, the boundaries between them are where the resolution flips, and there is no dead space anywhere —
 * including between rows, which is where a naive implementation leaves a one-pixel crack that only a careful
 * user ever finds. Where a target genuinely accepts nothing, it says so everywhere rather than in patches.
 *
 * <p>Resolution should also be continuous in the pointer position: a pixel of movement may change the answer
 * from one seam to the neighbouring one, never to a distant one. A target whose answer jumps around under a
 * slow-moving pointer is unusable however correct each individual answer is.
 *
 * <h2>Called often, on the GUI thread</h2>
 *
 * <p>Once per node under the pointer per frame of a drag, inside the frame. Keep it a lookup: no allocation
 * storms, no I/O, and nothing that mutates the model — the {@link Drop} it returns is a <em>description</em>,
 * and nothing in it happens until the user releases. A target that acted here would act on every frame the
 * pointer passed over it.
 */
@FunctionalInterface
public interface DropTarget {

    /** Declines everything, everywhere. */
    DropTarget NONE = (payload, x, y) -> Drop.NONE;

    /**
     * What would happen if {@code payload} were released at this point.
     *
     * @param payload what is being dragged; ask it for the forms this target understands
     * @param x       pointer x in client-space px, the space node rects are in
     * @param y       pointer y in client-space px
     * @return the effect, its indicator and its change as one value; {@link Drop#NONE} for a point this target
     *         does not accept
     */
    Drop resolve(Payload payload, float x, float y);
}
