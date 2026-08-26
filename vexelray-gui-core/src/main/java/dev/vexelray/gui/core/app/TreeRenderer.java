package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
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

    /**
     * The colours the renderer paints that no node declares — scrollbars, the shadow under an elevated box, line
     * numbers, the selection wash, and the ink a text node that never stated one falls back to. Chrome is the
     * framework's own furniture, so it comes from the theme rather than from a prop, and all of it resolves once
     * per frame: one small object per frame, against a vertex array.
     */
    private final Color scrollEdge;
    private final Color scrollTrough;
    private final Color scrollGrip;
    private final Color shadow;
    private final Color gutterInk;
    private final Color selection;
    private final Color ink;

    /**
     * The opacity inherited at the node currently being drawn — the product of every {@code OPACITY} prop on the
     * path from the root. Every colour this class hands to the {@link Canvas} goes through {@link #fade}, so the
     * transform reaches the chrome the tree never declared (a scrollbar, a shadow, the selection wash) as well as
     * the props a node carries. Walk-scoped rather than a parameter because it applies to *every* colour, and a
     * value threaded through fifteen signatures is a value that will eventually be forgotten on the sixteenth.
     */
    private float alpha = 1f;
    // The live clip, in the coordinates the nodes' own boxes are in: what masked() judges a child against. Starts
    // unbounded, narrows on every clip pushed, and is restored on the way back out of each node.
    private float clipL = Float.NEGATIVE_INFINITY;
    private float clipT = Float.NEGATIVE_INFINITY;
    private float clipR = Float.POSITIVE_INFINITY;
    private float clipB = Float.POSITIVE_INFINITY;

    private TreeRenderer(Theme theme) {
        this.scrollEdge = theme.color(Role.EDGE);
        this.scrollTrough = theme.color(Role.CHROME);
        this.scrollGrip = theme.color(Role.GRIP);
        this.shadow = theme.color(Role.SHADOW);
        this.gutterInk = theme.color(Role.FAINT);
        this.selection = theme.color(Role.HIGHLIGHT);
        // The fallback for a text node that never declared a colour. The model answers white when asked with no
        // opinion (RetainedNode.textColor), which is only right on a dark page -- so the renderer supplies one.
        this.ink = theme.color(Role.INK);
    }

    /** Single-face convenience: everything renders with {@code text} regardless of node font indices. */
    public static void emit(RetainedNode node, Canvas canvas, TextLayout text) {
        emit(node, canvas, new TextLayout[]{text}, Theme.DARK);
    }

    /** As {@link #emit(RetainedNode, Canvas, TextLayout[], Theme)}, in the framework's default theme. */
    public static void emit(RetainedNode node, Canvas canvas, TextLayout[] faces) {
        emit(node, canvas, faces, Theme.DARK);
    }

    /**
     * Emit the whole tree rooted at {@code node} into {@code canvas}. {@code faces} holds one {@link TextLayout}
     * per atlas face, index-aligned with {@code RetainedNode.font()}; each text node draws with its own face
     * (out-of-range indices degrade to face 0, matching the measurer).
     *
     * <p>{@code theme} supplies only the chrome the tree does not declare (see the fields above); every colour a
     * node carries was resolved when the prop was written.
     */
    public static void emit(RetainedNode node, Canvas canvas, TextLayout[] faces, Theme theme) {
        new TreeRenderer(theme == null ? Theme.DARK : theme).walk(node, canvas, faces);
    }

    private void walk(RetainedNode node, Canvas canvas, TextLayout[] faces) {
        if (!node.visible()) {
            return;   // hidden: nothing drawn, and the subtree is not walked
        }
        float inherited = alpha;
        alpha = inherited * node.opacity();
        // Fully transparent: skip the subtree exactly as a hidden node does. Not the same as hiding it, though —
        // it was laid out, so its box is real and it is still hit-testable unless it also said hitInert.
        if (alpha > 0f) {
            // Displacement, in multiples of this node's own baked em, so it tracks zoom and density with no
            // layout context to consult. Pushed around the node and its whole subtree, and cumulative.
            float dx = node.translateX() * node.emPx;
            float dy = node.translateY() * node.emPx;
            boolean moved = dx != 0f || dy != 0f;
            if (moved) {
                canvas.pushTranslate(dx, dy);
            }
            drawSelf(node, canvas, faceFor(faces, node));
            boolean scrollClip = node.overflowX || node.overflowY;
            // An overflowing container clips to its scroll viewport; a container that merely said so clips to its
            // whole border box. Both are the same mask, asked for two different ways, so only one is pushed.
            float keepL = clipL;
            float keepT = clipT;
            float keepR = clipR;
            float keepB = clipB;
            if (moved) {
                // The subtree draws dx/dy from where its boxes say it is, so the mask moves the other way in the
                // space those boxes are expressed in.
                clipL -= dx;
                clipT -= dy;
                clipR -= dx;
                clipB -= dy;
            }
            float flowL = clipL;
            float flowT = clipT;
            float flowR = clipR;
            float flowB = clipB;
            if (scrollClip) {
                // Clip children to the scroll viewport, honouring the container's (inset) rounded corner.
                float inset = node.viewX - node.x;
                float radius = Math.max(0f, node.cornerPx - inset);
                canvas.pushClip(node.viewX, node.viewY, node.viewW, node.viewH, radius);
                narrow(node.viewX, node.viewY, node.viewW, node.viewH);
            } else if (node.clip()) {
                canvas.pushClip(node.x, node.y, node.w, node.h, node.cornerPx);
                narrow(node.x, node.y, node.w, node.h);
            }
            for (RetainedNode child : node.children) {
                if (!child.floating() && !masked(child)) {
                    walk(child, canvas, faces);
                }
            }
            if (scrollClip) {
                canvas.popClip();
                drawScrollbars(node, canvas);   // the container's chrome, so still at the container's alpha
                // The scroll mask hides what scrolled out of the flow, and a float is not in the flow: it was
                // placed against the settled box and stays there while the content moves under it. Clipping it to
                // the viewport would cut chrome by the padding, the line-number gutter and the scrollbar strip —
                // an overlay trimmed by facts about the content it sits over. An explicit clip() is left to reach
                // it, because that one is a statement about the whole subtree rather than about the flow.
                clipL = flowL;
                clipT = flowT;
                clipR = flowR;
                clipB = flowB;
            }
            // Floating children last, out of the flow's mask as they were out of its layout — so they paint over
            // every sibling, which is what makes one an overlay.
            for (RetainedNode child : node.children) {
                if (child.floating() && !masked(child)) {
                    walk(child, canvas, faces);
                }
            }
            if (!scrollClip && node.clip()) {
                canvas.popClip();
            }
            if (moved) {
                canvas.popTranslate();
            }
            clipL = keepL;
            clipT = keepT;
            clipR = keepR;
            clipB = keepB;
        }
        alpha = inherited;   // one restore point, on every path out
    }

    /**
     * Bring the live mask in to {@code (x, y, w, h)} — the same rectangle just pushed on the canvas, tracked here
     * so {@link #masked} can answer without asking the canvas about its own state.
     */
    private void narrow(float x, float y, float w, float h) {
        clipL = Math.max(clipL, x);
        clipT = Math.max(clipT, y);
        clipR = Math.min(clipR, x + w);
        clipB = Math.min(clipB, y + h);
    }

    /**
     * Whether {@code child} is far enough outside the live mask that nothing it draws could land inside it.
     *
     * <p><b>Why this is worth the test.</b> A clip hides what it covers; it does not stop the geometry underneath
     * being built. A tailing log keeps thousands of line nodes and shows twenty of them, and without this every
     * one of the rest is turned into glyph quads, every frame, to be masked out — which is not merely wasteful:
     * it is what makes a scrollback overrun a fixed vertex buffer and take the window down with it. This is the
     * same reasoning {@code drawText} already applies to the visual lines <em>inside</em> one text node, applied
     * one level up to the nodes inside one container.
     *
     * <p><b>Why the slack.</b> A node is not guaranteed to draw inside its own box: text given a fixed height
     * smaller than it needs spills past the bottom of it rather than being cut off. So a box has to be a whole
     * box-length clear of the mask before it is dropped, which is far more than any spill and still culls all but
     * a line or two either side of a long list.
     *
     * <p>A box with no area is never culled. Its own rectangle says nothing about where its children are, and a
     * zero-sized wrapper around real content is a shape the layout is allowed to produce.
     */
    private boolean masked(RetainedNode child) {
        if (clipL == Float.NEGATIVE_INFINITY || child.w <= 0f || child.h <= 0f) {
            return false;   // nothing masks here, or nothing to judge it by
        }
        return child.x + child.w + child.w < clipL
                || child.x - child.w > clipR
                || child.y + child.h + child.h < clipT
                || child.y - child.h > clipB;
    }

    /**
     * {@code c} at the opacity inherited here. The single place the transform is applied: nothing in this class
     * passes a raw {@link Color} to the canvas.
     */
    private Color fade(Color c) {
        return c == null || alpha >= 1f ? c : Color.withAlpha(c, c.a() * alpha);
    }

    private static TextLayout faceFor(TextLayout[] faces, RetainedNode n) {
        int f = n.font();
        return faces[f <= 0 ? 0 : Math.min(f, faces.length - 1)];
    }

    /** Draw the reserved-space scrollbars (outlined track + pill thumb) for an overflowing container — chrome. */
    private void drawScrollbars(RetainedNode n, Canvas canvas) {
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
    private void thumb(Canvas canvas, float[] t, float sb) {
        float r = sb * 0.3f;
        canvas.shadowRoundRect(t[0], t[1] + 1f, t[2], t[3], r, 2.5f, fade(shadow));
        canvas.litRoundRect(t[0], t[1], t[2], t[3], r, 2f, 0.08f, fade(scrollGrip));
    }

    /** An outlined track: a border-coloured rounded rect with a subtle inner fill, so it reads against any panel. */
    private void track(Canvas canvas, float x, float y, float w, float h) {
        float r = Math.min(w, h) * 0.5f;
        canvas.fillRoundRect(x, y, w, h, r, fade(scrollEdge));
        canvas.fillRoundRect(x + 1.5f, y + 1.5f, Math.max(0f, w - 3f), Math.max(0f, h - 3f),
                Math.max(0f, r - 1.5f), fade(scrollTrough));
    }

    private void drawSelf(RetainedNode n, Canvas canvas, TextLayout text) {
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
            canvas.shadowRoundRect(n.x, n.y + e * 0.5f, n.w, n.h, rTop, rBottom, e, fade(shadow));
        }
        if (bg != null) {
            if (n.lit()) {
                // Bevel scales with the type size so the edge light stays proportionate under zoom.
                canvas.litRoundRect(n.x, n.y, n.w, n.h, rTop, rBottom,
                        Math.max(2f, n.emPx * 0.22f), 0.05f, fade(bg));
            } else {
                canvas.fillRoundRect(n.x, n.y, n.w, n.h, rTop, rBottom, fade(bg));
            }
        }
        // An image sits between the background and the border: a background shows through anything transparent in
        // it, and a border frames it. It takes the node's own radius, so a viewport rounds with the card it is in.
        Object image = n.image();
        if (image != null && n.w > 0f && n.h > 0f) {
            // An alpha, not a colour: the tint multiplies the texel, so there is no shade to choose here — only
            // the subtree opacity this class applies to everything it draws.
            canvas.image(n.x, n.y, n.w, n.h, rTop, rBottom, image, alpha);
        }
        if (bw > 0f && border != null) {
            canvas.strokeRoundRect(n.x, n.y, n.w, n.h, rTop, rBottom, bw, fade(border));
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
    private void drawText(RetainedNode n, String s, boolean hasText, java.util.List<Span> spans, float pad,
                                 Canvas canvas, TextLayout text) {
        TextMetrics m = n.textMetrics;
        if (m == null) {
            return;   // not a text node, or a measurer with no glyph metrics resolved none
        }
        // An empty document still publishes metrics — one empty visual line — so a focused empty field draws its
        // caret and a numbered editor its "1". The glyph/selection/span loops below are naturally no-ops on "".
        if (!hasText) {
            s = "";
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

        // Cull lines outside the scroll viewport: the clip would mask them anyway, but their glyph quads would
        // still be generated — an unbounded document must not translate into unbounded per-frame vertex data.
        float cullLo = clip ? n.viewY : Float.NEGATIVE_INFINITY;
        float cullHi = clip ? n.viewY + n.viewH : Float.POSITIVE_INFINITY;
        int selLo = Math.min(n.selectStart(), n.selectEnd());
        int selHi = Math.max(n.selectStart(), n.selectEnd());
        for (TextMetrics.VisualLine line : m.lines()) {
            if (line.top() + line.height() < cullLo || line.top() > cullHi) {
                continue;
            }
            // Narrow the span set to this line once: drawLineText consults the spans per character run, so
            // handing it the whole document's spans makes a densely highlighted document O(chars x spans)
            // per frame. Intersecting here keeps that inner loop proportional to what is on the line.
            java.util.List<Span> lineSpans = spansOn(spans, line);
            for (Span sp : lineSpans) {
                if (sp.bg() != null) {
                    fillLineRange(line, sp.start(), sp.end(), fade(sp.bg()), canvas);
                }
            }
            if (selHi > selLo) {
                fillLineRange(line, selLo, selHi, fade(selection), canvas);
            }
            drawLineText(n, s, line, lineSpans, canvas, text);
            for (Span sp : lineSpans) {
                if (sp.underline()) {
                    underlineLineRange(n, line, sp.start(), sp.end(),
                            fade(sp.fg() != null ? sp.fg() : n.textColor(ink)), canvas, text);
                }
            }
        }

        // The caret: a thin bar on its own visual line, only while shown this blink phase.
        if (n.caret() >= 0 && n.caretOn()) {
            int caret = n.caret();
            float w = Math.max(1f, n.textSizePx * 0.07f);
            canvas.fillRoundRect(m.caretX(caret), m.caretTop(caret), w, m.caretHeight(caret), 0f,
                    fade(n.textColor(ink)));
        }
        if (clip) {
            canvas.popClip();
        }
    }

    /**
     * Right-aligned hard-line numbers in the gutter. Only lines that <em>begin</em> a hard line carry a number
     * (the metrics decided that, not the renderer), so wrapped continuations are blank — which is what makes a
     * wrapped line read as one line.
     */
    private void drawGutter(RetainedNode n, TextMetrics m, Canvas canvas, TextLayout text) {
        float px = n.textSizePx;
        float left = n.x + n.textPadXPx;
        float right = left + n.gutterPx - n.gutterPadPx;
        canvas.pushClip(left, n.viewY, Math.max(1f, n.gutterPx), Math.max(1f, n.viewH), 0f);
        for (TextMetrics.VisualLine line : m.lines()) {
            if (line.number() <= 0
                    || line.top() + line.height() < n.viewY || line.top() > n.viewY + n.viewH) {
                continue; // unnumbered continuation, or culled outside the gutter's viewport (same as the text)
            }
            String label = Integer.toString(line.number());
            float w = text.glyphLayout().measure(label, px);
            TextLayout.TextStyle style = TextLayout.TextStyle.of(px)
                    .withWrap(TextLayout.WrapMode.NONE)
                    .withAlign(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
            canvas.text(text, label, right - w, line.top(), Math.max(1f, w), line.height(), style, fade(gutterInk));
        }
        canvas.popClip();
    }

    /** The spans whose range intersects {@code line} — the only ones any per-line drawing can need. */
    private static java.util.List<Span> spansOn(java.util.List<Span> spans, TextMetrics.VisualLine line) {
        if (spans.isEmpty()) {
            return spans;
        }
        java.util.List<Span> out = new java.util.ArrayList<>();
        for (Span sp : spans) {
            if (sp.start() < line.end() && sp.end() > line.start()) {
                out.add(sp);
            }
        }
        return out;
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
    private void drawLineText(RetainedNode n, String s, TextMetrics.VisualLine line,
                                     java.util.List<Span> spans, Canvas canvas, TextLayout text) {
        int from = Math.max(0, line.start());
        int to = Math.min(s.length(), line.end());
        int i = from;
        while (i < to) {
            Color fg = fgAt(spans, i, n.textColor(ink));
            int j = i + 1;
            while (j < to && java.util.Objects.equals(fgAt(spans, j, n.textColor(ink)), fg)) {
                j++;
            }
            // Each run is drawn at its exact baked x, in a box exactly one line high, so the canvas does no
            // alignment or wrapping of its own — the metrics already decided all of it.
            TextLayout.TextStyle style = TextLayout.TextStyle.of(n.textSizePx)
                    .withWrap(TextLayout.WrapMode.NONE)
                    .withAlign(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
            float x = line.caretX(i);
            float wRun = Math.max(1f, n.x + n.w - x);
            // Runs are split on the unfaded colour: the fade is uniform across the line, so it cannot merge or
            // divide a run, and comparing before it keeps the grouping independent of the current opacity.
            Color faded = fade(fg);
            if (n.textSunken()) {
                // Letterpress: the press depth scales with the type size, so the effect survives zoom.
                canvas.textSunken(text, s.substring(i, j), x, line.top(), wRun, line.height(), style, faded,
                        Math.max(1f, n.textSizePx * 0.07f));
            } else {
                canvas.text(text, s.substring(i, j), x, line.top(), wRun, line.height(), style, faded);
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
}
