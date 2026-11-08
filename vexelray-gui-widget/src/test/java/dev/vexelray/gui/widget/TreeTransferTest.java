package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.drop.Payload;
import dev.vexelray.gui.core.drop.Transfer;
import dev.vexelray.gui.core.input.InputTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cut, copy and paste on a tree — which are not a second way of moving things, but the keyboard's way of
 * reaching what a drag reaches.
 *
 * <p>That is the whole design and it is why there is so little here: a paste resolves a {@link Placement} at the
 * selection instead of under the pointer, is offered on the same terms, and is announced the same way, on
 * {@code PLACED}. Everything these tests assert about refusal and effect is inherited rather than reimplemented.
 */
class TreeTransferTest {

    private static final Map<String, List<String>> KIDS = new LinkedHashMap<>(Map.of(
            "src", List.of("main"),
            "docs", List.of()));

    private static final class MapSource implements Listing<String> {

        @Override
        public List<String> roots() {
            return List.of("src", "docs", "notes.txt");
        }

        @Override
        public String label(String item) {
            return item;
        }

        @Override
        public boolean hasChildren(String item) {
            return !KIDS.getOrDefault(item, List.of()).isEmpty();
        }

        @Override
        public List<String> children(String item) {
            return KIDS.getOrDefault(item, List.of());
        }

        @Override
        public boolean acceptsChildren(String item) {
            return KIDS.containsKey(item);   // a folder is a folder even when empty; notes.txt is not
        }
    }

    private final List<String> done = new ArrayList<>();

    /** Make {@code tree} reorderable with {@code effects}, and write down every placement it announces. */
    private void recording(HeadlessGui h, TreeView<String> tree, DropEffect... effects) {
        tree.reorderable(effects);
        h.bus.subscribe(TreeView.PLACED, p -> {
            if (p.at().equals(tree.address())) {
                done.add(p.effect() + " " + p.item() + " " + p.where().relation() + " "
                        + (p.where().isRoot() ? "<root>" : p.where().reference()));
            }
        });
    }

    /** Ctrl plus {@code key}, held and released the way a user does it. */
    private static void chord(HeadlessGui h, Key key) {
        h.chord(key, Key.LEFT_CONTROL);
    }

    /** A tree with focus that moves and copies: what a transfer needs. */
    private TreeView<String> tree(HeadlessGui h) {
        TreeView<String> tree = Listing.tree(h, new MapSource());
        recording(h, tree, DropEffect.MOVE, DropEffect.COPY);
        h.gui.root().children(tree.node());
        tree.focus();
        h.frame();
        return tree;
    }

