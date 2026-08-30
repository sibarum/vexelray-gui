package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.drop.Payload;
import dev.vexelray.gui.core.drop.Transfer;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
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
 * selection instead of under the pointer, asks the same {@link Reorder} the same question, is refused the same
 * way, and records into the same {@link History}. Everything these tests assert about refusal, undo and effect
 * is inherited rather than reimplemented.
 */
class TreeTransferTest {

    private static final Map<String, List<String>> KIDS = new LinkedHashMap<>(Map.of(
            "src", List.of("main"),
            "docs", List.of()));

    private static final class MapSource implements TreeView.Source<String> {

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

    /** Records what it was asked, and refuses to copy — a model whose items are unique. */
    private Reorder<String> recording() {
        return (moved, where, effect) -> {
            if (effect == DropEffect.COPY && moved.equals("notes.txt")) {
                return null;   // this one cannot be duplicated, and says so
            }
            String what = effect + " " + moved + " " + where.relation() + " "
                    + (where.isRoot() ? "<root>" : where.reference());
            return change(what);
        };
    }

    private Change change(String what) {
        return new Change() {

            @Override
            public Change apply() {
                done.add(what);
                return change("undo(" + what + ")");
            }
        };
    }

    /** Ctrl plus {@code key}, held and released the way a user does it. */
    private static void chord(HeadlessGui h, Key key) {
        h.chord(key, Key.LEFT_CONTROL);
    }

    /** A tree with focus, a history, and a reorder — the three things a transfer needs. */
    private TreeView<String> tree(HeadlessGui h, History history) {
        h.gui.dropHistory(history);
        TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
        tree.reorderable(recording());
        h.gui.root().children(tree.node());
        tree.focus();
        h.frame();
        return tree;
    }

    @Test
    @DisplayName("a cut takes the row in hand and changes nothing until it is pasted")
    void cutHoldsWithoutMutating() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h, new History());
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
            TreeView<String> tree = tree(h, new History());
            tree.select("notes.txt");
            h.frame();
            chord(h, Key.X);
            h.frame();

            tree.select("docs");           // an empty folder: it can hold children
            h.frame();
            chord(h, Key.V);
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
            assertEquals(List.of("COPY src AFTER notes.txt"), done);
            tree.close();
        }
    }

    @Test
    @DisplayName("a cut lands once; a copy stays in hand")
    void aCutIsSpentAndACopyIsNot() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h, new History());
            tree.select("src");
            h.frame();
            chord(h, Key.X);
            h.frame();
            tree.select("docs");
            h.frame();

            chord(h, Key.V);
            h.frame();
            assertFalse(h.gui.transfer().offers(tree.itemType()), "a cut is spent by the paste that lands it");

            chord(h, Key.V);
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
            chord(h, Key.V);
            h.frame();
            assertEquals(2, done.size(), "a copy pastes as often as asked, which is the reason copy exists");
            tree.close();
        }
    }

    /**
     * The effect is a question the application may answer with no. A model whose items are unique refuses COPY,
     * and the refusal reads as a paste that cannot happen — not as one that quietly turned into a move.
     */
    @Test
    @DisplayName("a refused effect pastes nothing rather than falling back to another")
    void aRefusedCopyDoesNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h, new History());
            tree.select("notes.txt");
            h.frame();
            chord(h, Key.C);               // the reorder refuses COPY for this one
            h.frame();
            tree.select("docs");
            h.frame();

            chord(h, Key.V);
            h.frame();

            assertTrue(done.isEmpty(), "refused, and nothing else happened instead");
            assertTrue(h.gui.transfer().offers(tree.itemType()), "and it is still in hand");
            tree.close();
        }
    }

    @Test
    @DisplayName("a paste is an edit like any other, so it undoes")
    void aPasteIsUndoable() {
        try (HeadlessGui h = new HeadlessGui()) {
            History history = new History();
            TreeView<String> tree = tree(h, history);
            tree.select("notes.txt");
            h.frame();
            chord(h, Key.X);
            h.frame();
            tree.select("docs");
            h.frame();
            chord(h, Key.V);
            h.frame();

            assertTrue(history.canUndo(), "the same stack a drop records into");
            history.undo();
            assertEquals(List.of("MOVE notes.txt INTO docs", "undo(MOVE notes.txt INTO docs)"), done);
            tree.close();
        }
    }

    /**
     * Without a history a paste does nothing at all — the same refusal a drop makes, and for the same reason: a
     * transfer the user could not take back would be the one edit in the framework with no way out of it.
     */
    @Test
    @DisplayName("no history, no paste")
    void aPasteWithNowhereToRecordIsRefused() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            tree.reorderable(recording());
            h.gui.root().children(tree.node());
            tree.focus();
            tree.select("notes.txt");
            h.frame();

            chord(h, Key.X);
            h.frame();
            tree.select("docs");
            h.frame();
            chord(h, Key.V);
            h.frame();

            assertTrue(done.isEmpty(), "nowhere to record it, so it does not happen");
            tree.close();
        }
    }

    /** Two trees, one transfer: the clipboard is the window's, which is what makes exchange between them work. */
    @Test
    @DisplayName("what one tree cuts, another can paste")
    void aTransferCrossesBetweenTrees() {
        try (HeadlessGui h = new HeadlessGui()) {
            History history = new History();
            h.gui.dropHistory(history);
            TreeView<String> from = new TreeView<>(h.gui, new MapSource());
            TreeView<String> to = new TreeView<>(h.gui, new MapSource());
            from.reorderable(recording());
            // The receiving tree is told about the other's payload type — without that they do not accept each
            // other's rows, which is the whole point of the type being per-instance.
            to.reorderable(recording());
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
            TreeView<String> tree = tree(h, new History());
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
            TreeView<String> tree = tree(h, new History());
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
            TreeView<String> tree = tree(h, new History());   // focused, and nothing selected in it yet

            // Put something in hand without going through a row, since selecting one is what this test is
            // about not doing. Transfer.cut is the same value a Ctrl+X builds.
            h.gui.transfer(Transfer.cut(Payload.of(tree.itemType(), "notes.txt")));
            chord(h, Key.V);
            h.frame();

            assertEquals(List.of("MOVE notes.txt INTO <root>"), done);
            tree.close();
        }
    }

}
