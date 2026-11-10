package dev.vexelray.gui.core;

import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.os.HitRegions;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which edge a resize is running from, as the tree sees it (docs/plans/window-grips.md §3.4): during the drag the
 * pointer is the window manager's, so this State is all a grip has to stay lit by.
 */
class WindowResizingTest {

    @Test
    void theHostsAnswerIsCommittedOnChangeOnly() {
        try (Gui gui = new Gui(Atchung.create())) {
            Seams seams = Seams.of(gui);
            List<Optional<WindowEdge>> seen = new ArrayList<>();
            seams.resizing().onCommit(v -> seen.add(v.value()));

            assertEquals(Optional.empty(), seams.resizing().value(), "no resize to begin with");
            seams.resizing(WindowEdge.BOTTOM_RIGHT);
            seams.resizing(WindowEdge.BOTTOM_RIGHT);   // every frame of the drag says the same
            seams.resizing(null);
            seams.resizing(null);

            assertEquals(List.of(Optional.of(WindowEdge.BOTTOM_RIGHT), Optional.empty()), seen,
                    "one commit when the drag starts and one when it ends, however many frames ask");
        }
    }

    @Test
    void everyResizeZoneIsAnEdgeAndNothingElseIs() {
        for (HitRegions.Zone zone : HitRegions.Zone.values()) {
            WindowEdge edge = WindowEdge.of(zone);
            if (zone.isResize()) {
                assertEquals(zone, edge.zone(), "the edge for " + zone);
            } else {
                assertNull(edge, zone + " is no edge");
            }
        }
        assertNull(WindowEdge.of(null), "no resize, no edge");
    }
}
