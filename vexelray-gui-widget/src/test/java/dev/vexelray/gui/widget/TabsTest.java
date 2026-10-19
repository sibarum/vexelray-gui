package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.draw.Picture;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tabs, and the visibility primitive underneath them.
 *
 * <p>The property worth protecting is that a page is <b>hidden, not removed</b>. Both look the same on screen and
 * they are not the same: registrations are keyed by node id and released when a node leaves the tree, so a page
 * rebuilt by remove/insert comes back drawn but inert. Switching away and back has to return a working page, with
 * its content and caret intact, or the widget is a trap for anything interactive placed on it.
 */
class TabsTest {

    private static Node page(HeadlessGui h, String label) {
        return h.gui.text(label).width(Length.FILL).height(Length.FILL);
    }

    /** A ramp the test drives by hand. */
    private static final class Manual implements Ramp {
        java.util.function.DoubleConsumer progress;
        Runnable done;

        @Override
        public void run(java.util.function.DoubleConsumer progress, Runnable done) {
            this.progress = progress;
            this.done = done;
        }
    }

    /** The indicator as the bar's overlay has it, after the frames that apply the write. */
    private static Picture.Fill indicator(HeadlessGui h, Tabs tabs) {
        h.frame();
        Picture overlay = h.retained(tabs.bar()).overlay();
        return overlay == null || overlay.isEmpty() ? null : (Picture.Fill) overlay.marks().get(0);
    }

    /** Header {@code i}'s left edge and width across the bar, in the bar's own pixels. */
    private static float[] header(Tabs tabs, int i) {
        float barX = tabs.bar().layout().rect().x();
        var r = tabs.header(i).layout().rect();
        return new float[] {r.x() - barX, r.w()};
    }

    @Test
    void theIndicatorSitsUnderTheSelectedHeaderAndGlidesToTheNext() {
        try (HeadlessGui h = new HeadlessGui()) {
            Manual ramp = new Manual();
            Tabs tabs = new Tabs(h.gui).indicator(ramp);
            tabs.add("One", page(h, "one"));
            tabs.add("A longer second", page(h, "two"));
            tabs.add("Three", page(h, "three"));
            h.gui.root().children(tabs.node());
            h.frame();

            Picture.Fill rest = indicator(h, tabs);
            float[] first = header(tabs, 0);
            assertTrue(rest != null, "laid out, the indicator is drawn");
            assertEquals(first[0], rest.x(), 0.01, "under the first header");
            assertEquals(first[1], rest.w(), 0.01, "and as wide");
            assertEquals(tabs.bar().layout().rect().h(), rest.y() + rest.h(), 0.01, "along the bar's bottom");

            tabs.select(2);
            float[] third = header(tabs, 2);
            ramp.progress.accept(0d);
            assertEquals(first[0], indicator(h, tabs).x(), 0.01, "at the start it is where it was");

            ramp.progress.accept(0.5d);
            Picture.Fill mid = indicator(h, tabs);
            double leftDone = (mid.x() - first[0]) / (third[0] - first[0]);
            double rightDone = (mid.x() + mid.w() - first[0] - first[1]) / (third[0] + third[1] - first[0] - first[1]);
            assertTrue(leftDone > 0 && rightDone < 1, "half way, between the two: " + leftDone + ", " + rightDone);
            assertTrue(rightDone > leftDone, "moving right, the leading edge is ahead: the bar stretches");

            ramp.progress.accept(1d);
            ramp.done.run();
            Picture.Fill landed = indicator(h, tabs);
            assertEquals(third[0], landed.x(), 0.01, "and it lands under the third");
            assertEquals(third[1], landed.w(), 0.01);
        }
    }

    @Test
    void aSelectionMidGlideTurnsRoundFromWhereTheIndicatorIs() {
        try (HeadlessGui h = new HeadlessGui()) {
            Manual ramp = new Manual();
            Tabs tabs = new Tabs(h.gui).indicator(ramp);
            tabs.add("One", page(h, "one"));
            tabs.add("Two", page(h, "two"));
            tabs.add("Three", page(h, "three"));
            h.gui.root().children(tabs.node());
            h.frame();
            indicator(h, tabs);

            tabs.select(2);
            ramp.progress.accept(0.5d);
            java.util.function.DoubleConsumer staleProgress = ramp.progress;
            Runnable staleDone = ramp.done;
            double midway = indicator(h, tabs).x();

            tabs.select(0);   // the ramp now holds the new glide's callbacks
            ramp.progress.accept(0d);
            assertEquals(midway, indicator(h, tabs).x(), 0.01, "the way back starts where it had got to");

            // The superseded glide finishing late, as a clock would deliver it: neither moves the indicator.
            staleProgress.accept(0.9d);
            staleDone.run();
            assertEquals(midway, indicator(h, tabs).x(), 0.01);
        }
    }

