package dev.vexelray.gui.widget;

import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Removing a node releases everything keyed by its id.
 *
 * <p>Removal used to be a private index deletion nothing could observe, so handlers, claims, focusability and
 * focus itself all outlived the node. The visible consequence was not a leak but a hijack: a removed node that
 * held focus went on holding it, so its {@code FOCUSED} claims kept resolving, and deleting a focused multiline
 * editor left its claim on Tab preempting traversal for the rest of the process.
 */
class NodeRemovalTest {

    @Test
    void aRemovedFocusedEditorStopsClaimingTab() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField editor = new TextField(h.gui, "");
            editor.multiline(true);                      // claims Tab at FOCUSED scope, to indent
            TextField other = new TextField(h.gui, "");
            h.gui.root().children(editor.node(), other.node());
            h.frame();
            h.focus(editor.node());

            h.tap(Key.TAB);
            assertEquals("    ", editor.text(), "while focused, the editor's claim takes Tab and indents");

            editor.node().remove();
            h.frame();

            h.tap(Key.TAB);
            assertEquals("    ", editor.text(),
                    "the removed editor is not focused any more, so Tab no longer reaches its claim");
        }
    }

    /** And an ordinary key handler stops firing once its node is gone. */
    @Test
    void aRemovedFieldStopsReceivingTypedText() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = new TextField(h.gui, "");
            h.gui.root().children(f.node());
            h.frame();
            h.focus(f.node());

            h.type("a");
            assertEquals("a", f.text());

            f.node().remove();
            h.frame();

            h.type("b");
            assertEquals("a", f.text(), "typing goes nowhere once the node has left the tree");
        }
    }

    /** Removing a subtree releases the descendants, not just the node named in the mutation. */
    @Test
    void removingAContainerReleasesItsChildren() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = new TextField(h.gui, "");
            var panel = h.gui.column().children(f.node());
            h.gui.root().children(panel);
            h.frame();
            h.focus(f.node());

            h.type("a");
            assertEquals("a", f.text());

            panel.remove();          // the field is a child, never named in the mutation
            h.frame();

            h.type("b");
            assertEquals("a", f.text(), "a descendant of a removed node is released with it");
        }
    }
}