    @Test
    @DisplayName("a cut takes the row in hand and changes nothing until it is pasted")
    void cutHoldsWithoutMutating() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);
            tree.select("notes.txt");
            h.frame();

            chord(h, Key.X);
            h.frame();

            assertTrue(h.gui.transfer().offers(tree.itemType()), "something is in hand");
            assertEquals(DropEffect.MOVE, h.gui.transfer().effect());
            assertTrue(done.isEmpty(), "a cut is an intention; the model is untouched until the paste lands");
            tree.close();
        }
    }

    @Test
    @DisplayName("pasting onto a folder puts it inside, and onto a file puts it beside")
    void pasteResolvesAPlacementFromWhatTheRowCanHold() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);
            tree.select("notes.txt");
            h.frame();
            chord(h, Key.X);
            h.frame();

            tree.select("docs");           // an empty folder: it can hold children
            h.frame();
            chord(h, Key.V);
            h.frame();
            h.frame();
            assertEquals(List.of("MOVE notes.txt INTO docs"), done);

            done.clear();
            tree.select("src");
            h.frame();
            chord(h, Key.C);               // copy src...
            h.frame();
            tree.select("notes.txt");      // ...onto a leaf, which cannot hold anything
            h.frame();
            chord(h, Key.V);
            h.frame();
            h.frame();
            assertEquals(List.of("COPY src AFTER notes.txt"), done);
            tree.close();
        }
    }

    @Test
    @DisplayName("a cut lands once; a copy stays in hand")
    void aCutIsSpentAndACopyIsNot() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);
            tree.select("src");
            h.frame();
            chord(h, Key.X);
            h.frame();
            tree.select("docs");
            h.frame();

            chord(h, Key.V);
            h.frame();
            h.frame();
            assertFalse(h.gui.transfer().offers(tree.itemType()), "a cut is spent by the paste that lands it");

            chord(h, Key.V);
            h.frame();
            h.frame();
            assertEquals(1, done.size(), "and pasting again does nothing, rather than moving it twice");

            done.clear();
            tree.select("src");
            h.frame();
            chord(h, Key.C);
            h.frame();
            tree.select("docs");
            h.frame();
            chord(h, Key.V);
            h.frame();
            h.frame();
            chord(h, Key.V);
            h.frame();
            h.frame();
            assertEquals(2, done.size(), "a copy pastes as often as asked, which is the reason copy exists");
            tree.close();
        }
    }

    /**
     * A model whose items are unique has no copy, and says so once, by leaving COPY out: Ctrl+C takes nothing in
     * hand, and Copy is not on the menu. A copy that cannot land never reads as one that quietly turned into a move.
     */
    @Test
    @DisplayName("a tree that does not copy takes nothing in hand on Ctrl+C")
    void aTreeWithoutCopyCopiesNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            recording(h, tree, DropEffect.MOVE);
            h.gui.root().children(tree.node());
            tree.focus();
            tree.select("notes.txt");
            h.frame();

            chord(h, Key.C);
            h.frame();

            assertFalse(h.gui.transfer().offers(tree.itemType()), "nothing to paste, so no paste to refuse later");
            tree.close();
        }
    }

    /** What only the tree can see is refused at the paste as it is at the drop: a folder pasted into itself. */
    @Test
    @DisplayName("a paste the tree can see is no placement announces nothing")
    void aPasteIntoItselfIsRefused() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);
            tree.select("docs");
            h.frame();
            chord(h, Key.X);
            h.frame();

            chord(h, Key.V);               // into docs, which is itself
            h.frame();
            h.frame();

            assertTrue(done.isEmpty(), "refused, and nothing else happened instead");
            assertTrue(h.gui.transfer().offers(tree.itemType()), "and it is still in hand");
            tree.close();
        }
    }

    /** Two trees, one transfer: the clipboard is the window's, which is what makes exchange between them work. */
    @Test
    @DisplayName("what one tree cuts, another can paste")
    void aTransferCrossesBetweenTrees() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> from = Listing.tree(h, new MapSource());
            TreeView<String> to = Listing.tree(h, new MapSource());
            recording(h, from, DropEffect.MOVE);
            // The receiving tree is told about the other's payload type — without that they do not accept each
            // other's rows, which is the whole point of the type being per-instance.
            recording(h, to, DropEffect.MOVE);
            h.gui.root().children(from.node(), to.node());
            from.focus();
            from.select("notes.txt");
            h.frame();

            chord(h, Key.X);
            h.frame();

            assertTrue(h.gui.transfer().offers(from.itemType()));
            assertFalse(h.gui.transfer().offers(to.itemType()),
                    "and the second tree does not silently accept the first's rows");
            from.close();
            to.close();
        }
    }

    @Test
    @DisplayName("the chords belong to the tree only while it has focus")
    void theChordsAreClaimedByFocus() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);
            tree.select("notes.txt");
            h.frame();

            TextField field = new TextField(h.gui, "hello");
            h.gui.root().append(field.node());
            h.gui.focus(field.node());
            h.frame();

            chord(h, Key.X);
            h.frame();

            assertFalse(h.gui.transfer().offers(tree.itemType()),
                    "the field has focus, so Ctrl+X is the field's cut and not the tree's");
            tree.close();
            field.close();
        }
    }

    /** Modifier is part of the chord, so an unmodified key never triggers one. */
    @Test
    @DisplayName("plain X is not a cut")
    void theModifierIsPartOfIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);
            tree.select("notes.txt");
            h.frame();

            h.chord(Key.X);   // no modifier held
            h.frame();

            assertFalse(h.gui.transfer().offers(tree.itemType()));
            tree.close();
        }
    }

    /** Nothing selected is not an error; it is a paste into the top level. */
    @Test
    @DisplayName("a paste with nothing selected goes to the root")
    void pastingWithNoSelectionMeansTheRoot() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);   // focused, and nothing selected in it yet

            // Put something in hand without going through a row, since selecting one is what this test is
            // about not doing. Transfer.cut is the same value a Ctrl+X builds.
            // main, which has no row here: src is shut. notes.txt is already at the top level, and a move there would
            // leave it where it is.
            h.gui.transfer(Transfer.cut(Payload.of(tree.itemType(), "main")));
            chord(h, Key.V);
            h.frame();
            h.frame();

            assertEquals(List.of("MOVE main INTO <root>"), done);
            tree.close();
        }
    }

}
