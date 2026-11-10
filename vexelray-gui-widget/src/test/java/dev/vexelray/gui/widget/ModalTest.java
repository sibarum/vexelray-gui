package dev.vexelray.gui.widget;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dialog spec: buttons in the order given, at most one Enter and one Escape, and no null to trip over. */
class ModalTest {

    /**
     * A dialog button grows with its label. It was 32dp tall around a rem label, and at 200% the label's lower half
     * hung out of it; growing in step keeps the proportions it has at 100%, where the label fits.
     */
    @Test
    void aDialogButtonZoomsWithItsLabel() {
        try (HeadlessGui h = new HeadlessGui()) {
            Modal m = Modal.of("Title", "Message").defaultButton("Do it", () -> { });
            dev.vexelray.gui.core.Node b = Modals.buttonNode(h.gui, m.defaultButton());
            h.gui.root().children(h.gui.row().children(b));
            h.frame();
            dev.vexelray.gui.core.layout.Rect at100 = h.gui.layoutSnapshot().node(h.retained(b).id).rect();
            float text100 = h.retained(b).textSizePx;
            assertEquals(32f, at100.h(), 0.5f, "32 at 100%, as it was");

            for (float zoom : new float[] {1.5f, 2f, 3f}) {
                h.gui.zoom(zoom);
                h.frame();
                dev.vexelray.gui.core.layout.Rect r = h.gui.layoutSnapshot().node(h.retained(b).id).rect();
                assertEquals(text100 * zoom, h.retained(b).textSizePx, 0.05f, "the label zooms");
                assertEquals(at100.h() * zoom, r.h(), 0.5f, "and the button with it, at " + zoom);
                assertEquals(at100.w() * zoom, r.w(), 2f * zoom, "across too, at " + zoom);
            }
        }
    }

    @Test
    void buttonsKeepTheOrderTheyWereAddedIn() {
        Modal m = Modal.of("Title", "Message")
                .button("One", () -> { })
                .defaultButton("Two", () -> { })
                .cancelButton("Three", () -> { });

        assertEquals(List.of("One", "Two", "Three"), m.buttons().stream().map(Modal.Button::label).toList());
        assertEquals("Two", m.defaultButton().label());
        assertEquals("Three", m.cancelButton().label());
    }

    @Test
    void asecondDefaultOrCancelReplacesTheFirst() {
        Modal m = Modal.of("Title", "Message")
                .defaultButton("Save", () -> { })
                .cancelButton("Cancel", () -> { })
                .defaultButton("Save as", () -> { });

        assertEquals("Save as", m.defaultButton().label(), "the last declaration wins");
        assertEquals(2, m.buttons().size(), "and the superseded one is gone, not stacked");
        assertEquals(List.of("Cancel", "Save as"), m.buttons().stream().map(Modal.Button::label).toList());
    }

    @Test
    void aModalWithNoButtonsIsAMessageNotAnError() {
        Modal m = Modal.of("Done", "Export finished.");
        assertEquals(List.of(), m.buttons());
        assertNull(m.defaultButton());
        assertNull(m.cancelButton(), "Escape then dismisses it and runs nothing");
    }

    @Test
    void aButtonWithoutAnActionStillWorks() {
        Modal m = Modal.of("Title", "Message").button("OK", null);
        assertEquals(1, m.buttons().size());
        m.buttons().getFirst().press();   // must not throw
    }

    @Test
    void aBlankLabelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Modal.of("T", "M").button("  ", () -> { }));
        assertThrows(IllegalArgumentException.class, () -> Modal.of("T", "M").button(null, () -> { }));
    }

    @Test
    void nullTitleAndMessageAreEmptyNotNull() {
        Modal m = Modal.of(null, null);
        assertEquals("", m.title());
        assertEquals("", m.message());
    }

    @Test
    void anExplicitSizeIsKeptAndZeroMeansSizeFromTheMessage() {
        Modal sized = Modal.of("T", "M").size(300, 200);
        assertEquals(300, sized.width());
        assertEquals(200, sized.height());
        Modal unsized = Modal.of("T", "M");
        assertEquals(0, unsized.width());
        assertEquals(0, unsized.height());
    }

    @Test
    void theButtonsListIsACopyTheCallerCannotEdit() {
        Modal m = Modal.of("T", "M").button("One", () -> { });
        assertThrows(UnsupportedOperationException.class, () -> m.buttons().clear());
    }

    @Test
    void actionsAreTheOnesGivenAndAreNotRunUntilCalled() {
        AtomicInteger runs = new AtomicInteger();
        Modal m = Modal.of("T", "M").defaultButton("Go", runs::incrementAndGet);
        assertEquals(0, runs.get(), "building a dialog runs nothing");

        Modal.Button go = m.defaultButton();
        assertTrue(go.isDefault());
        assertFalse(go.isCancel());
        go.press();
        assertEquals(1, runs.get());
    }

    /**
     * Three long labels used to run off a dialog sized for its message: the last button, usually the cancel, fell off
     * the right edge. An unsized dialog is now at least as wide as its buttons need; one sized explicitly is not
     * second-guessed.
     */
    @Test
    void anUnsizedDialogIsWideEnoughForItsButtons() {
        Modal two = Modal.of("Title", "Message").defaultButton("OK", () -> { }).cancelButton("Cancel", () -> { });
        assertEquals(460, Modals.width(two), "short buttons leave the comfortable reading width alone");

        Modal three = Modal.of("Title", "Message")
                .defaultButton("Switch to simplified graphics", () -> { })
                .button("Don't ask again", () -> { })
                .cancelButton("Not now", () -> { });
        assertTrue(Modals.width(three) >= Modals.buttonsWidth(three));
        assertTrue(Modals.width(three) > 460, "and long ones widen it: " + Modals.width(three));

        assertEquals(300, Modals.width(three.size(300, 200)), "an explicit size is the caller's call");
    }

    /** What a dialog answers is data on its buttons: the message each sends, readable before it is shown. */
    @Test
    void aButtonDeclaresTheMessageItSends() {
        var inbox = sibarum.atchung.Atchung.create().tick(() -> { }).inbox("view");
        sibarum.atchung.Sender<String> answer = inbox.sender(sibarum.atchung.Topic.edge("test.answer", String.class));
        Modal m = Modal.of("T", "M").defaultButton("Save", answer, "save").cancelButton("Cancel", answer, "keep")
                .button("Later", answer, "later");
        assertEquals("save", m.defaultButton().message());
        assertEquals("keep", m.cancelButton().message());
        assertNull(Modal.of("T", "M").defaultButton("OK").defaultButton().message(), "an OK only puts it away");
    }
}
