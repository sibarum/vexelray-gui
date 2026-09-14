package dev.vexelray.gui.core.text;

import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a text node's glyphs, caret boundaries and scroll offset actually land — the compute phase for text.
 *
 * <p>Pure, and holds nothing. Every answer is a function of the node it is given and the {@link TextMeasurer}
 * that can measure a string, which is why it extracted out of {@code Gui} first: there was no state to move and
 * no order to preserve, only code that had ended up next to {@code zoomIn()} because everything had.
 *
 * <p>It is a <b>writer</b> of the retained model, and one of the declared compute-stage ones: it sets
 * {@code textMetrics}, {@code scrollX} and {@code scrollY} on the node it resolves. That is the whole of what it
 * does, and it does it where {@code Gui.frame} says the compute phase runs — after layout, before publish
 * (docs/reference/layout-read-model.md §2.1).
 */
public final class TextGeometry {

    private TextGeometry() {
    }

    /**
     * Resolve one text node's scroll and caret geometry. Scroll is narrowed first — an editable field keeps its
     * caret in view, then clamps to the content — and the caret x positions are baked afterwards <em>with that
     * scroll applied</em>, so the metrics a widget reads describe exactly what the renderer draws. Getting that
     * order wrong is what made click-to-caret miss in a scrolled field (CaretScrollTest).
     */
    public static void resolve(RetainedNode n, TextMeasurer tm) {
        n.textMetrics = null;
        String s = n.textString();
        if (s == null || s.isEmpty()) {
            n.scrollX = 0f;   // a field emptied after scrolling must snap back to its origin
            n.scrollY = 0f;
            // An empty document is not "no text" — it is a document with one empty visual line: a caret boundary
            // at offset 0, and hard line number 1. Publishing that geometry is what lets a focused empty field
            // draw its blinking caret from the same read-model everything else reads, and an empty numbered
            // editor still show a "1" in its gutter. Without it the metrics were null, so the caret had nowhere
            // to be — a field looked dead precisely when it was inviting the first keystroke.
            float lineH = tm.intrinsic(n, Axis.VERTICAL, n.textSizePx);
            float viewW = n.viewW > 0f ? n.viewW : TextMetrics.contentWidth(n);
            float viewH = Math.max(1f, n.viewH > 0f ? n.viewH : n.h - 2f * TextMetrics.padY(n));
            float viewX = n.viewW > 0f ? n.viewX : n.x + n.textPadXPx;
            float viewY = n.viewW > 0f ? n.viewY : n.y + TextMetrics.padY(n);
            float top = n.multiline() ? viewY : viewY + switch (n.vAlign()) {
                case TOP -> 0f;
                case MIDDLE -> Math.max(0f, (viewH - lineH) * 0.5f);
                case BOTTOM -> Math.max(0f, viewH - lineH);
            };
            float left = viewX + switch (n.hAlign()) {
                case LEFT, JUSTIFY -> 0f;
                case CENTER -> viewW * 0.5f;
                case RIGHT -> viewW;
            };
            n.textMetrics = new TextMetrics(List.of(
                    new TextMetrics.VisualLine(0, 0, top, lineH, new float[]{left}, 1)));
            return;
        }
        float px = n.textSizePx;
        float[] adv = tm.caretAdvances(n.font(), s, px);
        if (adv == null) {
            return;           // a measurer with no glyph metrics (an atlas-less stub) — nothing to resolve
        }
        // The text-area viewport the layout resolved for this node (FlexLayout.layoutTextLeaf). It is already
        // inset by the padding and already excludes whatever the scrollbars reserved, so scroll offsets, thumb
        // geometry and caret metrics are all expressed against the one rectangle.
        float viewW = n.viewW > 0f ? n.viewW : TextMetrics.contentWidth(n);
        float viewH = Math.max(1f, n.viewH > 0f ? n.viewH : n.h - 2f * TextMetrics.padY(n));
        float lineH = tm.intrinsic(n, Axis.VERTICAL, px);
        boolean multiline = n.multiline();
        boolean wraps = n.wrapsText();
        // The breaks the layout already computed at the width it settled on — read, never recomputed, so the line
        // count the box was sized for and the lines drawn into it are the same object. The fallback covers a node
        // the layout has not reached yet.
        List<dev.vexelray.text.TextLayout.LineSpan> spans =
                n.lineSpans != null ? n.lineSpans : tm.lineSpans(n.font(), s, wraps ? viewW : 0f, px);

        // Where the caret sits, in line-relative terms: everything below is expressed against this.
        int caret = n.caret();
        int caretLine = caret < 0 ? 0 : lineIndexOf(spans, caret);

        if (n.editable()) {
            resolveTextScroll(n, adv, spans, caret, caretLine, lineH, viewW, viewH, wraps, multiline);
        }

        // Bake absolute geometry. A multiline node tops out (a growing document grows downward); everything else
        // centres its text *block* in the box — the whole block, not one line, or a label the layout sized for
        // three wrapped lines would draw them starting a line down and spill out the bottom.
        float viewX = n.viewW > 0f ? n.viewX : n.x + n.textPadXPx;
        float viewY = n.viewW > 0f ? n.viewY : n.y + TextMetrics.padY(n);
        float contentLeft = viewX - n.scrollX;
        // Vertical placement of the whole block. A multiline node tops out and scrolls (a growing document grows
        // downward); everything else honours the node's vAlign against the *block*, not one line — a label the
        // layout sized for three wrapped rows must not place them as though there were one.
        float blockH = spans.size() * lineH;
        float contentTop = multiline ? viewY - n.scrollY : viewY + switch (n.vAlign()) {
            case TOP -> 0f;
            case MIDDLE -> Math.max(0f, (viewH - blockH) * 0.5f);
            case BOTTOM -> Math.max(0f, viewH - blockH);
        };
        List<TextMetrics.VisualLine> lines = new ArrayList<>(spans.size());
        // Hard-line numbering: a visual line gets a number only when it *begins* a hard line — the first one, or
        // one whose predecessor ended at a '\n'. Wrapped continuations get 0 and draw no number.
        int hardLine = 1;
        for (int i = 0; i < spans.size(); i++) {
            var span = spans.get(i);
            // Horizontal placement, per line: a centred or right-aligned node indents each row by its own slack.
            // Baking it here is what lets a label be *read* correctly — before this, the published xs described a
            // left-aligned line while the renderer drew a centred one, so nothing but the renderer could trust
            // the geometry. A line wider than the view has no slack, so it pins to the left and scrolls.
            float slack = Math.max(0f, viewW - (adv[span.end()] - adv[span.start()]));
            float indent = switch (n.hAlign()) {
                case LEFT -> 0f;
                case CENTER -> slack * 0.5f;
                case RIGHT -> slack;
                // Real justification stretches the gaps *within* a line, so it cannot be an indent — it would
                // have to be baked into xs per word. Not modelled, so it starts at the left rather than
                // pretending: better a known-left line than geometry that lies about where the glyphs are.
                case JUSTIFY -> 0f;
            };
            float[] xs = new float[span.end() - span.start() + 1];
            for (int j = 0; j < xs.length; j++) {
                xs[j] = contentLeft + indent + (adv[span.start() + j] - adv[span.start()]);
            }
            boolean startsHardLine = i == 0 || spans.get(i - 1).hardBreak();
            int number = startsHardLine ? hardLine++ : 0;
            lines.add(new TextMetrics.VisualLine(span.start(), span.end(), contentTop + i * lineH, lineH, xs,
                    number));
        }
        n.textMetrics = new TextMetrics(lines);
    }

