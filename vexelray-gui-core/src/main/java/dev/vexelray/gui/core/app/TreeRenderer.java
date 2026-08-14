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
        if (s != null && !s.isEmpty()) {
            TextLayout.TextStyle style = TextLayout.TextStyle.of(n.textSizePx)
                    .withWrap(TextLayout.WrapMode.WORD_CHAR)
                    .withAlign(n.hAlign(), n.vAlign());
            canvas.text(text, s, n.x + pad, n.y, Math.max(1f, n.w - 2 * pad), n.h, style, n.textColor());
        }
        // Text-field caret: a thin vertical bar at the caret offset, drawn only while shown this blink phase.
        int caret = n.caret();
        if (caret >= 0 && n.caretOn()) {
            drawCaret(n, caret, s == null ? "" : s, pad, canvas, text);
        }
    }

    /** Draw the caret bar for an editable field at character {@code caret}, measuring the prefix advance. */
    private static void drawCaret(RetainedNode n, int caret, String s, float pad, Canvas canvas, TextLayout text) {
        int clamped = Math.max(0, Math.min(caret, s.length()));
        float px = n.textSizePx;
        float prefixW = text.glyphLayout().measure(s.substring(0, clamped), px);
        float caretX = n.x + pad + prefixW;
        // Vertically centre a bar roughly the text's line height within the field.
        float lineH = text.glyphLayout().ascent(px) + text.glyphLayout().descent(px);
        float caretY = n.y + (n.h - lineH) * 0.5f;
        float w = Math.max(1f, px * 0.07f);
        canvas.fillRoundRect(caretX, caretY, w, lineH, 0f, n.textColor());
    }
}
