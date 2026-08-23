package dev.vexelray.gui.typeset;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.layout.Length;
import sibarum.atchung.Subscription;

import java.util.ArrayList;
import java.util.List;

/**
 * A typeset block as a component: one container {@link Node} whose children are the engine's draw list, each draw
 * a floating node. This is the whole of the projection (docs/typeset.md §8), and it is the only file in the module
 * that knows what a {@code Gui} is.
 *
 * <p><b>An atom in flex layout.</b> The container is sized exactly to the block and its children all float, so
 * they take no space from siblings and add nothing to the parent's overflow. To the surrounding layout a block is
 * one box with an intrinsic size; inside it is a composition world with its own rules.
 *
 * <p><b>Nothing in core changes for this.</b> The block builds nodes through the public {@link Node} API and so
 * posts mutations like any widget — the model-writer guard stays green by construction rather than by discipline.
 *
 * <h2>Why dp, and not em</h2>
 * The tone map solves in pixels because a legibility floor is physical (docs/typeset.md §4.3), so by the time
 * there are coordinates the basis has already been applied and applying it again would be a second
 * multiplication. The block solves at {@code rootEmPx · zoom} and emits {@link Length#dp}, which resolves as
 * {@code v · dpi} — density and nothing else. Zoom is already inside the number; density is the one factor left,
 * and it is exactly the one {@code dp} supplies. Emitting {@code em} would apply the root em, zoom <em>and</em>
 * density all a second time.
 *
 * <p>docs/todo.md reached "emit dp" from a slightly wrong premise — that the solved basis already includes
 * density. It does not, and must not: a 9px floor means 9px at density 1 and 18 at density 2, or the floor
 * shrinks physically on exactly the displays where legibility is at stake.
 *
 * <h2>Why the box is the line box, not the ink</h2>
 * §8 said the container is sized to the draw list's {@code width × (ascent + descent)}, which is the block's
 * <b>ink</b>. That is not a legal container, and P4 is where it showed. A text node's box is a <em>line</em> box:
 * the renderer draws it {@code VAlign.TOP} and puts the baseline at {@code box.y + ascender · size}, so the box
 * top sits a full ascender above the baseline while the ink reaches only as high as the tallest glyph. Size the
 * container to the ink and every glyph's box begins above it — where core clamps a floating child back inside
 * ({@code FlexLayout.placeFloating}), sliding the whole block down by the difference.
 *
 * <p>So <b>vertically</b> the container is the union of the projected boxes, which contains the ink. That is the
 * better box on its own merits, and would have been even if the clamp did not exist: it is what every other text
 * box in this framework is, and it keeps a block's height from changing when its content happens to gain or lose
 * a descender — {@code a/b} and {@code a/c} are the same height, which hugging ink would not give.
 *
 * <p>The alternative was widening core's float contract so a child may overhang its parent. Rejected: the clamp
 * is right for the overlay case it was written for, this is not that case, and there was no need to make the two
 * argue when the block's own box was the thing that was wrong.
 *
 * <p><b>Horizontally the rule is the opposite</b>, and for a reason worth stating: the container is exactly
 * {@code 0 .. placed.width()}, which can be <em>wider</em> than anything drawn. A box may reserve advance it puts
 * no mark in — {@code x²} carries {@code scriptGapAfter} past its exponent, so its drawn union stops 0.8px short
 * of its width at a 16px base. That trailing space is the block's advance, exactly as a trailing space is part of
 * a text run's, and clipping to the drawn union would delete it and let a neighbour sit too close. Nothing clamps
 * either, because containment already guarantees every draw lies inside {@code [0, width]} — the invariant the
 * framework enforces on a box turns out to be precisely the precondition the projection needs.
 *
 * <p>So the two axes are bounded by different things, and each is the tight bound for what that axis means:
 * width is advance, height is the line box. The ink box stays available through {@link #placed()}.
 *
 * <h2>Sizes are set, never measured</h2>
 * Every projected node carries an explicit width and height computed from the same atlas metrics the engine laid
 * out with, rather than being left to size intrinsically. The block therefore does not depend on the application
 * having supplied a {@code TextMeasurer} that agrees with the atlas — and, because the container is the union of
 * boxes the block itself chose, core's clamps are arithmetically guaranteed to be no-ops rather than merely
 * expected to be.
 *
 * <h2>Rebuilds</h2>
 * A block re-solves and re-projects when its content changes or when the pixel basis does. Zoom and DPI are
 * already {@code State}s, so the trigger is a subscription rather than a poll, and {@link #close()} drops them.
 * The whole subtree is rebuilt: a draw list is small, and the tone map's slope depends on the block's extremes so
 * nearly every glyph moves anyway — a diff would be more machinery than the thing it optimises.
 */
public final class TypesetBlock implements AutoCloseable {

    private final Gui gui;
    private final Typeset engine;
    private final Node container;
    private final List<Subscription> basis = new ArrayList<>();

    /** The nodes currently projected from the draw list — replaced wholesale on every rebuild. */
    private final List<Node> projected = new ArrayList<>();

    private volatile Box content;
    private volatile Color ink;
    private volatile Placed placed = Placed.empty();

