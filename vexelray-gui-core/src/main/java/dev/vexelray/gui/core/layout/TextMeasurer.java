package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.model.RetainedNode;

/**
 * Supplies the intrinsic pixel size of a text node along one axis — the layout's one dependency on the glyph
 * atlas. Implemented by the app over VexelRay's {@code TextLayout} (width = measured advance, height = line
 * height at the node's text size). Keeps {@code FlexLayout} free of any rendering dependency.
 */
@FunctionalInterface
public interface TextMeasurer {
    /** Intrinsic px size of {@code textNode} along {@code axis} at the given resolved {@code textSizePx}. */
    float intrinsic(RetainedNode textNode, Axis axis, float textSizePx);

    /**
     * Caret offset (character index) nearest to {@code localX} — the pointer x measured from the start of the
     * text run, in px — for {@code text} at {@code textSizePx}. Used to place the caret on a click into an
     * editable field. The default (no measurement) returns 0; the app overrides it over the glyph atlas.
     */
    default int offsetAt(String text, float localX, float textSizePx) {
        return 0;
    }

    /**
     * The cumulative advance (px) of {@code text} at each character boundary: an array of length
     * {@code text.length() + 1} where element {@code i} is the width of {@code text[0..i)} (so {@code [0] == 0}).
     * Used to bake caret geometry into the layout read-model's {@code TextMetrics}. The default returns
     * {@code null} (no metrics available); an app over a real glyph atlas overrides it.
     */
    default float[] caretAdvances(String text, float textSizePx) {
        return null;
    }
}
