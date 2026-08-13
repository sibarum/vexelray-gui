package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A window resize is observable on the bus: {@link Gui#frame} publishes the new size to the {@code State<Viewport>}
 * (coalesced, latest-wins) whenever the viewport changes, so any component can react without touching the window.
 */
class ViewportStateTest {

    private static final TextMeasurer TM = (n, axis, sizePx) -> axis == Axis.HORIZONTAL ? 0f : sizePx;

    @Test
    void framePublishesViewportOnResize() {
        Gui gui = new Gui();
        List<Viewport> seen = new ArrayList<>();
        gui.viewport().onCommit(v -> seen.add(v.value()));

        gui.frame(800f, 600f, TM);   // initial size
        gui.frame(800f, 600f, TM);   // unchanged -> no new commit
        gui.frame(400f, 300f, TM);   // resize -> new commit

        assertEquals(new Viewport(400, 300), gui.viewport().current().value(), "State holds the latest size");
        assertEquals(List.of(new Viewport(800, 600), new Viewport(400, 300)), seen,
                "one commit per actual size change, none for the unchanged frame");
        gui.close();
    }
}
