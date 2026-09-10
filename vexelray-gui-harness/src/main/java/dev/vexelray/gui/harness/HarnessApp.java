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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * <p><b>One thread owns the application, and it is not the test's.</b> The {@code GuiApp} is constructed,
 * run and closed entirely on the loop thread — {@link #start} does not return until it exists. That is the
 * same rule the rest of the stack states as <i>"Vulkan, the window and present stay on the main thread"</i>,
 * with the loop thread as this harness's main thread; the test drives it through the bus, a wake, and the
 * waits below, and never touches a window itself.
 *
 * <p>It reads like an implementation detail and is not. Windows will not let a thread destroy a window
 * another thread created, so a harness that made its first window on the test's thread and every later one
 * on the loop's had no thread that could take them all down. It survived that for as long as no test put up
 * anything modal: disabling a window moves activation, and a teardown that had been getting away with the
 * split stopped being able to.
 *
 * <p><b>Needs a Vulkan device.</b> This is an integration harness, not a unit test fixture; on a machine
 * with no GPU it will fail to start rather than silently prove nothing.
 */
public final class HarnessApp implements AutoCloseable {

    /**
     * How long {@link #start} waits for the application to exist. Generous, because what happens inside it
     * is a Vulkan instance, a device and a swapchain on a cold driver — and a timeout here is a diagnosis,
     * not a budget: nothing legitimate takes seconds, so anything that reaches this has hung.
     */
    private static final long START_TIMEOUT_SECONDS = 30L;

    /** How long {@link #close} waits for the loop to stop and give its windows back. */
    private static final long CLOSE_TIMEOUT_MILLIS = 5_000L;

    private final Gui gui;
    private final WindowConfig config;
    private final Thread loop;
    private final AtomicLong frames = new AtomicLong();

    /** Released once the application exists, or once building it has failed; {@link #start} waits on it. */
    private final CountDownLatch up = new CountDownLatch(1);

    /**
     * The application and its first window, built on the loop thread and read from the test's. Not final,
     * because the thread that owns a window has to be the thread that made it, and that cannot be the one
     * running this constructor.
     */
    private volatile GuiApp app;
    private volatile HarnessWindow window;

    /**
     * Every window this application has opened, in the order it opened them — the main window first. Written
     * on the loop's thread as windows are created and read from the test's, hence the copy-on-write list.
     */
    private final List<HarnessWindow> opened = new CopyOnWriteArrayList<>();

    private volatile RuntimeException failure;

    private HarnessApp(Gui gui, WindowConfig config) {
        this.gui = gui;
        this.config = config;
        this.loop = Thread.ofPlatform().name("harness-loop").unstarted(this::live);
    }

    /**
     * Start the application and its loop. Returns once the application exists and its first window has been
     * made — not once it is idle, which is what {@link #settle} is for.
     *
     * @throws IllegalStateException if the application could not be built, which on a machine with no Vulkan
     *         device is the expected outcome and is meant to be loud
     */
    public static HarnessApp start(Gui gui, WindowConfig config) {
        HarnessApp harness = new HarnessApp(gui, config);
        harness.loop.start();
        harness.awaitUp();
        return harness;
    }

    /**
     * The loop thread's whole life: build the application, run it, and take it down again.
     *
     * <p><b>All three, on this thread.</b> The build is here because a window belongs to the thread that
     * created it and only that thread may destroy it; the close is here for the same reason, and in a
     * {@code finally} so a loop that died still gives its windows back.
     */
    private void live() {
        GuiApp built = null;
        try {
            // The factory, not a window: this is what makes "never shown" true of the popup a test opens as
            // well as of the window the test started with. GuiApp calls it synchronously for the main window
            // while it is being constructed, which is why the wrapper is there to be read straight after.
            built = new GuiApp(config, this::create);
            window = opened.get(0);
            app = built;   // last, so a non-null app means "came up whole" and nothing has to track that twice
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            up.countDown();   // whether it came up or not: a test waiting on this must not wait for a timeout
        }
        if (app == null) {
            // It may still have got as far as owning a device. Nothing else will ever hold this reference, so
            // giving it back here is the difference between a failed start and a leaked one.
            release(built);
            return;
        }
        try {
            app.run(gui, 0, frames::incrementAndGet);
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            release(app);
        }
    }

    /**
     * Give an application's windows and device back, on the thread that made them.
     *
     * <p>Keeps a teardown failure rather than throwing it: nothing is watching this thread's stack, and the
     * test's {@link #close} is what reports it. Never over a failure already recorded — the reason the loop
     * stopped is the more useful of the two.
     */
    private void release(GuiApp application) {
        if (application == null) {
            return;
        }
        try {
            application.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            }
        }
    }

    /** Wait for the loop thread to have built the application, and surface what happened if it could not. */
    private void awaitUp() {
        try {
            if (!up.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the application did not come up within "
                        + START_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the application to come up", e);
        }
        rethrow();
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
     * <p>Called on the loop thread — for the first window as well as the rest, which is the point — from
     * inside window creation, so it does nothing but create and record.
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
        window().postWake();
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
            throw new IllegalStateException(app == null
                    ? "the application never came up"
                    : "the application's frame loop died", e);
        }
    }

    @Override
    public void close() {
        // Nothing is closed here, which is the whole of the fix: live()'s finally runs on the thread that
        // made the windows, and this thread is not it. What is left is the request, the join, and reporting
        // whatever the loop thread had no stack to report on.
        HarnessWindow main = window;
        if (main != null) {
            main.requestStop();
            main.postWake();
        }
        try {
            loop.join(CLOSE_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        rethrow();
    }
}