    @Test
    void withNoIndicatorTheBarHasNoOverlay() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tabs tabs = new Tabs(h.gui);
            tabs.add("One", page(h, "one"));
            tabs.add("Two", page(h, "two"));
            h.gui.root().children(tabs.node());
            h.frame();
            tabs.select(1);
            assertEquals(null, indicator(h, tabs), "the default is the panel as it always was");
        }
    }

    /** Where {@code header} sits in {@code tabs}, or -1 — how a skin's argument is named in an assertion. */
    private static int at(Tabs tabs, Node header) {
        for (int i = 0; i < tabs.count(); i++) {
            if (tabs.header(i) == header) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void theFirstPageAddedIsSelected() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tabs tabs = new Tabs(h.gui);
            tabs.add("One", page(h, "one"));
            tabs.add("Two", page(h, "two"));
            h.gui.root().children(tabs.node());
            h.frame();

            assertEquals(0, tabs.selected(), "a freshly built panel is never blank");
            assertEquals(2, tabs.count());
        }
    }

    @Test
    void onlyTheSelectedPageIsLaidOut() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node one = page(h, "one");
            Node two = page(h, "two");
            Tabs tabs = new Tabs(h.gui);
            tabs.add("One", one);
            tabs.add("Two", two);
            h.gui.root().children(tabs.node());
            h.frame();

            assertTrue(one.layout().rect().h() > 0f, "the selected page occupies the content area");
            assertEquals(0f, two.layout().rect().h(), 0.01f, "the hidden one occupies nothing at all");

            tabs.select(1);
            h.frame();

            assertEquals(0f, one.layout().rect().h(), 0.01f, "and they swap");
            assertTrue(two.layout().rect().h() > 0f);
        }
    }

    /**
     * A frame can land anywhere in a selection, because the renderer drains concurrently with the thread selecting.
     * Shown before the outgoing page was floated, the incoming page split the content area with it at full opacity.
     * The transition below takes its frame from the middle of {@code select}, the worst place one can land.
     */
    @Test
    void aFrameMidSelectionNeverSplitsTheContentArea() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node one = page(h, "one");
            Node two = page(h, "two");
            float[] seen = new float[2];
            Tabs tabs = new Tabs(h.gui).transition((change, done) -> {
                h.frame();
                seen[0] = one.layout().rect().h();
                seen[1] = two.layout().rect().h();
            });
            tabs.add("One", one);
            tabs.add("Two", two);
            h.gui.root().children(tabs.node());
            h.frame();
            float full = one.layout().rect().h();
            assertTrue(full > 0f);

            tabs.select(1);
            assertEquals(full, seen[0], 0.01f, "mid-selection, the outgoing page still fills the area");
            assertEquals(0f, seen[1], 0.01f, "and the incoming one is not yet beside it");

            h.frame();
            assertEquals(full, one.layout().rect().h(), 0.01f, "then both fill it, the outgoing one floated");
            assertEquals(full, two.layout().rect().h(), 0.01f);
        }
    }

    /** The reason visibility exists rather than remove/insert: an interactive page has to survive a round trip. */
    @Test
    void aTextFieldOnAHiddenPageStillWorksWhenItComesBack() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField field = new TextField(h.gui, "");
            Tabs tabs = new Tabs(h.gui);
            tabs.add("Edit", field.node());
            tabs.add("Other", page(h, "other"));
            h.gui.root().children(tabs.node());
            h.frame();
            h.focus(field.node());
            h.type("ab");
            assertEquals("ab", field.text());

            tabs.select(1);
            h.frame();
            tabs.select(0);
            h.frame();

            h.focus(field.node());
            h.type("c");
            assertEquals("abc", field.text(),
                    "the field kept its content and its handlers across the round trip — hidden, not removed");
        }
    }

    /** A hidden page cannot be clicked, so nothing invisible is ever a pointer target. */
    @Test
    void aHiddenPageIsNotHitTestable() {
        try (HeadlessGui h = new HeadlessGui()) {
            boolean[] hit = {false, false};
            Node one = page(h, "one");
            Node two = page(h, "two");
            Tabs tabs = new Tabs(h.gui);
            tabs.add("One", one);
            tabs.add("Two", two);
            h.gui.onClick(one, () -> hit[0] = true);
            h.gui.onClick(two, () -> hit[1] = true);
            h.gui.root().children(tabs.node());
            h.frame();

            float y = one.layout().rect().y() + one.layout().rect().h() / 2f;
            h.click(50f, y);
            assertTrue(hit[0], "the shown page takes the click");
            assertFalse(hit[1], "the hidden one is not under the pointer, whatever its old rect said");
        }
    }

    @Test
    void clickingAHeaderSelectsItsPage() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tabs tabs = new Tabs(h.gui);
            tabs.add("One", page(h, "one"));
            tabs.add("Two", page(h, "two"));
            h.gui.root().children(tabs.node());
            h.frame();

            var r = tabs.header(1).layout().rect();
            h.click(r.x() + r.w() / 2f, r.y() + r.h() / 2f);
            h.frame();
            assertEquals(1, tabs.selected(), "the second header was clicked");
        }
    }

    /**
     * A skin is told the selection and the pointer state <b>together</b>, every time either moves — so it can be
     * a pure function of the two and never has to remember what it painted last.
     *
     * <p>The case that makes this worth having is the last assertion: selecting a tab the pointer is already on.
     * A bar whose hover shading and whose selection each wrote the background from their own handler paints the
     * not-hovered colour there and stays wrong until the pointer moves away.
     */
    @Test
    void aSkinIsToldTheSelectionAndThePointerStateTogether() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> painted = new ArrayList<>();
            Tabs[] panel = new Tabs[1];
            // A header carries no readable label -- text() sets one -- so it is named by its position, which is
            // also the only thing the panel itself knows a header by.
            Tabs tabs = new Tabs(h.gui).skin((header, selected, state) ->
                    painted.add(at(panel[0], header) + ":" + (selected ? "on" : "off") + ":" + state));
            panel[0] = tabs;
            tabs.add("One", page(h, "one"));
            tabs.add("Two", page(h, "two"));
            h.gui.root().children(tabs.node());
            h.frame();

            assertTrue(painted.contains("0:on:NORMAL"), "the first tab added is selected: " + painted);
            assertTrue(painted.contains("1:off:NORMAL"), "and the second is not: " + painted);

            var r = tabs.header(1).layout().rect();
            painted.clear();
            h.hover(r.x() + r.w() / 2f, r.y() + r.h() / 2f);
            assertEquals(List.of("1:off:HOVER"), painted, "the pointer arrived on an unselected tab");

            painted.clear();
            tabs.select(1);
            assertTrue(painted.contains("1:on:HOVER"),
                    "selected under the pointer, so still hovered: " + painted);
            assertTrue(painted.contains("0:off:NORMAL"), "and the one it left is not: " + painted);
        }
    }

    /** Arrow keys walk the bar, as FOCUSED claims on the header that holds focus. */
    @Test
    void arrowKeysMoveBetweenTabsWhileAHeaderIsFocused() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tabs tabs = new Tabs(h.gui);
            tabs.add("One", page(h, "one"));
            tabs.add("Two", page(h, "two"));
            tabs.add("Three", page(h, "three"));
            h.gui.root().children(tabs.node());
            h.frame();
            tabs.focus();

            h.tap(Key.RIGHT);
            assertEquals(1, tabs.selected());

            h.frame();
            tabs.focus();
            h.tap(Key.RIGHT);
            assertEquals(2, tabs.selected());
        }
    }

    /**
     * More headers than bar: walking Right past the edge keeps the selected header inside the bar, and focus goes
     * with it, so each press walks one further rather than answering from where the walk began.
     */
    @Test
    void walkingPastTheEdgeKeepsTheSelectedHeaderInView() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tabs tabs = new Tabs(h.gui);
            for (int i = 0; i < 12; i++) {
                tabs.add("Document " + i, page(h, "page " + i));
            }
            h.gui.root().children(h.gui.column().width(Length.rem(16f)).height(Length.rem(10f))
                    .children(tabs.node()));
            h.frame();
            tabs.focus();

            for (int i = 1; i < 12; i++) {
                h.tap(Key.RIGHT);
                h.frame();
                assertEquals(i, tabs.selected(), "one press, one tab along");
                var bar = tabs.node().layout().rect();
                var header = tabs.header(i).layout().rect();
                assertTrue(header.x() >= bar.x() - 0.5f && header.x() + header.w() <= bar.x() + bar.w() + 0.5f,
                        "tab " + i + " at " + header + " lies inside the bar " + bar);
            }
        }
    }

    /** Selection clamps, so an arrow at either end stays put rather than wrapping or throwing. */
    @Test
    void selectionClampsAtBothEnds() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tabs tabs = new Tabs(h.gui);
            tabs.add("One", page(h, "one"));
            tabs.add("Two", page(h, "two"));
            h.gui.root().children(tabs.node());
            h.frame();

            tabs.select(-5);
            assertEquals(0, tabs.selected());
            tabs.select(99);
            assertEquals(1, tabs.selected());
        }
    }

    @Test
    void selectionIsReported() {
        try (HeadlessGui h = new HeadlessGui()) {
            int[] seen = {-1};
            Tabs tabs = new Tabs(h.gui).onSelect(i -> seen[0] = i);
            tabs.add("One", page(h, "one"));
            tabs.add("Two", page(h, "two"));
            h.gui.root().children(tabs.node());
            h.frame();

            tabs.select(1);
            assertEquals(1, seen[0]);
        }
    }

    /**
     * Removal is reported while the panel is consistent: both of the bar's lists have already lost the tab, and
     * the selection has not moved yet. An owner told any later than that would be resolving the index against a
     * bar that had moved on; told any earlier, it would be shrinking its own list a moment before the bar shrank
     * its own. Neither is a state the two can be compared in, which is why this is a fixed point rather than a
     * detail — and why it is inline, not on the handler executor: a deferred notice is a desync with a timer.
     */
    @Test
    void removalIsReportedWhileTheTwoStructuresAgree() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> seen = new ArrayList<>();
            Tabs tabs = new Tabs(h.gui);
            tabs.onRemove(i -> seen.add(i + " of " + tabs.count() + " selected=" + tabs.selected()));
            tabs.add("One", page(h, "one"));
            tabs.add("Two", page(h, "two"));
            tabs.add("Three", page(h, "three"));
            h.gui.root().children(tabs.node());
            h.frame();
            tabs.select(2);

            tabs.remove(1);

            assertEquals(List.of("1 of 2 selected=-1"), seen, "the tab is gone from the bar, the selection pending");
            assertEquals(1, tabs.selected(), "which then moves to follow the tab that was showing");
        }
    }

    /** Out-of-range removals are no-ops, so an owner is not told about a tab that never left. */
    @Test
    void aRemovalThatDoesNothingIsNotReported() {
        try (HeadlessGui h = new HeadlessGui()) {
            int[] count = {0};
            Tabs tabs = new Tabs(h.gui).onRemove(i -> count[0]++);
            tabs.add("One", page(h, "one"));
            h.gui.root().children(tabs.node());
            h.frame();

            tabs.remove(-1);
            tabs.remove(7);
            assertEquals(0, count[0], "neither index named a tab");

            tabs.remove(0);
            assertEquals(1, count[0], "this one did");
        }
    }

    /**
     * The editor's rule — never be left with no document — said in the widget's terms: the handler adds a
     * replacement, and because the panel is unselected at that moment the new tab is selected by {@code add}
     * itself rather than left blank behind a bar with nothing lit.
     */
    @Test
    void theHandlerMayReplaceTheLastTabAsItGoes() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tabs tabs = new Tabs(h.gui);
            tabs.onRemove(i -> {
                if (tabs.count() == 0) {
                    tabs.add("Untitled", page(h, "empty"));
                }
            });
            tabs.add("One", page(h, "one"));
            h.gui.root().children(tabs.node());
            h.frame();

            tabs.remove(0);
            h.frame();

            assertEquals(1, tabs.count(), "closing the only tab left a fresh one in its place");
            assertEquals(0, tabs.selected(), "and it is the one showing");
        }
    }
}
