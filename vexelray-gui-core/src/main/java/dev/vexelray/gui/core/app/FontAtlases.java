package dev.vexelray.gui.core.app;

import dev.supirvast.vulkan.VulkanDevice;
import dev.vexelray.gui.core.text.TextFaces;
import dev.vexelray.text.AtlasPixels;
import dev.vexelray.text.FontSet;
import dev.vexelray.vulkan.present.AtlasTexture;

import java.util.ArrayList;
import java.util.List;

/**
 * An application's fonts on one device: the {@link TextFaces} that measures and resolves them, and one glyph atlas
 * texture per face, indexed by face id — what a {@code Canvas.Run}'s face names and set 0 is bound to.
 *
 * <p>Every face's texture is made here, at startup, on the main thread, whether or not anything has drawn in it yet:
 * if the build baked a face, something uses it, and what the application holds on the GPU is then a fact of its
 * build rather than of what it happened to show (docs/plans/font-families.md §1). Shared by every window.
 */
final class FontAtlases implements AutoCloseable {

    private final TextFaces faces;
    private final List<AtlasTexture> textures;

    private FontAtlases(TextFaces faces, List<AtlasTexture> textures) {
        this.faces = faces;
        this.textures = textures;
    }

    /** Upload every face of {@code faces} to {@code device}. Main thread. */
    static FontAtlases upload(VulkanDevice device, TextFaces faces) {
        List<AtlasTexture> made = new ArrayList<>();
        try {
            for (FontSet.Face f : faces.set().faces()) {
                AtlasPixels p = faces.set().pixels(f);
                made.add(new AtlasTexture(device, p.width(), p.height(), p.rgba()));
            }
        } catch (RuntimeException e) {
            made.forEach(AtlasTexture::close);   // a half-uploaded set is closed, not leaked
            throw e;
        }
        return new FontAtlases(faces, List.copyOf(made));
    }

    TextFaces faces() {
        return faces;
    }

    /** One texture per face, by face id. */
    List<AtlasTexture> textures() {
        return textures;
    }

    /** Face 0's: what the pipeline layout is built against, and what a frame binds before its first run. */
    AtlasTexture first() {
        return textures.get(0);
    }

    @Override
    public void close() {
        textures.forEach(AtlasTexture::close);
    }
}
