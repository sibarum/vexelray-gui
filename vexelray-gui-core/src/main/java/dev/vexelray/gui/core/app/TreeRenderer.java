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
 * <p>Depth and light are translated the same way: {@code elevation} becomes a {@code shadowRoundRect} under the
 * background, {@code lit} swaps the background fill for a {@code litRoundRect}, and borders are a real
 * {@code strokeRoundRect} ring — all of them transfer functions over the one rounded-box SDF the engine already
 * evaluates, so a whole lit, shadowed, outlined panel is still zero extra textures and one draw.
 */
public final class TreeRenderer {

    private static final Color SCROLL_OUTLINE = Color.rgb(0x39415a); // track border, visible against any panel
    private static final Color SCROLL_TRACK = Color.rgb(0x181d28);   // subtle inner fill
    private static final Color SCROLL_THUMB = Color.rgb(0x5a6685);   // clearly visible thumb
    private static final Color SHADOW = Color.withAlpha(Color.rgb(0x05070c), 0.55f); // elevation shadow ink

    private TreeRenderer() {
    }

    /** Emit the whole tree rooted at {@code node} into {@code canvas}, laying text with {@code text}. */
    public static void emit(RetainedNode node, Canvas canvas, TextLayout text) {
        if (!node.visible()) {
            return;   // hidden: nothing drawn, and the subtree is not walked
        }
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
            thumb(canvas, n.vThumbRect(), sb);
        }
        if (n.overflowX) {
            track(canvas, n.viewX, n.viewY + n.viewH, n.viewW, sb);
            thumb(canvas, n.hThumbRect(), sb);
        }
    }

    /** A lit pill floating just off its track: small shadow underneath, edge light on top — grabbable at a glance. */
    private static void thumb(Canvas canvas, float[] t, float sb) {
        float r = sb * 0.3f;
        canvas.shadowRoundRect(t[0], t[1] + 1f, t[2], t[3], r, 2.5f, SHADOW);
        canvas.litRoundRect(t[0], t[1], t[2], t[3], r, 2f, 0.08f, SCROLL_THUMB);
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
        float rTop = n.cornerPx;
        float rBottom = n.cornerBottomPx;
        float bw = n.borderPx;
        Color border = n.borderColor();
        // Elevation: an analytic soft shadow under the border-box, dropped slightly with the light overhead.
        if (n.elevationPx > 0f && (bg != null || border != null)) {
            float e = n.elevationPx;
            canvas.shadowRoundRect(n.x, n.y + e * 0.5f, n.w, n.h, rTop, rBottom, e, SHADOW);
        }
        if (bg != null) {
            if (n.lit()) {
                // Bevel scales with the type size so the edge light stays proportionate under zoom.
                canvas.litRoundRect(n.x, n.y, n.w, n.h, rTop, rBottom, Math.max(2f, n.emPx * 0.22f), 0.05f, bg);
            } else {
                canvas.fillRoundRect(n.x, n.y, n.w, n.h, rTop, rBottom, bg);
            }
        }
        if (bw > 0f && border != null) {
            canvas.strokeRoundRect(n.x, n.y, n.w, n.h, rTop, rBottom, bw, border);
        }

        String s = n.textString();
        float pad = n.textPadXPx;   // resolved by the layout — the renderer resolves no units of its own
        boolean hasText = s != null && !s.isEmpty();
        java.util.List<Span> spans = hasText ? n.spans() : java.util.List.of();

        drawText(n, s, hasText, spans, pad, canvas, text);
    }

    /**
     * Draw a text node from its published {@link TextMetrics} — the single source of truth for where every glyph,
     * selection rect and caret sits (docs/layout-read-model.md §11.4). The compute phase resolved scroll and
     * alignment and baked each visual line's absolute caret x, so the renderer measures and aligns nothing.
     *
     * <p>Labels and fields share this path. They used to differ: a label drew through {@code canvas.text} with
     * its own align and wrap while its metrics described a left-aligned block, so a centred label's published
     * geometry was a lie to every consumer except this renderer. That was a broken invariant, not a missing
     * feature — the read-model is supposed to describe what is drawn, and the only way to guarantee it is for the
     * drawing to come from the read-model.
     */
    private static void drawText(RetainedNode n, String s, boolean hasText, java.util.List<Span> spans, float pad,
                                 Canvas canvas, TextLayout text) {
        TextMetrics m = n.textMetrics;
        if (!hasText || m == null) {
            return;   // nothing to draw, or a measurer with no glyph metrics resolved none
        }
        // The gutter sits outside the text viewport, so it is drawn first, under its own clip: it scrolls
        // vertically with the lines but never horizontally with them.
        if (n.gutterPx > 0f) {
            drawGutter(n, m, canvas, text);
        }

        // Only an editable node masks: it is the one that scrolls, and clipping a label would newly hide text
        // that a too-short fixed height has always been allowed to spill. The viewport excludes the h-scrollbar
        // strip when there is one, so text never draws underneath the bar.
        boolean clip = n.editable();
        if (clip) {
            canvas.pushClip(n.viewX, n.viewY, Math.max(1f, n.viewW), Math.max(1f, n.viewH), 0f);
        }

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

        // The caret: a thin bar on its own visual line, only while shown this blink phase.
        if (n.caret() >= 0 && n.caretOn()) {
            int caret = n.caret();
            float w = Math.max(1f, n.textSizePx * 0.07f);
            canvas.fillRoundRect(m.caretX(caret), m.caretTop(caret), w, m.caretHeight(caret), 0f, n.textColor());
        }
        if (clip) {
            canvas.popClip();
        }
    }

    /** Dimmed line-number colour — present but never competing with the text it numbers. */
    private static final Color GUTTER_INK = Color.rgb(0x6b7590);

    /**
     * Right-aligned hard-line numbers in the gutter. Only lines that <em>begin</em> a hard line carry a number
     * (the metrics decided that, not the renderer), so wrapped continuations are blank — which is what makes a
     * wrapped line read as one line.
     */
    private static void drawGutter(RetainedNode n, TextMetrics m, Canvas canvas, TextLayout text) {
        float px = n.textSizePx;
        float left = n.x + n.textPadXPx;
        float right = left + n.gutterPx - n.gutterPadPx;
        canvas.pushClip(left, n.viewY, Math.max(1f, n.gutterPx), Math.max(1f, n.viewH), 0f);
        for (TextMetrics.VisualLine line : m.lines()) {
            if (line.number() <= 0) {
                continue;
            }
            String label = Integer.toString(line.number());
            float w = text.glyphLayout().measure(label, px);
            TextLayout.TextStyle style = TextLayout.TextStyle.of(px)
                    .withWrap(TextLayout.WrapMode.NONE)
                    .withAlign(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
            canvas.text(text, label, right - w, line.top(), Math.max(1f, w), line.height(), style, GUTTER_INK);
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
            float wRun = Math.max(1f, n.x + n.w - x);
            if (n.textSunken()) {
                // Letterpress: the press depth scales with the type size, so the effect survives zoom.
                canvas.textSunken(text, s.substring(i, j), x, line.top(), wRun, line.height(), style, fg,
                        Math.max(1f, n.textSizePx * 0.07f));
            } else {
                canvas.text(text, s.substring(i, j), x, line.top(), wRun, line.height(), style, fg);
            }
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

}
