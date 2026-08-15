package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.core.text.TextMetrics;
import dev.vexelray.text.TextLayout;

/**
 * Walks a {@link RetainedNode} tree and draws it with VexelRay's native {@link Canvas} — the whole of the GUI's
 * "rendering". Submission order is paint order: a node draws its background, then its border, then its text, then
 * its children on top, depth-first. There is no Vulkan, shader, or vertex code here (nor anywhere in the GUI);
 * this only translates model props into {@code Canvas} calls.
 *
 * <p>Borders use the fill-then-inset trick (VexelRay's {@code Canvas} has no stroke-rect primitive): draw the
 * rounded rect in the border colour, then the fill inset by the border width. Clipping children to their parent
 * awaits the engine's clip-rect (architecture.md E3); for now children are trusted to sit within their bounds.
 */
public final class TreeRenderer {

    /** Horizontal inset, in px, so text doesn't kiss rounded corners. */
    private static final float TEXT_PAD_X = 10f;

    private static final Color SCROLL_OUTLINE = Color.rgb(0x39415a); // track border, visible against any panel
    private static final Color SCROLL_TRACK = Color.rgb(0x181d28);   // subtle inner fill
    private static final Color SCROLL_THUMB = Color.rgb(0x5a6685);   // clearly visible thumb

    private TreeRenderer() {
    }

    /** Emit the whole tree rooted at {@code node} into {@code canvas}, laying text with {@code text}. */
    public static void emit(RetainedNode node, Canvas canvas, TextLayout text) {
        drawSelf(node, canvas, text);
        boolean clip = node.overflowX || node.overflowY;
        if (clip) {
            // Clip children to the scroll viewport, honouring the container's (inset) rounded corner.
            float inset = node.viewX - node.x;
            float radius = Math.max(0f, node.cornerPx - inset);
            canvas.pushClip(node.viewX, node.viewY, node.viewW, node.viewH, radius);
        }
        for (RetainedNode child : node.children) {
            emit(child, canvas, text);
        }
        if (clip) {
            canvas.popClip();
            drawScrollbars(node, canvas);
        }
    }

    /** Draw the reserved-space scrollbars (outlined track + pill thumb) for an overflowing container — chrome. */
    private static void drawScrollbars(RetainedNode n, Canvas canvas) {
        float sb = n.scrollbarPx;
        if (n.overflowY) {
            track(canvas, n.viewX + n.viewW, n.viewY, sb, n.viewH);
            float[] t = n.vThumbRect();
            canvas.fillRoundRect(t[0], t[1], t[2], t[3], sb * 0.3f, SCROLL_THUMB);
        }
        if (n.overflowX) {
            track(canvas, n.viewX, n.viewY + n.viewH, n.viewW, sb);
            float[] t = n.hThumbRect();
            canvas.fillRoundRect(t[0], t[1], t[2], t[3], sb * 0.3f, SCROLL_THUMB);
        }
    }

    /** An outlined track: a border-coloured rounded rect with a subtle inner fill, so it reads against any panel. */
    private static void track(Canvas canvas, float x, float y, float w, float h) {
        float r = Math.min(w, h) * 0.5f;
        canvas.fillRoundRect(x, y, w, h, r, SCROLL_OUTLINE);
        canvas.fillRoundRect(x + 1.5f, y + 1.5f, Math.max(0f, w - 3f), Math.max(0f, h - 3f),
                Math.max(0f, r - 1.5f), SCROLL_TRACK);
    }

