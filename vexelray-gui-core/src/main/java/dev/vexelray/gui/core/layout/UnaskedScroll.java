package dev.vexelray.gui.core.layout;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.model.RetainedNode;

import java.util.Locale;

/**
 * A box that scrolls sideways without having said it should: content wider than the box, from something that could
 * not be narrower, and a horizontal scrollbar nobody asked for (docs/reference/scrolling.md §5).
 *
 * <p>Vertical overflow is how a page longer than its window is read, so a box that scrolls down without declaring it
 * is ordinary. Sideways is not: a page is laid out to its width, and content wider than that is almost always one
 * thing that could not shrink — a label told not to wrap, a row of buttons, a fixed width — which reads as a style
 * the moment it appears, since the bar is drawn like any other. The About window shipped one ("+ uncommitted changes"
 * on a {@code wordWrap(false)} line), and only a person looking at it said so.
 *
 * <p>So a horizontal scroller that never declared its axis ({@code Node.scroll}) is reported, naming the part of its
 * content that is too wide, which is what to change. Declaring the axis, either way, is the answer for a box that is
 * meant to scroll sideways (a table, a code view, a timeline), and it is said once, at the box. Not reported: a text
 * leaf (it keeps its own rules, and a wrapped one never scrolls sideways), and the root when it is laid out on a
 * canvas wider than the window, which is {@code Gui.minSize} doing what it was asked (§2).
 */
final class UnaskedScroll {

    private static final float EPS = 1f;

    private UnaskedScroll() {
    }

    /** One finding: {@code box} scrolls along x undeclared, and {@code widest} is the part of it that does not fit. */
    record Finding(RetainedNode box, RetainedNode widest) {
    }

    /** Warn about the first such box in {@code root}'s laid-out tree, once per process. */
    static void report(RetainedNode root, float windowW, float canvasW) {
        Finding f = find(root, canvasW > windowW + EPS);
        if (f != null) {
            Diagnostics.dropped("FlexLayout/unasked-scroll-x", "a horizontal scrollbar nobody asked for", why(f, root));
        }
    }

    /**
     * The first undeclared horizontal scroller, depth first; {@code null} for none. {@code rootOnCanvas}: the root is
     * laid out on a canvas wider than the window, so its own scrolling was asked for.
     */
    static Finding find(RetainedNode root, boolean rootOnCanvas) {
        if (unasked(root) && !rootOnCanvas) {
            return new Finding(root, widest(root));
        }
        return walk(root);
    }

    private static Finding walk(RetainedNode n) {
        for (RetainedNode c : n.children) {
            if (!c.visible()) {
                continue;
            }
            if (unasked(c)) {
                return new Finding(c, widest(c));
            }
            Finding below = walk(c);
            if (below != null) {
                return below;
            }
        }
        return null;
    }

    private static boolean unasked(RetainedNode n) {
        return n.overflowX && !n.hasText() && !n.scrollXDeclared();
    }

    /**
     * The part that does not fit: down from {@code box}, the flow child reaching furthest right, for as long as it
     * reaches past the box's view, so the answer is the innermost thing that was too wide rather than the row holding
     * it. {@code box} itself when no child reaches past (an overflow of padding or gaps alone).
     */
    static RetainedNode widest(RetainedNode box) {
        float edge = box.viewX + box.viewW;
        RetainedNode at = box;
        while (true) {
            RetainedNode next = null;
            for (RetainedNode c : at.children) {
                if (!c.visible() || c.floating()) {
                    continue;
                }
                if (c.x + c.w > edge + EPS && (next == null || c.x + c.w > next.x + next.w)) {
                    next = c;
                }
            }
            if (next == null) {
                return at;
            }
            at = next;
        }
    }

    /** What scrolls, by how much, what is too wide and why, and the two ways out. */
    private static String why(Finding f, RetainedNode root) {
        RetainedNode box = f.box();
        String text = name(box, root) + " scrolls sideways, and nothing declared that it should: its content is "
                + px(box.contentW) + " wide in a " + px(box.viewW) + " view.";
        RetainedNode w = f.widest();
        if (w != box) {
            text += " What does not fit is " + name(w, root) + quoted(w) + ", reaching " + px(w.x + w.w - box.viewX)
                    + " into it" + (w.hasText() && !w.wrapsText() ? ": a label told not to wrap (wordWrap(false))" : "")
                    + ".";
        }
        return text + " Let it wrap, or give it only the room there is (Length.FILL, Length.grow); if " + name(box, root)
                + " is meant to scroll sideways, declare it with scroll(true, ...), and this is not said again."
                + " (ids are ottermate tree's; docs/reference/scrolling.md §5)";
    }

    private static String quoted(RetainedNode n) {
        String s = n.hasText() ? n.textString() : null;
        if (s == null || s.isEmpty()) {
            return "";
        }
        return " (\"" + (s.length() > 40 ? s.substring(0, 39) + "…" : s) + "\")";
    }

    private static String name(RetainedNode n, RetainedNode root) {
        return n.kind().name().toLowerCase(Locale.ROOT) + " " + n.id + (n == root ? ", the root," : "");
    }

    private static String px(float v) {
        return Math.round(v) + "px";
    }
}
