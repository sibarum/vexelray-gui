package dev.vexelray.gui.automation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a socket offers, of the two things it can (docs/reference/automation.md §5, <i>Two capabilities</i>).
 *
 * <p><b>Independent, and split where the verbs already split.</b> Remote control is the hand and what aiming it
 * needs: the driving verbs, and the view reads ({@code tree}, {@code find}, {@code shot}, ...) a click is aimed
 * through. Diagnostics is what the process recorded about itself: the flight recorder, the log, the domain verbs,
 * and the Inspector's link. Either may be on without the other, and when both are on they share a port or each
 * has its own — a statistics reader that cannot drive input is the point of having two.
 *
 * <p>Each verb surface answers which of these a line needs ({@link Commands#needs}), so the server holds no list;
 * {@code hello} and {@code help} are answered on every socket, because they are how a client learns what this one is.
 */
public enum Capability {

    /** The hand, and the reads it is aimed through. */
    CONTROL,

    /** The flight recorder, the log, the domains, and the Inspector link. */
    DIAGNOSTICS;

    /** As it is written on the wire, in the discovery file and in settings: {@code control}, {@code diagnostics}. */
    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The capability whose {@link #word()} is {@code word}, or none. */
    public static Optional<Capability> named(String word) {
        return Arrays.stream(values()).filter(c -> c.word().equals(word)).findFirst();
    }

    /** {@code control,diagnostics}, in declaration order; {@code -} for none. */
    public static String words(Set<Capability> set) {
        return set.isEmpty() ? "-" : EnumSet.copyOf(set).stream().map(Capability::word).collect(Collectors.joining(","));
    }

    /** Both, the set a socket serves when nobody split them. */
    public static Set<Capability> both() {
        return EnumSet.allOf(Capability.class);
    }
}
