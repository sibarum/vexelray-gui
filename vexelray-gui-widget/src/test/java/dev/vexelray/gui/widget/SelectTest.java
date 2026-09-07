package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drop-down, in the four claims that are not free: the popup is an overlay (it opens on a click, never on the
 * pointer, and takes nothing from the flow), the closed strip reserves its chevron so a longer value moves
 * nothing, the keyboard belongs to the popup while it is up and to the control while it is not, and multi-select
 * is the {@code SelectionModel} mode rather than a second widget — so an arrow key in a set-building popup moves
 * the cursor without choosing where it lands.
 */
class SelectTest {

    private static final List<String> SIZES = List.of("Small", "Medium", "Large", "Extra large");

    /** A page with the select at the top, laid out once. */
    private static Select<String> mount(HeadlessGui h, SelectionModel<String> model, List<String> options) {
        Select<String> select = new Select<>(h.gui, s -> s, model);
        select.options(options);
        h.gui.root().children(h.gui.column().width(Length.FILL).height(Length.FILL)
                .children(select.node()));
        h.frame();
        h.frame();   // the popup's anchor is read from the published layout, so give it one
        return select;
    }

    private static Select<String> single(HeadlessGui h) {
        return mount(h, SelectionModel.single(), SIZES);
    }

    /** Click the middle of the row showing {@code item}. Fails loudly if that row is not realized. */
    private static void clickRow(HeadlessGui h, Select<String> select, String item) {
        Node row = select.list().rowNode(item);
        assertNotNull(row, "the row for " + item + " has to exist before it can be clicked");
        Rect r = row.layout().visibleRect();
        h.click(r.centreX(), r.centreY());
    }

    private static void clickControl(HeadlessGui h, Select<String> select) {
        Rect r = select.node().layout().visibleRect();
        h.click(r.centreX(), r.centreY());
    }

    // ------------------------------------------------------------------ the overlay, and the no-hover rule

    @Test
    void aClickOpensThePopupAndThePointerArrivingDoesNot() {
        try (HeadlessGui h = new HeadlessGui()) {
            Select<String> select = single(h);

            Rect r = select.node().layout().visibleRect();
            h.hover(r.centreX(), r.centreY());
            assertFalse(select.shown(), "nothing appears on hover — that is the rule, not an omission");

            h.click(r.centreX(), r.centreY());
            h.frame();
            assertTrue(select.shown(), "a click is what opens it");
            assertTrue(select.popupNode().layout().rect().h() > 0f, "and the panel has a size once it is up");
            select.close();
        }
    }

    @Test
    void openingThePopupReflowsNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            Select<String> select = single(h);
            Rect before = select.node().layout().rect();

            clickControl(h, select);
            h.frame();

