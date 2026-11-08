package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import sibarum.atchung.Sender;

import java.util.ArrayList;
import java.util.List;

/**
 * What a dialog says and what it offers: a title, a message, and any number of buttons with labels and actions.
 * A value, not a window and not a class to extend — which is the point. Asking the user something should cost
 * one expression at the place the question arises, not a type per question:
 *
 * {@snippet :
 * Modals.show(Modal.of("Unsaved changes", "Save before closing?")
 *         .defaultButton("Save", closing, Closing.SAVE)
 *         .button("Discard", closing, Closing.DISCARD)
 *         .cancelButton("Cancel", closing, Closing.STAY));
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
 * handed straight to {@link Modals}. A button's message is sent after the dialog has closed, so whoever hears it may
 * itself put up the next dialog without waiting for anything.
 *
 * <p><b>A button sends a message</b> ({@link #button(String, Sender, Object)} and its siblings), as a menu item does,
 * or only puts the dialog away ({@link #defaultButton(String)}, {@link #cancelButton(String)}). Nothing on a dialog runs
 * code of its caller's (vexelray-framework's {@code docs/threading.md}, T7.2 and T7.5): the host that wants code to
 * run on an answer hears its own message.
 */
public final class Modal {

    static {
        Hosting.install();
    }

    /**
     * One button: its label, whether Enter or Escape reaches it, and the message it sends when chosen, or none for a
     * button that only puts the dialog away. Data, so what a dialog answers can be read off it before it is shown.
     */
    public static final class Button {

        private final String label;
        private final Sender<?> to;
        private final Object message;
        /** The host's code to run instead of a message ({@code WidgetSeams}), or null. */
        private final Runnable action;
        private final boolean isDefault;
        private final boolean isCancel;

        private Button(String label, Sender<?> to, Object message, boolean isDefault, boolean isCancel) {
            this(label, to, message, null, isDefault, isCancel);
        }

        private Button(String label, Sender<?> to, Object message, Runnable action, boolean isDefault,
                       boolean isCancel) {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("button label must not be blank");
            }
            this.label = label;
            this.to = to;
            this.message = message;
            this.action = action;
            this.isDefault = isDefault;
            this.isCancel = isCancel;
        }

        public String label() {
            return label;
        }

        /** What choosing this sends, or null for a button that only dismisses. */
        public Object message() {
            return message;
        }

        public boolean isDefault() {
            return isDefault;
        }

        public boolean isCancel() {
            return isCancel;
        }

        /** Send the message, if there is one: the presenter's, after the dialog's close was asked for. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        void press() {
            if (action != null) {
                action.run();
            } else if (to != null) {
                ((Sender) to).send(message);
            }
        }
    }

    /**
     * What kind of thing a dialog is saying, shown as an icon beside the message in that kind's colour: the first
     * thing read, before a word of it. {@link #NONE} is a dialog with no icon, laid out as dialogs always were.
     */
    public enum Severity {
        /** No icon. */
        NONE(null, Role.INK),
        /** Something to know: an export finished, a file was found. */
        INFO(Icon.INFO, Role.ACCENT),
        /** A choice to make: save before closing? */
        QUESTION(Icon.QUESTION, Role.ACCENT),
        /** A choice with a cost: discard changes, overwrite a file. */
        CAUTION(Icon.CAUTION, Role.CAUTION),
        /** Something went wrong: a file could not be read or written. */
        ERROR(Icon.ERROR, Role.DANGER),
        /** Something went right that was in doubt. */
        SUCCESS(Icon.SUCCESS, Role.SUCCESS);

        private final Icon icon;
        private final Role tone;

        Severity(Icon icon, Role tone) {
            this.icon = icon;
            this.tone = tone;
        }

        /** The icon beside the message, or null for none. */
        public Icon icon() {
            return icon;
        }

        /** The icon's colour. */
        public Role tone() {
            return tone;
        }
    }

    private final String title;
    private final String message;
    private final List<Button> buttons = new ArrayList<>();
    private int width;
    private int height;
    private Severity severity = Severity.NONE;

    private Modal(String title, String message) {
        this.title = title == null ? "" : title;
        this.message = message == null ? "" : message;
    }

    /** A dialog titled {@code title}, saying {@code message}. Add buttons, then show it. */
    public static Modal of(String title, String message) {
        return new Modal(title, message);
    }

    /** Add an ordinary button that sends {@code message} through {@code to} when chosen. */
    public <T> Modal button(String label, Sender<T> to, T message) {
        buttons.add(new Button(label, sending(to), sent(message), false, false));
        return this;
    }

    /** Add the button Enter presses, sending {@code message} through {@code to}: drawn accented; the last one wins. */
    public <T> Modal defaultButton(String label, Sender<T> to, T message) {
        buttons.removeIf(Button::isDefault);
        buttons.add(new Button(label, sending(to), sent(message), true, false));
        return this;
    }

    /** Add the button Enter presses that only puts the dialog away: the "OK" of a dialog that asks nothing. */
    public Modal defaultButton(String label) {
        buttons.removeIf(Button::isDefault);
        buttons.add(new Button(label, null, null, true, false));
        return this;
    }

    /** Add the button Escape and the close box press, sending {@code message} through {@code to}; the last one wins. */
    public <T> Modal cancelButton(String label, Sender<T> to, T message) {
        buttons.removeIf(Button::isCancel);
        buttons.add(new Button(label, sending(to), sent(message), false, true));
        return this;
    }

    /** Add the button Escape and the close box press that only puts the dialog away. */
    public Modal cancelButton(String label) {
        buttons.removeIf(Button::isCancel);
        buttons.add(new Button(label, null, null, false, true));
        return this;
    }

    // The host's forms, reached through WidgetSeams: code run when the button is chosen.

    Modal button(String label, Runnable action) {
        buttons.add(new Button(label, null, null, action, false, false));
        return this;
    }

    Modal defaultButton(String label, Runnable action) {
        buttons.removeIf(Button::isDefault);
        buttons.add(new Button(label, null, null, action, true, false));
        return this;
    }

    Modal cancelButton(String label, Runnable action) {
        buttons.removeIf(Button::isCancel);
        buttons.add(new Button(label, null, null, action, false, true));
        return this;
    }

    private static Sender<?> sending(Sender<?> to) {
        return java.util.Objects.requireNonNull(to, "to");
    }

    private static Object sent(Object message) {
        return java.util.Objects.requireNonNull(message, "message");
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

    /** Say what kind of thing this dialog is: its icon, and that icon's colour. */
    public Modal severity(Severity severity) {
        this.severity = severity == null ? Severity.NONE : severity;
        return this;
    }

    /** What kind of thing this dialog is saying. */
    public Severity severity() {
        return severity;
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
