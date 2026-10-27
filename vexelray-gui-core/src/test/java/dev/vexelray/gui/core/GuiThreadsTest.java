package dev.vexelray.gui.core;

import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@code Gui} runs on the thread that calls its frame, and on no other. In 0.1 it built two pools of its own, a
 * handler lane for input and an offload lane for slow work, unless it was handed lanes; this replaced the test that
 * held who owned them. In 0.2 a handler is the GUI thread's work and slow work is a job's, so there is nothing to own
 * (vexelray-framework's {@code docs/threading.md}, T1.1).
 */
class GuiThreadsTest {

    @Test
    void aGuiStartsNoThreadsOfItsOwn() {
        Set<Thread> before = Thread.getAllStackTraces().keySet();
        try (Gui gui = new Gui(Atchung.create())) {
            Node button = gui.box().width(Length.rem(4)).height(Length.rem(2));
            gui.root().children(button);
            int[] clicks = {0};
            Seams.of(gui).onClick(button, () -> clicks[0]++);
            for (int i = 0; i < 5; i++) {
                Seams.of(gui).frame(400f, 300f, (node, axis, px) -> 0f);
            }
            Set<String> started = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> !before.contains(t))
                    .filter(t -> t.getName().startsWith("vexelray-gui"))
                    .map(Thread::getName)
                    .collect(Collectors.toSet());
            assertEquals(Set.of(), started, "a tree, its input and its frames are the calling thread's alone");
        }
    }
}
