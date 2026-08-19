package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An application binds its command shortcuts (close tab, save, new) on <em>every</em> window it owns, because
 * keys are focal: the input layer delivers them only to the focused window, so a chord bound on one window's
 * {@code Gui} does nothing while another window has focus. These tests pin the framework behaviour that makes
 * that pattern work — a widget holding keyboard focus must not swallow the app's global chords.
 *
 * <p>Regression: with a folder-explorer window focused, Ctrl+W stopped closing editor tabs. The window's own
 * {@code Gui} had no shortcuts bound, and its focused {@link TreeView} was the only key consumer.
 */
class AppShortcutReachTest {

    private static TreeView.Source<Path> twoRoots() {
        return new TreeView.Source<>() {
            @Override
            public List<Path> roots() {
                return List.of(Path.of("alpha"), Path.of("beta"));
            }

            @Override
            public String label(Path item) {
                return item.getFileName().toString();
            }

            @Override
            public boolean hasChildren(Path item) {
                return false;
            }

            @Override
            public List<Path> children(Path item) {
                return List.of();
            }
        };
    }

    /** A focused tree consumes its own navigation keys but must let unclaimed app chords through. */
    @Test
    void aFocusedTreeDoesNotSwallowGlobalShortcuts() {
        try (HeadlessGui h = new HeadlessGui();
             TreeView<Path> tree = new TreeView<>(h.gui, twoRoots())) {
            tree.node().width(Length.FILL).height(Length.FILL);
            h.gui.root().children(tree.node());

            AtomicInteger closes = new AtomicInteger();
            h.gui.shortcut(Key.W, closes::incrementAndGet, sibarum.tactroller.api.Modifier.CONTROL);

            h.frame();
            tree.focus();
            h.frame();

            h.chord(Key.W, Key.LEFT_CONTROL);
            h.frame();

            assertEquals(1, closes.get(),
                    "a global Ctrl+W must reach the app even while the tree holds keyboard focus");
        }
    }

    /** The tree still owns the keys it does claim, so sharing a Gui costs it nothing. */
    @Test
    void theTreeStillOwnsItsNavigationKeys() {
        try (HeadlessGui h = new HeadlessGui();
             TreeView<Path> tree = new TreeView<>(h.gui, twoRoots())) {
            tree.node().width(Length.FILL).height(Length.FILL);
            h.gui.root().children(tree.node());
            h.gui.shortcut(Key.DOWN, () -> { }, sibarum.tactroller.api.Modifier.CONTROL);

            h.frame();
            tree.focus();
            h.frame();

            h.tap(Key.DOWN);
            h.frame();

            assertEquals("alpha", tree.selected().getFileName().toString(),
                    "Down moves the tree's own selection");
        }
    }
}
