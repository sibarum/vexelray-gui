package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.LayoutEnums.ScrollLock;
import dev.vexelray.gui.core.spi.ScrollSource;

/**
 * A box that masks one child and scrolls it: the whole of scrolling, with no scrollbar in it
 * (docs/plans/scrolling.md §2). Made with {@link Gui#scroller}.
 *
 * <p><b>What it does.</b> It clips its child to its own box, for painting and for the pointer; moves the child by
 * its offsets; takes the wheel, {@link Node#scrollIntoView}, {@link #scrollTo}, {@link #scrollToEdge} and
 * {@link #lock}; and publishes what it is looking at as one {@link ScrollRange} per axis ({@link #x()},
 * {@link #y()}). All of that works with no bar at all, which is how a trackpad scrolls.
 *
 * <p><b>What it does not do is draw a bar or make room for one.</b> A bar is an element placed beside the
 * Scroller and bound to one of its sources, so its room is ordinary flow: in a box sized to its content the bar
 * adds to the box and its neighbours move, and in a box sized from outside the Scroller gives the bar's room up.
 * The bar never goes inside the child, because the child is what the offset moves (§2.2).
 *
 * <p><b>How big it is.</b> As big as its content, until its parent has less room than its children ask for; then
 * it gives up the difference first, down to nothing. Across an axis its parent sizes, it fills. That is the one
 * sizing rule only a Scroller has (§2.1). Its child is laid out at its content's size along each axis the Scroller
 * scrolls, filling the view where the content is smaller, and never scrolls on its own.
 *
 * <p>Both axes scroll unless {@link #axes} says otherwise. Along an axis that does not, the child is as wide as the
 * Scroller, and content wider than that overflows.
 */
public final class Scroller {

    private final Node node;
    private final Node content;
    private final ScrollAxes axes;

    Scroller(Gui gui, Node node, Node content) {
        this.node = node;
        this.content = content;
        node.children(content);
        this.axes = new ScrollAxes(gui, node);
    }

    /** The node to place in a layout. */
    public Node node() {
        return node;
    }

    /** The child it scrolls. */
    public Node content() {
        return content;
    }

    /** Which axes scroll. An axis that does not publishes a range with nothing to scroll and ignores offsets. */
    public Scroller axes(boolean scrollsX, boolean scrollsY) {
        axes.enable(scrollsX, scrollsY);
        node.scroll(scrollsX, scrollsY);
        return this;
    }

    /**
     * Keep the vertical view at an edge as the content grows — {@link ScrollLock#BOTTOM} for a log. Scrolling away
     * from the edge detaches it and scrolling back re-attaches it, as {@link Node#scrollLock} describes.
     */
    public Scroller lock(ScrollLock lock) {
        node.scrollLock(lock);
        return this;
    }

    /** Look at the content from {@code (x, y)}, clamped to what it allows. {@link Float#NaN} leaves an axis. */
    public Scroller scrollTo(float x, float y) {
        node.scrollTo(x, y);
        return this;
    }

    /** Send a {@link #lock}ed Scroller back to its edge and re-attach it. See {@link Node#scrollToEdge}. */
    public Scroller scrollToEdge() {
        node.scrollToEdge();
        return this;
    }

    /** The horizontal axis, for a bar or anything else that shows and moves it: the library's, by {@code Seams.scrollSource}. */
    ScrollSource x() {
        return axes.x();
    }

    /** The vertical axis. */
    ScrollSource y() {
        return axes.y();
    }

}
