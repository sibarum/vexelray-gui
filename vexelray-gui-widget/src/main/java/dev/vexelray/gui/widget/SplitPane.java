package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutContext;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Role;

import java.util.function.Consumer;

/**
 * Two panes and a divider the user can drag: the tree beside the list, the list above the preview.
 *
 * <h2>One pane has a size, the other takes the rest</h2>
 * One pane is given an explicit size and the other grows into what is left, so resizing the window moves the far
 * edge and leaves the divider where the user put it. (Two grow weights would keep a ratio, which is the wrong
 * reading of a tree the user dragged to 250 px.) Which pane is the sized one is {@link #sized}: the tree pane of a
 * tree-and-list, but the <em>second</em> pane of a list-over-preview, whose bottom is the thing with a height
 * worth remembering. The divider is read as a <b>displacement</b>, the way {@link Table}'s column grip is, and
 * neither pane is dragged below its minimum.
 *
 * <h2>The divider does not grow</h2>
 * Its width is fixed and only its colour changes on hover and drag, which is the standing rule about hover. The
 * cursor is the resize arrow along the axis the divider moves on — {@link CursorShape#RESIZE_HORIZONTAL} between
 * side-by-side panes, {@link CursorShape#RESIZE_VERTICAL} between stacked ones — and it is held for the whole drag,
 * as {@link Table}'s column grip holds the same one.
 *
 * <p>What the pointer can land on and what is painted are two widths. The {@link #gutter} is the whole target —
 * by default 5 dp — and the {@link #line} is drawn centred inside it, filling it unless told otherwise. So a
 * layout whose panes stand apart (cards on a page) can make the gap between them the target, wide and easy to
 * land on, and paint a hairline down its middle.
 *
 * <h2>Lit for the whole drag, and faded rather than flipped</h2>
 * The line takes the accent while the pointer is over it <em>and for the whole of a drag</em>, wherever the
 * pointer is. A drag outruns the divider by up to a frame, so tracking the pointer's state alone would drop the
 * accent mid-gesture every time the pointer got ahead of the line it is moving. Given a {@link #motion} ramp, the
 * change between the two colours is a fade rather than a cut; give it a linear ease, as for any fade.
 *
 * <p>Remembering the size is the application's, through {@link #onResize} — which reports the sized pane's size in
 * {@code dp} at the end of each drag, ready for {@code WindowMemory} or {@code Settings}, so a restored size
 * arrives through {@link #size(Length)} before the first frame.
 */
public final class SplitPane {

    /** How the two panes sit. */
    public enum Orientation {
        /** First on the left, second on the right; the divider is vertical. */
        SIDE_BY_SIDE,
        /** First above, second below; the divider is horizontal. */
        STACKED
    }

    /** Which pane holds the explicit size. */
    public enum Pane { FIRST, SECOND }

    private static final Length DIVIDER = Length.dp(5);

    private final Gui gui;
    private final Orientation orientation;
    private final Node frame;
    private final Node firstBox;
    private final Node secondBox;
    private final Node divider;
    /** The painted line, centred in {@link #divider}. */
    private final Node grip;
    private volatile Length gutter = DIVIDER;
    /** The line's thickness, or null to fill the gutter. */
    private volatile Length line;
    private volatile Ramp motion;
    private volatile Pane sized = Pane.FIRST;
    /** Guarded by {@code this}: the pointer's state over the divider, and whether a drag is under way. */
    private InteractionState pointer = InteractionState.NORMAL;
    private boolean dragging;
    /** Guarded by {@code this}: how lit the line is, 0 at rest to 1 in the accent, and which fade owns it. */
    private float lit;
    private long fade;
    private volatile Length minFirst = Length.rem(6);
    private volatile Length minSecond = Length.rem(6);
    private volatile float sizeDp;
    private volatile Consumer<Float> onResize = dp -> { };

    /**
     * Two panes. Each pane node should be {@link Length#FILL} in both directions; it is placed in a box that
     * clips it.
     */
    public SplitPane(Gui gui, Orientation orientation, Node first, Node second) {
        this.gui = gui;
        this.orientation = orientation;
        boolean across = orientation == Orientation.SIDE_BY_SIDE;
        this.firstBox = gui.box().scroll(false, false).children(first);
        this.secondBox = gui.box().scroll(false, false).children(second);
        this.grip = gui.box().scroll(false, false);
        this.divider = gui.box().role("splitter")
                .direction(across ? Direction.ROW : Direction.COLUMN)
                .justify(Justify.CENTER)
                .alignItems(AlignItems.STRETCH)
                .scroll(false, false)
                .children(grip);
        shape();
        this.frame = gui.box().role("splitpane")
                .direction(across ? Direction.ROW : Direction.COLUMN)
                .width(Length.FILL).height(Length.FILL)
                .scroll(false, false)
                .children(firstBox, divider, secondBox);
        gui.cursor(divider, across ? CursorShape.RESIZE_HORIZONTAL : CursorShape.RESIZE_VERTICAL);
        gui.onDrag(divider, this::drag);
        gui.onState(divider, state -> {
            synchronized (this) {
                pointer = state;
            }
            retarget();
        });
        size(Length.rem(16));
        paint(0f);
    }

    /**
     * How wide the divider is across its axis — all of it the drag target. 5 dp unless this says otherwise. It
     * takes the space from the panes like any other child, so a gutter that is to be the gap between two
     * panes replaces whatever margin they kept from each other.
     */
    public SplitPane gutter(Length width) {
        this.gutter = width;
        shape();
        return this;
    }

    /** How thick the painted line is, centred in the {@link #gutter}; {@code null} (the default) fills it. */
    public SplitPane line(Length thickness) {
        this.line = thickness;
        shape();
        return this;
    }

