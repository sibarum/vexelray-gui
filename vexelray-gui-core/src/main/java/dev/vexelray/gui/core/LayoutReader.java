package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.LayoutSnapshot;

/**
 * The read side of a {@link Node} handle: supplies the latest published {@link LayoutSnapshot} so a node can
 * report its own computed layout (docs/layout-read-model.md). Separate from the write side ({@link MutationSink})
 * because reads and writes are different concerns — writes are ordered through the bus (single writer), reads are
 * a lock-free snapshot. Implemented by {@link Gui}.
 */
@FunctionalInterface
public interface LayoutReader {

    /** The latest computed-layout snapshot (never null; {@link LayoutSnapshot#EMPTY} before the first layout). */
    LayoutSnapshot snapshot();
}
