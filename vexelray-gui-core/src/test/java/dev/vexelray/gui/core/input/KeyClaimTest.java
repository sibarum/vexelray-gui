package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.NodeKind;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claims: preemption declared in advance, in place of the {@code preventDefault} an asynchronous bus cannot
 * offer (see {@link ClaimScope}). The most specific applicable scope wins, and when one applies the key reaches
 * nothing else — including the framework's own defaults, which are themselves global claims.
 */
class KeyClaimTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.clicks", ClickEvent.class);
    private static final Topic<KeyRouted> ROUTES = Topic.of("test.keys", KeyRouted.class);

    /** A root with two focusable children, so Tab traversal has somewhere to go. */
    private static RetainedNode tree() {
        RetainedNode root = new RetainedNode(0, NodeKind.BOX);
        for (long id = 1; id <= 2; id++) {
            RetainedNode c = new RetainedNode(id, NodeKind.BOX);
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

    @Test
    void tabTraversesByDefaultBecauseTheFrameworkClaimsItGlobally() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode root = tree();
        List<FocusEvent> focus = new ArrayList<>();
        Topic<FocusEvent> ft = Topic.of("test.focus", FocusEvent.class);
        d.focusTopic(ft);
        bus.subscribe(ft, focus::add);
        d.setFocusable(1, true);
        d.setFocusable(2, true);

        press(bus, Key.TAB);
        d.dispatch(root);

        assertTrue(focus.stream().anyMatch(e -> e.nodeId() == 1 && e.gained()),
                "with nothing claiming Tab, the framework's global claim moves focus");
    }

    @Test
    void aFocusedClaimOutranksTheFrameworksGlobalOne() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode root = tree();
        d.setFocusable(1, true);
        d.setFocusable(2, true);
        d.focus(1);

        List<String> log = new ArrayList<>();
        d.claim(1, Shortcut.of(Key.TAB), ClaimScope.FOCUSED, () -> log.add("indent"));

        press(bus, Key.TAB);
        d.dispatch(root);

        assertEquals(List.of("indent"), log, "the focused node's claim takes Tab");
        assertEquals(1, d.focusedId(), "and focus did not move");
    }

    @Test
    void aClaimByAnUnfocusedNodeDoesNotApply() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode root = tree();
        d.setFocusable(1, true);
        d.setFocusable(2, true);
        d.focus(2);

        List<String> log = new ArrayList<>();
        d.claim(1, Shortcut.of(Key.TAB), ClaimScope.FOCUSED, () -> log.add("indent"));

        press(bus, Key.TAB);
        d.dispatch(root);

        assertEquals(List.of(), log, "node 1 does not hold focus, so its claim is inert");
    }

    @Test
    void aVisibleClaimAppliesWhileItsNodeIsInTheTree() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode root = tree();
        List<String> log = new ArrayList<>();
        d.claim(2, Shortcut.of(Key.ESCAPE), ClaimScope.VISIBLE, () -> log.add("close"));

        press(bus, Key.ESCAPE);
        d.dispatch(root);
        assertEquals(List.of("close"), log, "the claiming node is present, so the claim applies");

        root.children.removeIf(c -> c.id == 2);   // the dialog goes away
        press(bus, Key.ESCAPE);
        d.dispatch(root);
        assertEquals(List.of("close"), log, "once it leaves the tree the claim stops applying");
    }

    @Test
    void aFocusedClaimOutranksAGlobalShortcut() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode root = tree();
        d.setFocusable(1, true);
        d.focus(1);

        List<String> log = new ArrayList<>();
        d.registerShortcut(Shortcut.of(Key.S, Modifier.CONTROL), () -> log.add("app-save"));
        d.claim(1, Shortcut.of(Key.S, Modifier.CONTROL), ClaimScope.FOCUSED, () -> log.add("field-save"));

        press(bus, Key.LEFT_CONTROL);
        press(bus, Key.S);
        d.dispatch(root);

        assertEquals(List.of("field-save"), log, "the focused element claimed the chord, so the app never sees it");
    }

    @Test
    void everyKeyIsReportedIncludingClaimedOnes() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        d.keyRoutedTopic(ROUTES);
        RetainedNode root = tree();
        d.setFocusable(1, true);
        d.focus(1);
        d.claim(1, Shortcut.of(Key.TAB), ClaimScope.FOCUSED, () -> { });

        List<KeyRouted> seen = new ArrayList<>();
        bus.subscribe(ROUTES, seen::add);

        press(bus, Key.TAB);       // claimed
        press(bus, Key.A);         // unclaimed, delivered to the focused node (which has no handler)
        d.dispatch(root);

        assertEquals(2, seen.size(), "an observer sees both, whatever the routing did with them");
        assertTrue(seen.get(0).claimed(), "Tab was preempted by the claim");
        assertEquals(1, seen.get(0).focusedNodeId(), "and the report says who had focus");
        assertEquals(false, seen.get(1).claimed(), "A was not claimed");
    }
}
