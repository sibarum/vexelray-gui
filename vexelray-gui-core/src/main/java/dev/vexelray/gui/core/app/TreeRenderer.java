package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.model.RetainedNode;
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

    private static final Color SCROLL_TRACK = Color.rgb(0x161b26);
    private static final Color SCROLL_THUMB = Color.rgb(0x39415a);

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

    /** Draw the reserved-space scrollbars (track + pill thumb) for an overflowing container — chrome, not clipped. */
    private static void drawScrollbars(RetainedNode n, Canvas canvas) {
        float sb = n.scrollbarPx;
        float minThumb = sb * 2f;
        if (n.overflowY) {
            float trackX = n.viewX + n.viewW;
            float th = Math.max(minThumb, n.viewH * (n.viewH / n.contentH));
            float maxScroll = Math.max(0f, n.contentH - n.viewH);
            float frac = maxScroll > 0f ? n.scrollY / maxScroll : 0f;
            float thumbY = n.viewY + frac * (n.viewH - th);
            canvas.fillRoundRect(trackX, n.viewY, sb, n.viewH, sb * 0.5f, SCROLL_TRACK);
            canvas.fillRoundRect(trackX + sb * 0.15f, thumbY, sb * 0.7f, th, sb * 0.35f, SCROLL_THUMB);
        }
        if (n.overflowX) {
            float trackY = n.viewY + n.viewH;
            float tw = Math.max(minThumb, n.viewW * (n.viewW / n.contentW));
            float maxScroll = Math.max(0f, n.contentW - n.viewW);
            float frac = maxScroll > 0f ? n.scrollX / maxScroll : 0f;
            float thumbX = n.viewX + frac * (n.viewW - tw);
            canvas.fillRoundRect(n.viewX, trackY, n.viewW, sb, sb * 0.5f, SCROLL_TRACK);
            canvas.fillRoundRect(thumbX, trackY + sb * 0.15f, tw, sb * 0.7f, sb * 0.35f, SCROLL_THUMB);
        }
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
        if (s != null && !s.isEmpty()) {
            TextLayout.TextStyle style = TextLayout.TextStyle.of(n.textSizePx)
                    .withWrap(TextLayout.WrapMode.WORD_CHAR)
                    .withAlign(n.hAlign(), n.vAlign());
            float pad = Math.min(TEXT_PAD_X, n.w * 0.25f);
            canvas.text(text, s, n.x + pad, n.y, Math.max(1f, n.w - 2 * pad), n.h, style, n.textColor());
        }
    }
}
