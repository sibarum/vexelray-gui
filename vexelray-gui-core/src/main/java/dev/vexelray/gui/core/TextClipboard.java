package dev.vexelray.gui.core;

/**
 * The framework's clipboard seam for text widgets — a minimal get/set over the system clipboard, kept out of
 * the input subsystem (the clipboard is a request/response service, not a device-event stream). The core ships
 * an in-memory default so widgets and tests work headless; an application installs an OS-backed implementation
 * (e.g. over {@code tactroller-clipboard}) via {@link Gui#clipboard(TextClipboard)}.
 */
public interface TextClipboard {

    /** The current clipboard text, or {@code null}/empty if there is none. */
    String get();

    /** Replace the clipboard text. */
    void set(String text);

    /** A process-local, in-memory clipboard — the default when no OS clipboard is installed. */
    final class InMemory implements TextClipboard {
        private volatile String text = "";

        @Override
        public String get() {
            return text;
        }

        @Override
        public void set(String text) {
            this.text = text == null ? "" : text;
        }
    }
}
