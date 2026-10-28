package dev.vexelray.gui.scroll;

import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.Scroller;
import dev.vexelray.gui.core.layout.Length;

import java.util.Optional;

/**
 * Content with a scrollbar: a {@link Scroller} and the bars that show it, in the arrangements nearly everything
 * wants (docs/plans/scrolling.md §4.2). Composition, not a new kind of node — {@link #node()} is an ordinary box,
 * and the parts are here to style.
 *
 * <ul>
 *   <li>{@link #vertical}: {@code row[ Scroller(y) grow, bar ]} — a page, a list.</li>
 *   <li>{@link #horizontal}: {@code column[ Scroller(x), bar ]} — a strip of tabs, a timeline.</li>
 *   <li>{@link #both}: the Scroller with a bar under it and one beside it, and a plain square where they meet.</li>
 * </ul>
 *
 * <p>The box is sized to its content until its parent has less room, and then gives way by what the Scroller can
 * give: so in a box sized to its content the bar adds to it and its neighbours move, and in a box sized from
 * outside the content's room is what is left after the bar. Neither is decided here.
 */
public final class Scrollbars {

    private final Node node;
    private final Scroller scroller;
    private final Scrollbar x;
    private final Scrollbar y;

    private Scrollbars(Node node, Scroller scroller, Scrollbar x, Scrollbar y) {
        this.node = node;
        this.scroller = scroller;
        this.x = x;
        this.y = y;
    }

    /** {@code content} scrolling vertically, with a bar on its right. */
    public static Scrollbars vertical(Gui gui, Node content) {
        Scroller scroller = gui.scroller(content).axes(false, true);
        scroller.node().width(Length.grow(1f));
        Scrollbar bar = Scrollbar.vertical(gui, Seams.of(gui).scrollSource(scroller, true));
        return new Scrollbars(gui.row().children(scroller.node(), bar.node()), scroller, null, bar);
    }

    /** {@code content} scrolling horizontally, with a bar under it. */
    public static Scrollbars horizontal(Gui gui, Node content) {
        Scroller scroller = gui.scroller(content).axes(true, false);
        Scrollbar bar = Scrollbar.horizontal(gui, Seams.of(gui).scrollSource(scroller, false));
        return new Scrollbars(gui.column().children(scroller.node(), bar.node()), scroller, bar, null);
    }

    /** {@code content} scrolling both ways, with a bar under it and one on its right. */
    public static Scrollbars both(Gui gui, Node content) {
        Scroller scroller = gui.scroller(content);
        scroller.node().width(Length.grow(1f));
        Scrollbar right = Scrollbar.vertical(gui, Seams.of(gui).scrollSource(scroller, true));
        Scrollbar under = Scrollbar.horizontal(gui, Seams.of(gui).scrollSource(scroller, false));
        // The square where the two bars would cross: a box, so the bar under stops where the one beside begins.
        Node corner = gui.box().width(Scrollbar.THICKNESS).height(Scrollbar.THICKNESS);
        Node top = gui.row().children(scroller.node(), right.node());
        Node bottom = gui.row().children(under.node(), corner);
        return new Scrollbars(gui.column().children(top, bottom), scroller, under, right);
    }

    /** The node to place in a layout. */
    public Node node() {
        return node;
    }

    /** What scrolls: for its sources, its lock, {@code scrollTo}. */
    public Scroller scroller() {
        return scroller;
    }

    /** The bar along x, if this arrangement has one. */
    public Optional<Scrollbar> horizontalBar() {
        return Optional.ofNullable(x);
    }

    /** The bar along y, if this arrangement has one. */
    public Optional<Scrollbar> verticalBar() {
        return Optional.ofNullable(y);
    }
}
