package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.style.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A row's label is in the ink the application last said for its item ({@link TreeView.Inks}): told before or after
 * the row is built, put back by naming {@link Role#INK}, and given way to by the selection.
 */
class TreeInkTest {

    private static final Map<String, List<String>> KIDS = Map.of("src", List.of("main", "test"));

    private static final class MapSource implements Listing<String> {

        @Override
        public List<String> roots() {
            return List.of("src", "docs");
        }

        @Override
        public String label(String item) {
            return item;
        }

        @Override
        public boolean hasChildren(String item) {
            return KIDS.containsKey(item);
        }

        @Override
        public List<String> children(String item) {
            return KIDS.getOrDefault(item, List.of());
        }
    }

    private static Object ink(HeadlessGui h, TreeView<String> tree, String item) {
        return h.retained(tree.labelNode(item)).textColor();
    }

    private static void tell(HeadlessGui h, TreeView<String> tree, Map<Object, Role> inks) {
        h.bus.publish(TreeView.INKS, new TreeView.Inks(tree.address(), inks));
        h.frame();
        h.frame();
    }

    @Test
    void aLabelIsInItsInkWhetherToldBeforeOrAfterItsRowIsBuilt() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            h.gui.root().children(tree.node());
            h.frame();

            tell(h, tree, Map.of("docs", Role.CAUTION, "main", Role.SUCCESS));
            assertSame(Role.CAUTION, ink(h, tree, "docs"), "a row there now takes its ink at once");
            assertSame(Role.INK, ink(h, tree, "src"), "an item never named is in the ink");

            tree.expandAll("src");
            h.frame();
            h.frame();
            assertSame(Role.SUCCESS, ink(h, tree, "main"), "an ink told before the row was built is kept for it");
            assertSame(Role.INK, ink(h, tree, "test"));

            tell(h, tree, Map.of("docs", Role.INK));
            assertSame(Role.INK, ink(h, tree, "docs"), "naming the ink puts it back");
            assertSame(Role.SUCCESS, ink(h, tree, "main"), "and an item not named keeps what it was told");
            tree.close();
        }
    }
}
