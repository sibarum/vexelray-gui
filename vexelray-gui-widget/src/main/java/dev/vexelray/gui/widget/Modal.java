package dev.vexelray.gui.widget;

import java.util.ArrayList;
import java.util.List;

/**
 * What a dialog says and what it offers: a title, a message, and any number of buttons with labels and actions.
 * A value, not a window and not a class to extend — which is the point. Asking the user something should cost
 * one expression at the place the question arises, not a type per question:
 *
 * {@snippet :
 * Modals.show(Modal.of("Unsaved changes", "Save before closing?")
 *         .defaultButton("Save", this::save)
 *         .button("Discard", this::discard)
 *         .cancelButton("Cancel", request::cancel));
 * }
 *
 * <p>Two buttons are special, and only by what dismisses them: the <b>default</b> button is the one Enter
 * presses, and the <b>cancel</b> button is the one Escape and the dialog's own close button press. A dialog with
 * no cancel button can still be closed — Escape then simply dismisses it, running nothing.
 *
 * <p>A modal with no buttons at all is a message: the user closes it and nothing happens. That is a legitimate
 * dialog, not a misconfiguration.
 *
 * <p>Immutable-ish by convention: each builder call returns {@code this}, and a spec is meant to be built and
 * handed straight to {@link Modals}. Actions run on the dialog's handler executor, off the GUI thread, after the
 * dialog has closed — so an action may itself put up the next dialog without waiting for anything.
 */
public final class Modal {

    /** One button: its label, what it does, and whether Enter or Escape reaches it. */
    public record Button(String label, Runnable action, boolean isDefault, boolean isCancel) {

        public Button {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("button label must not be blank");
            }
            if (action == null) {
                action = () -> { };
            }
        }
    }

    private final String title;
    private final String message;
    private final List<Button> buttons = new ArrayList<>();
    private int width;
    private int height;

    private Modal(String title, String message) {
        this.title = title == null ? "" : title;
        this.message = message == null ? "" : message;
    }

    /** A dialog titled {@code title}, saying {@code message}. Add buttons, then show it. */
    public static Modal of(String title, String message) {
        return new Modal(title, message);
    }

    /** Add an ordinary button. */
    public Modal button(String label, Runnable action) {
        buttons.add(new Button(label, action, false, false));
        return this;
    }

    /** Add the button Enter presses — the affirmative one, drawn accented. At most one; the last one wins. */
    public Modal defaultButton(String label, Runnable action) {
        buttons.removeIf(Button::isDefault);
        buttons.add(new Button(label, action, true, false));
        return this;
    }

    /** Add the button Escape (and the dialog's close button) presses. At most one; the last one wins. */
    public Modal cancelButton(String label, Runnable action) {
        buttons.removeIf(Button::isCancel);
        buttons.add(new Button(label, action, false, true));
        return this;
    }

    /**
     * Size the dialog's window explicitly, in the same logical pixels {@code WindowConfig} takes. Left unset,
     * {@link Modals} sizes it from the message — enough for the text it was given, which is right often enough
     * that saying so is the exception.
     */
    public Modal size(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        return this;
    }

    public String title() {
        return title;
    }

    public String message() {
        return message;
    }

    /** The buttons, in the order they were added — which is the order they are drawn, left to right. */
    public List<Button> buttons() {
        return List.copyOf(buttons);
    }

    /** The requested width, or 0 for "size me from the message". */
    public int width() {
        return width;
    }

    /** The requested height, or 0 for "size me from the message". */
    public int height() {
        return height;
    }

    /** The button Enter presses, or null. */
    public Button defaultButton() {
        return buttons.stream().filter(Button::isDefault).findFirst().orElse(null);
    }

    /** The button Escape presses, or null. */
    public Button cancelButton() {
        return buttons.stream().filter(Button::isCancel).findFirst().orElse(null);
    }
}
