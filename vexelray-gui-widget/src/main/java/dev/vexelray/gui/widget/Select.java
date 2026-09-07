package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutContext;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A control that shows one choice and hides the rest until asked: the closed strip, the list that floats over the
 * page when it is clicked, and the keyboard that drives both.
 *
 * <h2>Why this is a widget and {@link Segment} is a different one</h2>
 *
 * A segment puts every option on show, which is right for three words and impossible for three hundred. This is
 * the other end: the alternatives cost a click to see, and in exchange the control is one line high whatever the
 * list holds. Neither is a styling of the other — the choice between them is a choice about how many options
 * there are, and it is made by picking the type.
 *
 * <h2>Multi-select is the mode, not a second widget</h2>
 *
 * {@link SelectionModel.Mode} already makes "several" a <b>capability</b> rather than a case, so this takes a
 * model and asks it what it permits. Everything that differs between a single-choice drop-down and a multi-select
 * one falls out of that one answer, and mostly it falls out <em>for free</em>: an arrow key selects where it
 * lands in the first and merely moves the cursor in the second because {@link SelectionModel#lead} degrades to
 * {@link SelectionModel#at}, a click ticks one option or replaces the lot because {@link SelectionModel#toggle}
 * degrades the same way, and the closed strip reads out whatever is in the set. Those are one call each, not a
 * branch. <b>Exactly one thing here does ask</b> — whether making a choice ends the gesture, which is
 * {@code allowsMultiple()} in {@link #onAnyClick} — and asking a capability what it permits is what a capability
 * is for; a {@code switch} over the mode is what it is not.
 *
 * <h2>The list is a {@link ListView}, and that is the whole implementation</h2>
 *
 * Rows are not built here. Composing the list brings virtualisation (a select over ten thousand values costs a
 * popup's worth of nodes), {@link SelectionModel} (what is chosen, and where a range grows from), and
 * {@link ListView#reveal} — which is what makes opening scroll to the current value instead of to the top. A
 * drop-down that grew its own rows would be a second, worse list, and its Shift+Down would drift from the one
 * every other list has.
 *
 * <h2>Committing, and taking it back</h2>
 *
 * Moving through the options is not choosing: the selection changes as the cursor moves (watch
 * {@code selection().onChange} for the live preview), and {@link #onCommit} fires once, when the popup closes on
 * a choice. Escape restores the selection the popup opened with. This is the pair {@link ColorPicker} settled on
 * and for the same reason — collapsing them makes either every arrow key an undo entry or the control impossible
 * to preview.
 *
 * <h2>The two standing rules, and what they cost here</h2>
 *
 * <b>Nothing happens on hover.</b> The popup opens on a click or on a key, never on the pointer arriving, and the
 * closed strip <b>reserves its chevron slot</b> whether or not it has a value — so a control that goes from
 * "Medium" to "Extra large" grows nothing and moves nothing beside it.
 *
 * <p><b>Every key arrives as a claim.</b> While the popup is up it owns Up, Down, Enter and Escape at
 * {@link ClaimScope#VISIBLE} — the popup is on screen but need not hold focus, and a claim is preemption declared
 * in advance, so nothing downstream sees those keys and nothing had to be blocked to arrange it. The claims are
 * registered on opening and released on closing, because a hidden node keeps its claims and a shut drop-down
 * owning Escape would shadow whatever the page wants it for.
 *
 * @param <T> the value type — anything with equality; the label is a function of it, never a parallel list
 */
public final class Select<T> implements AutoCloseable {

    /** How many rows the popup shows before it scrolls. Beyond this the list virtualises, so the cost is flat. */
    private static final int VISIBLE_ROWS = 8;

    /** A row of the popup, in rem. Matches a menu item: this is the same gesture wearing a different frame. */
    private static final float ROW_REM = 1.6f;

    /** The hairline both the strip and the panel are framed in. Named because the flip has to account for it. */
    private static final float BORDER_DP = 1f;

    private static final Shortcut ESCAPE = Shortcut.of(Key.ESCAPE);
    private static final Shortcut ENTER = Shortcut.of(Key.ENTER);
    private static final Shortcut UP = Shortcut.of(Key.UP);
    private static final Shortcut DOWN = Shortcut.of(Key.DOWN);
    private static final Shortcut SHIFT_UP = Shortcut.of(Key.UP, Modifier.SHIFT);
    private static final Shortcut SHIFT_DOWN = Shortcut.of(Key.DOWN, Modifier.SHIFT);
    private static final Shortcut SPACE = Shortcut.of(Key.SPACE);
    private static final Shortcut HOME = Shortcut.of(Key.HOME);
    private static final Shortcut END = Shortcut.of(Key.END);

    private final Gui gui;
    private final Function<T, String> label;
    private final SelectionModel<T> selection;
    private final ListView<T> list;

    private final Node control;
    private final Node valueText;
    private final Node popup;

    private final List<Subscription> subs = new ArrayList<>();

    private volatile String placeholder = "";
    private volatile Function<Set<T>, String> summary;
    private volatile Consumer<Set<T>> onCommit = chosen -> { };

    private volatile boolean attached;
    private volatile boolean open;

    /** Where the popup was last put, so a re-anchor that would write the same numbers writes nothing. */
    private volatile Rect anchoredTo = Rect.ZERO;

    /** What was selected when the popup opened — what Escape puts back. */
    private volatile Set<T> opened = Set.of();

    /** A single-choice select labelled by {@link String#valueOf}. */
    public Select(Gui gui) {
        this(gui, String::valueOf);
    }

    /** A single-choice select over values {@code label} knows how to name. */
    public Select(Gui gui, Function<T, String> label) {
        this(gui, label, SelectionModel.single());
    }

    /**
     * A select over {@code selection} — hand it {@link SelectionModel#range()} for a multi-select drop-down, or a
     * model something else already holds to have both show the same choice.
     */
    public Select(Gui gui, Function<T, String> label, SelectionModel<T> selection) {
        this.gui = Objects.requireNonNull(gui, "gui");
        this.label = Objects.requireNonNull(label, "label");
        this.selection = Objects.requireNonNull(selection, "selection");
        this.summary = this::defaultSummary;
        Theme theme = gui.theme();

        this.valueText = gui.text("")
                .width(Length.FILL)
                .wordWrap(false)
                .textSize(Length.rem(1))
                .textColor(theme.color(Role.INK))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
        this.control = gui.row().role("combobox")
                .width(Length.FILL)
                .background(theme.color(Role.WELL))
                .border(Length.dp(BORDER_DP), theme.color(Role.EDGE))
                .corner(Length.rem(0.3f))
                .padding(Length.rem(0.2f), Length.rem(0.5f))
                .gap(Length.rem(0.4f))
                .alignItems(AlignItems.CENTER)
                .scroll(false, false)
                .children(valueText, chevron());
        gui.focusable(control, true);
        gui.cursor(control, CursorShape.POINTER);
        gui.onClick(control, this::toggleOpen);
        gui.onState(control, state -> control.background(theme.color(Role.WELL, state)));

        this.list = new ListView<>(gui, ROW_REM, (g, item) -> g.text(label.apply(item))
                        .width(Length.FILL)
                        .wordWrap(false)
                        .padding(Length.ZERO, Length.rem(0.5f))
                        .textSize(Length.rem(1))
                        .textColor(g.theme().color(Role.INK))
                        .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE),
                selection)
                .roles("listbox", "option")
                // A row in a popup is a tick, not a file: a plain click flips it rather than throwing away
                // everything ticked so far. Unconditional, because `toggle` degrades to `at` where only one may
                // be held — so this is the right gesture in both modes without asking which one is in force.
                .clickToggles(true)
                // Shut, and not even attached yet: without this the list would guess the window it needs from the
                // application's own height and build a screenful of rows behind a closed drop-down.
                .showing(false);
        list.selection().onChange(chosen -> readOut());
        list.selection().onLeadChange(lead -> readOut());

        this.popup = gui.column()
                .visible(false)
                .background(theme.color(Role.PANEL))
                .border(Length.dp(BORDER_DP), theme.color(Role.LINE))
                .corner(Length.rem(0.3f))
                .lit(theme.lit())
                .elevation(theme.elevation(Relief.FLOATING))
                .scroll(false, false)
                .children(list.node());

        // The popup follows the control: a page that scrolls, a window that resizes or a row that reflows all move
        // the thing this is anchored to, and an overlay that stayed put would be pointing at nothing. Read from the
        // same published layout the list virtualises against — no frame callback of this widget's own.
        subs.add(gui.layout().onCommit(snapshot -> reanchor(true)));
        subs.add(gui.bus().subscribe(gui.clicks(), this::onAnyClick));

        armClosed();
        readOut();
    }

    // ------------------------------------------------------------------ what it is made of

    /** The closed control — the node to place in a layout. The popup places itself. */
    public Node node() {
        return control;
    }

    /** The floating panel, for tests and for styling beyond the defaults. */
    public Node popupNode() {
        return popup;
    }

    /** The list inside the popup — for {@code roles}, or to read what is realized. */
    public ListView<T> list() {
        return list;
    }

    /** What is chosen, and where a range would grow from. The same type a list, a tree or a table uses. */
    public SelectionModel<T> selection() {
        return selection;
    }

    /** Whether the popup is up. The word {@link ContextMenu} and {@code FindBar} use for the same question. */
    public boolean shown() {
        return open;
    }

    // ------------------------------------------------------------------ what is on offer, and what is chosen

    /** The options, in order. Replaces what was there; the selection keeps whatever survives. */
    public Select<T> options(List<T> values) {
        list.items(values);
        readOut();
        return this;
    }

    /** What the closed strip says when nothing is chosen. Empty by default — a blank strip, not an invented word. */
    public Select<T> placeholder(String text) {
        this.placeholder = text == null ? "" : text;
        readOut();
        return this;
    }

    /**
     * How the closed strip reads out a selection. The default names one value with the label function and joins
     * several with commas, which is the only summary a framework can write: "3 selected" is an English sentence,
     * and minting one here is the same category of mistake as minting a colour. An application that wants a count,
     * a unit or another language says so through this.
     */
    public Select<T> summary(Function<Set<T>, String> reader) {
        this.summary = reader == null ? this::defaultSummary : reader;
        readOut();
        return this;
    }

    /** The chosen value, or null. With several chosen this is the lead — see {@link SelectionModel#one}. */
    public T value() {
        return selection.one();
    }

    /** Choose {@code value} as the application rather than the user: no {@link #onCommit}, because nobody chose. */
    public Select<T> value(T value) {
        selection.set(value == null ? List.of() : List.of(value));
        readOut();
        return this;
    }

    /**
     * React to a choice being <b>made</b> — the popup closing on Enter, a click on a row, or a click away — with
     * what is chosen. Not fired while the cursor moves through the options (watch {@code selection().onChange} for
     * that), and not fired by {@link #value(Object)}. Runs on a worker thread.
     */
    public Select<T> onCommit(Consumer<Set<T>> handler) {
        this.onCommit = handler == null ? chosen -> { } : handler;
        return this;
    }

    // ------------------------------------------------------------------ opening and shutting

    /** Put the popup up, anchored under the control, scrolled to whatever is currently chosen. */
    public void open() {
        if (open) {
            return;
        }
        opened = Set.copyOf(selection.selection());
        if (!attached) {
            attached = true;
            gui.root().append(popup);
        }
        open = true;
        disarmClosed();   // FOCUSED outranks VISIBLE: the control must let go of Down before the list can have it
        popup.visible(true);
        list.showing(true);
        anchoredTo = Rect.ZERO;   // force the first placement, whatever the control's rect happens to equal
        reanchor(false);
        armOpen();
        T lead = selection.lead();
        if (lead != null) {
            list.reveal(lead);
        }
    }

    /** Shut the popup, keeping what is chosen, and announce it. A click away and Enter both land here. */
    public void commit() {
        if (!open) {
            return;
        }
        shut();
        onCommit.accept(Set.copyOf(selection.selection()));
    }

    /**
     * Shut the popup and put back the selection it opened with — Escape. Nothing is announced, because taking a
     * choice back is not making one; the live preview reverts through {@code selection().onChange} like any other
     * change, which is what makes the preview honest rather than something the application has to undo.
     */
    public void cancel() {
        if (!open) {
            return;
        }
        Set<T> restore = opened;
        shut();
        selection.set(restore);
    }

    private void shut() {
        open = false;
        popup.visible(false);
        list.showing(false);
        disarmOpen();
        armClosed();
        readOut();
    }

    private void toggleOpen() {
        if (open) {
            commit();
        } else {
            open();
        }
    }

    // ------------------------------------------------------------------ the keyboard

    /**
     * While the popup is up it owns the arrows, Enter, Escape and Space at {@link ClaimScope#VISIBLE}.
     *
     * <p>Down and Up call {@link SelectionModel#lead}, which is the one call that is right in both modes: with one
     * choice permitted it degrades to {@link SelectionModel#at} and the arrow selects, with several it moves the
     * cursor and leaves the set alone so Space has something to flip. Shift+Arrow ranges, and in a single-choice
     * model that degrades too. None of this is a branch on the mode.
     */
    private void armOpen() {
        gui.claim(popup, DOWN, ClaimScope.VISIBLE, () -> step(1, false));
        gui.claim(popup, UP, ClaimScope.VISIBLE, () -> step(-1, false));
        gui.claim(popup, SHIFT_DOWN, ClaimScope.VISIBLE, () -> step(1, true));
        gui.claim(popup, SHIFT_UP, ClaimScope.VISIBLE, () -> step(-1, true));
        gui.claim(popup, HOME, ClaimScope.VISIBLE, () -> stepTo(0, false));
        gui.claim(popup, END, ClaimScope.VISIBLE, () -> stepTo(Integer.MAX_VALUE, false));
        gui.claim(popup, SPACE, ClaimScope.VISIBLE, this::toggleLead);
        gui.claim(popup, ENTER, ClaimScope.VISIBLE, this::commit);
        gui.claim(popup, ESCAPE, ClaimScope.VISIBLE, this::cancel);
    }

    private void disarmOpen() {
        for (Shortcut chord : List.of(DOWN, UP, SHIFT_DOWN, SHIFT_UP, HOME, END, SPACE, ENTER, ESCAPE)) {
            gui.releaseClaim(popup, chord);
        }
    }

    /**
     * While the popup is shut, Down and Enter on the focused control open it.
     *
     * <p>Registered and released rather than left standing, because {@link ClaimScope#FOCUSED} outranks
     * {@link ClaimScope#VISIBLE}: a control that kept its Down claim while its own popup was up would reopen the
     * popup instead of letting the list move, and the bug would look like an arrow key that does nothing.
     */
    private void armClosed() {
        gui.claim(control, DOWN, ClaimScope.FOCUSED, this::open);
        gui.claim(control, ENTER, ClaimScope.FOCUSED, this::open);
    }

    private void disarmClosed() {
        gui.releaseClaim(control, DOWN);
        gui.releaseClaim(control, ENTER);
    }

    /** Move the cursor {@code by} rows from where it is; with nothing under it, to the near end. */
    private void step(int by, boolean extend) {
        SelectionModel.Order<T> order = list.order();
        int at = order.indexOf(selection.lead());
        stepTo(at < 0 ? (by > 0 ? 0 : Integer.MAX_VALUE) : at + by, extend);
    }

    private void stepTo(int index, boolean extend) {
        SelectionModel.Order<T> order = list.order();
        if (order.size() == 0) {
            return;
        }
        T target = order.at(Math.clamp(index, 0, order.size() - 1));
        if (extend) {
            selection.extendTo(target, order);
        } else {
            selection.lead(target);
        }
        list.reveal(target);   // the keyboard never leaves the cursor somewhere the user cannot see
    }

    private void toggleLead() {
        T lead = selection.lead();
        if (lead != null) {
            selection.toggle(lead);
        }
    }

    // ------------------------------------------------------------------ where the popup goes

    /**
     * Put the popup under the control — or over it, when there is no room below and there is above. Read from the
     * layout read-model in client pixels and written in {@code dp}, the same conversion {@link ContextMenu} makes
     * and for the same reason: a screen position is a physical fact, so it survives a zoom unchanged.
     *
     * <p><b>A control that has scrolled out of sight takes its popup with it.</b> An overlay is floated on the
     * root, so nothing clips it when the thing it belongs to leaves the viewport — it would hang there over
     * whatever is drawn underneath, pointing at nothing. {@code clippedAway} is the read-model answering exactly
     * that question, so the popup shuts instead.
     */
    private void reanchor(boolean fromLayout) {
        if (!open) {
            return;
        }
        NodeLayout l = control.layout();
        if (!l.present() || l.clippedAway()) {
            // Gone from the screen, by either route: scrolled out of its viewport, or hidden along with the page
            // it is on — a tab switching, a panel collapsing. Only the layout path may conclude that, because the
            // placement `open` does itself runs before the frame that would prove the control is still there.
            if (fromLayout) {
                commit();
            }
            return;
        }
        Rect r = l.visibleRect();
        if (r.equals(anchoredTo)) {
            return;   // a handle is told, never asked: writing the same numbers again is a mutation per frame
        }
        anchoredTo = r;

        float dpi = Math.max(0.0001f, gui.dpi().value());
        float bodyPx = rowPx() * Math.clamp(list.count(), 1, VISIBLE_ROWS);
        // The panel is its rows plus its own border, top and bottom. Flipping on the rows alone puts a panel that
        // opens upwards two pixels *over* the control it belongs to, which is the sort of miss that looks like a
        // rounding error and is a missing term.
        float panelPx = bodyPx + 2f * BORDER_DP * dpi;
        float viewportH = gui.viewport().value().height();
        boolean above = r.y() + r.h() + panelPx > viewportH && r.y() - panelPx >= 0f;
        float y = above ? r.y() - panelPx : r.y() + r.h();

        list.node().height(Length.dp(bodyPx / dpi));
        popup.width(Length.dp(r.w() / dpi));
        popup.floatAt(Length.dp(r.x() / dpi), Length.dp(y / dpi));
    }

    /** One row in pixels at the current basis — what turns a count of options into a height. */
    private float rowPx() {
        return Length.rem(ROW_REM).scalarPx(
                new LayoutContext(gui.rootEmPx(), gui.zoom().value(), gui.dpi().value(),
                        gui.viewport().value().width(), gui.viewport().value().height()),
                0f);
    }

    // ------------------------------------------------------------------ the strip, and going away

    /** Write what is chosen into the closed strip, and only when it changed. */
    private void readOut() {
        Set<T> chosen = selection.selection();
        String text = chosen.isEmpty() ? placeholder : summary.apply(chosen);
        valueText.text(text == null ? "" : text);
        valueText.textColor(gui.theme().color(chosen.isEmpty() ? Role.FAINT : Role.INK));
    }

    private String defaultSummary(Set<T> chosen) {
        return chosen.stream().map(label).collect(Collectors.joining(", "));
    }

    /**
     * Click-away, by ancestry rather than by a set of owned ids: a popup's rows are virtualised, so which nodes it
     * owns is not knowable in advance and a set would have to be rebuilt every time the list scrolled. The
     * read-model publishes each node's parent, so "is this click inside me" is a walk up from where it landed.
     *
     * <p>Left button only, and nothing is blocked: the click that lands elsewhere still does what it always did.
     */
    private void onAnyClick(ClickEvent e) {
        if (!open || e.button() != MouseButton.LEFT) {
            return;
        }
        if (within(e.nodeId(), popup.id())) {
            // A click on a row is a choice, and where only one thing may be chosen, making it ends the gesture. A
            // multi-select popup stays up: the user is building a set, and shutting after the first tick would
            // make the second one a second opening.
            if (!selection.mode().allowsMultiple()) {
                commit();
            }
        } else if (!within(e.nodeId(), control.id())) {
            commit();   // somewhere else entirely: shut, keeping what was chosen. The control's own click toggles.
        }
    }

    /**
     * Whether {@code nodeId} is {@code ancestorId} or sits under it — a walk up the parent chain the read-model
     * publishes.
     *
     * <p>By ancestry rather than by a set of owned ids, which is what {@link ContextMenu} can afford and this
     * cannot: a popup's rows are virtualised, so which nodes it owns is not knowable in advance and the set would
     * have to be rebuilt every time the list scrolled.
     */
    private boolean within(long nodeId, long ancestorId) {
        var snapshot = gui.semanticSnapshot();
        long at = nodeId;
        while (at >= 0) {
            if (at == ancestorId) {
                return true;
            }
            long parent = snapshot.node(at).parentId();
            if (parent == at) {
                return false;   // a node that is its own parent is a broken snapshot, not a loop to follow
            }
            at = parent;
        }
        return false;
    }

    /**
     * The chevron, built from bars rather than written as a glyph: the bundled atlas carries no triangle and no
     * arrow, and a missing glyph draws as nothing without failing, so a lookalike character is a bug waiting for
     * a font change. Same answer {@link TitleBar} gives for maximize and restore.
     *
     * <p>Its width is fixed and it is always present, which is the reservation the no-hover rule asks for: the
     * strip is the same size holding "S" as holding "Extra large", so nothing beside it reflows when the value
     * changes.
     */
    private Node chevron() {
        Node stack = gui.column()
                .width(Length.dp(9))
                .alignItems(AlignItems.CENTER)
                .hitInert(true)
                .scroll(false, false);
        for (int w = 9; w >= 1; w -= 2) {
            stack.append(gui.box().size(Length.dp(w), Length.dp(1))
                    .background(gui.theme().color(Role.DIM)));
        }
        return stack;
    }

    /** Release the popup, the list, the subscriptions and every claim either node holds. */
    @Override
    public void close() {
        for (Subscription s : subs) {
            s.close();
        }
        subs.clear();
        if (open) {
            shut();
        }
        disarmClosed();
        list.close();
        gui.releaseNode(popup);
        gui.releaseNode(control);
        if (attached) {
            popup.remove();
        }
    }
}
