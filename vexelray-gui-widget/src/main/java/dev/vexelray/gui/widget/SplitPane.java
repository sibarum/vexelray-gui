package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutContext;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
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
 * cursor is {@link CursorShape#GRAB} for the reason {@link Table}'s grip gives: the shape vocabulary has no resize
 * cursor yet, and the two want the same one.
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
    private volatile Pane sized = Pane.FIRST;
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
        this.divider = gui.box().role("splitter")
                .width(across ? DIVIDER : Length.FILL)
                .height(across ? Length.FILL : DIVIDER);
        this.frame = gui.box().role("splitpane")
                .direction(across ? Direction.ROW : Direction.COLUMN)
                .width(Length.FILL).height(Length.FILL)
                .scroll(false, false)
                .children(firstBox, divider, secondBox);
        gui.cursor(divider, CursorShape.GRAB);
        gui.onDrag(divider, this::drag);
        gui.onState(divider, this::paint);
        size(Length.rem(16));
        paint(InteractionState.NORMAL);
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
        if (e.phase() == DragEvent.Phase.END) {
            onResize.accept(sizeDp);
            return;
        }
        if (e.phase() != DragEvent.Phase.MOVE) {
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
        float dividerPx = DIVIDER.scalarPx(ctx, 0f);
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

    private void paint(InteractionState s) {
        divider.background(gui.theme().color(s == InteractionState.NORMAL ? Role.LINE : Role.ACCENT));
    }
}
