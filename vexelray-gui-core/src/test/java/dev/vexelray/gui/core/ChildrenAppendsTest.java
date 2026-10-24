package dev.vexelray.gui.core;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Node.children(...)} reads like a setter and appends. Building a node with it is right and says nothing;
 * calling it on a node that already has children is said once, naming the two calls that mean what was likely meant
 * (Maistro's FN-6: a row "rebuilt" this way showed every chip twice).
 */
class ChildrenAppendsTest {

    @BeforeEach
    void silence() {
        Diagnostics.reset();
    }

    private static List<String> said() {
        return Diagnostics.recorded().stream().filter(m -> m.startsWith("children(...) replacing what was there"))
                .toList();
    }

    private static float noText(RetainedNode n, dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    private static Node chip(Gui gui) {
        return gui.box().width(Length.dp(10)).height(Length.dp(10));
    }

    @Test
    void buildingANodeWithItSaysNothing() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.root().children(gui.row().children(chip(gui), chip(gui)));
            gui.frame(200f, 100f, ChildrenAppendsTest::noText);
            assertEquals(List.of(), said());
        }
    }

    @Test
    void callingItAgainAppendsAndSaysSoNamingTheFix() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node row = gui.row().children(chip(gui), chip(gui));
            gui.root().children(row);
            gui.frame(200f, 100f, ChildrenAppendsTest::noText);

            row.children(chip(gui), chip(gui));
            gui.frame(200f, 100f, ChildrenAppendsTest::noText);

            assertEquals(4, row.layout() == null ? -1 : childCount(gui, row), "it appended, as it always has");
            List<String> m = said();
            assertEquals(1, m.size(), Diagnostics.recorded().toString());
            assertTrue(m.getFirst().contains("box " + row.id() + " had 2 children and was given 2 more"), m.getFirst());
            assertTrue(m.getFirst().contains("arrange(...)") && m.getFirst().contains("append(...)"), m.getFirst());
        }
    }

    @Test
    void appendSaysWhatItDoesAndIsNotReported() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node row = gui.row().children(chip(gui));
            gui.root().children(row);
            gui.frame(200f, 100f, ChildrenAppendsTest::noText);

            row.append(chip(gui), chip(gui));
            gui.frame(200f, 100f, ChildrenAppendsTest::noText);

            assertEquals(3, childCount(gui, row));
            assertEquals(List.of(), said());
        }
    }

    private static int childCount(Gui gui, Node node) {
        RetainedNode r = find(gui.frame(200f, 100f, ChildrenAppendsTest::noText), node.id());
        return r == null ? -1 : r.children.size();
    }

    private static RetainedNode find(RetainedNode from, long id) {
        if (from.id == id) {
            return from;
        }
        for (RetainedNode c : from.children) {
            RetainedNode hit = find(c, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }
}
