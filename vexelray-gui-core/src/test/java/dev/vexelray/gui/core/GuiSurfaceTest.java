package dev.vexelray.gui.core;

import dev.vexelray.gui.core.spi.Seams;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a view is handed, {@link Gui}, hands it no way to run code on the GUI thread but its own subscriptions
 * (vexelray-framework's {@code docs/threading.md}, T7.2). The widget library's and the host's seams are on
 * {@link Seams}, which nothing here returns.
 *
 * <p>The allowance is by what the function is, not by name: a subscription's stage runs as its subscriber
 * ({@link Gui#mailbox}, {@link Gui#hear}), and {@link Gui#batch} runs the caller's own code at once, on the caller's
 * thread. A function the GUI keeps and calls later, on its own thread, is a seam, and belongs on {@link Seams}.
 */
class GuiSurfaceTest {

    /** The public methods that take a function, and why each may. */
    private static final Set<String> ALLOWED = Set.of(
            "batch",     // runs the caller's code now, on the caller's thread
            "mailbox",   // a subscription: its stage runs as the subscriber
            "hear");     // the same, for a node's events

    @Test
    void guiTakesNoFunctionButASubscriptionOrTheCallersOwnCode() {
        Set<String> taking = new TreeSet<>();
        for (Method m : Gui.class.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || m.getDeclaringClass() == Object.class) {
                continue;
            }
            for (Class<?> p : m.getParameterTypes()) {
                if (isFunction(p)) {
                    taking.add(m.getName());
                }
            }
        }
        taking.removeAll(ALLOWED);
        assertEquals(Set.of(), taking, "a seam on Gui is one a view can run code in the frame through: move it to Seams");
    }

    @Test
    void nothingOnGuiLeadsToTheSeams() {
        Set<String> leading = new TreeSet<>();
        for (Method m : Gui.class.getMethods()) {
            if (Seams.class.isAssignableFrom(m.getReturnType())) {
                leading.add(m.getName());
            }
        }
        assertEquals(Set.of(), leading, "the seams are reached by Seams.of(gui), never from what a view is handed");
    }

    /**
     * A bus {@code State} carries {@code onCommit}, whose listener runs on whichever thread commits: in the frame,
     * for the GUI's own read models. So handing one out is handing out a seam, and they are on {@link Seams}.
     */
    @Test
    void guiHandsOutNoState() {
        Set<String> states = new TreeSet<>();
        for (Method m : Gui.class.getMethods()) {
            if (sibarum.atchung.State.class.isAssignableFrom(m.getReturnType())) {
                states.add(m.getName());
            }
        }
        assertEquals(Set.of(), states, "a State's onCommit runs code on the committing thread: hand it out from Seams");
    }

    /** A functional interface, an executor or a future: what T7.2 names. */
    private static boolean isFunction(Class<?> type) {
        return type.isAnnotationPresent(FunctionalInterface.class)
                || type == Runnable.class
                || java.util.concurrent.Executor.class.isAssignableFrom(type)
                || java.util.concurrent.Future.class.isAssignableFrom(type)
                || java.util.concurrent.CompletionStage.class.isAssignableFrom(type)
                || type == sibarum.atchung.Subscriber.class;
    }
}
