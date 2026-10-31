package dev.vexelray.gui.core.app;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.shadelet.CoreLooks;
import dev.vexelray.canvas.shadelet.LookTable;
import dev.vexelray.gui.core.Feed;
import dev.vexelray.gui.core.Pixels;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.target.ImageHandle;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.SampledImage;
import dev.vexelray.vulkan.present.StreamTexture;
import dev.supirvast.vulkan.VulkanDevice;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The images a device draws that no application uploaded: the placeholder a run with no image binds, and every
 * {@link Pixels} a tree names, uploaded the first frame it is drawn and kept until the device goes. And the canvas
 * shader the application composed, with its look table, which are not images but are the same kind of thing:
 * device-wide, drawn with by every window, never uploaded by an application. One per device,
 * shared by every window on it, closed with it. GUI thread only, as everything that touches the device is.
 *
 * <p>And every {@link Feed} a tree names: a stream texture per feed on this device, made the first frame the feed is
 * drawn, given the slot the feed shows whenever that changes, and let go when the feed is closed
 * ({@link #closedFeeds}), through the owner's release so that no frame in flight samples it.
 */
final class DeviceImages implements AutoCloseable {

    private final VulkanDevice device;
    private final AtlasTexture placeholder;
    private final ComposedShader fragment;
    private final LookTable looks;
    private final Map<Pixels, AtlasTexture> uploaded = new HashMap<>();
    private final Map<Feed, Fed> feeds = new HashMap<>();
    private final Runnable wake;

    /**
     * A feed's texture on this device, and which writing of which slot it holds: for a still, one texture made when its
     * one slot is first handed over, which never changes; for a feed that does, a stream of two, one shown and one
     * landing.
     */
    private static final class Fed {
        final StreamTexture stream;
        AtlasTexture still;
        int slot = -1;
        long generation;

        Fed(StreamTexture stream) {
            this.stream = stream;
        }

        SampledImage shown() {
            return stream != null ? stream.live() : still;
        }

        AutoCloseable texture() {
            return stream != null ? stream : still;
        }
    }

    /**
     * {@code shader} is the canvas fragment stage every pipeline on this device is built with; {@code wake} asks the
     * windows on it for a frame from any thread, and is what a feed drawn here runs when a slot is handed over (null for
     * a device that draws once, a headless capture).
     */
    DeviceImages(VulkanDevice device, CanvasShader.Composed shader, Runnable wake) {
        this.device = device;
        this.wake = wake;
        // The table first: a composition without the whole core set is refused here, before anything is allocated.
        this.looks = CoreLooks.table(shader.composition());
        this.fragment = CanvasShader.fragment(shader);
        this.placeholder = AtlasTexture.placeholder(device);
    }

    /** One transparent texel: what a run with no image binds, and what a pipeline layout is built against. */
    SampledImage placeholder() {
        return placeholder;
    }

    /**
     * The canvas fragment stage of the application's composition, loaded or lowered once: every window, its
     * offscreen capture and the headless capture build their pipelines with this one, so they draw alike.
     */
    ComposedShader fragment() {
        return fragment;
    }

    /**
     * The looks every canvas on this device names, written against {@link #fragment()}'s composition: the kinds'
     * own first, at the offsets every table has. Each window copies it into its {@link ShadeletTables} when it grows.
     */
    LookTable looks() {
        return looks;
    }

    /**
     * What a run's image handle draws with on this device: a {@link Pixels} uploaded here (the first time it is asked
     * for), a {@link Feed}'s texture with the slot it shows, anything else as it is. Pixels that cannot be read draw as
     * the placeholder, and say so once; so does a feed that is closed or has nothing handed over yet.
     */
    ImageHandle resolve(ImageHandle image) {
        if (image instanceof Feed feed) {
            return fed(feed);
        }
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

    /**
     * The feed's texture here, holding the slot it shows. The copy is queued now, before the frame that draws it is
     * submitted, so the same queue orders it first; a still uploads once, and a slot written again uploads again.
     */
    private SampledImage fed(Feed feed) {
        if (feed.closed()) {
            return placeholder;
        }
        Fed f = feeds.get(feed);
        if (f == null) {
            f = new Fed(feed.slots() == 1 ? null
                    : new StreamTexture(device, feed.width(), feed.height(), 2, feed.picture()));
            feeds.put(feed, f);
            if (wake != null) {
                feed.wake(wake);
            }
        }
        int slot = feed.select();
        if (slot >= 0 && (slot != f.slot || feed.generation(slot) != f.generation)) {
            if (f.stream == null) {
                // A still's one slot, once: shown, it is never free to be written again.
                f.still = new AtlasTexture(device, feed.width(), feed.height(), feed.chain(slot), feed.picture());
                f.slot = slot;
                f.generation = feed.generation(slot);
            } else {
                StreamTexture.Slot s = f.stream.take();
                if (s != null) {
                    s.write(feed.chain(slot));
                    f.stream.submit(s);
                    f.stream.show(s);
                    f.slot = slot;
                    f.generation = feed.generation(slot);
                }
            }
        }
        return f.slot < 0 ? placeholder : f.shown();
    }

    /**
     * Take out every feed closed since the last call, and add its texture to {@code out}, for the owner to release once
     * no frame in flight samples it. A feed closed before anything was handed over has none.
     */
    void closedFeeds(List<AutoCloseable> out) {
        for (Iterator<Map.Entry<Feed, Fed>> it = feeds.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Feed, Fed> e = it.next();
            if (e.getKey().closed()) {
                AutoCloseable t = e.getValue().texture();
                if (t != null) {
                    out.add(t);
                }
                it.remove();
            }
        }
    }

    @Override
    public void close() {
        for (AtlasTexture t : uploaded.values()) {
            if (t != placeholder) {
                t.close();
            }
        }
        uploaded.clear();
        for (Fed f : feeds.values()) {
            AutoCloseable t = f.texture();
            if (t != null) {
                try {
                    t.close();
                } catch (Exception e) {
                    Diagnostics.dropped("DeviceImages.close/feed", "a feed's texture would not close: " + e.getMessage(),
                            "the device is closing with it");
                }
            }
        }
        feeds.clear();
        placeholder.close();
    }
}
