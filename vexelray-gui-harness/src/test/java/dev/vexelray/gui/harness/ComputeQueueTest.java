package dev.vexelray.gui.harness;

import dev.supirvast.vulkan.VulkanInstance;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GuiApp.Compute}: an application that asks for nothing gets the device it always had, one family and one
 * queue; one that asks for a queue of compute's own gets one of a compute-only family beside the queue that draws,
 * where the device has such a family, and still draws.
 *
 * <p>Needs a Vulkan device; this is an integration test, not a unit test.
 */
class ComputeQueueTest {

    @Test
    void askingForNothingIsTheDeviceThereAlwaysWas() {
        try (HarnessApp harness = HarnessApp.start(new Gui(), WindowConfig.of("shared", 200, 120))) {
            GuiApp.Gpu gpu = harness.app().gpu();
            assertFalse(gpu.ownQueue());
            assertEquals(gpu.device().queueFamilyIndex(), gpu.computeFamily(), "compute shares the queue that draws");
            assertEquals(List.of(gpu.device().queueFamilyIndex()), gpu.device().queueFamilies(),
                    "no queue of another family was taken");
            assertEquals(1, gpu.device().queues().size(), "and no second queue of its own");
        }
    }

    @Test
    void computeGetsAQueueOfItsOwnBesideTheOneThatDraws() {
        Gui gui = new Gui();
        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("own queue", 200, 120),
                GuiApp.Compute.OWN_QUEUE)) {
            GuiApp.Gpu gpu = harness.app().gpu();
            VulkanInstance instance = gpu.instance();
            int computeOnly = instance.computeOnlyQueueFamily(gpu.device().physicalDevice());
            System.out.println("[compute queue] draws on family " + gpu.device().queueFamilyIndex()
                    + ", computes on family " + gpu.computeFamily()
                    + (computeOnly < 0 ? " (the device has no compute-only family)" : ""));
            if (computeOnly < 0) {
                assertFalse(gpu.ownQueue(), "with no compute-only family, compute shares the queue that draws");
                assertEquals(gpu.device().queueFamilyIndex(), gpu.computeFamily());
                return;
            }
            assertTrue(gpu.ownQueue());
            assertEquals(computeOnly, gpu.computeFamily());
            assertTrue(instance.queueFamilySupports(gpu.device().physicalDevice(), gpu.computeFamily(),
                    VulkanInstance.QUEUE_COMPUTE), "the family computes");
            assertFalse(instance.queueFamilySupports(gpu.device().physicalDevice(), gpu.computeFamily(),
                    VulkanInstance.QUEUE_GRAPHICS), "and is not the graphics family under another number");
            assertEquals(1, gpu.device().queues(gpu.computeFamily()).size(), "one queue of it");
            assertEquals(1, gpu.device().queues().size(), "and the queue that draws is still the only one of its own");
            assertTrue(gpu.device().timelineSemaphore(), "timelines, so drawing can wait for compute in the GPU");

            long was = harness.frames();
            gui.requestFrame();
            assertTrue(harness.awaitFrame(was, 5_000), "a device with two families still draws");
        }
    }
}
