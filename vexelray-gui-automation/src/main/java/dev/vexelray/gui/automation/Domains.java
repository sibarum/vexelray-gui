package dev.vexelray.gui.automation;

import java.util.Map;

/**
 * The domains whose verbs are planned and not built yet, and what each one is waiting for
 * (docs/plans/flight-recorder.md §5 and §8). One table, read three ways, so they cannot drift apart: the verb
 * itself answers {@code err not available yet}, {@code hello}'s {@code domain} line says {@code missing(...)}, and
 * the Inspector greys out the panel with the same words.
 *
 * <p>An entry leaves this table in the change that lands its verb.
 */
final class Domains {

    /** Verb, then what it needs that does not exist yet. In the order {@code hello} lists them. */
    static final Map<String, String> MISSING = missing();

    private static Map<String, String> missing() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("bus", "the atchung topic registry and tap");
        m.put("kron", "the kronometer snapshot");
        m.put("gpu", "presenter statistics from GuiApp");
        m.put("ledger", "the public probe Ledger snapshot");
        m.put("threads", "ProcessCpu in vexelray-os");
        m.put("heap", "the native-image heap and GC spike (flight-recorder.md Q5)");
        m.put("meter", "the meter verbs (meter.md)");
        m.put("diagnose", "the domain verbs it reads");
        return java.util.Collections.unmodifiableMap(m);
    }

    private Domains() {
    }

    /** Whether {@code verb} is one of these. */
    static boolean owns(String verb) {
        return MISSING.containsKey(verb);
    }

    /** The reply to a verb that is not built yet. */
    static String answer(String verb) {
        return "err not available yet: " + verb + " needs " + MISSING.get(verb);
    }

    /** The {@code missing(...)} entries of {@code hello}'s {@code domain} line. */
    static String line() {
        StringBuilder sb = new StringBuilder();
        MISSING.forEach((verb, needs) -> sb.append(' ').append(verb).append("=missing(").append(needs).append(')'));
        return sb.toString();
    }
}
