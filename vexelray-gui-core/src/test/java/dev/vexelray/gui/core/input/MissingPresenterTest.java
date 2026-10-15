package dev.vexelray.gui.core.input;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A right click on a node with a menu, on a tree that has nothing to draw menus with: the menu is not collected, and
 * that is said once rather than not at all. Core alone has no {@link MenuPresenter.Default} on its class path, which
 * is exactly this case.
 *
 * <p>The tree is a panel (1) under the root (0); the panel has the menu, the root has none.
 */
class MissingPresenterTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.missing-presenter.clicks", ClickEvent.class);

    private final Atchung bus = Atchung.create();
    private final InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
    private final Map<Long, RetainedNode> index = new HashMap<>();
    private final RetainedNode root = node(0, 0, 0, 200, 200, null);
    private final RetainedNode panel = node(1, 0, 0, 100, 100, root);

    MissingPresenterTest() {
        d.nodeLookup(index::get);
    }

    @BeforeEach
    @AfterEach
    void forgetWarnings() {
        Diagnostics.reset();
    }

    private RetainedNode node(long id, float x, float y, float w, float h, RetainedNode parent) {
        RetainedNode n = new RetainedNode(id);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        if (parent != null) {
            n.parent = parent;
            parent.children.add(n);
        }
        index.put(id, n);
        return n;
    }

    private void rightClick(int x, int y) {
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.RIGHT, x, y, 0));
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.RIGHT, x, y, 0));
        d.dispatch(root);
    }

    private static List<String> undrawn() {
        return Diagnostics.recorded().stream().filter(m -> m.contains("no MenuPresenter is installed")).toList();
    }

    @Test
    void aMenuWithNothingToDrawItIsSaidOnce() {
        List<String> asked = new ArrayList<>();
        d.onContextMenu(panel.id, m -> asked.add("asked"));

        rightClick(50, 50);
        rightClick(60, 60);

        assertEquals(1, undrawn().size(), "said, and said once: " + Diagnostics.recorded());
        assertTrue(asked.isEmpty(), "with nothing to show it, the menu is not built");
    }

    @Test
    void aClickWhereNoNodeHasAMenuSaysNothing() {
        d.onContextMenu(panel.id, m -> { });

        rightClick(150, 150);   // the root, outside the panel, has no menu of its own

        assertTrue(undrawn().isEmpty(), "no menu was asked for here: " + Diagnostics.recorded());
    }

    @Test
    void aTreeWithAPresenterSaysNothing() {
        List<List<MenuItem>> shown = new ArrayList<>();
        d.menuPresenter((where, items) -> shown.add(items));
        d.onContextMenu(panel.id, m -> m.item("Open", () -> { }));

        rightClick(50, 50);

        assertEquals(1, shown.size(), "the presenter drew it");
        assertTrue(undrawn().isEmpty());
    }
}
