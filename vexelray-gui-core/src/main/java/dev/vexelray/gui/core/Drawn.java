package dev.vexelray.gui.core;

/**
 * What a part's asked half sets on its own nodes for the frame being drawn: the frame contract's {@code Drawn}
 * (docs/plans/frame-contract.md B). The host keeps one per tree and hands it to every asked half, so asking allocates
 * nothing.
 *
 * <p>It addresses nodes the part's told half made, by their handles, and sets only what may change from one frame to
 * the next. It cannot add, remove or restructure a node: that is the told half's, in the widget tick, where layout
 * follows. What it may set grows when a witness needs it (the plan's open question 3): the Inspector's live timeline
 * needed an offset to follow the present, and a stretch to zoom without building its picture again on every frame;
 * Pix's picture, looked at closely, a scale to zoom about the pointer as it eases.
 *
 * <p>A value set stays set until it is set again: a part asked on a frame where nothing it shows has changed may leave
 * its nodes as they are.
 */
public interface Drawn {

    /**
     * Draw {@code node}, and everything under it, {@code dx} px right and {@code dy} px down of where layout put it.
     * Hit-testing follows it, as it follows a {@link dev.vexelray.gui.core.layout.LayoutMotion} displacement, so what
     * is clicked is what is seen. Zero is where layout put it. A node no longer in the tree is ignored.
     */
    Drawn offset(Node node, float dx, float dy);

    /**
     * Draw {@code node}'s own picture ({@link Node#picture}) stretched along x by {@code factor} about {@code originX},
     * in the node's own pixels ({@code 0} at its left edge): positions along x and a box's width stretch, and nothing
     * else does ({@link dev.vexelray.gui.draw.Picture#stretchX} says which is which). The node's box, its children,
     * its hit area and its clip do not stretch: the picture is still clipped to the box, so a picture that will be
     * stretched past its node's edges needs a node wide enough to show it. One is none; the factor is positive. A node
     * no longer in the tree is ignored.
     */
    Drawn stretch(Node node, float factor, float originX);

    /**
     * Draw {@code node} and everything under it {@code factor} times as large about the centre of its box, as
     * {@link Node#scale} does and multiplied with it: exact at any factor, laid out and hit where layout put it, and
     * clips do not scale. With {@link #offset}, it scales about where the offset draws it, so a picture zoomed about any
     * point is a scale and an offset. One is none; the factor is positive. A node no longer in the tree is ignored.
     */
    Drawn scale(Node node, float factor);

    /**
     * Show {@code slot} of {@code feed} in this frame, instead of the newest it was handed: a video's frame due now,
     * found by {@link Feed#due}. A slot not handed over is ignored, and the feed goes on showing what it showed. Every
     * node naming the feed shows the same slot; a part that wants two frames at once wants two feeds.
     */
    Drawn show(Feed feed, int slot);
}
