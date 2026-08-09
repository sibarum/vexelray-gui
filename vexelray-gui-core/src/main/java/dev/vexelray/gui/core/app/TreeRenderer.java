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

    private TreeRenderer() {
    }

    /** Emit the whole tree rooted at {@code node} into {@code canvas}, laying text with {@code text}. */
    public static void emit(RetainedNode node, Canvas canvas, TextLayout text) {
        drawSelf(node, canvas, text);
        for (RetainedNode child : node.children) {
            emit(child, canvas, text);
        }
    }

    private static void drawSelf(RetainedNode n, Canvas canvas, TextLayout text) {
        if (n.borderWidth > 0f && n.borderColor != null) {
            canvas.fillRoundRect(n.x, n.y, n.w, n.h, n.cornerRadius, n.borderColor);
            float bw = n.borderWidth;
            Color fill = n.background;
            if (fill != null) {
                float innerR = Math.max(0f, n.cornerRadius - bw);
                canvas.fillRoundRect(n.x + bw, n.y + bw, n.w - 2 * bw, n.h - 2 * bw, innerR, fill);
            }
        } else if (n.background != null) {
            canvas.fillRoundRect(n.x, n.y, n.w, n.h, n.cornerRadius, n.background);
        }

        if (n.text != null && !n.text.isEmpty()) {
            TextLayout.TextStyle style = TextLayout.TextStyle.of(n.textSize)
                    .withWrap(TextLayout.WrapMode.WORD_CHAR)
                    .withAlign(n.hAlign, n.vAlign);
            float pad = Math.min(TEXT_PAD_X, n.w * 0.25f);
            canvas.text(text, n.text, n.x + pad, n.y, Math.max(1f, n.w - 2 * pad), n.h, style, n.textColor);
        }
    }
}
