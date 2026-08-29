package dev.vexelray.gui.demo;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.text.TextLayout;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The activity rail: every chapter's running commentary, in one place, on the right of the window.
 *
 * <p>It is the gallery's answer to the question a showcase usually answers with a screenshot — <em>did that do
 * anything?</em> A chapter that opens a window, refuses a drop, counts a directory or accepts a submission says
 * so here, and because the rail is visible from every chapter the answer does not go away when the page does.
 *
 * <p><b>It tails.</b> {@link LayoutEnums.ScrollLock#BOTTOM} pins the view to the newest line as lines arrive —
 * until the user scrolls up, at which point it stops fighting them and stays where they put it. Scroll back to
 * the bottom and it re-arms.
 *
 * <p><b>It is bounded.</b> A demo left running for an hour is a tree with tens of thousands of text nodes in it,
 * every one of them measured on every relayout. Old lines are removed rather than accumulated, which is one call
 * on the handle of the node being dropped — the same handle vocabulary that appended it.
 *
 * <p>Every method here is safe from any thread, because {@link Node} handles are: a worker appends by posting a
 * mutation, and the next frame reconciles it.
 */
public final class Console {

    /** How many lines the rail keeps. Enough to hold the history of a chapter, not enough to grow without end. */
    private static final int KEEP = 200;

    private final Gui gui;
    private final Node lines;
    private final Deque<Node> shown = new ArrayDeque<>();

    Console(Gui gui) {
        this.gui = gui;
        this.lines = gui.column().width(Length.FILL).height(Length.FILL).gap(Length.rem(0.25f))
                .scrollLock(LayoutEnums.ScrollLock.BOTTOM);
    }

    /** The scrolling column of lines, to be placed in whatever holds it. */
    public Node node() {
        return lines;
    }

    /** An ordinary line: something happened, and it was expected. */
    public Console say(String text) {
        return line(text, Role.INK);
    }

    /** A quieter line: context around what happened, rather than the thing itself. */
    public Console note(String text) {
        return line(text, Role.DIM);
    }

    /** Something the application chose, and wants read: a submission accepted, a window opened. */
    public Console good(String text) {
        return line(text, Role.ACCENT);
    }

    /** Something refused. Not an error in the process — an answer the user is owed. */
    public Console refused(String text) {
        return line(text, Role.DANGER);
    }

    private synchronized Console line(String text, Role role) {
        Node n = gui.text(text).width(Length.FILL)
                .textSize(Length.rem(0.875f)).textColor(gui.theme().color(role))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
        lines.append(n);
        shown.addLast(n);
        while (shown.size() > KEEP) {
            shown.removeFirst().remove();
        }
        return this;
    }
}
