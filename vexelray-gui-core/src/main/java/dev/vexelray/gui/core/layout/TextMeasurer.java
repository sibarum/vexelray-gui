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
    float intrinsic(RetainedNode textNode, Axis axis);
}