    private static void drawSelf(RetainedNode n, Canvas canvas, TextLayout text) {
        // Border width, corner radius and text size were resolved to px by the layout pass (border-box), so the
        // renderer needs no units or layout context — it just paints the computed rect.
        Color bg = n.background();
        float radius = n.cornerPx;
        float bw = n.borderPx;
        Color border = n.borderColor();
        if (bw > 0f && border != null) {
            canvas.fillRoundRect(n.x, n.y, n.w, n.h, radius, border);
            if (bg != null) {
                float innerR = Math.max(0f, radius - bw);
                canvas.fillRoundRect(n.x + bw, n.y + bw, Math.max(0f, n.w - 2 * bw),
                        Math.max(0f, n.h - 2 * bw), innerR, bg);
            }
        } else if (bg != null) {
            canvas.fillRoundRect(n.x, n.y, n.w, n.h, radius, bg);
        }

        String s = n.textString();
        float pad = Math.min(TEXT_PAD_X, n.w * 0.25f);
        boolean hasText = s != null && !s.isEmpty();
        java.util.List<Span> spans = hasText ? n.spans() : java.util.List.of();

        if (n.editable()) {
            drawField(n, s, hasText, spans, pad, canvas, text);
            return;
        }

        // A label keeps the canvas's own wrapping and alignment: its metrics are published for reading, but its
        // *drawing* is not unified onto them yet, because that needs per-line h/v alignment baked into
        // TextMetrics (docs/layout-read-model.md §11.4, remaining work).
        float originX = n.x + pad;
        if (hasText) {
            for (Span sp : spans) {
                if (sp.bg() != null) {
                    fillRangeRect(n, s, originX, sp.start(), sp.end(), sp.bg(), canvas, text);
                }
            }
            boolean anyFg = false;
            for (Span sp : spans) {
                if (sp.fg() != null) {
                    anyFg = true;
                    break;
                }
            }
            if (anyFg) {
                drawSpannedText(n, s, originX, spans, canvas, text);
            } else {
                TextLayout.TextStyle style = TextLayout.TextStyle.of(n.textSizePx)
                        .withWrap(TextLayout.WrapMode.WORD_CHAR)
                        .withAlign(n.hAlign(), n.vAlign());
                canvas.text(text, s, originX, n.y, Math.max(1f, n.w - 2 * pad), n.h, style, n.textColor());
            }
            for (Span sp : spans) {
                if (sp.underline()) {
                    drawUnderline(n, s, originX, sp.start(), sp.end(),
                            sp.fg() != null ? sp.fg() : n.textColor(), canvas, text);
                }
            }
        }
    }

    /**
     * Draw an editable field from its published {@link TextMetrics} — the single source of truth for where every
     * glyph, selection rect and caret sits (docs/layout-read-model.md §11.4). The compute phase already resolved
     * scroll and baked each visual line's absolute caret x positions, so the renderer measures nothing: it cannot
     * disagree with the widget about what is under the pointer, and a multi-line field needs no separate code
     * path here at all.
     */
    private static void drawField(RetainedNode n, String s, boolean hasText, java.util.List<Span> spans, float pad,
                                  Canvas canvas, TextLayout text) {
        // Mask overflow to the viewport the layout resolved — which excludes the h-scrollbar strip when there is
        // one, so text never draws underneath the bar. Multi-line clips vertically too.
        canvas.pushClip(n.viewX, n.viewY, Math.max(1f, n.viewW), Math.max(1f, n.viewH), 0f);

        TextMetrics m = n.textMetrics;
        if (hasText && m != null) {
            int selLo = Math.min(n.selectStart(), n.selectEnd());
            int selHi = Math.max(n.selectStart(), n.selectEnd());
            for (TextMetrics.VisualLine line : m.lines()) {
                for (Span sp : spans) {
                    if (sp.bg() != null) {
                        fillLineRange(line, sp.start(), sp.end(), sp.bg(), canvas);
                    }
                }
                if (selHi > selLo) {
                    fillLineRange(line, selLo, selHi, SELECTION, canvas);
                }
                drawLineText(n, s, line, spans, canvas, text);
                for (Span sp : spans) {
                    if (sp.underline()) {
                        underlineLineRange(n, line, sp.start(), sp.end(),
                                sp.fg() != null ? sp.fg() : n.textColor(), canvas, text);
                    }
                }
            }
        }

        // The caret: a thin bar on its own visual line, only while shown this blink phase.
        if (m != null && n.caret() >= 0 && n.caretOn()) {
            int caret = n.caret();
            float w = Math.max(1f, n.textSizePx * 0.07f);
            canvas.fillRoundRect(m.caretX(caret), m.caretTop(caret), w, m.caretHeight(caret), 0f, n.textColor());
        }
        canvas.popClip();
    }

