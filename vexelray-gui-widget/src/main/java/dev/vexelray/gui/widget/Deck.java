package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;

import java.util.ArrayList;
import java.util.List;

/**
 * One stack of cards on a {@link Board}: a column the user reorders by dragging, and that cards are dragged into
 * and out of from the board's other decks.
 *
 * <pre>{@code
 * Deck<Task> todo = board.column()
 *         .items(model.todo())
 *         .reorderable((moved, where, effect) -> model.move(moved, TODO, where));
 * model.onChange(() -> todo.items(model.todo()));
 * }</pre>
 *
 * <p>A deck holds the application's list and nothing else. It never reorders anything on its own account: a drop
 * asks {@link #reorderable the reorder} for a {@link dev.vexelray.gui.core.edit.Change}, the drop's history performs
 * it, and the application hands the deck its new list. That last step is the same one undo and every other edit
 * take, which is why all of them animate alike — the deck compares the list it is handed with what it is showing
 * and moves the difference into place, whatever caused it.
 *
 * <h2>Where a drop lands</h2>
 *
 * <p>Wherever the carried card's middle is nearest to. The slots are measured on the deck as it would stand with
 * the carried card taken out, so they do not move while the gap opening between them does — a slot boundary that
 * travelled with the animation it causes would chase the pointer, and a pointer resting on it would flicker
 * between two answers. The boundary between two slots is fixed, half-way between them, and a pixel of movement
 * changes the answer to a neighbour or not at all.
 *
 * <p>Every point in the deck answers, including the empty space below the last card, which is the end of the
 * deck. The slot the card came from answers "nothing": putting a card back where it was is not an edit, and is
 * shown as the card going home rather than as a drop.
 *
 * <h2>The node</h2>
 *
 * <p>{@link #node()} is the deck's box, a column that scrolls vertically and fills its parent; size, pad and paint
 * it as any other box. The cards are inside it, in a column of their own, and are the board's to place.
 */
public final class Deck<T> {

    final Board<T> board;
    /** The deck's box: what the application sizes, and what drags start on and drop into. */
    final Node node;
    /** The column the slots are in. No padding and no gap, so a slot's top is the sum of the heights above it. */
    final Node inner;
    /** What the deck shows, in order: cards, and the gaps opening and closing between them. Guarded by the board. */
    final List<Board.Slot<T>> slots = new ArrayList<>();
    /** The application's list, as last given — or as a committed drop predicts it will be. Guarded by the board. */
    final List<T> items = new ArrayList<>();
    /** What a placement here means, or null for a deck that cards can be neither dragged from nor into. */
    volatile Reorder<T> reorder;
    /** Whether {@link #slots} has changed order since it was last written to {@link #inner}. Guarded by the board. */
    boolean orderDirty;
    /** Whether the deck has been given a list before. Its first is shown as it stands, not animated in. */
    boolean shown;
    /** The offset this paint is scrolling the deck to, or NaN when it is not being scrolled. Guarded by the board. */
    float scrollWriteX = Float.NaN;
    float scrollWriteY = Float.NaN;

    Deck(Board<T> board, Gui gui) {
        this.board = board;
        this.inner = gui.column().width(Length.FILL).height(Length.AUTO);
        this.node = gui.column().width(Length.FILL).height(Length.FILL).scroll(false, true).children(inner);
    }

    /** The deck's box. */
    public Node node() {
        return node;
    }

    /** The board this deck belongs to: the cards it can exchange with, and how they move. */
    public Board<T> board() {
        return board;
    }

    /**
     * Show {@code items}, in this order.
     *
     * <p>Call it whenever the list changes, however it changed — a drop, an undo, a sort, a fetch. Cards already
     * shown keep their nodes and travel to their new places; a card new to the board grows into its slot; one no
     * longer in any deck fades out of it while the slot closes. A card that leaves this deck for another deck of
     * the same board flies there, whichever of the two decks is handed its new list first.
     *
     * <p>Items are matched by {@code equals}, and an item belongs to at most one deck of a board at a time.
     * Safe from any thread.
     */
    public Deck<T> items(List<? extends T> items) {
        board.show(this, List.copyOf(items));
        return this;
    }

    /** What this deck is showing, in order — including a drop it predicted and the application has yet to confirm. */
    public List<T> items() {
        return board.itemsOf(this);
    }

    /**
     * Let this deck's cards be dragged, and cards be dropped into it, with {@code reorder} saying what each
     * placement means.
     *
     * <p>A {@link Placement} here is {@code BEFORE} or {@code AFTER} a card in this deck, never one being carried,
     * and {@code INTO} a null reference for a deck with no cards in it. Return null to refuse one: the carried card
     * then shows that releasing would take it home. Asked on the GUI thread, once per frame while the pointer is
     * over the deck, so it must be cheap; the {@link dev.vexelray.gui.core.edit.Change} it returns is performed
     * only on release, through the GUI's {@link Gui#dropHistory}, which must be set for anything to commit.
     *
     * <p>Alt+Up and Alt+Down move a focused card within its deck, and Alt+Left and Alt+Right to the neighbouring
     * deck, through the same question and the same history.
     */
    public Deck<T> reorderable(Reorder<T> reorder) {
        this.reorder = java.util.Objects.requireNonNull(reorder, "reorder");
        return this;
    }
}