    /**
     * Fade the line between its resting colour and the accent along {@code ramp}, instead of switching in one
     * frame. A fade that is interrupted turns round from wherever it had got to. {@code null} cuts, as before.
     */
    public SplitPane motion(Ramp ramp) {
        this.motion = ramp;
        return this;
    }

    private void shape() {
        boolean across = orientation == Orientation.SIDE_BY_SIDE;
        Length g = gutter;
        Length l = line == null ? Length.FILL : line;
        divider.width(across ? g : Length.FILL).height(across ? Length.FILL : g);
        grip.width(across ? l : Length.FILL).height(across ? Length.FILL : l);
    }

    /** The node to place in a layout. */
    public Node node() {
        return frame;
    }

    /** Which pane has the explicit size; the other takes what is left. The first, unless this says otherwise. */
    public SplitPane sized(Pane pane) {
        this.sized = pane;
        place(Length.dp(sizeDp));
        return this;
    }

    /** The size of the sized pane. Applies at once and does not notify {@link #onResize}. */
    public SplitPane size(Length size) {
        float em = Math.max(1f, gui.rootEmPx());
        this.sizeDp = size.scalarPx(LayoutContext.of(em * 100f, em * 100f), 0f)
                / Math.max(0.0001f, gui.dpi().value());
        place(size);
        return this;
    }

    /** The smallest the first pane may be dragged to. */
    public SplitPane minFirst(Length min) {
        this.minFirst = min;
        return this;
    }

    /** The least the second pane keeps. */
    public SplitPane minSecond(Length min) {
        this.minSecond = min;
        return this;
    }

    /** The sized pane's size in dp after a drag ends. Runs on a worker thread. */
    public SplitPane onResize(Consumer<Float> handler) {
        this.onResize = handler == null ? dp -> { } : handler;
        return this;
    }

    /** The painted line, for a test to read. */
    Node grip() {
        return grip;
    }

    /** The sized pane's current size in dp. */
    public float sizeDp() {
        return sizeDp;
    }

    private void place(Length size) {
        boolean across = orientation == Orientation.SIDE_BY_SIDE;
        Node fixed = sized == Pane.FIRST ? firstBox : secondBox;
        Node free = sized == Pane.FIRST ? secondBox : firstBox;
        if (across) {
            fixed.width(size).height(Length.FILL);
            free.width(Length.grow(1f)).height(Length.FILL);
        } else {
            fixed.width(Length.FILL).height(size);
            free.width(Length.FILL).height(Length.grow(1f));
        }
    }

    private void drag(DragEvent e) {
        boolean across = orientation == Orientation.SIDE_BY_SIDE;
        if (e.phase() != DragEvent.Phase.MOVE) {
            synchronized (this) {
                dragging = e.phase() == DragEvent.Phase.START;
            }
            retarget();
            if (e.phase() == DragEvent.Phase.END) {
                onResize.accept(sizeDp);
            }
            return;
        }
        boolean first = sized == Pane.FIRST;
        NodeLayout frameLayout = frame.layout();
        NodeLayout sizedLayout = (first ? firstBox : secondBox).layout();
        if (!frameLayout.present() || !sizedLayout.present()) {
            return;
        }
        float dpi = Math.max(0.0001f, gui.dpi().value());
        LayoutContext ctx = LayoutContext.of(frameLayout.rect().w(), frameLayout.rect().h());
        float total = across ? frameLayout.rect().w() : frameLayout.rect().h();
        float dividerPx = gutter.scalarPx(ctx, 0f);
        float now = across ? sizedLayout.rect().w() : sizedLayout.rect().h();
        float lo = (first ? minFirst : minSecond).scalarPx(ctx, 0f);
        float other = (first ? minSecond : minFirst).scalarPx(ctx, 0f);
        float hi = Math.max(lo, total - dividerPx - other);
        // Dragging toward the sized pane shrinks it when it is the second one: the divider moves the same way as
        // the pointer, and the pane on the far side of it is the one that gets smaller or larger.
        float delta = (across ? e.dx() : e.dy()) * (first ? 1f : -1f);
        float wanted = Math.min(hi, Math.max(lo, now + delta));
        this.sizeDp = wanted / dpi;
        place(Length.dp(sizeDp));
    }

    /** Head for the accent if the pointer is on the divider or dragging it, for the resting colour otherwise. */
    private void retarget() {
        Ramp ramp = motion;
        float from;
        float to;
        long mine;
        // Painted under the lock as well as decided under it: the pointer's state and the drag's phases arrive on
        // different handler threads, and two paints decided in one order must not land in the other.
        synchronized (this) {
            to = dragging || pointer != InteractionState.NORMAL ? 1f : 0f;
            from = lit;
            mine = ++fade;
            if (ramp == null || from == to) {
                lit = to;
                paint(to);
                return;
            }
        }
        ramp.run(p -> {
            float t = from + (to - from) * (float) Math.max(0d, Math.min(1d, p));
            synchronized (this) {
                if (fade != mine) {
                    return;   // a later change turned the fade round; it owns the line now
                }
                lit = t;
                paint(t);
            }
        }, () -> { });
    }

    /** Paint the line {@code t} of the way from its resting colour to the accent, mixed in Oklab. */
    private void paint(float t) {
        Color rest = gui.theme().color(Role.LINE);
        Color active = gui.theme().color(Role.ACCENT);
        Color c;
        if (t <= 0f) {
            c = rest;
        } else if (t >= 1f) {
            c = active;
        } else if (rest.a() == 0f) {
            c = Oklab.of(active).toColor(active.a() * t);   // nothing to mix from: fade the accent in over the gap
        } else {
            c = Oklab.of(rest).mix(Oklab.of(active), t).toColor(rest.a() + (active.a() - rest.a()) * t);
        }
        grip.background(c);
    }
}