    /** Fill the part of character range {@code [start, end)} that falls on {@code line}, using its baked xs. */
    private static void fillLineRange(TextMetrics.VisualLine line, int start, int end, Color color, Canvas canvas) {
        int lo = Math.max(line.start(), Math.min(start, end));
        int hi = Math.min(line.end(), Math.max(start, end));
        if (hi <= lo) {
            return;
        }
        float x0 = line.caretX(lo);
        float x1 = line.caretX(hi);
        canvas.fillRoundRect(x0, line.top(), Math.max(1f, x1 - x0), line.height(), 0f, color);
    }

    /** Draw one visual line's glyphs, split into maximal runs of a constant effective foreground colour. */
    private static void drawLineText(RetainedNode n, String s, TextMetrics.VisualLine line,
                                     java.util.List<Span> spans, Canvas canvas, TextLayout text) {
        int from = Math.max(0, line.start());
        int to = Math.min(s.length(), line.end());
        int i = from;
        while (i < to) {
            Color fg = fgAt(spans, i, n.textColor());
            int j = i + 1;
            while (j < to && java.util.Objects.equals(fgAt(spans, j, n.textColor()), fg)) {
                j++;
            }
            // Each run is drawn at its exact baked x, in a box exactly one line high, so the canvas does no
            // alignment or wrapping of its own — the metrics already decided all of it.
            TextLayout.TextStyle style = TextLayout.TextStyle.of(n.textSizePx)
                    .withWrap(TextLayout.WrapMode.NONE)
                    .withAlign(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
            float x = line.caretX(i);
            canvas.text(text, s.substring(i, j), x, line.top(),
                    Math.max(1f, n.x + n.w - x), line.height(), style, fg);
            i = j;
        }
    }

    /** Underline the part of {@code [start, end)} falling on {@code line}, just below its baseline. */
    private static void underlineLineRange(RetainedNode n, TextMetrics.VisualLine line, int start, int end,
                                           Color color, Canvas canvas, TextLayout text) {
        int lo = Math.max(line.start(), Math.min(start, end));
        int hi = Math.min(line.end(), Math.max(start, end));
        if (hi <= lo) {
            return;
        }
        float thickness = Math.max(1f, n.textSizePx * 0.06f);
        float baseline = line.top() + text.glyphLayout().ascent(n.textSizePx);
        canvas.fillRoundRect(line.caretX(lo), baseline + thickness,
                Math.max(1f, line.caretX(hi) - line.caretX(lo)), thickness, 0f, color);
    }

    /** X (px, absolute) of the caret position before character {@code offset}, from the (scrolled) text origin. */
    private static float runX(float originX, String s, int offset, float px, TextLayout text) {
        int clamped = Math.max(0, Math.min(offset, s.length()));
        return originX + text.glyphLayout().measure(s.substring(0, clamped), px);
    }

    /** Top y (px) of the single text line within the node, vertically centred. */
    private static float lineTop(RetainedNode n, TextLayout text) {
        return n.y + (n.h - lineHeight(n, text)) * 0.5f;
    }

    private static float lineHeight(RetainedNode n, TextLayout text) {
        return text.glyphLayout().ascent(n.textSizePx) + text.glyphLayout().descent(n.textSizePx);
    }

    /** Fill a rect over the character range [start, end) from {@code originX} — used for span backgrounds. */
    private static void fillRangeRect(RetainedNode n, String s, float originX, int start, int end, Color color,
                                      Canvas canvas, TextLayout text) {
        int lo = Math.max(0, Math.min(start, end));
        int hi = Math.min(s.length(), Math.max(start, end));
        if (hi <= lo) {
            return;
        }
        float x0 = runX(originX, s, lo, n.textSizePx, text);
        float x1 = runX(originX, s, hi, n.textSizePx, text);
        canvas.fillRoundRect(x0, lineTop(n, text), Math.max(1f, x1 - x0), lineHeight(n, text), 0f, color);
    }

    /** Underline the character range [start, end) just below the baseline. */
    private static void drawUnderline(RetainedNode n, String s, float originX, int start, int end, Color color,
                                      Canvas canvas, TextLayout text) {
        int lo = Math.max(0, Math.min(start, end));
        int hi = Math.min(s.length(), Math.max(start, end));
        if (hi <= lo) {
            return;
        }
        float px = n.textSizePx;
        float x0 = runX(originX, s, lo, px, text);
        float x1 = runX(originX, s, hi, px, text);
        float baseline = lineTop(n, text) + text.glyphLayout().ascent(px);
        float thickness = Math.max(1f, px * 0.06f);
        canvas.fillRoundRect(x0, baseline + thickness, Math.max(1f, x1 - x0), thickness, 0f, color);
    }

    /**
     * Draw {@code s} split into maximal runs of a constant effective foreground colour (the topmost fg span at
     * each character, else the node colour), from the (scrolled) origin. Single-line, left-origin — the case for
     * editable fields and simple labels; wrapped/centred spanned text is a later refinement.
     */
    private static void drawSpannedText(RetainedNode n, String s, float originX, java.util.List<Span> spans,
                                        Canvas canvas, TextLayout text) {
        float px = n.textSizePx;
        int len = s.length();
        int i = 0;
        while (i < len) {
            Color fg = fgAt(spans, i, n.textColor());
            int j = i + 1;
            while (j < len && java.util.Objects.equals(fgAt(spans, j, n.textColor()), fg)) {
                j++;
            }
            float x = runX(originX, s, i, px, text);
            TextLayout.TextStyle style = TextLayout.TextStyle.of(px)
                    .withWrap(TextLayout.WrapMode.NONE)
                    .withAlign(TextLayout.HAlign.LEFT, n.vAlign());
            canvas.text(text, s.substring(i, j), x, n.y, Math.max(1f, n.x + n.w - x), n.h, style, fg);
            i = j;
        }
    }

    /** The effective foreground colour at character {@code i}: the last fg span covering it, else {@code base}. */
    private static Color fgAt(java.util.List<Span> spans, int i, Color base) {
        Color fg = base;
        for (Span sp : spans) {
            if (sp.fg() != null && sp.covers(i)) {
                fg = sp.fg();
            }
        }
        return fg;
    }

    /** Translucent selection background, sized text-color-neutral so glyphs stay legible on top. */
    private static final Color SELECTION = Color.withAlpha(Color.rgb(0x3aa0ff), 0.35f);

    /** Draw the selection highlight from {@code selectStart} to {@code selectEnd} (order-independent). */
    private static void drawSelection(RetainedNode n, String s, float originX, Canvas canvas, TextLayout text) {
        int lo = Math.max(0, Math.min(n.selectStart(), n.selectEnd()));
        int hi = Math.min(s.length(), Math.max(n.selectStart(), n.selectEnd()));
        if (hi <= lo) {
            return;
        }
        float px = n.textSizePx;
        float x0 = runX(originX, s, lo, px, text);
        float x1 = runX(originX, s, hi, px, text);
        canvas.fillRoundRect(x0, lineTop(n, text), Math.max(1f, x1 - x0), lineHeight(n, text), 0f, SELECTION);
    }

    /** Draw the caret bar for an editable field at character {@code caret}, measuring the prefix advance. */
    private static void drawCaret(RetainedNode n, int caret, String s, float originX, Canvas canvas, TextLayout text) {
        float px = n.textSizePx;
        float caretX = runX(originX, s, caret, px, text);
        float lineH = lineHeight(n, text);
        float caretY = n.y + (n.h - lineH) * 0.5f;
        float w = Math.max(1f, px * 0.07f);
        canvas.fillRoundRect(caretX, caretY, w, lineH, 0f, n.textColor());
    }
}
