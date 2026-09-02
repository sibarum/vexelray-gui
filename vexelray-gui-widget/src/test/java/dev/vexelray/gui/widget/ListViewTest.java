package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The list's one claim, in the two halves it comes in: the tree holds viewport-many rows however long the list is,
 * <em>and</em> everything that addresses a row still works for the rows that therefore do not exist.
 */
class ListViewTest {

    /** Two rem a row, at the harness's 16px root em and 1:1 scale. */
    private static final float ROW_REM = 2f;
    private static final float ROW_PX = 32f;

    /** A box four rows tall, so the window is small and the arithmetic is one line of head maths. */
    private static final Length BOX_H = Length.rem(8);

    /**
     * A box twelve rows tall, for the tests that click rows. A row below the fold is clipped, so a click at its
     * coordinates lands outside the list and means nothing — which is correct, and would otherwise be tested
     * accidentally instead of the gesture the test is about.
     */
    private static final Length TALL_H = Length.rem(24);

    // ------------------------------------------------------------------ the window

    @Test
    void aHundredThousandItemsCostAScreenfulOfRows() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mount(h, 100_000);

            assertEquals(100_000, list.count());
            assertTrue(list.realizedRows() < 20,
                    "a screenful and a little overscan, not a hundred thousand: " + list.realizedRows());
            assertNotNull(list.rowNode("item00000"), "the top of the list is what exists");
            assertNull(list.rowNode("item99999"), "and the bottom of it emphatically does not");
        }
    }

    /**
     * The spacers are what make the scrollbar honest before anything is built: the scroller's content is the whole
     * list's height from the first frame, so the thumb is the right size and dragging it to the end does not walk
     * the list into existence one screen at a time.
     */
    @Test
    void theScrollerIsAsTallAsTheWholeListFromTheFirstFrame() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mount(h, 1_000);
            assertEquals(1_000 * ROW_PX, list.node().layout().contentH(), 1f,
                    "the content is every row's worth of height, realized or not");
            assertTrue(list.node().layout().overflowY());
        }
    }

    /**
     * The reason rows are kept rather than rebuilt. A scroll of one row leaves every other row in the window
     * exactly as it was — the same node, with its registrations and its state — and builds one.
     */
    @Test
    void scrollingByOneRowKeepsTheRowsThatStayed() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mount(h, 500);
            Node stayed = list.rowNode("item00005");
            assertNotNull(stayed);
            int before = list.realizedRows();

            scrollBy(h, list, ROW_PX);

            assertSame(stayed, list.rowNode("item00005"), "a row still in the window is the same node");
            assertEquals(before, list.realizedRows(), "and the window is still a window");
        }
    }

    /** Scrolled far enough and the old rows are gone — the window is a window, not a high-water mark. */
    @Test
    void scrollingAwayReleasesTheRowsLeftBehind() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mount(h, 500);
            assertNotNull(list.rowNode("item00000"));

            scrollBy(h, list, 100 * ROW_PX);

            assertNull(list.rowNode("item00000"), "the first row was released, not merely hidden");
            assertTrue(list.realizedRows() < 20, "and the window did not grow: " + list.realizedRows());
            assertNotNull(list.rowNode("item00100"), "what is on screen is what exists");
        }
    }

    // ------------------------------------------------------------------ addressing a row that has no node

    /**
     * The half an application gets wrong. An item outside the window has no node, so there is nothing to scroll
     * to — the list has to build the row first, and it can, because the spacers put a row exactly where its index
     * says whatever the scroller is currently doing.
     */
    @Test
    void revealingAnItemThatHasNoRowBuildsOneAndScrollsToIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mount(h, 1_000);
            assertNull(list.rowNode("item00400"), "nothing to scroll to yet");

            list.reveal("item00400");
            h.frame().frame();

            Node row = list.rowNode("item00400");
            assertNotNull(row, "the row exists now");
            assertInView(list, row);
            assertTrue(list.realizedRows() < 20, "and it did not build the four hundred in between");
        }
    }

    @Test
    void revealingAnItemTheListDoesNotHoldDoesNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mount(h, 100);
            int before = list.realizedRows();
            list.reveal("nothing like it");
            h.frame();
            assertEquals(before, list.realizedRows());
        }
    }

    // ------------------------------------------------------------------ selection, as gestures

    @Test
    void aClickSelectsTheRowUnderIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mount(h, 100);
            clickRow(h, list, "item00002");
            assertEquals(Set.of("item00002"), list.selection().selection());
        }
    }

    /**
     * The whole reason a left click carries its modifiers: these three gestures are three different commands, and
     * the handler that decides which one it was has to be holding what decided it.
     */
    @Test
    void ctrlAndShiftClicksAreTheOperationsTheyLookLike() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mountTall(h, 100);

            clickRow(h, list, "item00001");
            clickRow(h, list, "item00003", Key.LEFT_CONTROL);
            assertEquals(Set.of("item00001", "item00003"), list.selection().selection(), "ctrl-click adds");

            clickRow(h, list, "item00005", Key.LEFT_SHIFT);
            assertEquals(Set.of("item00001", "item00003", "item00004", "item00005"), list.selection().selection(),
                    "and shift-click ranges from the anchor the ctrl-click left");
        }
    }

    @Test
    void theArrowKeysMoveTheSelectionAndShiftExtendsIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mount(h, 100);
            h.focus(list.node());

            h.tap(Key.DOWN).frame();
            assertEquals(Set.of("item00000"), list.selection().selection(), "the first arrow lands on the first row");
            h.tap(Key.DOWN).frame();
            assertEquals(Set.of("item00001"), list.selection().selection());

            h.chord(Key.DOWN, Key.LEFT_SHIFT).frame();
            h.chord(Key.DOWN, Key.LEFT_SHIFT).frame();
            assertEquals(Set.of("item00001", "item00002", "item00003"), list.selection().selection(),
                    "shift+arrow grows the same extent shift-click would");
        }
    }

    /** Walking off the bottom of the window brings the selection with it — a keyboard never selects out of sight. */
    @Test
    void walkingPastTheFoldScrollsToKeepUp() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mount(h, 100);
            h.focus(list.node());
            for (int i = 0; i < 12; i++) {
                h.tap(Key.DOWN).frame();
            }
            assertEquals(Set.of("item00011"), list.selection().selection());
            assertTrue(list.node().layout().scrollY() > 0f, "the list scrolled rather than selecting off-screen");
            assertInView(list, list.rowNode("item00011"));
        }
    }

    @Test
    void endSelectsTheLastItemEvenThoughItHasNoRowYet() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mount(h, 1_000);
            h.focus(list.node());
            h.tap(Key.END).frame().frame();

            assertEquals(Set.of("item00999"), list.selection().selection());
            assertNotNull(list.rowNode("item00999"), "and the row it needed was built to receive it");
            assertInView(list, list.rowNode("item00999"));
        }
    }

    @Test
    void ctrlASelectsEveryItemAndNotEveryRow() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mount(h, 1_000);
            h.focus(list.node());
            h.chord(Key.A, Key.LEFT_CONTROL).frame();

            assertEquals(1_000, list.selection().size(), "every item is selected");
            assertTrue(list.realizedRows() < 20, "and still only a screenful of rows exists");
        }
    }

    /** Replacing the contents keeps what survives and drops what does not — the selection is over items. */
    @Test
    void replacingTheItemsKeepsTheSelectionThatSurvives() {
        try (HeadlessGui h = new HeadlessGui()) {
            ListView<String> list = mountTall(h, 10);
            clickRow(h, list, "item00002");
            clickRow(h, list, "item00004", Key.LEFT_CONTROL);

            list.items(List.of("item00004", "fresh"));
            h.frame();

            assertEquals(Set.of("item00004"), list.selection().selection(),
                    "the one that is still there stayed; the one that went, went");
        }
    }

    // ------------------------------------------------------------------ harness

    private static ListView<String> mount(HeadlessGui h, int count) {
        return mount(h, count, BOX_H);
    }

    /** A list tall enough that the rows a gesture test means to click are on screen to be clicked. */
    private static ListView<String> mountTall(HeadlessGui h, int count) {
        return mount(h, count, TALL_H);
    }

    private static ListView<String> mount(HeadlessGui h, int count, Length height) {
        ListView<String> list = new ListView<>(h.gui, ROW_REM, (g, item) -> g.text(item));
        list.node().height(height);
        h.gui.root().children(list.node());
        list.items(names(count));
        h.frame().frame();
        return list;
    }

    private static List<String> names(int count) {
        List<String> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(String.format("item%05d", i));
        }
        return items;
    }

    /** Scroll the list by {@code px} with the wheel, in whole rows — the harness's notch is not our business. */
    private static void scrollBy(HeadlessGui h, ListView<String> list, float px) {
        Rect box = list.node().layout().rect();
        float x = box.x() + box.w() / 2f;
        float y = box.y() + box.h() / 2f;
        float target = list.node().layout().scrollY() + px;
        for (int i = 0; i < 400 && list.node().layout().scrollY() < target - 0.5f; i++) {
            h.wheel(0, -1, x, y);
        }
        h.frame();
    }

    private static void clickRow(HeadlessGui h, ListView<String> list, String item, Key... held) {
        Node row = list.rowNode(item);
        assertNotNull(row, "the test means to click a row that exists: " + item);
        Rect r = row.layout().rect();
        for (Key k : held) {
            h.down(k);
        }
        h.frame();
        h.click(r.x() + r.w() / 2f, r.y() + r.h() / 2f);
        for (Key k : held) {
            h.up(k);
        }
        h.frame();
    }

    private static void assertInView(ListView<String> list, Node row) {
        assertNotNull(row);
        Rect box = list.node().layout().rect();
        Rect r = row.layout().rect();
        assertTrue(r.y() >= box.y() - 0.5f && r.y() + r.h() <= box.y() + box.h() + 0.5f,
                "the row sits inside the list's box: row " + r + " in " + box);
    }
}
