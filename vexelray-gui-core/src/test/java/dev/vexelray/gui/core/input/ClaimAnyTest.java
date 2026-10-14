package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A claim on every chord — what a key-binding recorder holds while it is armed. It is a claim like any other, so
 * it outranks the framework's defaults (Tab) and the application's global chords while it applies, and it is
 * outranked by a claim that named its chord at the same scope.
 */
class ClaimAnyTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.any.clicks", ClickEvent.class);

    private static RetainedNode tree() {
        RetainedNode root = new RetainedNode(0);
        for (long id = 1; id <= 2; id++) {
            RetainedNode c = new RetainedNode(id);
            c.w = 10;
            c.h = 10;
            c.parent = root;
            root.children.add(c);
        }
        root.w = 100;
        root.h = 100;
        return root;
    }

    private static void press(Atchung bus, Key k) {
        bus.publish(InputTopics.INPUT, new InputEvent.KeyPressed(k, 0));
    }

    private static void release(Atchung bus, Key k) {
        bus.publish(InputTopics.INPUT, new InputEvent.KeyReleased(k, 0));
    }

    @Test
    void aFocusedWildcardHearsTheChordAndPreemptsGlobalOnes() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode root = tree();
        d.setFocusable(1, true);
        d.setFocusable(2, true);
        d.focus(1);
        List<String> log = new ArrayList<>();
        d.registerShortcut(Shortcut.of(Key.S, Modifier.CONTROL), () -> log.add("save"));
        d.claimAny(1, ClaimScope.FOCUSED, chord -> log.add("recorded " + chord.text()));

        press(bus, Key.LEFT_CONTROL);
        press(bus, Key.S);
        d.dispatch(root);
        release(bus, Key.S);
        release(bus, Key.LEFT_CONTROL);
        press(bus, Key.TAB);
        d.dispatch(root);

        assertEquals(List.of("recorded Ctrl+S", "recorded Tab"), log,
                "while armed, Ctrl+S is recorded rather than saving and Tab rather than moving focus");
        assertEquals(1, d.focusedId(), "Tab was recorded, so focus stayed");
    }

    @Test
    void aNamedClaimAtTheSameScopeOutranksTheWildcard() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode root = tree();
        d.setFocusable(1, true);
        d.focus(1);
        List<String> log = new ArrayList<>();
        // Registered in the order that would win if precedence were registration: wildcard first.
        d.claimAny(1, ClaimScope.FOCUSED, chord -> log.add("any " + chord.text()));
        d.claim(1, Shortcut.of(Key.ENTER), ClaimScope.FOCUSED, () -> log.add("enter"));

        press(bus, Key.ENTER);
        d.dispatch(root);
        release(bus, Key.ENTER);
        press(bus, Key.F2);
        d.dispatch(root);

        assertEquals(List.of("enter", "any F2"), log);
    }

    @Test
    void releasingTheWildcardRestoresTheDefaultsAndKeepsNamedClaims() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode root = tree();
        d.setFocusable(1, true);
        d.setFocusable(2, true);
        d.focus(1);
        List<String> log = new ArrayList<>();
        d.claim(1, Shortcut.of(Key.ENTER), ClaimScope.FOCUSED, () -> log.add("enter"));
        d.claimAny(1, ClaimScope.FOCUSED, chord -> log.add("any"));

        d.releaseClaimAny(1);
        press(bus, Key.TAB);
        d.dispatch(root);
        release(bus, Key.TAB);
        d.focus(1);
        press(bus, Key.ENTER);
        d.dispatch(root);

        assertEquals(List.of("enter"), log, "the wildcard is gone; the node's named claim is not");
    }

    @Test
    void aWildcardOnAnUnfocusedNodeHearsNothing() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode root = tree();
        d.setFocusable(1, true);
        d.setFocusable(2, true);
        d.focus(2);
        List<String> log = new ArrayList<>();
        d.claimAny(1, ClaimScope.FOCUSED, chord -> log.add("any"));

        press(bus, Key.A);
        d.dispatch(root);

        assertEquals(List.of(), log);
    }
}
