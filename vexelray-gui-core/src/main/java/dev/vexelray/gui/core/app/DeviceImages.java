package dev.vexelray.gui.core.app;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.Pixels;
import dev.vexelray.target.ImageHandle;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.SampledImage;
import dev.supirvast.vulkan.VulkanDevice;

import java.util.HashMap;
import java.util.Map;

/**
 * The images a device draws that no application uploaded: the placeholder a run with no image binds, and every
 * {@link Pixels} a tree names, uploaded the first frame it is drawn and kept until the device goes. One per device,
 * shared by every window on it, closed with it. GUI thread only, as everything that touches the device is.
 */
final class DeviceImages implements AutoCloseable {

    private final VulkanDevice device;
    private final AtlasTexture placeholder;
    private final Map<Pixels, AtlasTexture> uploaded = new HashMap<>();

    DeviceImages(VulkanDevice device) {
        this.device = device;
        this.placeholder = AtlasTexture.placeholder(device);
    }

    /** One transparent texel: what a run with no image binds, and what a pipeline layout is built against. */
    SampledImage placeholder() {
        return placeholder;
    }

    /**
     * What a run's image handle draws with on this device: a {@link Pixels} uploaded here (the first time it is asked
     * for), anything else as it is. Pixels that cannot be read draw as the placeholder, and say so once.
     */
    ImageHandle resolve(ImageHandle image) {
        if (!(image instanceof Pixels pixels)) {
            return image;
        }
        AtlasTexture t = uploaded.get(pixels);
        if (t == null) {
            try {
                t = new AtlasTexture(device, pixels.width(), pixels.height(), pixels.rgba());
            } catch (RuntimeException e) {
                Diagnostics.dropped("DeviceImages.resolve/" + pixels.getClass().getName(),
                        "pixels that could not be read or uploaded: " + e.getMessage(),
                        "the placeholder is drawn in their place, so what they show is blank");
                t = placeholder;
            }
            uploaded.put(pixels, t);
        }
        return t;
    }

    @Override
    public void close() {
        for (AtlasTexture t : uploaded.values()) {
            if (t != placeholder) {
                t.close();
            }
        }
        uploaded.clear();
        placeholder.close();
    }
}
