package dev.vexelray.gui.core.app;

import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The queue that draws, offered to a thread of its own where compute has no queue of its own
 * ({@link ComputeQueue#drawingQueue()}): the same queue the main thread submits every frame to, so that thread is the
 * only one that may submit to it, and {@link #run} hands the work there and waits for it.
 *
 * <p>For the little GPU work a simulation computing elsewhere (on the CPU, say) still has to do on the application's
 * device: copying a finished step into the buffers its picture reads. A device with one queue family (llvmpipe, and
 * some real GPUs) has nothing else to offer it.
 *
 * <p><b>Hand over the submission, nothing more.</b> Every frame waits while the main thread runs what is handed here.
 * Making buffers and pipelines, recording, waiting on a fence and freeing touch no queue: do them on the thread that
 * asked, which is waiting anyway, and pass {@link #run} only the call that submits.
 *
 * <p><b>Never hangs.</b> A main thread that has stopped taking work (closing, or itself waiting on the thread that
 * asked) is waited for {@value #WAIT_SECONDS} seconds and then given up on, with an {@link IllegalStateException}.
 */
public final class DrawingQueue {

    private static final int WAIT_SECONDS = 5;

    private final GuiApp app;
    private final Thread main;
    private final VulkanInstance instance;
    private final VulkanDevice device;
    private final int family;

    DrawingQueue(GuiApp app, Thread main, VulkanInstance instance, VulkanDevice device, int family) {
        this.app = app;
        this.main = main;
        this.instance = instance;
        this.device = device;
        this.family = family;
    }

    /** The instance the device was made from. */
    public VulkanInstance instance() {
        return instance;
    }

    /** The application's device. */
    public VulkanDevice device() {
        return device;
    }

    /** The family that draws: {@code GpuContext.on(instance, device, family)} makes a context whose work goes through {@link #run}. */
    public int family() {
        return family;
    }

    /** Whether this is the main thread, where {@link #run} runs work at once. */
    public boolean onMainThread() {
        return Thread.currentThread() == main;
    }

    /**
     * Run {@code work} on the main thread, between frames, and return once it has: at once when this is the main
     * thread. What it throws is thrown here.
     *
     * @throws IllegalStateException when the main thread has not taken the work in {@value #WAIT_SECONDS} seconds
     */
    public void run(Runnable work) {
        if (onMainThread()) {
            work.run();
            return;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        app.post(() -> {
            try {
                work.run();
                done.complete(null);
            } catch (RuntimeException | Error e) {
                done.completeExceptionally(e);
            }
        });
        try {
            done.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("the main thread did not take the drawing queue's work in "
                    + WAIT_SECONDS + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the main thread", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(e.getCause());
        }
    }
}
