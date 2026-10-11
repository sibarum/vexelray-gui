package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.drop.DragState;
import dev.vexelray.gui.core.drop.DragWatch;
import dev.vexelray.gui.core.drop.Drop;
import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.drop.Payload;
import dev.vexelray.gui.core.drop.PayloadType;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.draw.Picture;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Cards the user can pick up, carry and put down — within a stack, and between the stacks of one board.
 *
 * <pre>{@code
 * Board<Task> board = new Board<Task>(gui, task -> card(task))   // what a card looks like: any node
 *         .motion(motion.arrival);                              // how long moving takes; none, and it is instant
 * Deck<Task> todo  = board.column().items(model.todo()).reorderable(...);
 * Deck<Task> doing = board.column().items(model.doing()).reorderable(...);
 * gui.dropHistory(history);                                     // until there is one, nothing commits
 * }</pre>
 *
 * <p>The application says what a card looks like and what putting one somewhere means. The board does the rest:
 * the press that becomes a drag, the card lifting off the deck and following the hand, the deck opening a gap where
 * it would land, the drop, and the card settling into place; or Escape, and the card flying back to the gap it
 * left. Undo puts it back the same way, because undo is the application handing a deck its old list.
 *
 * <h2>Nothing jumps</h2>
 *
 * <p>That is the requirement, and the design is what it takes to hold it everywhere rather than in the cases
 * somebody thought of. Every card is in a <b>slot</b> — a box in a deck whose height can be animated — or in
 * <b>flight</b>, floating over the window. Every change is one of four things done to those: a slot opens, a slot
 * closes, a card flies from where it is drawn to a slot, a card fades. A drag is a flight that follows the pointer;
 * a drop is a flight to the gap the drag opened; a cancel is a flight to the gap the card left; a card arriving from
 * nowhere opens a slot around itself; a card leaving fades while its slot closes; undo is any of these. A card is
 * only ever swapped between a slot and the window when the two are drawn in the same place at the same size, so the
 * swap is never seen; and every animation starts from where things are <em>drawn</em>, so an interrupted one turns
 * rather than restarts.
 *
 * <h2>What the board owns, and what it does not</h2>
 *
 * <p>A card's node is the application's, built once per item by the factory and kept while the item is on the
 * board — it is the same node that rests in a deck, follows the pointer and lands in another deck, so its state
 * survives the trip. Two things on it are the board's: its <b>elevation</b>, which is how a card shows it has been
 * picked up (it rests at {@link #resting} and is carried at {@link Relief#OVERLAY}), and the gap the board leaves
 * around it ({@link #spacing}). The board draws the rest of its look itself: a hairline in the theme's accent round
 * a carried card, and a faint pad of the same accent where it would land.
 *
 * <h2>Deliberately not here</h2>
 *
 * <p>Scrolling a deck by holding a card near its edge: the wheel works during a drag, and an edge that scrolls on
 * hover is the convention {@code docs/reference/transfer.md} §10 declines to inherit without a decision. Carrying
 * several cards at once, which {@code Payload} can express and nothing has asked for. And layouts other than a
 * column — a row, a grid, free placement — which are a deck that measures its slots differently; the flights, the
 * gaps and the drag are this class's and do not change.
 */
public final class Board<T> {

    /** How visible the hairline round a carried card is, at full lift. */
    private static final float RIM_ALPHA = 0.6f;

    /** How thick it is, px. */
    private static final float RIM_PX = 1.5f;

    /** The landing pad: a wash of the accent, and an edge of it. */
    private static final float PAD_FILL_ALPHA = 0.07f;
    private static final float PAD_EDGE_ALPHA = 0.38f;

    private final Gui gui;
    private final Function<? super T, ? extends Node> faces;
    private final PayloadType<T> itemType = PayloadType.of("board-card");
    final List<Deck<T>> decks = new CopyOnWriteArrayList<>();

    /** Every card the board has built, by item. Guarded by this. */
    private final Map<T, Card<T>> cards = new HashMap<>();

    /** Cards out of any deck: carried, landing, or fading. Guarded by this. */
    private final List<Flight<T>> flights = new ArrayList<>();

    /** Animations waiting for the lock to be released before they start. See {@link #flush}. */
    private final List<Runnable> starts = new ArrayList<>();

    /** The one watch every deck registers, so a frame is handled once however many decks hear about it. */
    private final DragWatch watch = new DragWatch() {
        @Override
        public void moved(DragState now) {
            Board.this.moved(now);
        }

        @Override
        public void ended(DragState last, DropEffect performed) {
            Board.this.ended(performed);
        }

        @Override
        public void armed(float pressX, float pressY, float x, float y) {
            Board.this.armed(pressX, pressY, x, y);
        }

        @Override
        public void disarmed() {
            Board.this.disarmed();
        }
    };

    /**
     * How much of the pointer's travel a pressed card follows before it comes loose. Less than all of it, so the
     * card visibly resists — it is held in its lane, and being pulled — and more than none, so the hand can see it
     * has hold of it. The moment it comes loose it catches up the rest, which is the transition.
     */
    private static final float PULL = 0.35f;

    /** The card pressed and not yet loose, or null. Guarded by this. */
    private Card<T> armedCard;

    private volatile Ramp motion;
    private volatile Length spacing = Length.rem(0.3125f);
    private volatile int restRung = Relief.CONTROL;

    /** The drag this board began and has not finished with, or null. Guarded by this. */
    private Held<T> held;

    /** The frame last handled, so the same state shown to several decks is acted on once. Guarded by this. */
    private DragState lastMoved;

    private long tokens;

    /**
     * A board whose cards look like whatever {@code faces} builds for each item.
     *
     * <p>The factory is called once per item, when the item first appears in a deck, and the node is removed when
     * the item leaves the board. Give the node its own background and corners; a card's width is the deck's.
     */
    public Board(Gui gui, Function<? super T, ? extends Node> faces) {
        this.gui = Objects.requireNonNull(gui, "gui");
        this.faces = Objects.requireNonNull(faces, "faces");
    }

    /**
     * Time every movement on {@code ramp}: lifting, landing, gaps opening and closing, cards arriving and leaving.
     *
     * <p>Give it the application's arrival ramp — eased out, so a card decelerates into its slot rather than
     * stopping dead in it. Without one everything moves instantly, which is the reduced-motion path and is still
     * correct: the card still follows the pointer and the gap still opens, they just do not take time to.
     */
    public Board<T> motion(Ramp ramp) {
        this.motion = ramp;
        return this;
    }

    /**
     * The space the board keeps above and below every card, so two cards stand twice this apart. It belongs to
     * the card rather than to the deck so that a gap opened for a card is exactly the card's own height, and the
     * card lands in it without anything moving.
     */
    public Board<T> spacing(Length aroundEachCard) {
        this.spacing = Objects.requireNonNull(aroundEachCard, "aroundEachCard");
        return this;
    }

    /** The elevation rung a card rests at in its deck; it is carried at {@link Relief#OVERLAY}. */
    public Board<T> resting(int rung) {
        this.restRung = rung;
        return this;
    }

    /** A new deck on this board: a column of cards. Cards move between any two decks of one board. */
    public Deck<T> column() {
        Deck<T> deck = new Deck<>(this, gui);
        decks.add(deck);
        gui.onDragSource(deck.node, (x, y) -> payloadAt(deck, x, y));
        gui.onDrop(deck.node, (payload, x, y) -> resolve(deck, payload, x, y));
        gui.watchDrag(deck.node, watch);
        return deck;
    }

    /**
     * The form this board offers its cards as. Private to the board, so two boards do not take each other's
     * cards; another widget that should accept a card asks for this.
     */
    public PayloadType<T> itemType() {
        return itemType;
    }

    /** The node built for {@code item}, or null if it is on no deck of this board. */
    public synchronized Node cardOf(T item) {
        Card<T> card = cards.get(item);
        return card == null ? null : card.face;
    }

    /**
     * Whether everything is where it belongs: nothing carried, nothing flying, no gap open and no slot growing or
     * shrinking. For a test, or a script waiting to photograph the board at rest.
     */
    public synchronized boolean settled() {
        if (held != null || armedCard != null || !flights.isEmpty()) {
            return false;
        }
        for (Deck<T> deck : decks) {
            for (Slot<T> slot : deck.slots) {
                if (slot.card == null || slot.leaving || slot.open.running) {
                    return false;
                }
            }
        }
        for (Card<T> card : cards.values()) {
            if (card.lift.running || card.press.running || card.give.running || card.hold.running) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------- the model

    /** A card: the item, the node the application built for it, and the carrier the board moves it in. */
    static final class Card<T> {
        final T item;
        /** The board's: padded by the spacing, focusable, and what flies. */
        final Node carrier;
        /** The application's. The board writes only its elevation. */
        final Node face;
        final Tween lift;
        /** Where it rests, or null while it flies. */
        Slot<T> slot;
        /** Its flight, or null while it rests. */
        Flight<T> flight;
        /** Its height the last time it was measured, px — for when it has not been laid out where it is now. */
        float h;
        /** Whether anything of a lift is painted on it that has to be cleared. */
        boolean liftPainted;
        /**
         * The least the carried outline shows while the card is up, whatever the lift: 1 when the focus ring was
         * showing on it at the moment it was picked up. The outline replaces the ring for the length of the carry,
         * and a ring that blinked out for the outline to fade in would be a flash on every drag of a focused card.
         */
        float rimFloor;
        /** Whether the board has taken the focus ring off the face for the carry, and owes it back. */
        boolean ringOff;
        /** 0 at rest .. 1 pressed into its lane: held, and not yet come loose. */
        final Tween press;
        /** 1 gripped (the button is down) .. 0 hanging from the pointer (latched, the hand let go). */
        final Tween hold;
        /**
         * How much of {@link #pullX}/{@link #pullY} is drawn: 1 while held before the threshold, easing to 0 when
         * the press turns out to be a click and the card springs back.
         */
        final Tween give;
        /** How far the held card has been pulled toward the pointer before coming loose, px. */
        float pullX;
        float pullY;
        /** Whether a pull is drawn on it that has to be cleared. */
        boolean pullPainted;

        Card(Board<T> board, T item, Node carrier, Node face) {
            this.item = item;
            this.carrier = carrier;
            this.face = face;
            this.lift = new Tween(board, 0f);
            this.press = new Tween(board, 0f);
            this.hold = new Tween(board, 1f);
            this.give = new Tween(board, 0f);
        }
    }

    /** A lane's view being put back where it was when the drag began, on the same clock as the card going home. */
    record ScrollBack<T>(Deck<T> deck, float fromX, float fromY, float toX, float toY) {
    }

    /** A place in a deck: a card resting, a card arriving or leaving, or an empty gap opening or closing. */
    static final class Slot<T> {
        final Deck<T> deck;
        final Node box;
        /** The landing pad: shown in a gap a drag opened, never under a card. */
        final Node pad;
        /** 0 closed .. 1 open: a gap's height over its {@link #full}, or a card's over the card's own. */
        final Tween open;
        Card<T> card;
        /** A card on its way out: the slot closes and the card fades with it. */
        boolean leaving;
        /** Shows the landing pad. */
        boolean marked;
        /** A gap's height when fully open, px. */
        float full;
        /** What was last written to the box, so a slot at rest costs no writes. */
        float paintedPx = Float.NaN;
        float paintedPad = Float.NaN;

        Slot(Board<T> board, Deck<T> deck, Node box, Node pad, float open) {
            this.deck = deck;
            this.box = box;
            this.pad = pad;
            this.open = new Tween(board, open);
        }

        /** A card at rest, sized by its content rather than by the board. */
        boolean natural() {
            return card != null && !leaving && !open.running && open.value == 1f;
        }
    }

    /** A card out of every deck, floating over the window. */
    static final class Flight<T> {
        final Card<T> card;
        final Tween travel;
        final Tween fade;
        /** Where it is drawn now: the carrier's top-left and width, px. */
        float x;
        float y;
        float w;
        /** Where the current travel began. */
        float fromX;
        float fromY;
        float fromW;
        /** The slot it is landing in, or null — carried, or fading out. */
        Slot<T> to;
        /** Following the pointer. */
        boolean held;
        /** The lanes scrolling back with it, on a cancel. Empty otherwise. */
        List<ScrollBack<T>> scrollBacks = List.of();
        /** The pointer riding home on it at the grip, on a cancel; null otherwise, or once the hand took it back. */
        dev.vexelray.gui.core.input.PointerRide ride;
        float gripX;
        float gripY;

        Flight(Board<T> board, Card<T> card, float x, float y, float w, float opacity) {
            this.card = card;
            this.x = x;
            this.y = y;
            this.w = w;
            this.travel = new Tween(board, 0f);
            this.fade = new Tween(board, opacity);
        }
    }

    /** The drag this board began. */
    static final class Held<T> {
        final long token;
        final Card<T> card;
        final Deck<T> home;
        /** The card that came after it when it was picked up, or null if it was the last. */
        final T homeBefore;
        final float pressX;
        final float pressY;
        /** Where on the card it was taken hold of, px from the carrier's top-left. */
        final float grabX;
        final float grabY;
        /** The card's height, which every gap it opens has. */
        final float h;
        final Tween catchUp;
        /** How far the card was from the pointer's grip when it was lifted, which {@link #catchUp} takes away. */
        float offX;
        float offY;
        float px;
        float py;
        boolean lifted;
        Flight<T> flight;
        /** The gap the drag is holding open, or null over something that is not this board. */
        Slot<T> gap;
        /** The last placement a deck accepted, and the indicator that identifies it. */
        Deck<T> aimDeck;
        Placement<T> aimAt;
        Rect aimRect;
        /** Where the release committed to, once the drop's change has been performed. */
        Deck<T> landDeck;
        Placement<T> landAt;
        /** Where every lane was looking when the card came loose, so a cancel can put them all back. */
        Map<Deck<T>, float[]> scrolledAt = Map.of();

        Held(Board<T> board, long token, Card<T> card, Deck<T> home, T homeBefore, float pressX, float pressY,
             Rect at) {
            this.token = token;
            this.card = card;
            this.home = home;
            this.homeBefore = homeBefore;
            this.pressX = pressX;
            this.pressY = pressY;
            this.grabX = pressX - at.x();
            this.grabY = pressY - at.y();
            this.h = at.h();
            this.catchUp = new Tween(board, 0f);
        }
    }

    /**
     * A number heading somewhere on the board's ramp, from wherever it is now.
     *
     * <p>Retargeting mid-way starts the next leg from the current value rather than from either end, which is what
     * keeps an interrupted motion continuous; the epoch is what lets a superseded ramp find out it lost and stop
     * writing, rather than two ramps taking turns at one value.
     */
    static final class Tween {
        private final Board<?> board;
        float from;
        float to;
        float value;
        boolean running;
        int epoch;

        Tween(Board<?> board, float value) {
            this.board = board;
            this.from = value;
            this.to = value;
            this.value = value;
        }

        /** Head for {@code target}. Started once the board's lock is released. */
        void toward(float target) {
            if (target == to && (running || value == target)) {
                return;
            }
            from = value;
            to = target;
            running = true;
            int e = ++epoch;
            Ramp ramp = board.motion;
            board.starts.add(() -> board.drive(this, e, ramp));
        }

        /** Be {@code v} now, abandoning any leg in progress. */
        void snap(float v) {
            epoch++;
            running = false;
            from = v;
            to = v;
            value = v;
        }
    }

    private void drive(Tween tween, int epoch, Ramp ramp) {
        if (ramp == null) {
            step(tween, epoch, 1d);
            finish(tween, epoch);
            return;
        }
        ramp.run(p -> step(tween, epoch, p), () -> finish(tween, epoch));
    }

    private void step(Tween tween, int epoch, double p) {
        synchronized (this) {
            if (tween.epoch != epoch) {
                return;
            }
            tween.value = tween.from + (tween.to - tween.from) * (float) p;
            gui.batch(this::paint);
        }
    }

    private void finish(Tween tween, int epoch) {
        synchronized (this) {
            if (tween.epoch != epoch) {
                return;
            }
            tween.value = tween.to;
            tween.running = false;
            gui.batch(() -> {
                tidy();
                paint();
            });
        }
        flush();
    }

    /**
     * Start the animations queued while the lock was held.
     *
     * <p>Never under the lock: a ramp may call back synchronously, and the clock that drives it has a lock of its
     * own that it holds while calling back — so starting one from inside this lock is two locks taken in opposite
     * orders on two threads, which is a deadlock waiting for the right frame.
     */
    private void flush() {
        while (true) {
            List<Runnable> run;
            synchronized (this) {
                if (starts.isEmpty()) {
                    return;
                }
                run = new ArrayList<>(starts);
                starts.clear();
            }
            for (Runnable r : run) {
                r.run();
            }
        }
    }

    /** Change the model and repaint, as one group of edits that lands in one frame, then start what it set moving. */
    private void act(Runnable change) {
        synchronized (this) {
            gui.batch(() -> {
                change.run();
                paint();
            });
        }
        flush();
    }

    // ---------------------------------------------------------------------------------------- the lists

    void show(Deck<T> deck, List<T> items) {
        act(() -> {
            deck.items.clear();
            for (T item : items) {
                if (!deck.items.contains(item)) {
                    deck.items.add(item);   // an item shown twice keeps its first place
                }
            }
            reconcileAll();
            deck.shown = true;
        });
    }

    synchronized List<T> itemsOf(Deck<T> deck) {
        return List.copyOf(deck.items);
    }

    /** Bring every deck into line with its list. */
    private void reconcileAll() {
        for (Deck<T> deck : decks) {
            reconcile(deck);
        }
    }

    /**
     * Move whatever {@code deck} shows into line with what its list says.
     *
     * <p>The cards that keep their places are the longest run already in the list's order; everything else is
     * moved, which for the ordinary case — one card taken out of order — moves exactly that one. Each card that
     * has to go somewhere gets a gap opened for it just before the next card that is staying, and flies there from
     * wherever it is drawn: another deck, a slot it is fading out of, a flight already heading somewhere else. A
     * card the board has never built grows its slot instead. A card the list no longer has fades while its slot
     * closes — and if another deck wants it before it has gone, it flies there from the middle of the fade.
     *
     * <p>A card being carried is nobody's to place until it is put down, whatever the lists say meanwhile.
     */
    private void reconcile(Deck<T> deck) {
        T carried = held != null && held.lifted ? held.card.item : null;
        List<T> want = new ArrayList<>(deck.items);
        if (carried != null) {
            want.remove(carried);
        }

        // Out: cards this deck shows that its list does not have.
        for (Slot<T> slot : deck.slots) {
            if (slot.card != null && !slot.leaving && !want.contains(slot.card.item)) {
                slot.leaving = true;
                slot.open.toward(0f);
            }
        }
        for (Flight<T> flight : flights) {
            if (flight.to != null && flight.to.deck == deck && !want.contains(flight.card.item)) {
                flight.to = null;          // it was coming here and is wanted nowhere: it fades where it is
                flight.fade.toward(0f);
            }
        }

        // Where the wanted cards are now, in the order this deck draws them.
        List<T> placed = new ArrayList<>();
        for (Slot<T> slot : deck.slots) {
            T item = slot.card != null && !slot.leaving ? slot.card.item : landingIn(slot);
            if (item != null && want.contains(item)) {
                placed.add(item);
            }
        }
        java.util.Set<T> keep = longestInOrder(placed, want);

        // In: everything wanted that is not already where it belongs.
        for (int i = 0; i < want.size(); i++) {
            T item = want.get(i);
            if (keep.contains(item)) {
                continue;
            }
            T before = null;
            for (int j = i + 1; j < want.size(); j++) {
                if (keep.contains(want.get(j))) {
                    before = want.get(j);
                    break;
                }
            }
            Slot<T> gap = insertGap(deck, before);
            Card<T> card = cards.get(item);
            if (card == null) {
                arrive(gap, build(item));
            } else {
                send(card, gap);
            }
        }
    }

    /** The item flying into {@code slot}, or null. */
    private T landingIn(Slot<T> slot) {
        for (Flight<T> flight : flights) {
            if (flight.to == slot) {
                return flight.card.item;
            }
        }
        return null;
    }

    /** The items of {@code placed} that form the longest run already in {@code want}'s order. */
    private static <T> java.util.Set<T> longestInOrder(List<T> placed, List<T> want) {
        int n = placed.size();
        int[] rank = new int[n];
        for (int i = 0; i < n; i++) {
            rank[i] = want.indexOf(placed.get(i));
        }
        int[] length = new int[n];
        int[] prev = new int[n];
        int best = -1;
        for (int i = 0; i < n; i++) {
            length[i] = 1;
            prev[i] = -1;
            for (int j = 0; j < i; j++) {
                if (rank[j] < rank[i] && length[j] + 1 > length[i]) {
                    length[i] = length[j] + 1;
                    prev[i] = j;
                }
            }
            if (best < 0 || length[i] > length[best]) {
                best = i;
            }
        }
        java.util.Set<T> keep = new java.util.HashSet<>();
        for (int i = best; i >= 0; i = prev[i]) {
            keep.add(placed.get(i));
        }
        return keep;
    }

    /** Build the card for {@code item}: the application's node, in a carrier the board can move. */
    private Card<T> build(T item) {
        Node face = faces.apply(item);
        Node carrier = gui.column().width(Length.FILL).height(Length.AUTO)
                .padding(spacing, Length.ZERO).children(face);
        Card<T> card = new Card<>(this, item, carrier, face);
        face.elevation(elevation(0f));
        // The keyboard's way to the same placements a drag reaches, through the same question and the same history.
        // On the face, which is the node the user sees: the focus ring is drawn round the focused node's own box
        // and corners, and round the carrier it would be a square frame standing off the card by its spacing.
        gui.focusable(face, true);
        gui.claim(face, Shortcut.of(Key.UP, Modifier.ALT), ClaimScope.FOCUSED, () -> nudge(card, 0, -1));
        gui.claim(face, Shortcut.of(Key.DOWN, Modifier.ALT), ClaimScope.FOCUSED, () -> nudge(card, 0, 1));
        gui.claim(face, Shortcut.of(Key.LEFT, Modifier.ALT), ClaimScope.FOCUSED, () -> nudge(card, -1, 0));
        gui.claim(face, Shortcut.of(Key.RIGHT, Modifier.ALT), ClaimScope.FOCUSED, () -> nudge(card, 1, 0));
        // An open hand over a card: it can be picked up. Hover changes nothing on screen but the pointer, which is
        // the one thing that may. A button on the card keeps its own pointer — the nearer node decides.
        gui.cursor(face, dev.vexelray.gui.core.input.CursorShape.GRAB);
        cards.put(item, card);
        return card;
    }

    /** A new card grows its slot from nothing, fading in as it does — at once, for a deck's first list. */
    private void arrive(Slot<T> slot, Card<T> card) {
        slot.card = card;
        card.slot = slot;
        slot.box.append(card.carrier);
        if (slot.deck.shown) {
            slot.open.toward(1f);
        } else {
            // A deck's first list is its starting state, not a change to it — whether it arrives before the first
            // frame or after a load. Growing every card in on the way up is a window that cannot sit still.
            slot.open.snap(1f);
        }
    }

    /**
     * Send {@code card} to {@code gap}, from wherever it is drawn.
     *
     * <p>A card in a slot leaves it as a gap of the height it was drawn at, closing, and takes off from exactly
     * where it was — so neither the deck it left nor the card moves in the frame it happens. A card already flying
     * turns toward the new slot from where it is. A card that has never been laid out has nowhere to take off from,
     * and simply arrives.
     */
    private void send(Card<T> card, Slot<T> gap) {
        Flight<T> flight = card.flight;
        if (flight == null) {
            Slot<T> from = card.slot;
            NodeLayout at = card.carrier.layout();
            if (!at.present()) {
                if (from != null) {
                    detach(from);
                }
                arrive(gap, card);
                return;
            }
            float opacity = from != null && from.leaving ? from.open.value : 1f;
            if (from != null) {
                detach(from);
            }
            Rect r = at.rect();
            flight = new Flight<>(this, card, r.x(), r.y(), r.w(), opacity);
            takeOff(card, flight);
        }
        land(flight, gap);
        flight.fade.toward(1f);
    }

    /** Empty {@code slot} of its card, leaving a gap exactly as tall as it is drawn, which then closes. */
    private void detach(Slot<T> slot) {
        float drawn = slotPx(slot);
        slot.card.slot = null;
        slot.card = null;
        slot.leaving = false;
        slot.marked = false;
        slot.full = drawn;
        slot.open.snap(drawn > 0f ? 1f : 0f);
        slot.open.toward(0f);
    }

    /** Lift {@code card}'s carrier out of its deck and over the window, drawn where it already was. */
    private void takeOff(Card<T> card, Flight<T> flight) {
        card.flight = flight;
        flights.add(flight);
        gui.root().append(card.carrier);
        card.carrier.hitInert(true);
    }

    /** Head {@code flight} for {@code gap}, from where it is drawn now. */
    private void land(Flight<T> flight, Slot<T> gap) {
        flight.held = false;
        flight.to = gap;
        flight.fromX = flight.x;
        flight.fromY = flight.y;
        flight.fromW = flight.w;
        gap.full = cardPx(flight.card);
        flight.travel.snap(0f);
        flight.travel.toward(1f);
        flight.card.lift.toward(0f);
        flight.card.rimFloor = 0f;   // the outline sets down with the card
        aimGaps();
    }

    /**
     * Put a landed card into its slot. Called only when the two are drawn identically — the travel finished, the
     * gap fully open at the card's own height — so this is a change of parent that nobody can see.
     */
    private void settle(Flight<T> flight) {
        Slot<T> slot = flight.to;
        Card<T> card = flight.card;
        flights.remove(flight);
        card.flight = null;
        card.slot = slot;
        slot.card = card;
        slot.marked = false;
        slot.leaving = false;
        slot.box.append(card.carrier);
        card.carrier.unfloat().width(Length.FILL).hitInert(false).opacity(1f);
        slot.paintedPx = Float.NaN;
        if (flight.ride != null) {
            flight.ride.end();   // home, and the pointer with it
            flight.ride = null;
        }
        flight.scrollBacks = List.of();
    }

    // ------------------------------------------------------------------------------------------- the gaps

    /** The cards a deck is showing and keeping — what its slots are measured over. */
    private static <T> List<Slot<T>> standing(Deck<T> deck) {
        List<Slot<T>> out = new ArrayList<>();
        for (Slot<T> slot : deck.slots) {
            if (slot.card != null && !slot.leaving) {
                out.add(slot);
            }
        }
        return out;
    }

    /** Where the {@code k}th place among {@code items} is, said relative to a card rather than as a count. */
    private static <T> Placement<T> placementIn(List<T> items, int k) {
        if (k < items.size()) {
            return Placement.before(items.get(k));
        }
        return items.isEmpty() ? Placement.intoRoot() : Placement.after(items.get(items.size() - 1));
    }

    private static <T> List<T> itemsOf(List<Slot<T>> slots) {
        List<T> out = new ArrayList<>(slots.size());
        for (Slot<T> slot : slots) {
            out.add(slot.card.item);
        }
        return out;
    }

    /** A new, closed gap just before {@code before}'s place in {@code deck}, or at its end. */
    private Slot<T> insertGap(Deck<T> deck, T before) {
        int at = deck.slots.size();
        if (before != null) {
            for (int i = 0; i < deck.slots.size(); i++) {
                Slot<T> slot = deck.slots.get(i);
                T here = slot.card != null && !slot.leaving ? slot.card.item : landingIn(slot);
                if (before.equals(here)) {
                    at = i;
                    break;
                }
            }
        }
        return newSlot(deck, at);
    }

    private Slot<T> newSlot(Deck<T> deck, int at) {
        Node pad = gui.box().width(Length.FILL).height(Length.FILL).hitInert(true).visible(false);
        Node box = gui.column().width(Length.FILL).height(Length.ZERO).clip(true).children(pad);
        Slot<T> slot = new Slot<>(this, deck, box, pad, 0f);
        deck.slots.add(at, slot);
        deck.orderDirty = true;
        return slot;
    }

    /**
     * The gap at {@code where} in {@code deck}: one already there if one is — still open from a moment ago, or
     * closing and about to be wanted again — or a new one, closed.
     *
     * <p>Reusing the gap that is already there is what makes a pointer that wanders off a slot and back reopen the
     * same gap from wherever it had closed to, instead of a second gap opening beside a first one closing.
     */
    private Slot<T> gapAt(Deck<T> deck, Placement<T> where) {
        List<Slot<T>> slots = deck.slots;
        int lo = -1;                 // the card the gap comes after
        int hi = slots.size();       // the card it comes before
        T ref = where.reference();
        int refAt = -1;
        if (ref != null) {
            for (int i = 0; i < slots.size(); i++) {
                Slot<T> slot = slots.get(i);
                if (slot.card != null && !slot.leaving && ref.equals(slot.card.item)) {
                    refAt = i;
                    break;
                }
            }
        }
        if (refAt >= 0 && where.relation() == Placement.Relation.BEFORE) {
            hi = refAt;
            for (int i = refAt - 1; i >= 0; i--) {
                if (isStanding(slots.get(i))) {
                    lo = i;
                    break;
                }
            }
        } else if (refAt >= 0) {
            lo = refAt;
            for (int i = refAt + 1; i < slots.size(); i++) {
                if (isStanding(slots.get(i))) {
                    hi = i;
                    break;
                }
            }
        } else {
            for (int i = slots.size() - 1; i >= 0; i--) {
                if (isStanding(slots.get(i))) {
                    lo = i;
                    break;
                }
            }
        }
        for (int i = hi - 1; i > lo; i--) {
            if (slots.get(i).card == null) {
                return slots.get(i);
            }
        }
        return newSlot(deck, hi);
    }

    private static boolean isStanding(Slot<?> slot) {
        return slot.card != null && !slot.leaving;
    }

    /**
     * Open the gaps something is heading for, and close every other.
     *
     * <p>A gap is wanted by the drag holding it open, or by a card flying into it; everything else closes, from
     * however far open it is.
     */
    private void aimGaps() {
        for (Deck<T> deck : decks) {
            for (Slot<T> slot : deck.slots) {
                if (slot.card != null) {
                    continue;
                }
                boolean wanted = held != null && held.gap == slot;
                if (wanted) {
                    slot.full = held.h;
                }
                for (Flight<T> flight : flights) {
                    if (flight.to == slot) {
                        wanted = true;
                        break;
                    }
                }
                slot.open.toward(wanted ? 1f : 0f);
            }
        }
    }

    /**
     * Put away what has finished: cards that have landed go into their slots, gaps that have closed and slots that
     * have emptied are removed, cards that have faded out of the board are forgotten.
     */
    private void tidy() {
        boolean settled = false;
        for (Flight<T> flight : new ArrayList<>(flights)) {
            if (flight.held) {
                continue;
            }
            if (flight.to != null) {
                if (!flight.travel.running && flight.travel.value == 1f
                        && !flight.to.open.running && flight.to.open.value == 1f
                        && !flight.fade.running && flight.fade.value == 1f) {
                    settle(flight);
                    settled = true;
                }
            } else if (!flight.fade.running && flight.fade.value == 0f) {
                flights.remove(flight);
                forget(flight.card);
                flight.card.carrier.remove();
            }
        }
        for (Deck<T> deck : decks) {
            for (int i = deck.slots.size() - 1; i >= 0; i--) {
                Slot<T> slot = deck.slots.get(i);
                if (slot.open.running || slot.open.value > 0f) {
                    continue;
                }
                boolean emptyGap = slot.card == null && (held == null || held.gap != slot) && landingIn(slot) == null;
                if (emptyGap || slot.leaving) {
                    if (slot.leaving) {
                        forget(slot.card);
                    }
                    deck.slots.remove(i);
                    slot.box.remove();
                }
            }
        }
        if (settled) {
            reconcileAll();   // a card that landed somewhere its deck's list does not have is put right now
        }
    }

    /** Drop a card the board no longer shows. Its node goes with whatever removed it. */
    private void forget(Card<T> card) {
        if (card != null && cards.get(card.item) == card) {
            cards.remove(card.item);
        }
    }

    // ------------------------------------------------------------------------------------------- the drag

    /** What a press on {@code deck} picks up: the card under it, or nothing over a gap or the empty space below. */
    private Payload payloadAt(Deck<T> deck, float x, float y) {
        if (deck.reorder == null) {
            return null;
        }
        synchronized (this) {
            List<Slot<T>> slots = deck.slots;
            for (int i = 0; i < slots.size(); i++) {
                Slot<T> slot = slots.get(i);
                if (!slot.natural()) {
                    continue;   // a card arriving or leaving is in motion, and a gap has nothing to pick up
                }
                Rect r = slot.card.carrier.layout().rect();
                if (y >= r.y() && y < r.y() + r.h() && x >= r.x() && x < r.x() + r.w()) {
                    T after = null;
                    for (int j = i + 1; j < slots.size(); j++) {
                        if (isStanding(slots.get(j))) {
                            after = slots.get(j).card.item;
                            break;
                        }
                    }
                    held = new Held<>(this, ++tokens, slot.card, deck, after, x, y, r);
                    lastMoved = null;
                    return Payload.of(itemType, slot.card.item);
                }
            }
            return null;
        }
    }

    /**
     * Where the carried card would land in {@code deck}, as one value carrying the indicator and the change.
     *
     * <p>Measured against the deck with the carried card taken out, so that the slots do not move while the gap
     * between them opens. The slot nearest the carried card's own top edge wins: its middle, if the card were
     * centred on the pointer, but a card is held wherever it was grabbed and it is the card the user is aiming,
     * not the pointer.
     */
    private Drop resolve(Deck<T> deck, Payload payload, float x, float y) {
        Optional<T> offered = payload.as(itemType);
        Reorder<T> reorder = deck.reorder;
        if (offered.isEmpty() || reorder == null) {
            return Drop.NONE;
        }
        T item = offered.get();
        long token;
        Placement<T> where;
        Rect slotRect;
        synchronized (this) {
            Held<T> h = held;
            if (h == null || !h.card.item.equals(item)) {
                return Drop.NONE;   // only a drag this board began has a card to aim
            }
            List<Slot<T>> standing = standing(deck);
            standing.removeIf(s -> s.card == h.card);
            Rect inner = deck.inner.layout().rect();
            float top = y - h.grabY;
            float edge = inner.y();
            int k = 0;
            for (Slot<T> slot : standing) {
                float hk = cardPx(slot.card);
                if (top <= edge + hk / 2f) {
                    break;
                }
                edge += hk;
                k++;
            }
            List<T> items = itemsOf(standing);
            if (deck == h.home && k == homeIndex(h, items)) {
                return Drop.NONE;   // where it came from: not an edit, and drawn as the card going home
            }
            where = placementIn(items, k);
            slotRect = new Rect(inner.x(), edge, inner.w(), h.h);
            token = h.token;
        }
        // Outside the lock: the application's code, which may take locks of its own.
        Change change = reorder.move(item, where, DropEffect.MOVE);
        if (change == null) {
            return Drop.NONE;
        }
        synchronized (this) {
            if (held != null && held.token == token) {
                held.aimDeck = deck;
                held.aimAt = where;
                held.aimRect = slotRect;
            }
        }
        return Drop.move(slotRect, () -> {
            committed(token, deck, where);
            return change.apply();
        });
    }

    /** Where the carried card's own gap is among {@code items}: before the card that followed it, or the end. */
    private static <T> int homeIndex(Held<T> h, List<T> items) {
        int at = h.homeBefore == null ? -1 : items.indexOf(h.homeBefore);
        return at < 0 ? items.size() : at;
    }

    /** The drop's change is being performed: remember where it went, for {@link #ended} to land the card there. */
    private synchronized void committed(long token, Deck<T> deck, Placement<T> where) {
        if (held != null && held.token == token) {
            held.landDeck = deck;
            held.landAt = where;
        }
    }

    /**
     * A frame of a drag: lift the card on the first one, follow the pointer, and open the gap the drop resolved to.
     *
     * <p>The gap comes from the published resolution — the very value the drop would commit — rather than from a
     * second reading of the pointer, so the gap drawn is the place the card would go. Over something that is not
     * this board, every gap closes, because the card would go there; over nothing, the gap it came from opens,
     * because that is where it would go.
     */
    private void moved(DragState now) {
        synchronized (this) {
            if (now == lastMoved) {
                return;
            }
            Held<T> h = held;
            // The same drag as last frame — a hand held still, a card carried and waiting — writes nothing. Every
            // write owes a frame, and a carry repainted every frame for no change is a loop that never parks.
            boolean same = lastMoved != null && now.equals(lastMoved) && h != null && h.lifted;
            lastMoved = now;
            if (h == null || same) {
                return;
            }
            gui.batch(() -> {
                if (!h.lifted) {
                    lift(h, now);
                }
                h.px = now.x();
                h.py = now.y();
                // Gripped while the button is down; hanging from the pointer, a rung lower, once it is up.
                h.card.hold.toward(now.latched() ? 0f : 1f);
                if (now.accepts() && now.indicator().equals(h.aimRect)) {
                    h.gap = gapAt(h.aimDeck, h.aimAt);
                } else if (now.accepts()) {
                    h.gap = null;
                } else {
                    h.gap = gapAt(h.home, homePlacement(h));
                }
                if (h.gap != null) {
                    h.gap.marked = true;
                }
                aimGaps();
                paint();
            });
        }
        flush();
    }

    private Placement<T> homePlacement(Held<T> h) {
        List<T> items = itemsOf(standing(h.home));
        return placementIn(items, homeIndex(h, items));
    }

    /**
     * Take the card out of its deck. Its slot stays exactly as tall, empty — the gap it left — and the card goes
     * over the window at exactly the place it was drawn, so the frame this happens in looks like the one before.
     * Then the card catches up with the pointer, which has already travelled the drag threshold, and rises.
     */
    private void lift(Held<T> h, DragState now) {
        Card<T> card = h.card;
        Slot<T> slot = card.slot;
        Rect r = card.carrier.layout().rect();
        // Where the press had pulled it to, which is where it is drawn: the flight starts there, not at its slot.
        float pulledX = 0f;
        float pulledY = 0f;
        if (armedCard == card) {
            pulledX = card.pullX * card.give.value;
            pulledY = card.pullY * card.give.value;
            card.give.snap(0f);   // the pull is now the flight's offset, written in the same batch
            card.press.toward(0f);
            armedCard = null;
        } else if (armedCard != null) {
            disarm();
        }
        card.h = r.h();
        slot.card = null;
        slot.full = r.h();
        slot.open.snap(1f);
        slot.marked = true;
        card.slot = null;
        Flight<T> flight = new Flight<>(this, card, r.x() + pulledX, r.y() + pulledY, r.w(), 1f);
        flight.held = true;
        h.flight = flight;
        takeOff(card, flight);
        // The rest of the way to the grip, which the card catches up: the transition the user sees as "loose".
        h.offX = h.pressX + pulledX - now.x();
        h.offY = h.pressY + pulledY - now.y();
        h.catchUp.snap(1f);
        h.catchUp.toward(0f);
        card.lift.toward(1f);
        card.hold.snap(1f);
        Map<Deck<T>, float[]> at = new HashMap<>();
        for (Deck<T> deck : decks) {
            NodeLayout l = deck.node.layout();
            at.put(deck, new float[] {l.scrollX(), l.scrollY()});
        }
        h.scrolledAt = at;
        dev.vexelray.gui.core.input.FocusState focus = gui.focusState();
        card.rimFloor = focus.nodeId() == card.face.id() && gui.theme().focusRing().shows(focus.cause(), false)
                ? 1f : 0f;
        h.lifted = true;
    }

    /**
     * The drag is over. A drop into a deck lands the card in the gap it opened; a drop onto something else that
     * moved it lets it fade where it was let go; anything else — Escape, a release over nothing, a copy taken
     * elsewhere — flies it home.
     */
    private void ended(DropEffect performed) {
        synchronized (this) {
            Held<T> h = held;
            if (h == null) {
                return;
            }
            held = null;
            lastMoved = null;
            if (!h.lifted) {
                return;   // never drawn: nothing moved, so nothing has to move back
            }
            gui.batch(() -> {
                Flight<T> flight = h.flight;
                T item = h.card.item;
                if (h.landDeck != null) {
                    predict(item, h.landDeck, h.landAt);
                    Slot<T> gap = gapAt(h.landDeck, h.landAt);
                    gap.marked = true;
                    land(flight, gap);
                    // The thing just moved is the thing now acted on (transfer.md §6). The dispatcher aimed focus
                    // at what was under the release, which is the lane — the card flies pointer-transparent — so
                    // it is put right here, and Alt+arrows go on from the card that was just put down.
                    gui.focus(flight.card.face);
                } else if (performed == DropEffect.MOVE) {
                    for (Deck<T> deck : decks) {
                        deck.items.remove(item);   // taken by something that is not this board
                    }
                    flight.held = false;
                    flight.to = null;
                    flight.card.lift.toward(0f);
                    flight.card.rimFloor = 0f;
                    flight.fade.toward(0f);
                } else {
                    Slot<T> gap = gapAt(h.home, homePlacement(h));
                    gap.marked = true;
                    land(flight, gap);
                    goBack(h, flight);
                }
                aimGaps();
                reconcileAll();
                paint();
            });
        }
        flush();
    }

    /**
     * Undo the whole gesture, not only the card's part of it: every lane the wheel moved while the card was carried
     * scrolls back to where it was looking when the card came loose, and the pointer rides home on the card at the
     * point it was gripped — all of it on the card's own travel, a step per frame, so the card arrives in a slot that
     * has stopped moving and the pointer ends where the drag began. The card is focused, which is where the drag
     * began too.
     */
    private void goBack(Held<T> h, Flight<T> flight) {
        List<ScrollBack<T>> back = new ArrayList<>();
        for (Map.Entry<Deck<T>, float[]> e : h.scrolledAt.entrySet()) {
            NodeLayout now = e.getKey().node.layout();
            float[] was = e.getValue();
            if (now.scrollX() != was[0] || now.scrollY() != was[1]) {
                back.add(new ScrollBack<>(e.getKey(), now.scrollX(), now.scrollY(), was[0], was[1]));
            }
        }
        flight.scrollBacks = back;
        flight.gripX = h.grabX;
        flight.gripY = h.grabY;
        flight.ride = gui.ridePointer();
        gui.focus(flight.card.face);
    }

    /**
     * A press is held on a card and has not come loose: the card sinks into its lane and follows the pointer part of
     * the way, held back. Called every frame of it, so the pull tracks the hand exactly.
     */
    private void armed(float pressX, float pressY, float x, float y) {
        synchronized (this) {
            if (held != null && held.lifted) {
                return;
            }
            Card<T> card = armedCard;
            if (card == null) {
                card = cardAt(pressX, pressY);
                if (card == null) {
                    return;   // a press on something that is not one of this board's cards
                }
                armedCard = card;
                card.give.snap(1f);
                card.press.toward(1f);
            }
            float pullX = (x - pressX) * PULL;
            float pullY = (y - pressY) * PULL;
            if (pullX == card.pullX && pullY == card.pullY && armedCard == card && card.give.value == 1f
                    && !card.press.running) {
                return;   // held still: nothing to draw, and a write would owe a frame for nothing
            }
            card.pullX = pullX;
            card.pullY = pullY;
            gui.batch(this::paint);
        }
        flush();
    }

    /** The press came up before it came loose: a click, and the card springs back. */
    private void disarmed() {
        synchronized (this) {
            if (armedCard == null) {
                return;
            }
            gui.batch(() -> {
                disarm();
                paint();
            });
        }
        flush();
    }

    /** The armed card was clicked, not carried: it springs back into its lane and rises back to rest. */
    private void disarm() {
        Card<T> card = armedCard;
        armedCard = null;
        card.give.toward(0f);
        card.press.toward(0f);
    }

    /** The card resting under {@code (x, y)} in a deck cards can be picked up from, or null. */
    private Card<T> cardAt(float x, float y) {
        for (Deck<T> deck : decks) {
            if (deck.reorder == null) {
                continue;
            }
            for (Slot<T> slot : deck.slots) {
                if (!slot.natural()) {
                    continue;
                }
                Rect r = slot.card.carrier.layout().rect();
                if (y >= r.y() && y < r.y() + r.h() && x >= r.x() && x < r.x() + r.w()) {
                    return slot.card;
                }
            }
        }
        return null;
    }

    /**
     * Write a committed placement into the lists, so the decks show it now rather than when the application
     * gets round to saying so. Idempotent: an application that already handed over its new lists from inside the
     * change gets the same lists back. One that disagrees later simply hands over different ones.
     */
    private void predict(T item, Deck<T> to, Placement<T> where) {
        for (Deck<T> deck : decks) {
            deck.items.remove(item);
        }
        int at = to.items.size();
        if (where.reference() != null) {
            int ref = to.items.indexOf(where.reference());
            if (ref >= 0) {
                at = where.relation() == Placement.Relation.BEFORE ? ref : ref + 1;
            }
        }
        to.items.add(at, item);
    }

    /**
     * Move a card from the keyboard: within its deck by one ({@code dIndex}), or to the same place in the
     * neighbouring deck ({@code dDeck}). The same question a drop asks, recorded in the same history, so undo does
     * not care which way the card was moved.
     */
    private void nudge(Card<T> card, int dDeck, int dIndex) {
        Deck<T> target;
        Placement<T> where;
        synchronized (this) {
            if (held != null || card.slot == null || card.slot.leaving) {
                return;   // in motion, or mid-drag: the pointer has it
            }
            Deck<T> from = card.slot.deck;
            List<T> mine = itemsOf(standing(from));
            int i = mine.indexOf(card.item);
            if (dDeck == 0) {
                List<T> others = new ArrayList<>(mine);
                others.remove(card.item);
                int k = i + dIndex;
                if (k < 0 || k > others.size()) {
                    return;
                }
                target = from;
                where = placementIn(others, k);
            } else {
                int d = decks.indexOf(from) + dDeck;
                if (d < 0 || d >= decks.size()) {
                    return;
                }
                target = decks.get(d);
                List<T> theirs = itemsOf(standing(target));
                where = placementIn(theirs, Math.min(i, theirs.size()));
            }
        }
        Reorder<T> reorder = target.reorder;
        History history = gui.dropHistory();
        if (reorder == null || history == null) {
            return;   // the same refusal a drop makes: nothing the user could not take back
        }
        Change change = reorder.move(card.item, where, DropEffect.MOVE);
        if (change == null) {
            return;
        }
        history.perform(change);
        Deck<T> to = target;
        Placement<T> at = where;
        act(() -> {
            predict(card.item, to, at);
            reconcileAll();
        });
    }

    // ------------------------------------------------------------------------------------------ geometry

    /** A card's height, px: as laid out, or as last seen if it has not been laid out where it is now. */
    private float cardPx(Card<T> card) {
        NodeLayout at = card.carrier.layout();
        if (at.present() && at.rect().h() > 0f) {
            card.h = at.rect().h();
        }
        return card.h;
    }

    /** How tall {@code slot} is drawn, px. */
    private float slotPx(Slot<T> slot) {
        if (slot.natural()) {
            NodeLayout at = slot.box.layout();
            return at.present() ? at.rect().h() : cardPx(slot.card);
        }
        float full = slot.card != null ? cardPx(slot.card) : slot.full;
        return full * slot.open.value;
    }

    /** Where {@code slot} is drawn: its deck's column, at the sum of the heights above it. */
    private Rect slotRect(Slot<T> slot) {
        Deck<T> deck = slot.deck;
        Rect inner = deck.inner.layout().rect();
        float x = inner.x();
        float y = inner.y();
        // A lane being scrolled back in this very batch: the layout read is the last frame's, and this frame's offset
        // is the one being written, so the column is where the last frame had it less the scroll it is about to do.
        // Without this the card going home trails its slot by a frame's scroll all the way back.
        if (!Float.isNaN(deck.scrollWriteY)) {
            NodeLayout view = deck.node.layout();
            x += view.scrollX() - deck.scrollWriteX;
            y += view.scrollY() - deck.scrollWriteY;
        }
        for (Slot<T> s : deck.slots) {
            if (s == slot) {
                break;
            }
            y += slotPx(s);
        }
        return new Rect(x, y, inner.w(), slotPx(slot));
    }

    private float dpi() {
        return gui.dpi().value();
    }

    private Length px(float px) {
        return Length.dp(px / dpi());
    }

    /** A card's elevation at rest: what it is built with. */
    private Length elevation(float lift) {
        Relief relief = gui.theme().relief();
        float rest = rungRem(relief, restRung);
        float carried = rungRem(relief, Relief.OVERLAY);
        return Length.rem(rest + (carried - rest) * lift);
    }

    /**
     * Where a card stands off the page for the state it is in. Pressed, it sinks a rung toward its lane — held, and
     * not loose. Lifted, it rises to the top of the ladder while the button grips it, and a rung below that once the
     * hand has let go and it hangs from the pointer. Every state its own depth, and every change between them a
     * tween, so the depth never steps.
     */
    private Length elevation(Card<T> card) {
        Relief relief = gui.theme().relief();
        float rest = rungRem(relief, restRung);
        float pressed = rungRem(relief, restRung - 1);
        float base = rest + (pressed - rest) * card.press.value;
        float hanging = rungRem(relief, Relief.FLOATING);
        float gripped = rungRem(relief, Relief.OVERLAY);
        float carried = hanging + (gripped - hanging) * card.hold.value;
        return Length.rem(base + (carried - base) * card.lift.value);
    }

    private static float rungRem(Relief relief, int rung) {
        int r = Math.clamp(rung, Relief.FLUSH, Relief.OVERLAY);
        return r == Relief.FLUSH ? 0f : relief.baseRem() * (float) Math.pow(relief.ratio(), r - 1);
    }

    // --------------------------------------------------------------------------------------------- paint

    /**
     * Write everything moving to its nodes. Called inside a batch with the lock held, after any change and on every
     * step of every animation, so what lands in a frame is one consistent picture.
     */
    private void paint() {
        Color accent = gui.theme().color(Role.ACCENT);
        // Lanes scrolling back go first, so every slot measured below is measured against the offset this batch
        // writes rather than the one the last frame drew.
        for (Deck<T> deck : decks) {
            deck.scrollWriteX = Float.NaN;
            deck.scrollWriteY = Float.NaN;
        }
        for (Flight<T> flight : flights) {
            if (flight.to == null || flight.scrollBacks.isEmpty()) {
                continue;
            }
            float t = flight.travel.value;
            for (ScrollBack<T> back : flight.scrollBacks) {
                float sx = back.fromX() + (back.toX() - back.fromX()) * t;
                float sy = back.fromY() + (back.toY() - back.fromY()) * t;
                back.deck().node.scrollTo(sx, sy);
                back.deck().scrollWriteX = sx;
                back.deck().scrollWriteY = sy;
            }
        }
        for (Deck<T> deck : decks) {
            if (deck.orderDirty) {
                deck.orderDirty = false;
                for (int i = 0; i < deck.slots.size(); i++) {
                    deck.inner.insert(deck.slots.get(i).box, i);
                }
            }
            for (Slot<T> slot : deck.slots) {
                paintSlot(slot, accent);
            }
        }
        for (Flight<T> flight : flights) {
            paintFlight(flight);
        }
        for (Card<T> card : cards.values()) {
            paintLift(card, accent);
        }
    }

    private void paintSlot(Slot<T> slot, Color accent) {
        if (slot.natural()) {
            if (!Float.isInfinite(slot.paintedPx)) {
                // The pad's inset goes too: a gap that a card has landed in is the card's slot now, and padding
                // left over from the pad would stand the card off by it — every card below moving at once.
                slot.box.height(Length.AUTO).clip(false).padding(Length.ZERO, Length.ZERO);
                slot.pad.visible(false);
                slot.card.carrier.opacity(1f);
                slot.paintedPx = Float.POSITIVE_INFINITY;   // the mark for "sized by its content"
                slot.paintedPad = Float.NaN;
            }
            return;
        }
        float drawn = slotPx(slot);
        if (drawn != slot.paintedPx) {
            slot.box.height(px(drawn)).clip(true);
            slot.paintedPx = drawn;
        }
        float px = drawn;
        if (slot.card != null) {
            slot.card.carrier.opacity(slot.open.value);   // a card arriving or leaving fades with its slot
        }
        float pad = slot.card == null && slot.marked ? slot.open.value : 0f;
        if (pad != slot.paintedPad) {
            slot.paintedPad = pad;
            if (pad <= 0f) {
                slot.pad.visible(false);
                return;
            }
            // Inset as a card is inset by its spacing, so the pad is where the card's face will be — but never by
            // more than half the gap, so a nearly closed gap still closes onto a line rather than inverting.
            float inset = Math.min(spacingPx(), px / 2f);
            float corner = held != null ? held.card.face.layout().cornerTopPx() : 0f;
            slot.box.padding(px(inset), Length.ZERO);
            slot.pad.visible(true)
                    .background(Color.withAlpha(accent, PAD_FILL_ALPHA))
                    .border(Length.dp(1f), Color.withAlpha(accent, PAD_EDGE_ALPHA))
                    .corner(px(corner))
                    .opacity(pad);
        } else if (pad > 0f) {
            slot.box.padding(px(Math.min(spacingPx(), px / 2f)), Length.ZERO);
        }
    }

    private float spacingPx() {
        // The spacing as laid out: where the face sits inside its carrier. Any card will do; they all share it.
        for (Card<T> card : cards.values()) {
            NodeLayout c = card.carrier.layout();
            NodeLayout f = card.face.layout();
            if (c.present() && f.present()) {
                return f.rect().y() - c.rect().y();
            }
        }
        return 0f;
    }

    private void paintFlight(Flight<T> flight) {
        if (flight.held) {
            Held<T> h = held;
            if (h != null && h.flight == flight) {
                flight.x = h.px - h.grabX + h.offX * h.catchUp.value;
                flight.y = h.py - h.grabY + h.offY * h.catchUp.value;
            }
        } else if (flight.to != null) {
            Rect to = slotRect(flight.to);
            float t = flight.travel.value;
            flight.x = flight.fromX + (to.x() - flight.fromX) * t;
            flight.y = flight.fromY + (to.y() - flight.fromY) * t;
            flight.w = flight.fromW + (to.w() - flight.fromW) * t;
            // The hand rides home on the card, at the grip: the same step, so the two cannot come apart. If the
            // hand has moved the pointer itself, the ride has yielded and the card goes on alone.
            if (flight.ride != null && !flight.ride.to(flight.x + flight.gripX, flight.y + flight.gripY)) {
                flight.ride.end();
                flight.ride = null;
            }
        }
        flight.card.carrier.floatAt(px(flight.x), px(flight.y)).width(px(flight.w)).opacity(flight.fade.value);
    }

    /**
     * A pressed card, pulled part of the way toward the pointer: drawn displaced inside its slot, which does not move.
     * A visual offset rather than a layout one, so the lane does not reflow under a press that may yet be a click.
     */
    private void paintPull(Card<T> card) {
        float give = card.give.value;
        if (give <= 0f && !card.pullPainted) {
            return;
        }
        float em = gui.rootEmPx() * gui.zoom().value() * dpi();
        card.carrier.translate(card.pullX * give / em, card.pullY * give / em);
        card.pullPainted = give > 0f;
    }

    /**
     * The carried look: the card stands off the page and is ringed in the accent, both by how far it is lifted.
     *
     * <p>The ring is the board's own outline, and while it shows, the focus ring is off: a carried card that is
     * also focused would otherwise wear two accent outlines a few pixels apart. The focus ring comes back when the
     * card has set down.
     */
    private void paintLift(Card<T> card, Color accent) {
        paintPull(card);
        float lift = card.lift.value;
        float rim = Math.max(lift, card.rimFloor);
        boolean depth = lift > 0f || card.press.value > 0f || card.press.running || card.hold.running;
        if (depth) {
            card.face.elevation(elevation(card));
        }
        if (rim > 0f && !card.ringOff) {
            card.face.focusRing(dev.vexelray.gui.core.style.FocusRing.NONE);
            card.ringOff = true;
        } else if (rim <= 0f && card.ringOff) {
            card.face.focusRing(null);
            card.ringOff = false;
        }
        if (rim <= 0f && !card.liftPainted) {
            return;
        }
        if (!depth) {
            card.face.elevation(elevation(card));   // the last step down, back to rest
        }
        card.liftPainted = rim > 0f;
        if (!card.liftPainted) {
            card.carrier.overlay(null);
            return;
        }
        NodeLayout c = card.carrier.layout();
        NodeLayout f = card.face.layout();
        if (!c.present() || !f.present()) {
            return;
        }
        Rect cr = c.rect();
        Rect fr = f.rect();
        card.carrier.overlay(Picture.of(new Picture.Outline(
                fr.x() - cr.x(), fr.y() - cr.y(), fr.w(), fr.h(),
                f.cornerTopPx(), f.cornerBottomPx(), RIM_PX,
                Color.withAlpha(accent, RIM_ALPHA * rim))));
    }
}
