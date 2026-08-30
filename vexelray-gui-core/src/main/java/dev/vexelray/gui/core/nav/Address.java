package dev.vexelray.gui.core.nav;

/**
 * Where to navigate to: a window, by the name it is registered under ({@code GuiApp.window(key, ...)}), and a
 * landmark within it ({@code Gui.landmark(name, node)}). A value, and that is the point — a destination that is
 * a value can be put in a hyperlink, in a macro, in a test, or on the bus, and every one of those routes ends at
 * the same place.
 *
 * <p><b>Two parts, because a landmark alone is not a destination.</b> The same name can be a landmark in two
 * windows without either being wrong: a preferences window and an inspector both having {@code "theme.accent"}
 * is not a collision, it is two windows that both say something about the accent. Naming the window is what
 * makes the address total.
 *
 * <p>Written {@code window/landmark}, and a bare {@code landmark} means whichever window is asked — which is what
 * a link inside a document usually means and what {@code Gui.navigate(name)} passes.
 */
public record Address(String window, String landmark) {

    /** Separator between the window name and the landmark, in the text form. */
    public static final char SEPARATOR = '/';

    /** The window part of an address that does not name one: "whichever window this reaches". */
    public static final String ANY_WINDOW = "";

    public Address {
        if (window == null || landmark == null) {
            throw new IllegalArgumentException("address parts must not be null");
        }
        if (landmark.isBlank()) {
            throw new IllegalArgumentException("landmark must not be blank");
        }
        if (window.indexOf(SEPARATOR) >= 0 || landmark.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException("address parts must not contain '" + SEPARATOR + "': " + window
                    + SEPARATOR + landmark);
        }
    }

    /** A landmark in whichever window answers — the form a link within one window takes. */
    public static Address of(String landmark) {
        return new Address(ANY_WINDOW, landmark);
    }

    /** A landmark in the window registered as {@code window}. */
    public static Address of(String window, String landmark) {
        return new Address(window, landmark);
    }

    /** Parse {@code window/landmark}, or a bare {@code landmark} for {@link #ANY_WINDOW}. */
    public static Address parse(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("address must not be blank");
        }
        String t = s.trim();
        int slash = t.indexOf(SEPARATOR);
        return slash < 0 ? of(t) : new Address(t.substring(0, slash), t.substring(slash + 1));
    }

    /** Whether this address names a particular window, rather than leaving it to whoever receives it. */
    public boolean windowNamed() {
        return !window.isEmpty();
    }

    /** This address, bound to {@code key} if it named no window; unchanged if it did. */
    public Address inWindow(String key) {
        return windowNamed() ? this : new Address(key, landmark);
    }

    @Override
    public String toString() {
        return windowNamed() ? window + SEPARATOR + landmark : landmark;
    }
}
