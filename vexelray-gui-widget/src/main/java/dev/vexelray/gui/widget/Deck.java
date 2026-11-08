package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Addressed;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.scroll.Scrollbars;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.List;

/**
 * One stack of cards on a {@link Board}: a column the user reorders by dragging, and that cards are dragged into
 * and out of from the board's other decks.
 *
 * <pre>{@code
 * Deck<Task> todo = board.column().name("todo").items(model.todo()).reorderable();
 * gui.mailbox(Deck.PLACED, todo.address(), p -> logic.send(new Move(p.item(), "todo", p.where())));
 * // told the logic's new list: todo.items(list);
 * }</pre>
 *
 * <p>A deck holds the application's list and nothing else. It never reorders anything on its own account: a drop
 * says where the card was put ({@link #PLACED}), the application moves it in its own model, and hands the deck its
 * new list. Meanwhile the deck shows the card where it was dropped, which the new list confirms. That last step is the same one undo and every other edit
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
 * <p>{@link #node()} is the deck's box, a Scroller with its bar beside it, filling its parent; size, pad and paint
 * it as any other box. The cards are inside it, in a column of their own, and are the board's to place.
 */
public final class Deck<T> {

    /**
     * A card put in this deck, by a drop or from the keyboard: before or after a card already in it, or into it when
     * it is empty. Always {@code MOVE}. The deck shows the card there until it is handed a list that says otherwise.
     * Every one, in order.
     */
    public static final Topic<Placed> PLACED = Addressed.edge(Placed.class);

    /**
     * The user put {@code item} at {@code where} in the deck at {@code at}, meaning {@code effect}: what
     * {@link #PLACED} carries, addressed to the deck. An announcement, not a request the deck waits on: the
     * arrangement is the application's, so it makes the move in its own model and then says so by handing the deck
     * its new list. A type of the deck's own, so it names one channel (vexelray-framework's threading.md, T4.1).
     *
     * @param at     the deck's address
     * @param item   what was placed
     * @param where  where, relative to an item already there
     * @param effect {@code MOVE} for a drag or a cut, {@code COPY} for a copy
     */
    public record Placed(String at, Object item, Placement<?> where, dev.vexelray.gui.core.drop.DropEffect effect)
            implements dev.vexelray.gui.core.Addressed {
        public Placed {
            java.util.Objects.requireNonNull(at, "at");
            java.util.Objects.requireNonNull(item, "item");
            java.util.Objects.requireNonNull(where, "where");
            java.util.Objects.requireNonNull(effect, "effect");
        }

        @Override
        public Object address() {
            return at;
        }
    }


    final Board<T> board;
    /** The Scroller's node: the box the cards are seen through, its offset, where drags start and land. */
    final Node node;
    /** What the application places and sizes: the deck with its scrollbar beside it. */
    private final Node box;
    /** The column the slots are in. No padding and no gap, so a slot's top is the sum of the heights above it. */
    final Node inner;
    /** What the deck shows, in order: cards, and the gaps opening and closing between them. Guarded by the board. */
    final List<Board.Slot<T>> slots = new ArrayList<>();
    /** The application's list, as last given — or as a committed drop predicts it will be. Guarded by the board. */
    final List<T> items = new ArrayList<>();
    /** Whether cards can be dragged from this deck and into it. */
    volatile boolean reorderable;
    private final Gui gui;
    private final WidgetName name;
    /** Whether {@link #slots} has changed order since it was last written to {@link #inner}. Guarded by the board. */
    boolean orderDirty;
    /** Whether the deck has been given a list before. Its first is shown as it stands, not animated in. */
    boolean shown;
    /** The offset this paint is scrolling the deck to, or NaN when it is not being scrolled. Guarded by the board. */
    float scrollWriteX = Float.NaN;
    float scrollWriteY = Float.NaN;

    Deck(Board<T> board, Gui gui) {
        this.board = board;
        // The board opens and closes the gaps here and carries the cards itself, so the window's motion keeps out of it
        // (Node.childMotion): two clocks on one card put it in two places (MainFrame's Workspaces editor).
        this.inner = gui.column().width(Length.FILL).height(Length.AUTO).childMotion(false);
        // The cards scroll in a Scroller with a bar beside it; the board works in the Scroller's box.
        Scrollbars scrolling = Scrollbars.vertical(gui, inner);
        this.node = scrolling.scroller().node();
        this.box = scrolling.node().width(Length.FILL).height(Length.FILL);
        this.gui = gui;
        this.name = new WidgetName(gui, "deck", box);
    }

    /** The deck's box, with its scrollbar beside it. */
    public Node node() {
        return box;
    }

    /** The box the cards are seen through: the Scroller's node, narrower than {@link #node()} by the bar. */
    Node view() {
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
     * Let this deck's cards be dragged, and cards of the same board be dropped into it. Each landing is announced on
     * {@link #PLACED}.
     *
     * <p>A {@link Placement} here is {@code BEFORE} or {@code AFTER} a card in this deck, never one being carried,
     * and {@code INTO} the root for a deck with no cards in it. Every slot of a reorderable deck is offered but the
     * one the card came from, which is drawn as the card going home: decided by the board in the frame, from what it
     * can see, never by asking the application. A placement only the model can refuse is refused when the
     * application hears it, by handing the deck the list it had.
     *
     * <p>Alt+Up and Alt+Down move a focused card within its deck, and Alt+Left and Alt+Right to the neighbouring
     * deck, through the same placements and the same announcement.
     */
    public Deck<T> reorderable() {
        this.reorderable = true;
        return this;
    }

    /**
     * Call this deck {@code name} in its GUI, so its address ({@link #address()}) is the GUI's name and this. Unique
     * among the GUI's named widgets. Until named, a deck is called {@code deck-<id>}.
     */
    public Deck<T> name(String name) {
        this.name.name(name);
        return this;
    }

    /** What this deck is called in its GUI. */
    public String name() {
        return name.name();
    }

    /** Where this deck is on the bus: what its {@link #PLACED} carries. */
    public String address() {
        return name.address();
    }

    /** Say that {@code item} was put at {@code where} in this deck. */
    void announce(T item, Placement<T> where) {
        gui.bus().publish(PLACED, new Placed(address(), item, where, DropEffect.MOVE));
    }
}
