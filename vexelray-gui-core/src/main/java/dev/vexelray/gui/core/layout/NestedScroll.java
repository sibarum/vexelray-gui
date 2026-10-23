package dev.vexelray.gui.core.layout;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.model.RetainedNode;

import java.util.Locale;

/**
 * A scroller inside another along the same axis that fills it: two scrollbars side by side, each scrolling part of
 * the same content (docs/reference/scrolling.md §3).
 *
 * <p>Scrollbars appear from overflow alone, so nobody asks for this; it is what two reasonable decisions add up to.
 * The commonest: a body that grows into the window and scrolls its content, under a {@code Gui.minSize} taller than
 * the window, which lays the root out on a canvas taller than the window and scrolls it too. Each is right alone,
 * and the picture they make together reads as a style, so nothing else would ever say so — which is what
 * {@link Diagnostics} is for.
 *
 * <p>Filling is the line between this and a scroller that belongs inside another. A list inside a scrolling page is
 * shorter than the page's view and shows its own bar beside its own rows; an inner scroller at least as long as the
 * outer one's view puts its bar beside the outer's, along the whole of it.
 */
final class NestedScroll {

    private static final float EPS = 1f;

    /** One pair: {@code inner} scrolls along {@code axis} inside {@code outer}, which scrolls along it too. */
    record Finding(char axis, RetainedNode inner, RetainedNode outer) {
    }

    private NestedScroll() {
    }

    /**
     * Warn about the first pair along each axis in {@code root}'s laid-out tree, once per axis per process. The
     * window and canvas sizes are the layout's, so a root scrolling because of {@code Gui.minSize} is named as such.
     */
    static void report(RetainedNode root, float windowW, float windowH, float canvasW, float canvasH) {
        Finding[] found = find(root);
        for (Finding f : found) {
            if (f != null) {
                boolean y = f.axis() == 'y';
                Diagnostics.dropped("FlexLayout/nested-scroll-" + f.axis(), "one scrollbar along " + f.axis(),
                        why(f, root, y ? windowH : windowW, y ? canvasH : canvasW));
            }
        }
    }

    /** The first pair along x and along y, in that order; either may be {@code null}. */
    static Finding[] find(RetainedNode root) {
        Finding[] found = new Finding[2];
        walk(root, root.overflowX ? root : null, root.overflowY ? root : null, found);
        return found;
    }

    /** Down the flow (a hidden or floating node scrolls nothing in its parent's view), nearest scroller per axis. */
    private static void walk(RetainedNode n, RetainedNode outerX, RetainedNode outerY, Finding[] found) {
        for (RetainedNode c : n.children) {
            if (!c.visible() || c.floating()) {
                continue;
            }
            if (found[0] == null && c.overflowX && outerX != null && c.w >= outerX.viewW - EPS) {
                found[0] = new Finding('x', c, outerX);
            }
            if (found[1] == null && c.overflowY && outerY != null && c.h >= outerY.viewH - EPS) {
                found[1] = new Finding('y', c, outerY);
            }
            if (found[0] != null && found[1] != null) {
                return;
            }
            walk(c, c.overflowX ? c : outerX, c.overflowY ? c : outerY, found);
        }
    }

    /** What happened, why the outer one scrolls, and what keeps one: specific enough to act on from the log alone. */
    private static String why(Finding f, RetainedNode root, float window, float canvas) {
        boolean y = f.axis() == 'y';
        String inner = name(f.inner(), root);
        String outer = name(f.outer(), root);
        String extent = y ? "height" : "width";
        String text = inner + " scrolls inside " + outer + " and fills its " + extent + " ("
                + px(y ? f.inner().h : f.inner().w) + " in a " + px(y ? f.outer().viewH : f.outer().viewW)
                + " view), so their two scrollbars stand side by side. ";
        if (f.outer() == root && canvas > window + EPS) {
            text += "The root scrolls because it is laid out on a canvas " + px(canvas) + (y ? " tall" : " wide")
                    + " in a " + px(window) + " window: Gui.minSize is larger than the window. Size the minimum to"
                    + " what cannot scroll, and " + inner + " is the one scroller.";
        } else {
            text += outer + " scrolls because its content is longer than its view: give " + inner
                    + " only the room left beside the rest (Length.grow), or stop one of them scrolling along "
                    + f.axis() + " with scroll(...).";
        }
        return text + " (ids are ottermate tree's; docs/reference/scrolling.md §3)";
    }

    private static String name(RetainedNode n, RetainedNode root) {
        return n.kind().name().toLowerCase(Locale.ROOT) + " " + n.id + (n == root ? ", the root," : "");
    }

    private static String px(float v) {
        return Math.round(v) + "px";
    }
}
