package dev.vexelray.gui.scroll;

import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.ScrollRange;
import dev.vexelray.gui.core.ScrollSource;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.WheelEvent;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.draw.Sketch;
import sibarum.atchung.Subscription;

/**
 * A scrollbar: an ordinary node that shows one axis of a {@link ScrollSource} and moves it
 * (docs/plans/scrolling.md §4).
 *
 * <p><b>An element, not chrome.</b> It has a size like any other node — {@link #THICKNESS} across, {@code FILL}
 * along — and takes its room from the flow it is placed in, so whether a bar grows the box around it or comes out
 * of its neighbour is decided by that box's own sizing and nothing here. The layout does not know it is a bar.
 *
 * <p><b>It shows a range and asks for offsets.</b> The track and thumb are a picture drawn from the source's
 * {@link ScrollRange}; a drag on the thumb, a press on the track (a page toward the press) and the wheel over the
 * bar all ask the source to move, and the bar draws what the source publishes back. So it drives a
 * {@code Scroller}, a 3D view's camera or a terminal's scrollback the same way, and never disagrees with them.
 *
 * <p><b>It keeps its room when there is nothing to scroll</b>, and draws nothing in it: the space is reserved, so
 * nothing moves when the content grows past its box, and the bar shows only on overflow (§4.1). {@link #collapsing}
 * is the other choice — no room until there is something to scroll, and then the box the bar is in grows by it.
 *
 * <p>Place it anywhere — beside what it scrolls is usual, and {@link Scrollbars} builds that — but never inside
 * a {@code Scroller}'s content, which the offset moves.
 */
public final class Scrollbar implements AutoCloseable {

    /** How thick a bar is: the thickness the old reserved strip had, so a bar is the size it always was. */
    public static final Length THICKNESS = Length.rem(0.85f);

    private final Gui gui;
    private final Node node;
    private final ScrollSource source;
    private final boolean vertical;
    private final Subscription watching;

    /** Where on the thumb a drag took hold, along the axis; NaN while not dragging the thumb. Guarded by this. */
    private float grab = Float.NaN;

    /** Whether the bar takes no room while there is nothing to scroll. See {@link #collapsing}. */
    private volatile boolean collapsing;

    /** A bar along x, showing and moving {@code source}. */
    public static Scrollbar horizontal(Gui gui, ScrollSource source) {
        return new Scrollbar(gui, source, false);
    }

    /** A bar along y, showing and moving {@code source}. */
    public static Scrollbar vertical(Gui gui, ScrollSource source) {
        return new Scrollbar(gui, source, true);
    }

    private Scrollbar(Gui gui, ScrollSource source, boolean vertical) {
        this.gui = gui;
        this.source = source;
        this.vertical = vertical;
        this.node = gui.box().role("scrollbar")
                .width(vertical ? THICKNESS : Length.FILL)
                .height(vertical ? Length.FILL : THICKNESS);
        // The range and the box are the two things a drawing depends on, and either can change without the other.
        this.watching = source.range().onCommitLatest(v -> {
            if (collapsing) {
                node.visible(v.value().scrolls());
            }
            paint();
        });
        Seams.of(gui).onResize(node, layout -> paint());
        Seams.of(gui).onDrag(node, this::pointer);
        Seams.of(gui).onWheel(node, this::wheel);
    }

    /** The node to place in a layout. */
    public Node node() {
        return node;
    }

    /** What it shows and moves. */
    public ScrollSource source() {
        return source;
    }

    /** Whether it runs along y. */
    public boolean isVertical() {
        return vertical;
    }

    /**
     * Take no room while there is nothing to scroll: the bar is hidden until its range scrolls, and then the box it is
     * in grows by it and what follows moves — a strip of tabs that overflows gets taller, rather than giving its
     * headers' height to the bar. Shown or hidden a frame after the range changes, since the range is what the
     * layout produced.
     *
     * <p>Settles in one step for a bar across the axis it shows (a horizontal bar under a row: its height cannot
     * change the row's width), and for a vertical bar beside content that only grows taller as it narrows, which is
     * what wrapped text does. Content that could fit only once narrowed would flip; give such a box room for its bar.
     */
    public Scrollbar collapsing() {
        collapsing = true;
        node.visible(source.range().value().scrolls());
        return this;
    }

    /** Stop following the source. The node stays where it is; remove it as any other. */
    @Override
    public void close() {
        watching.close();
    }

    // --- input ----------------------------------------------------------------------------------------------

    /**
     * A press on the thumb takes hold of it where it was pressed, and the drag moves the offset so that point stays
     * under the pointer. A press on the track pages once toward the press. Runs on a worker thread.
     */
    private void pointer(DragEvent e) {
        float along = vertical ? e.y() - e.nodeY() : e.x() - e.nodeX();
        float track = vertical ? e.nodeH() : e.nodeW();
        float thickness = vertical ? e.nodeW() : e.nodeH();
        ScrollRange range = source.range().value();
        Thumb thumb = Thumb.of(range, track, thickness);
        synchronized (this) {
            switch (e.phase()) {
                case START -> {
                    if (thumb.holds(along)) {
                        grab = along - thumb.start();
                    } else if (range.scrolls()) {
                        grab = Float.NaN;
                        source.scrollBy(along < thumb.start() ? -range.view() : range.view());
                    }
                }
                case MOVE -> {
                    if (!Float.isNaN(grab)) {
                        source.scrollTo(thumb.offsetFor(along - grab, range));
                    }
                }
                case END -> grab = Float.NaN;
            }
        }
    }

    /** The wheel over the bar scrolls what it shows. A horizontal bar takes a plain wheel's turn sideways too. */
    private void wheel(WheelEvent w) {
        float d = vertical ? w.dy() : (w.dx() != 0f ? w.dx() : w.dy());
        if (d != 0f) {
            source.scrollBy(d);
        }
    }

    // --- drawing --------------------------------------------------------------------------------------------

    /** Draw the track, and the thumb when there is anything to scroll, in the bar's own px. */
    private void paint() {
        NodeLayout layout = node.layout();
        if (!layout.present()) {
            return;   // not laid out yet: the first resize paints it
        }
        float w = layout.rect().w();
        float h = layout.rect().h();
        if (w <= 0f || h <= 0f) {
            node.picture(null);
            return;
        }
        float track = vertical ? h : w;
        float thickness = vertical ? w : h;
        Thumb thumb = Thumb.of(source.range().value(), track, thickness);
        if (thumb.length() <= 0f) {
            node.picture(null);   // nothing to scroll: the room is kept, and nothing is shown in it
            return;
        }

        Color edge = gui.theme().color(Role.EDGE);
        Color trough = gui.theme().color(Role.CHROME);
        Color grip = gui.theme().color(Role.GRIP);
        float r = Math.min(w, h) * 0.5f;
        // An outlined track, as the old strip had: a rim and a trough inside it, so it reads against any panel.
        Sketch s = new Sketch()
                .fill(0, 0, w, h, r, edge)
                .fill(1.5, 1.5, Math.max(0f, w - 3f), Math.max(0f, h - 3f), Math.max(0f, r - 1.5f), trough);
        float across = 0.6f * thickness;
        float side = 0.2f * thickness;
        float round = across * 0.5f;
        if (vertical) {
            s.fill(side, thumb.start(), across, thumb.length(), round, grip);
        } else {
            s.fill(thumb.start(), side, thumb.length(), across, round, grip);
        }
        node.picture(s.picture());
    }
}
