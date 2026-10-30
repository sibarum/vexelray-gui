package dev.vexelray.gui.core;

/**
 * What a part's asked half sets on its own nodes for the frame being drawn: the frame contract's {@code Drawn}
 * (docs/plans/frame-contract.md B). The host keeps one per tree and hands it to every asked half, so asking allocates
 * nothing.
 *
 * <p>It addresses nodes the part's told half made, by their handles, and sets only what may change from one frame to
 * the next. It cannot add, remove or restructure a node: that is the told half's, in the widget tick, where layout
 * follows. What it may set grows when a witness needs it (the plan's open question 3); the Inspector's live timeline
 * needs an offset, so an offset is what there is.
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
}
