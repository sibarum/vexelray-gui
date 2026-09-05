package dev.vexelray.gui.harness;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * <p>Everything the GPU touches is real: genuine windows are created and simply never shown, so the
 * surface, swapchain, presenter and pixels behave exactly as in production. Only pump, wait, wake and
 * focus are intercepted — see {@link HarnessWindow}.
 *
 * <p><b>Every</b> window, not just the first. The application is constructed with a window factory
 * ({@link GuiApp#GuiApp(WindowConfig, java.util.function.Function)}), so a menu, a dialog or a tool window
 * opened by the interaction under test is asked for off screen and wrapped, exactly as the main window is.
 * That is not tidiness: a mapped window takes real focus and real input, so a test asking where the keyboard
 * went would be answered by the window manager rather than by the framework, and {@link #windows()} would be
 * describing a screen it no longer matches.
 *
 * <p><b>Needs a Vulkan device.</b> This is an integration harness, not a unit test fixture; on a machine
 * with no GPU it will fail to start rather than silently prove nothing.
 */
public final class HarnessApp implements AutoCloseable {

    private final Gui gui;
    private final GuiApp app;
    private final HarnessWindow window;
    private final Thread loop;
    private final AtomicLong frames = new AtomicLong();

    /**
     * Every window this application has opened, in the order it opened them — the main window first. Written
     * on the loop's thread as windows are created and read from the test's, hence the copy-on-write list.
     */
    private final List<HarnessWindow> opened = new CopyOnWriteArrayList<>();

    private volatile RuntimeException failure;

    private HarnessApp(Gui gui, WindowConfig config) {
        this.gui = gui;
        // The factory, not a window: this is what makes "never shown" true of the popup a test opens as well
        // as of the window the test started with. GuiApp calls it synchronously for the main window while
        // this constructor runs, which is why the wrapper is there to be read on the next line.
        this.app = new GuiApp(config, this::create);
        this.window = opened.get(0);
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

    /** The main window, for driving focus and reading the budgets the loop parked on. */
    public HarnessWindow window() {
        return window;
    }

    /**
     * Every window the application has open, in the order it opened them — {@link #window()} is the first.
     *
     * <p>The list only grows: a window the application closed stays here, because what a test asks of it is
     * "was this ever put on screen", and a wrapper that had been dropped could not answer. Each entry is the
     * genuine window the framework is presenting to, wrapped — {@link HarnessWindow#real()} reaches the OS
     * window underneath, which is where an assertion that nothing was <em>mapped</em> has to look.
     */
    public List<HarnessWindow> windows() {
        return List.copyOf(opened);
    }

    /**
     * The window factory the application is built with: a genuine window, asked for off screen, wrapped, and
     * remembered.
     *
     * <p><b>{@link WindowConfig#hidden} is the load-bearing word</b>, not the wrapper. A window is on screen
     * from the instant the platform makes it, painted with the class background so a slow Vulkan bring-up has
     * something to sit behind — so a harness that created windows the ordinary way and merely refused to
     * {@code show()} them was refusing to do a thing that had already happened. Asking for it hidden is what
     * makes "created and never shown" literally true; {@link HarnessWindow#show()} then keeps it true when the
     * presenter's first frame would have revealed it.
     *
     * <p>Called on the main thread, from inside window creation, so it does nothing but create and record.
     */
    private HarnessWindow create(WindowConfig config) {
        HarnessWindow wrapped = new HarnessWindow(NativePlatform.current().createWindow(config.hidden(true)));
        opened.add(wrapped);
        return wrapped;
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
