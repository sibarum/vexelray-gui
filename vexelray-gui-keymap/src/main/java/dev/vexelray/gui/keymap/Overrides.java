package dev.vexelray.gui.keymap;

import dev.vexelray.gui.core.input.Trigger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the user changed: for each action they rebound, the chords it has now. An action that is absent keeps its
 * defaults; one present with no chords was deliberately unbound. That is the whole of a key map's state — the
 * defaults are code, and live with the code.
 *
 * <p><b>Deltas, not a snapshot.</b> Storing every chord would freeze the defaults of the version that wrote the
 * file: an action added later, or a default fixed later, would never reach anybody who had once changed one key.
 * Storing what differs means a new default arrives everywhere the user has not said otherwise.
 *
 * <p><b>Entries for actions nobody declared are kept.</b> A profile written by a newer build, or by a window this
 * process never opened, names actions this run does not know. They are carried through {@link #with} and back out
 * through {@link #text()} untouched, so loading a file and saving it again never loses what was in it.
 *
 * <p><b>The text form</b> is one line per action, the chords separated by spaces — {@code file.save = Ctrl+S
 * Ctrl+Shift+S}, or {@code file.save =} for unbound — because a chord's own spelling ({@link Shortcut#text()})
 * has no spaces, while every punctuation mark is a key somebody can bind. {@code #} starts a comment. Reading is
 * lenient line by line, as {@code Settings} is about a file: a line it cannot read is skipped, never a reason to
 * lose the rest.
 */
public record Overrides(Map<String, List<Trigger>> chords) {

    /** Nothing changed: every action has its defaults. */
    public static final Overrides NONE = new Overrides(Map.of());

    public Overrides {
        // Sorted by id so the text form is stable — a file that is diffed, or synced between machines, should
        // change by the line that changed and nothing else.
        TreeMap<String, List<Trigger>> copy = new TreeMap<>();
        chords.forEach((id, list) -> copy.put(id, distinct(list)));
        chords = Collections.unmodifiableMap(copy);
    }

    /** Whether {@code id} has been changed from its defaults. */
    public boolean has(String id) {
        return chords.containsKey(id);
    }

    /** The chords {@code id} was given, or null if it keeps its defaults. */
    public List<Trigger> get(String id) {
        return chords.get(id);
    }

    /** These overrides with {@code id} bound to exactly {@code list} (empty: unbound). */
    public Overrides with(String id, List<Trigger> list) {
        TreeMap<String, List<Trigger>> next = new TreeMap<>(chords);
        next.put(id, list);
        return new Overrides(next);
    }

    /** These overrides with {@code id} back on its defaults. */
    public Overrides without(String id) {
        if (!chords.containsKey(id)) {
            return this;
        }
        TreeMap<String, List<Trigger>> next = new TreeMap<>(chords);
        next.remove(id);
        return new Overrides(next);
    }

    /** One line per changed action, in id order: {@code id = Ctrl+S Ctrl+Shift+S}. Empty if nothing changed. */
    public String text() {
        StringBuilder out = new StringBuilder();
        for (String line : lines()) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /** {@link #text()} as its lines, without terminators — the shape a settings list holds. */
    public List<String> lines() {
        List<String> out = new ArrayList<>(chords.size());
        chords.forEach((id, list) -> {
            StringBuilder line = new StringBuilder(id).append(" =");
            for (Trigger s : list) {
                line.append(' ').append(s.text());
            }
            out.add(line.toString());
        });
        return out;
    }

    /** Read what {@link #text()} wrote. Never throws over content; an unreadable line or chord is skipped. */
    public static Overrides parse(String text) {
        return text == null ? NONE : parse(List.of(text.split("\\R")));
    }

    /** Read what {@link #lines()} wrote. */
    public static Overrides parse(List<String> lines) {
        TreeMap<String, List<Trigger>> out = new TreeMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || eq <= 0) {
                continue;
            }
            String id = line.substring(0, eq).strip();
            if (!Keymap.validId(id)) {
                continue;
            }
            List<Trigger> list = new ArrayList<>();
            String rest = line.substring(eq + 1).strip();
            for (String word : rest.isEmpty() ? new String[0] : rest.split("\\s+")) {
                try {
                    list.add(Trigger.parse(word));
                } catch (IllegalArgumentException unreadable) {
                    // A chord this build cannot spell — a key from a newer tactroller, a hand edit gone wrong —
                    // costs that chord, not the action and not the file.
                }
            }
            if (list.isEmpty() && !rest.isEmpty()) {
                // Every chord unreadable is not the user saying "unbound": they said something this build cannot
                // read, and the action is better off on its defaults than silently dead.
                continue;
            }
            out.put(id, list);
        }
        return out.isEmpty() ? NONE : new Overrides(out);
    }

    private static List<Trigger> distinct(List<Trigger> list) {
        return List.copyOf(new LinkedHashSet<>(list));
    }
}
