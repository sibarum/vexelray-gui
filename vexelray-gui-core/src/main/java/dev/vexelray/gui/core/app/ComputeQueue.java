package dev.vexelray.gui.core.app;

import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;
import dev.vexelray.gui.core.app.GuiApp.Compute;

/**
 * A queue of the application's device that computes beside the one that draws, lent to one owner: whoever holds
 * it is the only one that submits to it, from one thread of its own. Got once, from {@link GuiApp#lendComputeQueue}.
 *
 * <p><b>The one part of Vulkan that is not the main thread's.</b> The window, present and the queue that draws stay
 * there. A queue is externally synchronised, so a second one, which nothing on the main thread submits to, can be
 * another thread's without a lock: a simulation that runs back to back on a thread of its own, and never makes a
 * frame wait for it. Lending it once is what keeps it one thread's.
 *
 * <p>Not {@linkplain #available() available} where the device has no compute-only queue family, or where the
 * application did not ask for one ({@link Compute#OWN_QUEUE}): then there is nothing to lend that the main thread
 * does not already submit to, and {@link #why()} says which.
 */
public final class ComputeQueue {

    private final VulkanInstance instance;
    private final VulkanDevice device;
    private final int family;
    private final String why;

    private ComputeQueue(VulkanInstance instance, VulkanDevice device, int family, String why) {
        this.instance = instance;
        this.device = device;
        this.family = family;
        this.why = why;
    }

    static ComputeQueue of(VulkanInstance instance, VulkanDevice device, int family) {
        return new ComputeQueue(instance, device, family, null);
    }

    static ComputeQueue none(String why) {
        return new ComputeQueue(null, null, -1, why);
    }

    /** Whether there is a queue here to compute on. */
    public boolean available() {
        return why == null;
    }

    /** Why there is no queue here, or {@code null} when there is. */
    public String why() {
        return why;
    }

    /** The instance the device was made from. */
    public VulkanInstance instance() {
        require();
        return instance;
    }

    /**
     * The application's device. Its buffers are what the window's pictures can read where they are; which of them
     * this queue may write while a frame reads them is a matter of what the two signal and wait for.
     */
    public VulkanDevice device() {
        require();
        return device;
    }

    /** The queue family lent, a compute-only one: {@code GpuContext.on(instance, device, family)} submits to it. */
    public int family() {
        require();
        return family;
    }

    private void require() {
        if (why != null) {
            throw new IllegalStateException("no compute queue to lend: " + why);
        }
    }
}
