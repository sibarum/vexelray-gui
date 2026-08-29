package dev.vexelray.gui.harness;

import dev.vexelray.os.HitRegions;
import dev.vexelray.os.Key;
import dev.vexelray.os.NativeWindow;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * A real window the user never sees, with the frame loop's half of it under a test's control.
 *
 * <p>Everything Vulkan needs — the surface, the size, the handle — is delegated to a genuine
 * {@link NativeWindow}, so rendering, the swapchain and the presenter all behave exactly as they do in
 * the application. {@link #show()} is a no-op, so the window is created and never mapped. What is
 * <em>intercepted</em> is the four methods the host loop uses to decide whether to draw:
 * {@link #pumpEvents()}, {@link #waitEvents(long)}, {@link #postWake()} and {@link #isFocused()}.
 *
 * <h2>The wait really waits</h2>
 *
 * {@link #waitEvents} blocks until {@link #postWake} is called or the budget elapses, and that is the
 * whole point rather than an implementation detail. A harness whose wait returned immediately would let
 * the loop spin, and a spinning loop draws the next frame whether or not anything asked for one — so
 * every test would pass, including against the bug it was written for. The failures this exists to
 * catch are all of the form <em>nothing asked for a frame</em>, and they are only visible to a loop
 * that would otherwise have stayed asleep.
 *
 * <p>{@link #budgets()} records what the loop decided each iteration, which is the other half: it is
 * how a test asserts that an idle application parked rather than merely that it looked idle.
 *
 * <p>Not thread-safe by accident — the loop calls this from its own thread and a test drives it from
 * another, which is exactly the arrangement a real application has.
 */
public final class HarnessWindow implements NativeWindow {

    private final NativeWindow real;
    private final Object lock = new Object();
    private final List<Long> budgets = new ArrayList<>();

    private boolean woken;
    private boolean focused = true;
    private boolean closing;
    private long waits;

    /**
     * Wrap {@code real}, which should be a freshly created window that has not been shown.
     *
     * <p>The caller keeps ownership: this closes the wrapped window when the application closes it,
     * because that is what the application would have done to a window of its own.
     */
    public HarnessWindow(NativeWindow real) {
        this.real = java.util.Objects.requireNonNull(real, "real");
    }

    // ---- the four the loop uses -------------------------------------------------------------------

    @Override
    public boolean pumpEvents() {
        real.pumpEvents();
        // The wrapped window's close state is the OS's business and a test's is not: a harness run ends
        // when the test says so, not when a window nobody can see decides it has been dismissed.
        synchronized (lock) {
            return !closing;
        }
    }

    @Override
    public void waitEvents(long timeoutNanos) {
        long deadline = timeoutNanos == Long.MAX_VALUE ? Long.MAX_VALUE
                : System.nanoTime() + Math.max(0L, timeoutNanos);
        synchronized (lock) {
            budgets.add(timeoutNanos);
            waits++;
            while (!woken && !closing) {
                long remaining = deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.nanoTime();
                if (deadline != Long.MAX_VALUE && remaining <= 0) {
                    break;
                }
                try {
                    // Milliseconds, like the platform it stands in for, and never zero for a positive
                    // budget: rounding a sub-millisecond wait to zero turns this into a spin, which is
                    // the one behaviour that would make every test here meaningless.
                    lock.wait(deadline == Long.MAX_VALUE ? 0L : Math.max(1L, remaining / 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            woken = false;
            lock.notifyAll();
        }
    }

    @Override
    public void postWake() {
        synchronized (lock) {
            woken = true;
            lock.notifyAll();
        }
    }

    @Override
    public boolean isFocused() {
        synchronized (lock) {
            return focused;
        }
    }

    // ---- what a test drives -----------------------------------------------------------------------

    /** Whether the application should believe anyone is looking at it. */
    public void focused(boolean focused) {
        synchronized (lock) {
            this.focused = focused;
        }
        postWake();   // the budget changes with focus, so the loop should recompute it now
    }

    /** End the run: the next pump reports the window gone and {@code run()} returns. */
    public void requestStop() {
        synchronized (lock) {
            closing = true;
            lock.notifyAll();
        }
    }

    /**
     * Every budget the loop has parked on, in order and in nanoseconds. {@link Long#MAX_VALUE} is
     * "until something wakes me".
     */
    public List<Long> budgets() {
        synchronized (lock) {
            return List.copyOf(budgets);
        }
    }

    /** How many times the loop has decided to wait — one per iteration that got as far as parking. */
    public long waits() {
        synchronized (lock) {
            return waits;
        }
    }

    /** Forget the recorded budgets, so a test can measure one interaction rather than the whole run. */
    public void resetBudgets() {
        synchronized (lock) {
            budgets.clear();
        }
    }

    // ---- delegated: everything the GPU and the framework need to be real --------------------------

    @Override
    public int width() {
        return real.width();
    }

    @Override
    public int height() {
        return real.height();
    }

    /** Deliberately nothing: the window exists, is rendered into, and is never put on screen. */
    @Override
    public void show() {
    }

    @Override
    public boolean isKeyDown(Key key) {
        return real.isKeyDown(key);
    }

    @Override
    public long createVulkanSurface(long vkInstance, MemorySegment vkGetInstanceProcAddr) {
        return real.createVulkanSurface(vkInstance, vkGetInstanceProcAddr);
    }

    @Override
    public long osHandle() {
        return real.osHandle();
    }

    @Override
    public void close() {
        requestStop();
        real.close();
    }

    @Override
    public int screenX() {
        return real.screenX();
    }

    @Override
    public int screenY() {
        return real.screenY();
    }

    @Override
    public int outerWidth() {
        return real.outerWidth();
    }

    @Override
    public int outerHeight() {
        return real.outerHeight();
    }

    @Override
    public void setPosition(int x, int y) {
        real.setPosition(x, y);
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        real.setBounds(x, y, width, height);
    }

    @Override
    public void hide() {
    }

    @Override
    public boolean isVisible() {
        return false;
    }

    @Override
    public void focus() {
    }

    @Override
    public void setEnabled(boolean enabled) {
        real.setEnabled(enabled);
    }

    @Override
    public boolean cancelClose() {
        return real.cancelClose();
    }

    @Override
    public void setCursor(NativeWindow.Cursor cursor) {
        real.setCursor(cursor);
    }

    @Override
    public void setHitRegions(HitRegions regions) {
        real.setHitRegions(regions);
    }

    @Override
    public void setFrameSink(Runnable renderOneFrame) {
        real.setFrameSink(renderOneFrame);
    }

    @Override
    public void minimize() {
    }

    @Override
    public void maximize() {
    }

    @Override
    public void restore() {
    }

    @Override
    public boolean isMaximized() {
        return false;
    }

    /** Never: a window that reported itself minimized would have the host skip presenting it. */
    @Override
    public boolean isMinimized() {
        return false;
    }

    @Override
    public void requestClose() {
        requestStop();
    }
}
