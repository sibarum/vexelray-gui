package dev.vexelray.gui.harness;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A whole application, running its real frame loop, with the loop under a test's control.
 *
 * <p>The gap this fills is narrow and it is the one that matters. The framework's input tests already
 * synthesise clicks end to end — {@code InputDispatcherTest} publishes raw edges on the bus and watches
 * the handler fire — but every one of them calls {@code Gui.frame()} by hand. A test that draws its own
 * frames cannot observe the only question render-on-demand actually asks: <b>after this click, does a
 * frame arrive on its own?</b> Nothing that hand-drives frames can fail when a wake is missing, which is
 * why five missing wakes shipped past a green suite.
 *
 * <pre>{@code
 * try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("test", 400, 300))) {
 *     harness.settle();                       // let it reach its idle state
 *     long before = harness.frames();
 *     harness.click(120, 80);                 // no pointer motion, exactly like a touchpad tap
 *     assertTrue(harness.awaitFrame(before, 2_000), "a click must produce a frame");
 * }
 * }</pre>
 *
 * <p>Everything the GPU touches is real: a genuine window is created and simply never shown, so the
 * surface, swapchain, presenter and pixels behave exactly as in production. Only pump, wait, wake and
 * focus are intercepted — see {@link HarnessWindow}.
 *
 * <p><b>Needs a Vulkan device.</b> This is an integration harness, not a unit test fixture; on a machine
 * with no GPU it will fail to start rather than silently prove nothing.
 */
public final class HarnessApp implements AutoCloseable {

    private final Gui gui;
    private final GuiApp app;
    private final HarnessWindow window;
    private final NativeWindow real;
    private final Thread loop;
    private final AtomicLong frames = new AtomicLong();

    private volatile RuntimeException failure;

    private HarnessApp(Gui gui, WindowConfig config) {
        this.gui = gui;
        this.real = NativePlatform.current().createWindow(config);
        this.window = new HarnessWindow(real);
        this.app = new GuiApp(config, window);
        this.loop = Thread.ofPlatform().name("harness-loop").unstarted(() -> {
            try {
                app.run(gui, 0, frames::incrementAndGet);
            } catch (RuntimeException e) {
                failure = e;
            }
        });
    }

    /** Start the application and its loop. Returns once the loop thread is running, not once it is idle. */
    public static HarnessApp start(Gui gui, WindowConfig config) {
        HarnessApp harness = new HarnessApp(gui, config);
        harness.loop.start();
        return harness;
    }

    /** The application, for wiring pacing, idle bounds and anything else a real host would set. */
    public GuiApp app() {
        return app;
    }

    /** The window, for driving focus and reading the budgets the loop parked on. */
    public HarnessWindow window() {
        return window;
    }

    /** Frames presented since the loop started. */
    public long frames() {
        return frames.get();
    }

    /**
     * A left click at {@code (x, y)} with <b>no pointer movement</b> — press and release at one point.
     *
     * <p>The shape that broke: a touchpad tap carries no motion with it, so nothing but the click itself
     * can wake a parked loop. A test that moves the pointer first proves nothing, because the movement
     * is an OS event and would have woken the loop on its own.
     */
    public void click(int x, int y) {
        click(x, y, MouseButton.LEFT);
    }

    /**
     * As {@link #click(int, int)}, with the button named - {@code RIGHT} for a context menu.
     *
     * <p>Worth having as its own case rather than a parameter nobody passes: a menu opened by one click
     * and chosen from by another is two interactions deep, and every step of that chain is somewhere a
     * frame can fail to arrive.
     */
    public void click(int x, int y, MouseButton button) {
        gui.bus().publish(InputTopics.INPUT, new InputEvent.ButtonPressed(button, x, y, 0));
        gui.bus().publish(InputTopics.INPUT, new InputEvent.ButtonReleased(button, x, y, 0));
        // Input published on the bus is not an OS event, so nothing has told the loop it arrived. A real
        // click would have: this stands in for the WM_LBUTTONDOWN that wakes the loop before its own
        // dispatch ever sees the event. Everything *after* this wake is what is under test.
        window.postWake();
    }

    /**
     * Block until the frame count passes {@code was}, or {@code timeoutMillis} elapses.
     *
     * @return whether a frame arrived — {@code false} is the assertion failure this harness exists for
     */
    public boolean awaitFrame(long was, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            rethrow();
            if (frames.get() > was) {
                return true;
            }
            try {
                Thread.sleep(2L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        rethrow();
        return frames.get() > was;
    }

    /**
     * Block until {@code condition} holds, or {@code timeoutMillis} elapses.
     *
     * <p>Handlers run on the worker executor, so a frame arriving does not mean the handler that asked
     * for it has finished - and a test that asserts the moment a frame lands is racing it. This is the
     * wait for anything an application does <em>because</em> of an interaction, as opposed to the frame
     * itself.
     *
     * @return whether it held
     */
    public boolean await(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            rethrow();
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(2L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        rethrow();
        return condition.getAsBoolean();
    }

    /**
     * Wait until the application stops producing frames of its own accord, so a test measures its
     * interaction rather than the tail of start-up.
     *
     * <p>Gives up after {@code timeoutMillis} rather than failing: an application with a caret never goes
     * fully quiet, and a harness that insisted on it would be untestable against exactly the applications
     * most worth testing.
     */
    public void settle(long quietMillis, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        long last = -1;
        long stableSince = System.nanoTime();
        while (System.nanoTime() < deadline) {
            rethrow();
            long now = frames.get();
            if (now != last) {
                last = now;
                stableSince = System.nanoTime();
            } else if (System.nanoTime() - stableSince >= quietMillis * 1_000_000L) {
                return;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** {@link #settle(long, long)} with workable defaults: quiet for 150 ms, giving up after 3 s. */
    public void settle() {
        settle(150L, 3_000L);
    }

    private void rethrow() {
        RuntimeException e = failure;
        if (e != null) {
            throw new IllegalStateException("the application's frame loop died", e);
        }
    }

    @Override
    public void close() {
        window.requestStop();
        window.postWake();
        try {
            loop.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        app.close();
        rethrow();
    }
}
