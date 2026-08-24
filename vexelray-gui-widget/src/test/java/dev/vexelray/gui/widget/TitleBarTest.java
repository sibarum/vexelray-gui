package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The title bar's caption buttons, and the one property the widget's binding story rests on: a bar built before
 * its window exists has to command the <em>current</em> {@link WindowControls} when clicked, not the ones it was
 * constructed with.
 *
 * <p>That order is not a special case, it is the only order available — the chrome is part of the tree, the tree is
 * built before {@code GuiApp} opens a window, and the controls belong to that window — so every application
 * constructs the bar against {@link WindowControls#NONE} and rebinds. A bar that resolved its controls once would
 * have three permanently dead buttons while its drag strip and its maximize icon both worked, which is exactly the
 * shape of defect that ships unnoticed.
 */
class TitleBarTest {

    /** {@code TitleBar.BUTTON_W} and {@code BAR_H} — the metrics the click geometry below is derived from. */
    private static final float BUTTON_W = 46f;
    private static final float BAR_H = 32f;
    /** The harness viewport width; the caption buttons sit against its right edge. */
    private static final float VIEW_W = 800f;

    /** A {@link WindowControls} that records what it was asked to do. */
    private static final class Recorder implements WindowControls {
        private final List<String> calls = new ArrayList<>();

        @Override
        public void minimize() {
            calls.add("minimize");
        }

        @Override
        public void toggleMaximize() {
            calls.add("toggleMaximize");
        }

        @Override
        public boolean maximized() {
            return false;
        }

        @Override
        public void close() {
            calls.add("close");
        }
    }

    /** The centre of the nth caption button counting from the right: 0 is close, 1 maximize, 2 minimize. */
    private static float buttonX(int fromRight) {
        return VIEW_W - (fromRight + 0.5f) * BUTTON_W;
    }

    private static TitleBar bar(HeadlessGui h, WindowControls controls) {
        TitleBar bar = new TitleBar(h.gui, controls, "Test");
        h.gui.root().children(bar.node());
        h.frame();
        return bar;
    }

    @Test
    void clicksReachTheControlsBoundAfterConstruction() {
        try (HeadlessGui h = new HeadlessGui()) {
            // The order every application is obliged to use: build the chrome, open the window, then bind.
            TitleBar bar = bar(h, WindowControls.NONE);
            Recorder window = new Recorder();
            bar.controls(window);

            h.click(buttonX(2), BAR_H / 2f);
            h.click(buttonX(1), BAR_H / 2f);
            h.click(buttonX(0), BAR_H / 2f);

            assertEquals(List.of("minimize", "toggleMaximize", "close"), window.calls,
                    "every button commands the window bound after construction, not the one passed to it");
        }
    }

    @Test
    void rebindingRewiresEveryButton() {
        try (HeadlessGui h = new HeadlessGui()) {
            Recorder first = new Recorder();
            TitleBar bar = bar(h, first);
            Recorder second = new Recorder();
            bar.controls(second);

            h.click(buttonX(0), BAR_H / 2f);

            assertEquals(List.of(), first.calls, "the window it used to command hears nothing");
            assertEquals(List.of("close"), second.calls, "the window it commands now does");
        }
    }

    /**
     * The same two bindings, performed by the framework. Every application wrote them by hand at every window,
     * because a bar is necessarily built before the window it belongs to — and both halves are silent when
     * forgotten: miss the first and the buttons do nothing, miss the second and they command a destroyed handle.
     */
    @Test
    void aBarWiresItselfToWhateverWindowItsSpecOpens() {
        try (HeadlessGui h = new HeadlessGui()) {
            TitleBar bar = bar(h, WindowControls.NONE);
            RecordingWindow window = new RecordingWindow();
            WindowSpec spec = bar.commands(WindowSpec.of(WindowConfig.of("Test", 100, 100), h.gui));

            spec.onCreated().accept(window);            // what the frame loop does once the window exists
            h.click(buttonX(0), BAR_H / 2f);
            assertEquals(List.of("requestClose"), window.calls, "the caption commands the window it opened");

            window.calls.clear();
            spec.onClosed().run();                      // and what it does once the window is gone
            h.click(buttonX(0), BAR_H / 2f);
            assertEquals(List.of(), window.calls, "a bar outlives its window and must stop commanding it");
        }
    }

    /** Wiring itself in must not displace what the application asked for at the same moments. */
    @Test
    void wiringItselfInLeavesTheApplicationsOwnCallbacksAlone() {
        try (HeadlessGui h = new HeadlessGui()) {
            TitleBar bar = bar(h, WindowControls.NONE);
            List<String> app = new ArrayList<>();
            WindowSpec spec = bar.commands(WindowSpec.of(WindowConfig.of("Test", 100, 100), h.gui)
                    .onCreated(w -> app.add("placed"))
                    .onClosed(() -> app.add("forgotten")));

            RecordingWindow window = new RecordingWindow();
            spec.onCreated().accept(window);
            spec.onClosed().run();

            assertEquals(List.of("placed", "forgotten"), app);
        }
    }

    /** A window that only records what it was told to do. Everything else {@link NativeWindow} defaults. */
    private static final class RecordingWindow implements NativeWindow {
        private final List<String> calls = new ArrayList<>();

        @Override
        public void requestClose() {
            calls.add("requestClose");
        }

        @Override
        public void minimize() {
            calls.add("minimize");
        }

        @Override
        public int width() {
            return 100;
        }

        @Override
        public int height() {
            return 100;
        }

        @Override
        public boolean pumpEvents() {
            return true;
        }

        @Override
        public void show() {
        }

        @Override
        public boolean isKeyDown(dev.vexelray.os.Key key) {
            return false;
        }

        @Override
        public long createVulkanSurface(long vkInstance, java.lang.foreign.MemorySegment vkGetInstanceProcAddr) {
            return 0L;
        }

        @Override
        public long osHandle() {
            return 0L;
        }

        @Override
        public void close() {
        }
    }
}
