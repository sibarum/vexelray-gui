package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.NodeLayout;
import sibarum.atchung.Committer;
import sibarum.atchung.State;

/**
 * A node's own scroll, as one {@link ScrollSource} per axis: the range read off its published layout, and offsets
 * asked of it. Made with {@link Gui#scrollAxes}, and what a {@link Scroller} is made of.
 *
 * <p>For a node that scrolls itself rather than inside a Scroller: a multi-line text field, whose caret-follow,
 * wheel and line culling are the text node's own. A scrollbar bound to {@link #y()} shows and moves the text's scroll
 * exactly as one bound to a Scroller does (docs/plans/scrolling.md §7.3).
 *
 * <p>One per node: it is the node's scroll watch.
 */
public final class ScrollAxes {

    private final Node node;
    private final Axis x = new Axis(true);
    private final Axis y = new Axis(false);

    ScrollAxes(Gui gui, Node node) {
        this.node = node;
        gui.onScroll(node, layout -> {
            x.publish(layout);
            y.publish(layout);
        });
    }

    /** The horizontal axis. */
    public ScrollSource x() {
        return x;
    }

    /** The vertical axis. */
    public ScrollSource y() {
        return y;
    }

    /** Which axes publish a range and take offsets; an axis that does not shows nothing to scroll. */
    void enable(boolean scrollsX, boolean scrollsY) {
        x.enabled = scrollsX;
        y.enabled = scrollsY;
    }

    /** One axis as a source: the range read off the node's published layout, and offsets asked of the node. */
    private final class Axis implements ScrollSource {

        private final boolean horizontal;
        private final State<ScrollRange> range;
        private final Committer<ScrollRange, ScrollRange> set;
        private volatile boolean enabled = true;

        Axis(boolean horizontal) {
            this.horizontal = horizontal;
            State.Builder<ScrollRange> builder = State.of(ScrollRange.NONE);
            this.set = builder.mutation("set", (current, next) -> next);
            this.range = builder.build();
        }

        @Override
        public State<ScrollRange> range() {
            return range;
        }

        @Override
        public void scrollTo(float offset) {
            if (enabled) {
                node.scrollTo(horizontal ? offset : Float.NaN, horizontal ? Float.NaN : offset);
            }
        }

        /** Read this axis off a published layout. On the GUI thread, inside the publish (ReadModels.onScroll). */
        void publish(NodeLayout layout) {
            float view = horizontal ? layout.viewW() : layout.viewH();
            ScrollRange next = !enabled ? new ScrollRange(view, view, 0f)
                    : new ScrollRange(Math.max(view, horizontal ? layout.contentW() : layout.contentH()), view,
                            horizontal ? layout.scrollX() : layout.scrollY());
            range.commitIfChanged(set, next);
        }
    }
}