            assertEquals(before.y(), select.node().layout().rect().y(), 0.01f,
                    "the popup floats out of flow, so the control did not move");
            assertEquals(before.h(), select.node().layout().rect().h(), 0.01f, "nor did it change size");
            select.close();
        }
    }

    @Test
    void thePopupIsAnchoredUnderTheControlAndAsWideAsIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            Select<String> select = single(h);
            Rect control = select.node().layout().rect();

            clickControl(h, select);
            h.frame();
            Rect popup = select.popupNode().layout().rect();

            assertEquals(control.x(), popup.x(), 1f, "left edges line up");
            assertEquals(control.y() + control.h(), popup.y(), 1f, "and it hangs off the bottom");
            assertEquals(control.w(), popup.w(), 1f, "as wide as the thing it belongs to");
            select.close();
        }
    }

    /**
     * The reservation the no-hover rule asks for: the chevron is in the same place whatever the value says, so a
     * control that goes from "Small" to "Extra large" moves nothing beside it.
     */
    @Test
    void theChevronKeepsItsPlaceWhenTheValueChangesWidth() {
        try (HeadlessGui h = new HeadlessGui()) {
            Select<String> select = single(h);

            select.value("Small");
            h.frame();
            Rect narrow = chevron(h, select);

            select.value("Extra large");
            h.frame();
            Rect wide = chevron(h, select);

            assertEquals(narrow.x(), wide.x(), 0.01f, "the chevron did not slide when the value grew");
            assertEquals(narrow.w(), wide.w(), 0.01f, "and it is the same width either way");
            select.close();
        }
    }

    /** The control's second child, read through the published model rather than through an accessor for a test. */
    private static Rect chevron(HeadlessGui h, Select<String> select) {
        List<Long> kids = h.gui.semanticSnapshot().node(select.node().id()).children();
        assertEquals(2, kids.size(), "the closed strip is a value and a chevron, always both");
        NodeLayout l = h.gui.layoutSnapshot().node(kids.get(1));
        assertTrue(l.present(), "the chevron is laid out whether or not there is a value");
        return l.rect();
    }

    // ------------------------------------------------------------------ choosing, in one mode and in the other

    @Test
    void choosingInASingleSelectShutsThePopupAndCommitsOnce() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Set<String>> commits = new ArrayList<>();
            Select<String> select = single(h);
            select.onCommit(commits::add);

            clickControl(h, select);
            h.frame();
            clickRow(h, select, "Large");
            h.frame();

            assertFalse(select.shown(), "one choice was permitted, so making it is the end of the gesture");
            assertEquals("Large", select.value());
            assertEquals(List.of(Set.of("Large")), commits, "committed once, with what was chosen");
            select.close();
        }
    }

    @Test
    void choosingInAMultiSelectLeavesThePopupUpAndCommitsWhenItCloses() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Set<String>> commits = new ArrayList<>();
            Select<String> select = mount(h, SelectionModel.range(), SIZES);
            select.onCommit(commits::add);

            clickControl(h, select);
            h.frame();
            clickRow(h, select, "Small");
            h.frame();
            assertTrue(select.shown(), "a set is being built — shutting after the first tick would end it early");
            assertEquals(List.of(), commits, "and nothing has been chosen yet, only ticked");

            clickRow(h, select, "Large");
            h.frame();
            assertEquals(Set.of("Small", "Large"), select.selection().selection());

            h.tap(Key.ENTER);
            h.frame();
            assertFalse(select.shown());
            assertEquals(List.of(Set.of("Small", "Large")), commits, "one commit, when the popup closed");
            select.close();
        }
    }

    /** The strip is a read-out of the selection, whichever mode put it there. */
    @Test
    void theClosedStripSaysWhatIsChosen() {
        try (HeadlessGui h = new HeadlessGui()) {
            Select<String> select = mount(h, SelectionModel.range(), SIZES);
            select.placeholder("Choose…");
            h.frame();
            assertEquals("Choose…", stripText(h, select), "nothing chosen: the placeholder, not a blank");

            select.selection().set(List.of("Small", "Large"));
            h.frame();
            assertEquals("Small, Large", stripText(h, select),
                    "several chosen: the labels joined — a framework does not get to invent an English summary");

            select.summary(chosen -> chosen.size() + " of " + SIZES.size());
            h.frame();
            assertEquals("2 of 4", stripText(h, select), "and an application that wants one says so");
            select.close();
        }
    }

    private static String stripText(HeadlessGui h, Select<String> select) {
        List<Long> kids = h.gui.semanticSnapshot().node(select.node().id()).children();
        return h.gui.semanticSnapshot().node(kids.get(0)).text();
    }

    // ------------------------------------------------------------------ the keyboard

    /**
     * The whole reason {@code SelectionModel.lead} had to exist. In a set-building popup an arrow key has to be
     * able to reach a row without choosing it, or Space has nothing to flip and the only way to the fourth option
     * is to select it — which is exactly what must not happen on the way past.
     */
    @Test
    void anArrowMovesTheCursorWithoutChoosingWhenSeveralMayBeChosen() {
        try (HeadlessGui h = new HeadlessGui()) {
            Select<String> select = mount(h, SelectionModel.range(), SIZES);
            h.focus(select.node());

            clickControl(h, select);
            h.frame();
            h.tap(Key.DOWN);
            h.frame();
            h.tap(Key.DOWN);
            h.frame();

            assertEquals("Medium", select.selection().lead(), "the cursor walked two rows down");
            assertEquals(Set.of(), select.selection().selection(), "and chose nothing on the way");

            h.tap(Key.SPACE);
            h.frame();
            assertEquals(Set.of("Medium"), select.selection().selection(), "Space is what chooses");
            select.close();
        }
    }

    /**
     * The same call in a single-select model: {@code lead} degrades to {@code at}, so the arrow selects where it
     * lands. One call, two behaviours, and no branch on the mode anywhere in the widget.
     */
    @Test
    void anArrowSelectsWhereItLandsWhenOnlyOneMayBeChosen() {
        try (HeadlessGui h = new HeadlessGui()) {
            Select<String> select = single(h);
            h.focus(select.node());

            clickControl(h, select);
            h.frame();
            h.tap(Key.DOWN);
            h.frame();

            assertEquals(Set.of("Small"), select.selection().selection(),
                    "a single-select list has nowhere to put a cursor that is not the selection");
            select.close();
        }
    }

    /**
     * FOCUSED outranks VISIBLE, so a control that kept its own open-claim while its popup was up would reopen
     * instead of letting the list move — a bug that looks like an arrow key doing nothing.
     */
    @Test
    void theControlHandsDownBackToThePopupWhileItIsUp() {
        try (HeadlessGui h = new HeadlessGui()) {
            Select<String> select = single(h);
            h.focus(select.node());

            h.tap(Key.DOWN);          // the control is focused and shut: this opens it
            h.frame();
            assertTrue(select.shown(), "Down on the closed control opens it");

            h.tap(Key.DOWN);          // now the popup owns Down
            h.frame();
            assertEquals("Small", select.selection().lead(), "the second Down moved the list, not the popup state");
            assertTrue(select.shown(), "and it certainly did not reopen a popup that was already up");
            select.close();
        }
    }

    @Test
    void escapeRestoresTheSelectionThePopupOpenedWith() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Set<String>> commits = new ArrayList<>();
            Select<String> select = single(h);
            select.onCommit(commits::add);
            select.value("Medium");
            h.frame();
            h.focus(select.node());

            clickControl(h, select);
            h.frame();
            h.tap(Key.DOWN);
            h.frame();
            assertEquals("Large", select.value(), "the preview moved as the cursor did");

            h.tap(Key.ESCAPE);
            h.frame();

            assertFalse(select.shown());
            assertEquals("Medium", select.value(), "Escape put back what the popup opened with");
            assertEquals(List.of(), commits, "taking a choice back is not making one");
            select.close();
        }
    }

    @Test
    void clickingAwayShutsThePopupAndKeepsTheChoice() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Set<String>> commits = new ArrayList<>();
            Select<String> select = mount(h, SelectionModel.range(), SIZES);
            select.onCommit(commits::add);

            clickControl(h, select);
            h.frame();
            clickRow(h, select, "Small");
            h.frame();

            h.click(700f, 550f);   // the far corner of the page, well clear of both the strip and the panel
            h.frame();

            assertFalse(select.shown(), "a click somewhere else shuts it");
            assertEquals(List.of(Set.of("Small")), commits, "and keeps what was ticked");
            select.close();
        }
    }

    // ------------------------------------------------------------------ where the popup goes, and when it leaves

    /** With no room below and room above, the panel opens upwards rather than sliding to fit or cropping. */
    @Test
    void aSelectAtTheBottomOfThePageOpensUpwards() {
        try (HeadlessGui h = new HeadlessGui()) {
            Select<String> select = new Select<>(h.gui, s -> s, SelectionModel.single());
            select.options(SIZES);
            h.gui.root().children(h.gui.column().width(Length.FILL).height(Length.FILL)
                    .children(h.gui.box().width(Length.FILL).height(Length.grow(1)), select.node()));
            h.frame();
            h.frame();

            Rect control = select.node().layout().rect();
            clickControl(h, select);
            h.frame();
            Rect popup = select.popupNode().layout().rect();

            assertTrue(popup.y() + popup.h() <= control.y() + 1f,
                    "the panel is above the control, not hanging off the bottom of the viewport: "
                            + popup + " vs " + control);
            select.close();
        }
    }

    /**
     * An overlay is floated on the root, so nothing clips it when the control it belongs to scrolls away — it
     * would hang there pointing at nothing. The read-model answers that question directly, so the popup shuts.
     */
    @Test
    void aControlScrolledOutOfSightTakesItsPopupWithIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            Select<String> select = new Select<>(h.gui, s -> s, SelectionModel.single());
            select.options(SIZES);
            Node viewport = h.gui.column().width(Length.FILL).height(Length.dp(300)).scroll(false, true)
                    .children(select.node(), h.gui.box().width(Length.FILL).height(Length.dp(2000)));
            h.gui.root().children(viewport);
            h.frame();
            h.frame();

            clickControl(h, select);
            h.frame();
            assertTrue(select.shown(), "up, and anchored inside the scroller");

            // Below the panel, which is floated over the top of the scroller and would otherwise take the wheel
            // itself — the popup is hit before the page, which is the whole point of it being a floating last child.
            h.wheel(0, -10, 400f, 250f);   // negative dy is downwards
            h.frame();
            h.frame();

            assertTrue(select.node().layout().clippedAway(), "the control really did scroll out of its viewport");
            assertFalse(select.shown(), "the control is nowhere on screen, so its popup is not either");
            select.close();
        }
    }

    /**
     * The other way a control leaves the screen: the page it is on is put away. A gallery, a tab strip and a
     * collapsing panel all hide by {@code visible(false)} rather than by removing, and an overlay floated on the
     * root does not go with them unless it is told to.
     */
    @Test
    void hidingThePageTakesThePopupWithIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            Select<String> select = new Select<>(h.gui, s -> s, SelectionModel.single());
            select.options(SIZES);
            Node page = h.gui.column().width(Length.FILL).height(Length.FILL).children(select.node());
            h.gui.root().children(page);
            h.frame();
            h.frame();

            clickControl(h, select);
            h.frame();
            assertTrue(select.shown());

            page.visible(false);
            h.frame();
            h.frame();

            assertFalse(select.shown(), "the page went away, so the panel floating over it did too");
            select.close();
        }
    }

    // ------------------------------------------------------------------ what composing the list bought

    @Test
    void tenThousandOptionsCostAPopupfulOfRows() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> many = new ArrayList<>();
            for (int i = 0; i < 10_000; i++) {
                many.add(String.format("option-%05d", i));
            }
            Select<String> select = mount(h, SelectionModel.single(), many);

            clickControl(h, select);
            h.frame();
            h.frame();

            assertTrue(select.list().realizedRows() < 30,
                    "a popup's worth of rows, not ten thousand: " + select.list().realizedRows());
            select.close();
        }
    }

    /** Opening goes to the current value rather than to the top — the reason {@code reveal} is worth composing. */
    @Test
    void openingScrollsToWhatIsAlreadyChosen() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> many = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                many.add("option-" + i);
            }
            Select<String> select = mount(h, SelectionModel.single(), many);
            select.value("option-400");
            h.frame();

            assertNull(select.list().rowNode("option-400"), "nowhere near the window while the popup is shut");

            clickControl(h, select);
            h.frame();
            h.frame();

            assertNotNull(select.list().rowNode("option-400"),
                    "opening realized the row for the current value instead of showing the top of the list");
            select.close();
        }
    }
}