    /** Build a block for {@code content} and project it immediately, so {@link #node()} is usable at once. */
    public TypesetBlock(Gui gui, Typeset engine, Box content) {
        this.gui = gui;
        this.engine = engine;
        this.content = content;
        // The theme's primary ink, so a block matches the text around it until told otherwise.
        this.ink = gui.theme().color(Role.INK);
        // Never a scroll container: a block is a fixed-aspect atom in v1, and scrolling belongs to whatever holds
        // it. It also keeps the container's content box equal to its border box, which is what a floating child's
        // offsets resolve against.
        this.container = gui.box().scroll(false, false);
        basis.add(gui.zoom().onCommit(v -> rebuild()));
        basis.add(gui.dpi().onCommit(v -> rebuild()));
        rebuild();
    }

    /** The node to place in a layout. Sized to the block; every child of it floats. */
    public Node node() {
        return container;
    }

    /** Replace the content and re-project. */
    public TypesetBlock content(Box root) {
        this.content = root;
        rebuild();
        return this;
    }

    /** The colour glyphs and bars draw in; the theme's primary ink by default (Role.INK). */
    public TypesetBlock ink(Color colour) {
        this.ink = colour == null ? gui.theme().color(Role.INK) : colour;
        rebuild();
        return this;
    }

    /** The geometry behind the current projection, in pixels — the block's <em>ink</em> box and its draw list.
     *  For tests, tools, and anything aligning to the ink rather than to the container. */
    public Placed placed() {
        return placed;
    }

    /**
     * The pixel size an authored ratio of 1.0 solves against: the root em under the current zoom, and
     * deliberately <b>not</b> density, which {@link Length#dp} applies when the coordinates resolve.
     */
    private float basisPx() {
        return gui.rootEmPx() * gui.zoom().value();
    }

    /** Drop the basis subscriptions. The nodes stay: removing the block from the tree is the caller's business,
     *  since the caller is what put it there. */
    @Override
    public void close() {
        for (Subscription s : basis) {
            s.close();
        }
        basis.clear();
    }

    private synchronized void rebuild() {
        for (Node old : projected) {
            old.remove();
        }
        projected.clear();

        Placed p = engine.layout(content, basisPx());
        this.placed = p;

        // Two passes over one emit. The first builds every node and records where it wants to sit, in the draw
        // list's own baseline-relative space; only then is the vertical union — and so the container's top —
        // known. The width comes from the block, not from the union; see the class documentation.
        Projection projection = new Projection();
        p.emitTo(projection);
        projection.place(p.width());
    }

    /**
     * The projection itself, written as a {@link Placed.Sink} — two methods, no switch, and nothing here asks
     * what kind of draw it holds. A draw kind added later is a compile error in exactly this class and in every
     * other consumer, which is the visible, deliberate consequence widening a closed interface should have
     * (docs/typeset.md §3.1).
     */
    private final class Projection implements Placed.Sink {

        private final List<Node> nodes = new ArrayList<>();
        private final List<double[]> boxes = new ArrayList<>();   // {x, y} of each node's top-left
        private double top = Double.POSITIVE_INFINITY;
        private double bottom = Double.NEGATIVE_INFINITY;

        @Override
        public void glyphs(String text, String face, double x, double y, double size, Object sourceRef) {
            // A text node draws VAlign.TOP and the renderer puts its first baseline at box.y + ascender · size,
            // so the box top is that far above the baseline the engine placed — and the box is exactly one line
            // of this face deep, which is what contains the ink without leading.
            double boxTop = y - engine.ascenderOf(face) * size;
            double width = engine.advanceOf(face, text) * size;
            double height = (engine.ascenderOf(face) - engine.descenderOf(face)) * size;
            add(gui.text(text)
                            .font(engine.faceIndexOf(face))
                            .textSize(dp(size))
                            .textColor(ink)
                            .size(dp(width), dp(height)),
                    x, boxTop, width, height);
        }

        @Override
        public void bar(double x, double y, double width, double height) {
            add(gui.box().background(ink).size(dp(width), dp(height)), x, y, width, height);
        }

        /**
         * Every projected node is hit-inert: a block is read-only, and a glyph that could steal a hover from
         * whatever the block sits in would be a bug with no upside. (Selection, if it ever lands, is additive
         * and arrives with its own target.)
         */
        private void add(Node node, double x, double y, double width, double height) {
            nodes.add(node.hitInert(true));
            boxes.add(new double[]{x, y});
            top = Math.min(top, y);
            bottom = Math.max(bottom, y + height);
        }

        /**
         * Size the container — {@code blockWidth} across, the box union deep — and float every node at its offset
         * from that box's top-left. No offset is negative and none overhangs, which are the two conditions under
         * which core's clamps are arithmetic no-ops rather than silent corrections.
         */
        void place(double blockWidth) {
            if (nodes.isEmpty()) {
                container.size(dp(blockWidth), dp(0));
                return;
            }
            container.size(dp(blockWidth), dp(bottom - top));
            for (int i = 0; i < nodes.size(); i++) {
                double[] at = boxes.get(i);
                Node node = nodes.get(i);
                node.floatAt(dp(at[0]), dp(at[1] - top));
                projected.add(node);
                container.append(node);
            }
        }
    }

    private static Length dp(double px) {
        return Length.dp((float) px);
    }
}
