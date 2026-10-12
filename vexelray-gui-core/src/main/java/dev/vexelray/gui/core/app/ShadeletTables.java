package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.shadelet.Composition;
import dev.vexelray.canvas.shadelet.LookTable;
import dev.vexelray.vulkan.present.StorageBuffer;
import dev.supirvast.vulkan.VulkanDevice;

/**
 * The buffer a canvas pipeline binds at set 2: the device's {@link LookTable} below
 * {@link Composition#RECORDS_BASE}, and one canvas's element records above it.
 *
 * <p>One per canvas that draws, not one per device, though the look table is the device's. The records are a frame's,
 * rewritten every frame, and each window's frames are fenced on their own: a buffer shared between windows would have
 * one window's records overwritten while the GPU still read another's. So each window keeps its own, as it keeps its
 * own vertex buffer, under the same synchronisation; the table part is copied in again only when it has grown.
 */
final class ShadeletTables implements AutoCloseable {

    private static final sibarum.probe.Log LOG = sibarum.probe.Log.of("gui.canvas");

    private final StorageBuffer buffer;
    /** The {@link LookTable#version} last copied in, or -1 before the first. */
    private int uploadedLooks = -1;
    /** Said once, not once a frame, as {@code GuiWindow} says a vertex overflow. */
    private boolean warnedOverCapacity;

    ShadeletTables(VulkanDevice device) {
        this.buffer = new StorageBuffer(device, Composition.TABLES_FLOATS, Composition.TABLES_BINDING);
    }

    StorageBuffer buffer() {
        return buffer;
    }

    /**
     * After {@code canvas} is emitted, beside its vertices: the table if it moved since the last upload (drawing a
     * look for the first time grows it), and the frame's records. Records past the capacity are left out, and said.
     */
    void upload(LookTable looks, Canvas canvas) {
        if (looks.version() != uploadedLooks) {
            float[] table = looks.floats();
            buffer.update(0, table, table.length);
            uploadedLooks = looks.version();
        }
        int records = canvas.recordCount();
        if (records > Composition.RECORDS_CAPACITY) {
            if (!warnedOverCapacity) {
                warnedOverCapacity = true;
                LOG.warn("canvas wrote " + records + " floats of element records and the tables hold "
                        + Composition.RECORDS_CAPACITY + "; uploading what fits. Draw fewer elements with "
                        + "per-instance values.");
            }
            records = Composition.RECORDS_CAPACITY;
        }
        buffer.update(Composition.RECORDS_BASE, canvas.records(), records);
    }

    @Override
    public void close() {
        buffer.close();
    }
}
