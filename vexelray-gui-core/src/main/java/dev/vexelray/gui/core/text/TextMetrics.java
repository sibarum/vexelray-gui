package dev.vexelray.gui.core.text;

import java.util.List;

/**
 * Computed caret geometry for a text node, published as data in the layout read-model
 * (docs/layout-read-model.md). Pure and transport-serializable: the visual lines and the per-boundary x
 * positions are baked in <b>absolute root-space pixels</b> at publish time (core measures once, with the
 * atlas), so a widget — or a remote client with no atlas — answers point↔offset and vertical navigation as pure
 * lookups. Coordinates match {@code ClickEvent} and the renderer, so no pad/scroll bookkeeping leaks to callers.
 */
public record TextMetrics(List<VisualLine> lines) {

    /** Horizontal text inset (px) the renderer uses; kept here so publish and render agree on the text origin. */
    public static final float PAD_X = 10f;

    /**
     * One visual line: its character range {@code [start, end]} (end inclusive of the caret-after-last position),
     * its top and height in root space, and {@code xs} — the absolute x of the caret before each character in the
     * line, length {@code (end - start) + 1} (so {@code xs[0]} is the line's left caret x and the last entry is
     * the caret x after the final character).
     */
    public record VisualLine(int start, int end, float top, float height, float[] xs) {

        public float bottom() {
            return top + height;
        }

        /** Absolute caret x for a character {@code offset} within (or clamped to) this line. */
        public float caretX(int offset) {
            int i = clampIndex(offset - start);
            return xs[i];
        }

        /** The character offset in this line whose caret x is nearest {@code absX}. */
        public int nearestOffset(float absX) {
            int best = 0;
            float bestD = Math.abs(xs[0] - absX);
            for (int i = 1; i < xs.length; i++) {
                float d = Math.abs(xs[i] - absX);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            return start + best;
        }

        private int clampIndex(int i) {
            return i < 0 ? 0 : Math.min(i, xs.length - 1);
        }
    }

    /** The offset nearest the absolute point {@code (absX, absY)} — the query a click uses. */
    public int offsetAt(float absX, float absY) {
        return lineByY(absY).nearestOffset(absX);
    }

    /** Absolute caret x for {@code offset} (in its visual line). */
    public float caretX(int offset) {
        return lineOf(offset).caretX(offset);
    }

    /** Absolute top y of the visual line containing {@code offset}. */
    public float caretTop(int offset) {
        return lineOf(offset).top();
    }

    /** Height of the visual line containing {@code offset}. */
    public float caretHeight(int offset) {
        return lineOf(offset).height();
    }

    /** The offset one visual line up from {@code offset}, nearest column {@code desiredX} (unchanged on line 0). */
    public int offsetAbove(int offset, float desiredX) {
        int li = lineIndexOf(offset);
        return li <= 0 ? offset : lines.get(li - 1).nearestOffset(desiredX);
    }

    /** The offset one visual line down from {@code offset}, nearest column {@code desiredX} (unchanged on last). */
    public int offsetBelow(int offset, float desiredX) {
        int li = lineIndexOf(offset);
        return li >= lines.size() - 1 ? offset : lines.get(li + 1).nearestOffset(desiredX);
    }

    /** Start offset of the visual line containing {@code offset} (visual Home). */
    public int lineStart(int offset) {
        return lineOf(offset).start();
    }

    /** End offset of the visual line containing {@code offset} (visual End). */
    public int lineEnd(int offset) {
        return lineOf(offset).end();
    }

    /** The visual line containing {@code offset} — the last line whose start &le; offset. */
    public VisualLine lineOf(int offset) {
        return lines.get(lineIndexOf(offset));
    }

    private int lineIndexOf(int offset) {
        int idx = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).start() <= offset) {
                idx = i;
            } else {
                break;
            }
        }
        return idx;
    }

    private VisualLine lineByY(float absY) {
        for (VisualLine l : lines) {
            if (absY < l.bottom()) {
                return l;
            }
        }
        return lines.get(lines.size() - 1);
    }
}
