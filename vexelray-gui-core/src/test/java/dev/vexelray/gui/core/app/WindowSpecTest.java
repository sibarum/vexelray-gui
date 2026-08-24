package dev.vexelray.gui.core.app;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The lifecycle moments on a window spec, and the one distinction between them: {@code onCreated} and
 * {@code onClosed} are <b>notifications</b>, so more than one party may want to hear them and they add;
 * {@code onCloseRequest} is a <b>decision</b>, so it replaces.
 */
class WindowSpecTest {

    private static final WindowConfig CONFIG = WindowConfig.of("Test", 100, 100);

    /**
     * The property a component needs in order to wire itself into a window an application is still describing —
     * a {@code TitleBar} binding its caption buttons, say. If these replaced, whichever was chained last would
     * silently win, and which one that is would depend on the order an application happened to write.
     */
    @Test
    void creationAndClosureNotifyEveryoneWhoAsked() {
        try (Gui gui = new Gui(Atchung.create())) {
            List<String> heard = new ArrayList<>();
            WindowSpec spec = WindowSpec.of(CONFIG, gui)
                    .onCreated(w -> heard.add("first created"))
                    .onClosed(() -> heard.add("first closed"))
                    .onCreated(w -> heard.add("second created"))
                    .onClosed(() -> heard.add("second closed"));

            spec.onCreated().accept(null);
            spec.onClosed().run();

            assertEquals(List.of("first created", "second created", "first closed", "second closed"), heard,
                    "both are told, in the order they asked to be");
        }
    }

    @Test
    void aSpecWithNoCallbacksIsSafeToRun() {
        try (Gui gui = new Gui(Atchung.create())) {
            WindowSpec spec = WindowSpec.of(CONFIG, gui);
            spec.onCreated().accept(null);
            spec.onClosed().run();       // the default is a no-op, not a null
            assertSame(CONFIG, spec.config());
        }
    }

    /** Passing null adds nothing rather than clearing what is there — the older callers relied on it. */
    @Test
    void nullAddsNothing() {
        try (Gui gui = new Gui(Atchung.create())) {
            List<String> heard = new ArrayList<>();
            WindowSpec spec = WindowSpec.of(CONFIG, gui)
                    .onCreated(w -> heard.add("created"))
                    .onCreated(null)
                    .onClosed(() -> heard.add("closed"))
                    .onClosed(null);

            spec.onCreated().accept(null);
            spec.onClosed().run();

            assertEquals(List.of("created", "closed"), heard);
        }
    }

    /**
     * A close request is answered once. Two handlers both calling {@code proceed()} or {@code cancel()} on the
     * same request is a bug, so this one deliberately does not compose.
     */
    @Test
    void aCloseRequestHasExactlyOneAnswer() {
        try (Gui gui = new Gui(Atchung.create())) {
            List<String> asked = new ArrayList<>();
            WindowSpec spec = WindowSpec.of(CONFIG, gui)
                    .onCloseRequest(r -> asked.add("first"))
                    .onCloseRequest(r -> asked.add("second"));

            spec.onCloseRequest().accept(null);

            assertEquals(List.of("second"), asked, "the latest handler is the one that decides");
        }
    }
}