    /**
     * Narrow an editable node's scroll so the caret stays in view (docs/reference/layout-read-model.md §2.2, §11.3 step 4).
     * A wrapped node never scrolls horizontally — there is nothing to the right to reach — and a single-line node
     * never scrolls vertically.
     */
    private static void resolveTextScroll(RetainedNode n, float[] adv,
                                          List<dev.vexelray.text.TextLayout.LineSpan> spans, int caret,
                                          int caretLine, float lineH, float viewW, float viewH,
                                          boolean wraps, boolean multiline) {
        // Follow the caret only when it has *moved*. A field that reports overflow is wheel- and drag-scrollable
        // like any other scroller, so following every frame would drag the view back to the caret the instant the
        // user scrolled away from it. Clamping still runs unconditionally.
        boolean caretMoved = caret != n.caretFollowed;
        n.caretFollowed = caret;

        if (wraps) {
            n.scrollX = 0f;
        } else {
            if (caret >= 0 && caretMoved) {
                var line = spans.get(caretLine);
                float caretRel = adv[clamp(caret, line.start(), line.end())] - adv[line.start()];
                if (caretRel - n.scrollX > viewW) {
                    n.scrollX = caretRel - viewW;
                }
                if (caretRel - n.scrollX < 0f) {
                    n.scrollX = caretRel;
                }
            }
            float widest = 0f;
            for (var line : spans) {
                widest = Math.max(widest, adv[line.end()] - adv[line.start()]);
            }
            n.scrollX = Math.max(0f, Math.min(n.scrollX, Math.max(0f, widest - viewW)));
        }

        if (!multiline) {
            n.scrollY = 0f;
            return;
        }
        if (caret >= 0 && caretMoved) {
            float caretTop = caretLine * lineH;
            if (caretTop + lineH - n.scrollY > viewH) {
                n.scrollY = caretTop + lineH - viewH;
            }
            if (caretTop - n.scrollY < 0f) {
                n.scrollY = caretTop;
            }
        }
        n.scrollY = Math.max(0f, Math.min(n.scrollY, Math.max(0f, spans.size() * lineH - viewH)));
    }

    /** The visual line containing {@code offset}: the last span whose start is at or before it. */
    private static int lineIndexOf(List<dev.vexelray.text.TextLayout.LineSpan> spans, int offset) {
        int found = 0;
        for (int i = 0; i < spans.size(); i++) {
            if (spans.get(i).start() <= offset) {
                found = i;
            } else {
                break;
            }
        }
        return found;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
