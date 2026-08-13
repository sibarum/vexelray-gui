package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pointer capture: once a drag starts on a node, MOVE events keep routing to it (with a clamped fraction) even when
 * the pointer leaves the node, until release — the primitive sliders and scrollbars are built on.
 */
class DragCaptureTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.clicks", ClickEvent.class);

    private static RetainedNode node(long id, float x, float y, float w, float h) {
        RetainedNode n = new RetainedNode(id, NodeKind.BOX);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        return n;
    }

    @Test
    void dragTracksOffNodeUntilRelease() {
        Atchung bus = Atchung.create();
        InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run);

        RetainedNode root = node(0, 0, 0, 300, 100);
        RetainedNode track = node(7, 10, 0, 100, 20); // x in [10,110)
        track.parent = root;
        root.children.add(track);

        List<String> log = new ArrayList<>();
        dispatcher.onDrag(7, e -> log.add(e.phase() + ":" + Math.round(e.fractionX() * 100)));

        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, 60, 10, 0)); // START @ 50%
        bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(200, 10, 140, 0, 0));            // off right -> 100%
        bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(10, 10, -190, 0, 0));            // left edge -> 0%
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, 10, 10, 0)); // END
        dispatcher.dispatch(root);

        assertEquals(List.of("START:50", "MOVE:100", "MOVE:0", "END:0"), log,
                "drag captures on press and keeps tracking off-node with a clamped fraction until release");
    }
}
