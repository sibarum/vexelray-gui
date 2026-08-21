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
        m.buttons().getFirst().action().run();   // must not throw
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
        go.action().run();
        assertEquals(1, runs.get());
    }
}
