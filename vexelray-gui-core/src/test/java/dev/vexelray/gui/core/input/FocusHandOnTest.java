package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the keyboard goes when what has it stops being there to type into, and where keys typed straight after a
 * chord go: Vexplore v0.2's FN-3 (a hidden field kept the keyboard) and FN-5 (keys after Ctrl+L landed in the list).
 */
class FocusHandOnTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.clicks", ClickEvent.class);

    /** A root holding a breadcrumb button (1), a path field (2) and a list (3), in that order. */
    private final Map<Long, RetainedNode> index = new HashMap<>();
    private final RetainedNode root = add(0, null);
    private final RetainedNode crumb = add(1, root);
    private final RetainedNode path = add(2, root);
    private final RetainedNode list = add(3, root);

    private RetainedNode add(long id, RetainedNode parent) {
        RetainedNode n = new RetainedNode(id);
        n.w = 100;
        n.h = 20;
        if (parent != null) {
            n.parent = parent;
            parent.children.add(n);
        }
        index.put(id, n);
        return n;
    }

    private InputDispatcher dispatcher(Atchung bus) {
        InputDispatcher d = new InputDispatcher(bus, "test", CLICKS);
        d.nodeLookup(index::get);
        d.setFocusable(1, true);
        d.setFocusable(2, true);
        d.setFocusable(3, true);
        return d;
    }

    private static void publish(Atchung bus, InputEvent e) {
        bus.publish(InputTopics.input("test"), e);
    }

    @Test
    void aNodeHiddenWhileItHasTheKeyboardHandsItOnInTabOrder() {
        Atchung bus = Atchung.create();
        InputDispatcher d = dispatcher(bus);
        StringBuilder intoList = new StringBuilder();
        d.onChar(3, intoList::appendCodePoint);
        d.focus(2);
        d.dispatch(root);
        assertEquals(2, d.focused());

        path.set(PropKey.VISIBLE, false);
        publish(bus, new InputEvent.CharTyped('x', 0));
        d.dispatch(root);
        assertEquals(3, d.focused(), "the next focusable after the field's place has it");
        assertEquals("x", intoList.toString(), "and the key typed in the frame it was hidden reaches it, not the field");
    }

    @Test
    void handingOnWrapsAndSkipsWhatIsHiddenOrDisabled() {
        Atchung bus = Atchung.create();
        InputDispatcher d = dispatcher(bus);
        d.focus(3);
        d.dispatch(root);
        crumb.set(PropKey.DISABLED, true);
        list.set(PropKey.VISIBLE, false);
        d.dispatch(root);
        assertEquals(2, d.focused(), "past the end it wraps, over the disabled crumb, to the field");
    }

    @Test
    void aHiddenNodeUnderAHiddenAncestorHandsOnToo() {
        Atchung bus = Atchung.create();
        RetainedNode bar = add(4, root);
        RetainedNode inBar = add(5, bar);
        InputDispatcher d = dispatcher(bus);
        d.setFocusable(5, true);
        d.focus(5);
        d.dispatch(root);
        bar.set(PropKey.VISIBLE, false);
        d.dispatch(root);
        assertEquals(1, d.focused(), "the bar hidden, its field lets go, to the first focusable after it (wrapping)");
        assertTrue(!inBar.shownInTree());
    }

    @Test
    void aNodeFocusedWhileHiddenKeepsItUntilItHasBeenShown() {
        Atchung bus = Atchung.create();
        InputDispatcher d = dispatcher(bus);
        path.set(PropKey.VISIBLE, false);
        d.focus(2);           // a view focusing the field in the same breath as it shows it
        d.dispatch(root);
        assertEquals(2, d.focused(), "not yet shown, so not taken away");
        path.set(PropKey.VISIBLE, null);
        d.dispatch(root);
        path.set(PropKey.VISIBLE, false);
        d.dispatch(root);
        assertEquals(3, d.focused(), "once seen shown, hiding it hands the keyboard on");
    }

    @Test
    void nothingToHandOnToLeavesTheKeyboardWithNothing() {
        Atchung bus = Atchung.create();
        InputDispatcher d = dispatcher(bus);
        d.setFocusable(1, false);
        d.setFocusable(3, false);
        d.focus(2);
        d.dispatch(root);
        path.set(PropKey.VISIBLE, false);
        d.dispatch(root);
        assertEquals(-1, d.focused());
    }

    /** FN-5: Ctrl+L sends a message whose hearer moves the keyboard; the keys after it wait for that. */
    @Test
    void keysAfterAChordThatHoldsTheRestReachWhereTheChordMovedTheKeyboard() {
        Atchung bus = Atchung.create();
        InputDispatcher d = dispatcher(bus);
        StringBuilder intoList = new StringBuilder();
        StringBuilder intoPath = new StringBuilder();
        d.onChar(3, intoList::appendCodePoint);
        d.onChar(2, intoPath::appendCodePoint);
        boolean[] heard = {false};
        // What Gui.shortcut with a sender registers: the send, then the hold. The message is heard after dispatch.
        d.registerShortcut(Shortcut.of(Key.L, Modifier.CONTROL), () -> {
            heard[0] = true;
            d.holdRest();
        });
        d.focus(3);

        publish(bus, new InputEvent.KeyPressed(Key.LEFT_CONTROL, 0));
        publish(bus, new InputEvent.KeyPressed(Key.L, 0));
        publish(bus, new InputEvent.KeyReleased(Key.L, 0));
        publish(bus, new InputEvent.KeyReleased(Key.LEFT_CONTROL, 0));
        for (char c : "C:/".toCharArray()) {
            publish(bus, new InputEvent.CharTyped(c, 0));
        }
        assertTrue(d.dispatch(root), "held input owes the next frame");
        assertTrue(heard[0]);
        assertEquals("", intoList.toString(), "nothing typed after the chord went where the keyboard was");

        d.focus(2);           // the view, hearing the message after dispatch, moves the keyboard
        publish(bus, new InputEvent.CharTyped('U', 0));
        d.dispatch(root);
        assertEquals("C:/U", intoPath.toString(), "held keys first, in order, then the new ones");
        assertEquals("", intoList.toString());
    }

    @Test
    void aChordAmongHeldInputHoldsWhatFollowsItAgain() {
        Atchung bus = Atchung.create();
        InputDispatcher d = dispatcher(bus);
        StringBuilder typed = new StringBuilder();
        d.onChar(3, typed::appendCodePoint);
        int[] chords = {0};
        d.registerShortcut(Shortcut.of(Key.F2), () -> {
            chords[0]++;
            d.holdRest();
        });
        d.focus(3);
        publish(bus, new InputEvent.KeyPressed(Key.F2, 0));
        publish(bus, new InputEvent.CharTyped('a', 0));
        publish(bus, new InputEvent.KeyPressed(Key.F2, 0));
        publish(bus, new InputEvent.CharTyped('b', 0));
        d.dispatch(root);
        assertEquals(1, chords[0]);
        assertEquals("", typed.toString());
        d.dispatch(root);
        assertEquals(2, chords[0]);
        assertEquals("a", typed.toString(), "one frame per chord, and order kept");
        d.dispatch(root);
        assertEquals("ab", typed.toString());
    }

    @Test
    void aChordThatHoldsNothingDelaysNothing() {
        Atchung bus = Atchung.create();
        InputDispatcher d = dispatcher(bus);
        StringBuilder typed = new StringBuilder();
        d.onChar(3, typed::appendCodePoint);
        d.registerShortcut(Shortcut.of(Key.F3), () -> { });   // a command run here, not a message: Tab's kind
        d.focus(3);
        publish(bus, new InputEvent.KeyPressed(Key.F3, 0));
        publish(bus, new InputEvent.CharTyped('a', 0));
        d.dispatch(root);
        assertEquals("a", typed.toString());
    }
}
