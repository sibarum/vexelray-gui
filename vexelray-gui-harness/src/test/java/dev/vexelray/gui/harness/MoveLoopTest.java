package dev.vexelray.gui.harness;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.vulkan.present.AtlasTexture;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A window being moved or resized still runs what was posted to it. Windows runs a drag inside a modal loop of its
 * own, which suspends the host loop for as long as the gesture lasts, and the frames it pulls through the window's
 * sink are the only frames there are. Those once ran no posted work: a playing video, whose every frame is a texture
 * made through {@code post}, stood still on one picture for the length of the drag.
 *
 * <p>{@link HarnessWindow#beginMove} holds the loop in its pump and pulls the sink, as the platform does.
 *
 * <p>Needs a Vulkan device; this is an integration test, not a unit test.
 */
class MoveLoopTest {

    private static final int SIZE = 4;

    private static byte[] white() {
        byte[] rgba = new byte[SIZE * SIZE * 4];
        java.util.Arrays.fill(rgba, (byte) 255);
        return rgba;
    }

    @Test
    void postedWorkRunsDuringAMove() throws Exception {
        Gui gui = new Gui();
        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("move", 200, 120))) {
            harness.settle();
            harness.window().beginMove();
            try {
                assertTrue(harness.await(() -> harness.window().pulls() > 2, 5_000), "the move pulls frames");

                // A task posted by a task runs in the same frame (GuiApp.post), so both land inside the move.
                CompletableFuture<Long> nested = new CompletableFuture<>();
                harness.app().post(() -> {
                    long outer = harness.window().pulls();
                    harness.app().post(() -> nested.complete(harness.window().pulls() - outer));
                });
                assertEquals(0L, nested.get(5, TimeUnit.SECONDS), "posted work runs while the window is moved, "
                        + "and a task posted by a task in the same frame");
            } finally {
                harness.window().endMove();
            }
        }
    }

    @Test
    void aTextureReleasedDuringAMoveIsClosedDuringIt() throws Exception {
        Gui gui = new Gui();
        Node picture = gui.box().size(Length.dp(40), Length.dp(40));
        gui.root().children(picture);

        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("move-release", 200, 120))) {
            harness.settle();
            harness.window().beginMove();
            try {
                assertTrue(harness.await(() -> harness.window().pulls() > 2, 5_000), "the move pulls frames");
                // What a playing video does each frame: show the next picture, give back the last.
                CompletableFuture<AtlasTexture> made = new CompletableFuture<>();
                harness.app().post(() -> {
                    AtlasTexture t = harness.app().texture(white(), SIZE, SIZE);
                    picture.image(t);
                    made.complete(t);
                });
                AtlasTexture first = made.get(5, TimeUnit.SECONDS);
                harness.app().post(() -> {
                    picture.image(harness.app().texture(white(), SIZE, SIZE));
                    harness.app().release(first);
                });
                assertTrue(harness.await(first::isClosed, 5_000), "a texture released mid-drag is closed mid-drag, "
                        + "not piled up behind it");
            } finally {
                harness.window().endMove();
            }
        }
    }
}
