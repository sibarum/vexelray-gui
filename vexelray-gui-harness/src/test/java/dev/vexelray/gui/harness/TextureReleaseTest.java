package dev.vexelray.gui.harness;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.vulkan.present.AtlasTexture;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GuiApp.release}: a texture an application has stopped showing is closed for it, and one it is still
 * showing is not. The second half is the one that matters — a texture closed under a node is a destroyed
 * descriptor set bound by the next frame, which no unit test can see and a validation layer reports only if one
 * is loaded.
 *
 * <p>Everything Vulkan is done on the loop's own thread, through {@code post}, because that is where an
 * application's callbacks run and where this method is documented to be called.
 *
 * <p>Needs a Vulkan device; this is an integration test, not a unit test.
 */
class TextureReleaseTest {

    private static final int SIZE = 4;

    private static byte[] white() {
        byte[] rgba = new byte[SIZE * SIZE * 4];
        java.util.Arrays.fill(rgba, (byte) 255);
        return rgba;
    }

    /** Run {@code work} on the loop thread and hand back what it returned. */
    private static <T> T onLoop(HarnessApp harness, Supplier<T> work) throws Exception {
        CompletableFuture<T> done = new CompletableFuture<>();
        harness.app().post(() -> {
            try {
                done.complete(work.get());
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        return done.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aTextureNoLongerShownIsClosed() throws Exception {
        Gui gui = new Gui();
        Node picture = gui.box().size(Length.dp(40), Length.dp(40));
        gui.root().children(picture);

        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("release", 200, 120))) {
            harness.settle();
            AtlasTexture first = onLoop(harness, () -> {
                AtlasTexture t = harness.app().texture(white(), SIZE, SIZE);
                picture.image(t);
                return t;
            });
            harness.settle();

            // What a browser does on every selection: show the next picture, give back the last.
            onLoop(harness, () -> {
                picture.image(harness.app().texture(white(), SIZE, SIZE));
                harness.app().release(first);
                return null;
            });
            assertTrue(harness.await(first::isClosed, 5_000), "a released texture nothing shows is closed");
        }
    }

    @Test
    void aTextureStillShownIsKeptOpen() throws Exception {
        Gui gui = new Gui();
        Node picture = gui.box().size(Length.dp(40), Length.dp(40));
        gui.root().children(picture);

        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("kept", 200, 120))) {
            harness.settle();
            AtlasTexture shown = onLoop(harness, () -> {
                AtlasTexture t = harness.app().texture(white(), SIZE, SIZE);
                picture.image(t);
                harness.app().release(t);   // wrong order: the node still names it
                return t;
            });
            harness.settle();
            assertFalse(shown.isClosed(), "a texture a node still names must outlive its release");

            // Once the node lets go, the release that was waiting completes.
            onLoop(harness, () -> {
                picture.image(null);
                return null;
            });
            assertTrue(harness.await(shown::isClosed, 5_000), "the release completes once nothing names it");
        }
    }

    @Test
    void releasingNothingIsRefused() throws Exception {
        Gui gui = new Gui();
        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("foreign", 200, 120))) {
            harness.settle();
            Exception refused = assertThrows(Exception.class, () -> onLoop(harness, () -> {
                harness.app().release(null);
                return null;
            }));
            assertInstanceOf(IllegalArgumentException.class, refused.getCause());
        }
    }
}
