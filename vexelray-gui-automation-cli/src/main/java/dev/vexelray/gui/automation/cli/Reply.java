package dev.vexelray.gui.automation.cli;

import java.util.List;

/**
 * One answer from the application, already unframed: the lines between the command and the terminator.
 *
 * <p>The wire says a reply begins with {@code ok} or {@code err} and ends at a line containing only {@code .}
 * ({@code AutomationServer}'s class javadoc). The terminator is not kept — it is framing, not content — and
 * the verdict is read off the first line rather than reconstructed by the caller, because a caller that
 * reconstructs it is a caller that can get it wrong. {@link #err()} is the whole of {@code R5}: the exit
 * status of the tool is this predicate, or'd over a session.
 *
 * <p>A blank command produces a blank reply — {@code Automation.command} returns an empty string for one — and
 * that counts as {@code ok}. Nothing was asked, so nothing failed.
 *
 * @param command what was sent, kept so a transcript can be read without the script beside it
 * @param lines   the reply body, in order, terminator removed
 */
public record Reply(String command, List<String> lines) {

    public Reply {
        lines = List.copyOf(lines);
    }

    /** The first line, or {@code ""} — where {@code ok} or {@code err} lives. */
    public String first() {
        return lines.isEmpty() ? "" : lines.getFirst();
    }

    /**
     * Whether the application refused or failed.
     *
     * <p>Matched on the first line only. A multi-line {@code ok} reply — {@code tree}, {@code find},
     * {@code help} — may legitimately contain the word anywhere below it, and a listing that mentions a node
     * named "error" is not a failed command.
     */
    public boolean err() {
        return first().startsWith("err");
    }

    /** The reply as it would be printed: the body, newline-separated, without the terminator. */
    public String text() {
        return String.join("\n", lines);
    }
}
