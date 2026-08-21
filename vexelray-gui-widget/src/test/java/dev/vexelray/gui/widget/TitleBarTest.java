package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.WindowControls;
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
}
