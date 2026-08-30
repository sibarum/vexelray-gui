package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.text.Link;
import dev.vexelray.gui.core.text.Span;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hyperlinks in a text field: underlined only while the modifier is held, followed by Ctrl+click and Ctrl+Enter,
 * and carried along by the edits around them.
 *
 * <p>The two assertions that carry the design are that the underline is <b>derived</b> — it appears and
 * disappears with the key and is never committed into the document — and that following a link is not a text
 * gesture, so the caret is exactly where it was left afterwards.
 */
class HyperlinkTest {

    /** Underline spans currently on the field's node, which is what the renderer would draw. */
    private static List<Span> underlines(HeadlessGui h, TextField field) {
        List<Span> spans = h.retained(field.node()).spans();
        return spans == null ? List.of() : spans.stream().filter(Span::underline).toList();
    }

    private static TextField field(HeadlessGui h, String text) {
        TextField f = new TextField(h.gui, text);
        f.node().width(Length.dp(400));
        h.gui.root().append(f.node());
        h.frame();
        return f;
    }

    @Test
    void aLinkUnderlinesItselfOnlyWhileTheModifierIsHeld() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "see the accent swatch");
            f.links(List.of(new Link(8, 14, "prefs/theme.accent")));
            h.frame();

            assertTrue(underlines(h, f).isEmpty(), "nothing is underlined until the user asks");

            h.down(Key.LEFT_CONTROL).frame();

            assertEquals(List.of(Span.underline(8, 14)), underlines(h, f),
                    "holding the modifier shows every link at once");

            h.up(Key.LEFT_CONTROL).frame();

            assertTrue(underlines(h, f).isEmpty(), "and releasing it puts them away again");
            assertTrue(f.spans().isEmpty(),
                    "the underline is derived, never committed: the document is exactly as it was");
        }
    }

    @Test
    void aModifierStuckByLosingFocusIsReleasedRatherThanHeldForEver() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "see the accent swatch");
            f.links(List.of(new Link(8, 14, "prefs/theme.accent")));
            h.down(Key.LEFT_CONTROL).frame();
            assertFalse(underlines(h, f).isEmpty(), "armed");

            // Alt-tab away: the key comes up over somebody else's window and this one never hears the release.
            h.bus.publish(dev.vexelray.gui.core.input.InputTopics.INPUT,
                    new sibarum.tactroller.api.InputEvent.FocusChanged(false, 0));
            h.frame();

            assertTrue(underlines(h, f).isEmpty(),
                    "a window that lost focus is not still holding Ctrl, whatever it last saw");
        }
    }

    @Test
    void ctrlClickFollowsTheLinkAndLeavesTheCaretAlone() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "see the accent swatch");
            AtomicReference<String> followed = new AtomicReference<>();
            f.links(List.of(new Link(8, 14, "prefs/theme.accent")));
            f.onLinkActivate(link -> followed.set(link.target()));
            f.caret(0);
            h.frame();

            dev.vexelray.gui.core.layout.Rect box = f.node().layout().rect();
            float x = box.x() + HeadlessGui.FIELD_PAD_X + 10 * HeadlessGui.CELL + 1f;
            float y = box.y() + box.h() / 2f;

            h.click(x, y);
            assertNull(followed.get(), "a plain click on the words of a link is still a click in text");
            assertEquals(10, f.caret(), "which places the caret");

            h.down(Key.LEFT_CONTROL).frame();
            h.click(x, y);
            h.up(Key.LEFT_CONTROL).frame();

            assertEquals("prefs/theme.accent", followed.get(), "Ctrl+click follows it");
            assertEquals(10, f.caret(),
                    "and going somewhere is not an edit: the caret is where the user left it");
        }
    }

    @Test
    void ctrlEnterFollowsTheLinkTheCaretIsIn() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "see the accent swatch");
            AtomicReference<String> followed = new AtomicReference<>();
            f.links(List.of(new Link(8, 14, "prefs/theme.accent")));
            f.onLinkActivate(link -> followed.set(link.target()));

            h.focus(f.node());
            f.caret(0);
            h.frame();
            h.chord(Key.ENTER, Key.LEFT_CONTROL);
            assertNull(followed.get(), "the caret is not in a link");

            f.caret(10);
            h.frame();
            h.chord(Key.ENTER, Key.LEFT_CONTROL);
            h.frame();

            assertEquals("prefs/theme.accent", followed.get(),
                    "a link has to be reachable from the keyboard, or it is only half a feature");
        }
    }

    @Test
    void aLinkFollowsItsTextThroughEdits() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "see the accent swatch");
            f.links(List.of(new Link(8, 14, "prefs/theme.accent")));

            h.focus(f.node());
            f.caret(0);
            h.frame();
            h.type("please ");

            assertEquals(new Link(15, 21, "prefs/theme.accent"), f.links().get(0),
                    "text inserted before a link moves it, with no bookkeeping asked of the caller");
        }
    }

    @Test
    void aLinkWhoseTextIsDeletedIsGone() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "see the accent swatch");
            f.links(List.of(new Link(8, 14, "prefs/theme.accent")));

            h.focus(f.node());
            f.select(8, 14);
            h.frame();
            h.tap(Key.BACKSPACE);
            h.frame();

            assertTrue(f.links().isEmpty(),
                    "a link whose words are gone is not a link with an empty range — it is not there");
        }
    }
}
