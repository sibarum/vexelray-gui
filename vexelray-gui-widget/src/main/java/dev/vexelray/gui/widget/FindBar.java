package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.function.Supplier;

/**
 * The bar a find gesture is: a strip that is not there until Ctrl+F asks for it, carrying one query field and one
 * line of status about the answer — where typing searches, Enter steps to the next match, Shift+Enter to the
 * previous one, and Escape puts the bar away and hands the keyboard back.
 *
 * <p><b>The second thing to be searched is what makes this a widget.</b> The tree grew this bar first, and a
 * multiline field wants the same one: the same chord, the same keys, the same strip in the same place. What the
 * two do <em>not</em> share is what a search costs — walking a lazy hierarchy fetches it, so the tree stops at
 * the first match and reports "No match" only after reaching the end, while a field's text is already in memory
 * and can afford to count every match and say "3 of 17". That difference is the whole of {@link Search}: this
 * class owns the bar and the gesture, and knows nothing about what a match is or how dearly it was bought.
 *
 * <p><b>Hidden is not merely invisible.</b> A hidden node takes no space, draws nothing and is no Tab stop, so a
 * thing nobody searches is the thing there was before there was searching — which is the only honest way to add a
 * bar that appears. Asking for it is a chord, never a hover: chrome that arrives because the pointer came near
 * would move the target out from under it.
 */
final class FindBar implements AutoCloseable {

    /**
     * The chord that opens a bar. Package-visible because an owner that stops being searchable has to hand it
     * back ({@code gui.releaseClaim(node, FindBar.FIND)}) — a claim outlives the reason it was made otherwise.
     */
    static final Shortcut FIND = Shortcut.of(Key.F, Modifier.CONTROL);

    /** Escape shuts the bar; Shift+Enter steps backwards, which plain Enter (a submit) cannot express. */
    private static final Shortcut ESCAPE = Shortcut.of(Key.ESCAPE);
    private static final Shortcut STEP_BACK = Shortcut.of(Key.ENTER, Modifier.SHIFT);

    /**
     * What the thing being searched has to be able to do. All four run on the handler executor, because a search
     * is an application-sized piece of work — the tree's walks the filesystem — and none of them may assume it
     * is the only one in flight: a bar that supersedes searches is a bar whose owner can be asked twice.
     */
    interface Search {

        /** Search for {@code query} afresh — a query nobody has seen, so the answer is the first one there is. */
        void first(String query);

        /** Step to the answer after the current one, wrapping past the last. */
        void next(String query);

        /** Step to the answer before the current one, wrapping past the first. */
        void previous(String query);

        /** The bar is away: stop showing whatever the search was showing, and take the keyboard back. */
        void closed();
    }

    private final Gui gui;
    private final Node bar;
    private final TextField field;
    private final Node statusLine;
    private final Search search;

    /** Whether the bar is up. Kept here because a {@link Node} handle is write-only — it is told, never asked. */
    private volatile boolean shown;

    /** The status last written, for the same reason: so writing it again can be skipped. */
    private volatile String reported = "";

    /** Build a bar, shut, over {@code search}. The owner places {@link #node()} and opens it with {@link #openOn}. */
    FindBar(Gui gui, Search search) {
        this.gui = gui;
        this.search = search;
        this.field = new TextField(gui);
        this.field.node().width(Length.grow(1)).height(Length.rem(2f));
        this.statusLine = gui.text("")
                .width(Length.em(7))
                .textSize(Length.rem(0.85f))
                .textColor(gui.theme().color(Role.DIM))
                .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE);
        this.bar = gui.row()
                .width(Length.FILL)
                .visible(false)
                .gap(Length.dp(6))
                .padding(Length.dp(3), Length.dp(3))
                .background(gui.theme().color(Role.CHROME))
                .scroll(false, false)
                .children(field.node(), statusLine);

        // Every edit of the query is a search of its own, which is what makes it incremental: typing "src" is
        // three searches, and the owner is free to abandon the first two. Enter is the same query asked again from
        // where the last answer left off, so the two together are find and find-next.
        field.onChange(search::first);
        field.onSubmit(search::next);
        // Shift+Enter cannot arrive as a submit — a submit carries no modifiers — so it is a claim, like the two
        // below. Claims rather than key handlers throughout: they preempt, so Escape here never also reaches the
        // field as a keystroke, and the query field keeps Enter for stepping instead of for anything else.
        gui.claim(field.node(), STEP_BACK, ClaimScope.FOCUSED, () -> search.previous(query()));
        gui.claim(field.node(), ESCAPE, ClaimScope.FOCUSED, this::dismiss);
        // Ctrl+F again, with the caret already in the bar, selects the query rather than opening a second bar:
        // the chord means "search for something", and the something you would type replaces what is there.
        gui.claim(field.node(), FIND, ClaimScope.FOCUSED, () -> field.select(0, field.text().length()));
    }

    /**
     * Open this bar with Ctrl+F while {@code owner} holds focus, seeded with whatever {@code seed} supplies.
     *
     * <p>{@link ClaimScope#FOCUSED}, so the chord belongs to the owner only while the owner is the thing being
     * keyboarded — an application's own Ctrl+F keeps working everywhere else, and this outranks nothing.
     */
    FindBar openOn(Node owner, Supplier<String> seed) {
        gui.claim(owner, FIND, ClaimScope.FOCUSED, () -> open(seed == null ? "" : seed.get()));
        return this;
    }

    /** The strip. The owner places it — in flow above the thing it searches, or floating over it. */
    Node node() {
        return bar;
    }

    /** The status line, for an owner that wants to restyle it and for a test that wants to read it. */
    Node statusNode() {
        return statusLine;
    }

    /** The query field's node — where the caret goes while the bar is up. */
    Node queryNode() {
        return field.node();
    }

    /** What is being searched for right now. */
    String query() {
        return field.text();
    }

    /** Whether the bar is up. */
    boolean shown() {
        return shown;
    }

    /**
     * Put the bar up with {@code seed} in it and the caret after it.
     *
     * <p>Setting the query <em>is</em> a search: the field publishes the change, so a seeded bar arrives with its
     * first answer already found and an empty one arrives having cleared the last. Ctrl+F therefore starts a
     * search rather than resuming one, and an owner that seeds from the selection has said "this, then" in one
     * gesture.
     */
    void open(String seed) {
        status("");
        field.text(seed == null ? "" : seed);
        shown = true;
        bar.visible(true);
        gui.focus(field.node());
    }

    /** Shut the bar and tell the owner, which is what gets the keyboard back to the thing being searched. */
    void dismiss() {
        shown = false;
        bar.visible(false);
        status("");
        search.closed();
    }

    /**
     * Report the last answer — "3 of 17", "No match", "Searching…". Empty says nothing at all.
     *
     * <p>Written only when it changes. A node handle is told, never asked, so nothing downstream can notice that a
     * prop was set to what it already held — and an owner that recomputes its answer every time the caret moves
     * would otherwise post a mutation per keystroke saying the same thing.
     */
    void status(String text) {
        String next = text == null ? "" : text;
        if (!next.equals(reported)) {
            reported = next;
            statusLine.text(next);
        }
    }

    /** Release the query field (and with it every claim registered on it). The strip goes with its owner's tree. */
    @Override
    public void close() {
        field.close();
    }
}
