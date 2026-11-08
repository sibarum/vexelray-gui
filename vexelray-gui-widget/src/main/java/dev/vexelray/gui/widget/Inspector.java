package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Timing;
import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.Addressed;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.scroll.Scrollbars;
import sibarum.atchung.Subscription;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * A panel of settings, built from what they are rather than from how they look: hand it {@link Property
 * properties} and it lays out the labelled rows, the section headings and the value column.
 *
 * <h2>Why the declaration is the input</h2>
 *
 * A settings panel hand-built out of rows is the same twenty lines per row — a label, a control, a value, the
 * gaps between them, the heading above the first of a group — and the twentieth row is where one of them is
 * three pixels out and the column stops lining up. Worse, the panel then <em>is</em> the schema: what is settable
 * can only be read off the layout code, and a second view of the same model (a search, a preset dump, a
 * transcript of what the user changed) has nothing to read. Declaring the properties puts the schema somewhere a
 * program can look at it, and the layout becomes a consequence rather than a thing to keep in agreement.
 *
 * <h2>There is no switch here</h2>
 *
 * This class never asks a property what kind it is; it asks for a node. See {@link Property} for why that is the
 * whole of what keeps the editor vocabulary open, and note what it costs: an inspector cannot special-case a
 * kind, cannot lay a "range" out differently from a "number", and should not want to. A row that needs to look
 * different is a different {@code Property}.
 *
 * <h2>Sections are declared, not sorted</h2>
 *
 * Rows appear in the order given, and a section heading is drawn the first time a section is seen. So the order
 * on screen is the order in the source, and moving a row moves it — there is no comparator quietly deciding that
 * {@code Look} comes before {@code Sampling} because of how they are spelled.
 *
 * <h2>Cards</h2>
 *
 * {@link #card} is the other shape this panel takes: a heading with a switch and a badge on it, and a body of
 * rows that folds away. It is here rather than in an application because a layer list is the same thing every
 * time — the disclosure, the enabled state that dims the rows without removing them, and the fold that keeps the
 * panel scrollable. Rows inside a card are ordinary properties, so a card's contents cost nothing extra to
 * declare.
 *
 * <h2>Told values, announced edits</h2>
 *
 * The values are the application's, so the panel holds none of its own and reads none: it is told them as one
 * message, {@link Values} on {@link #VALUES}, naming each row by its id, and says what the user changed as
 * {@link Edited} on {@link #EDITED}. A card's switch is a value like any row's. A value told before its row is added
 * is kept and shown when the row is built, so the order the panel and its values arrive in does not matter.
 */
public final class Inspector implements AutoCloseable {

    /**
     * A row, or a card's switch, edited by the user: the inspector at {@code inspector}, the row {@code property}, now
     * {@code value}. The last in a frame for each row, so a drag along a slider is heard once a frame.
     */
    public static final Topic<Edited> EDITED =
            Addressed.state(Edited.class, e -> List.of(e.inspector(), e.property()));

    /** What the rows of the inspector at {@code inspector} now hold, by id: shown, never reported back as an edit. */
    public static final Topic<Values> VALUES = Addressed.edge(Values.class);

    /** The row {@code property} of the inspector at {@code inspector} edited to {@code value}. */
    public record Edited(String inspector, String property, Object value) implements Addressed {
        @Override
        public Object address() {
            return inspector;
        }
    }

    /**
     * What the rows named in {@code values} now hold, for the inspector at {@code inspector}: the rest keep what they
     * were last told. A row's value is the type its factory says (a {@code Double}, a {@code Boolean}, an option's
     * value); a card's is a {@code Boolean}.
     */
    public record Values(String inspector, Map<String, Object> values) implements Addressed {
        public Values {
            values = Map.copyOf(values);
        }

        @Override
        public Object address() {
            return inspector;
        }
    }

    private static final Length ROW_GAP = Length.rem(0.5f);
    private static final Length VALUE_W = Length.rem(2.6f);

    /**
     * The panel's type scale, and why it is set here rather than inherited.
     *
     * <p>An inspector is a narrow column — a few hundred pixels — carrying a label, a control and a number on one
     * line, and at the default body size those three do not fit: the control takes its natural width, the value
     * column is reserved, and the label is what gets squeezed, down to a few pixels where it wraps one character
     * per line and reads as a vertical smear. That is what happened here before these existed. So the sizes are
     * the widget's own, in the proportion the shape needs: the label a little under body size, the value and the
     * section heading smaller again, since a number beside its own label does not need to compete with it.
     */
    private static final Length LABEL_SIZE = Length.rem(0.72f);
    private static final Length VALUE_SIZE = Length.rem(0.66f);
    private static final Length HEADING_SIZE = Length.rem(0.6f);
    private static final Length TITLE_SIZE = Length.rem(0.78f);
    private static final Length BADGE_SIZE = Length.rem(0.58f);

    /**
     * The disclosure: the set's chevron, right while a card is folded and down while it is open, as a tree row and a
     * panel's header say it. An icon, not a character, so it is there whatever atlas the application baked.
     */
    private static final dev.vexelray.gui.icons.Icon FOLDED = dev.vexelray.gui.icons.Icon.CHEVRON_RIGHT;
    private static final dev.vexelray.gui.icons.Icon OPEN = dev.vexelray.gui.icons.Icon.CHEVRON_DOWN;

    private final Gui gui;
    /** The rows, in the order added: what the Scroller moves. */
    private final Node body;
    /** What is placed: the rows with their scrollbar beside them. */
    private final Node box;
    private final List<Property> properties = new ArrayList<>();
    private final Map<String, Node> headings = new LinkedHashMap<>();
    private final List<Card> cards = new ArrayList<>();
    /** Every row by id, the panel's and its cards', so a told value finds it. GUI thread. */
    private final Map<String, Property> rowsById = new HashMap<>();
    /** Every card by id. GUI thread. */
    private final Map<String, Card> cardsById = new HashMap<>();
    /** The last value told for each id, whether its row is here yet or not. GUI thread. */
    private final Map<String, Object> told = new HashMap<>();
    private final WidgetName name;
    private Subscription valuesSub;
    /** The time this panel gives whatever its controls move; null for none, which is the default. */
    private volatile Timing knobs;

    /** Build an empty inspector on {@code gui}. */
    public Inspector(Gui gui) {
        this.gui = gui;
        this.body = gui.column()
                .width(Length.FILL)
                .gap(Length.rem(0.15f))
                .padding(Length.rem(0.4f), Length.rem(0.6f));
        // The rows scroll in a Scroller with a bar beside it, and the panel as a whole is the pair.
        this.box = Scrollbars.vertical(gui, body).node().role("inspector").width(Length.grow(1f));
        this.name = new WidgetName(gui, "inspector", box);
        this.valuesSub = gui.mailbox(VALUES, name.address(), this::told);
    }

    /** The node to place in a layout: the rows, scrolling vertically, with their bar beside them. */
    public Node node() {
        return box;
    }

    /**
     * Call this inspector {@code name} in its tree ("settings"): its {@link #address()} is the tree's name and this,
     * which its edits carry and its values are sent to. On the GUI thread, where the inspector was built.
     */
    public Inspector name(String name) {
        java.util.Objects.requireNonNull(name, "name");
        if (!name.equals(this.name.name())) {
            Subscription next = gui.mailbox(VALUES, gui.address(name), this::told);
            valuesSub.close();
            valuesSub = next;
            this.name.name(name);
        }
        return this;
    }

    /** Where this inspector is on the bus: {@code "<tree>.<name>"}. */
    public String address() {
        return name.address();
    }

    /** Append a row, drawing its section heading first if this is the section's first row. */
    public Inspector add(Property property) {
        body.append(row(property, heading(property.section(), body)));
        properties.add(property);
        return this;
    }

    /** Append several rows. */
    public Inspector add(Property... rows) {
        for (Property p : rows) {
            add(p);
        }
        return this;
    }

    /**
     * Append a foldable card: a switch, a title, a badge, and rows that fold away. The switch is a value like any
     * row's, a {@code Boolean} named {@code id}: told by {@link #VALUES}, and edited on {@link #EDITED}. Off until
     * told.
     *
     * @param id    what the switch's edits and values name it by: unique in the inspector, rows included
     * @param title the card's heading
     * @param badge a short word shown at the right of the heading — a count, a state, a unit; empty for none
     */
    public Card card(String id, String title, String badge) {
        claim(id);
        Card c = new Card(gui, id, title, badge);
        cards.add(c);
        cardsById.put(id, c);
        body.append(c.node());
        Object value = told.get(id);
        if (value != null) {
            c.show(value);
        }
        return c;
    }

    /**
     * Give the switches in this panel their time, so a knob crosses its track rather than jumping — every card
     * head's switch and every row whose editor has something to move, the ones already here and the ones added
     * afterwards. Passing null, or never calling this, keeps the instant flip, which is what this panel did
     * before there was any motion and is the whole of the reduced-motion path.
     *
     * <p><b>Why it is said here rather than to each control.</b> A panel builds its own switches — a card head's
     * is the card's, and a {@link Property#flag} row's is the property's — so before this there was no reach into
     * them at all and {@link Toggle#transition} was unreachable from the outside: the motion could only have been
     * had by an application replacing the widget. So the panel offers the ramp to everything it built and lets
     * each decide (see {@link Property#motion}), which keeps the routing one line here and asks nothing of a
     * property that has nothing to animate.
     *
     * <p>Only the knobs. A card's <em>fold</em> is still instant — that is a layout animation rather than a
     * transform, so it belongs with the one {@code TreeView} runs for a subtree and is not this method dressed
     * up differently.
     */
    public Inspector motion(Timing knobs) {
        this.knobs = knobs;
        for (Property p : properties) {
            p.motion(knobs);
        }
        for (Card c : cards) {
            c.motion(knobs);
        }
        return this;
    }

    /** Release the inspector's subscription. Call it when the panel is taken away for good. */
    @Override
    public void close() {
        valuesSub.close();
    }

    /** Values told: kept for rows still to come, and shown on the rows already here. In the widget tick. */
    private void told(Values values) {
        for (Map.Entry<String, Object> e : values.values().entrySet()) {
            told.put(e.getKey(), e.getValue());
            Property row = rowsById.get(e.getKey());
            if (row != null) {
                row.show(e.getValue());
            }
            Card card = cardsById.get(e.getKey());
            if (card != null) {
                card.show(e.getValue());
            }
        }
    }

    /** Take {@code id} for a row or a card, refusing one already taken. */
    private void claim(String id) {
        java.util.Objects.requireNonNull(id, "id");
        if (rowsById.containsKey(id) || cardsById.containsKey(id)) {
            throw new IllegalArgumentException("two rows with the id '" + id + "' could not be told apart in what "
                    + "EDITED and VALUES carry");
        }
    }

    /** Publish the user's edit of {@code id}. */
    private void edited(String id, Object value) {
        gui.bus().publish(EDITED, new Edited(name.address(), id, value));
    }

    /**
     * The heading for a section, drawn once. Returns the indent rows under it should carry — which is nothing;
     * the heading is the separator, and indenting under it would cost the value column its alignment with the
     * rows of every other section.
     */
    private Node heading(String section, Node into) {
        if (section.isEmpty() || headings.containsKey(section)) {
            return into;
        }
        Node label = gui.text(section.toUpperCase(java.util.Locale.ROOT))
                .textSize(HEADING_SIZE)
                .textColor(Role.FAINT);
        // The rule beside the heading is the heading's underline made horizontal: it says "a group starts here"
        // without a full-width line boxing the group in, which over a translucent panel reads as a second panel.
        Node rule = gui.box().width(Length.grow(1f)).height(Length.dp(1))
                .background(Role.LINE);
        Node head = gui.row().role("inspector-section")
                .width(Length.grow(1f)).gap(ROW_GAP)
                .padding(Length.rem(0.45f), Length.ZERO)
                .alignItems(AlignItems.CENTER)
                .children(label, rule);
        headings.put(section, head);
        into.append(head);
        return into;
    }

    /** One row: label, control, value. Built here so every row in every card shares one geometry. */
    private Node row(Property property, Node unused) {
        claim(property.id());
        rowsById.put(property.id(), property);
        Node label = gui.text(property.name())
                .width(Length.grow(1f))
                .textSize(LABEL_SIZE)
                .wordWrap(false)
                .textColor(Role.DIM);
        Node value = gui.text("").width(VALUE_W)
                .textSize(VALUE_SIZE)
                .wordWrap(false)
                .textColor(Role.FAINT)
                .align(dev.vexelray.text.TextLayout.HAlign.RIGHT, dev.vexelray.text.TextLayout.VAlign.MIDDLE);
        String id = property.id();
        Node control = property.editor(gui, value::text, v -> edited(id, v));
        Object current = told.get(id);
        if (current != null) {
            property.show(current);   // told before the row was here: shown from its first frame
        }
        // Offered to every row, wherever the row came from — the panel or one of its cards — so a row added
        // after the panel was given its motion is not the one that jumps. Most rows ignore it.
        property.motion(knobs);
        return gui.row().role("inspector-row")
                .width(Length.grow(1f))
                .gap(ROW_GAP)
                .padding(Length.rem(0.2f), Length.ZERO)
                .alignItems(AlignItems.CENTER)
                .children(label, control, value);
    }

    /**
     * A foldable group with a switch on its heading.
     *
     * <p><b>Folding hides, and disabling dims; neither removes.</b> A removed row comes back inert — its
     * registrations are keyed by node id and released when it leaves the tree — which is the same reason
     * {@link Tabs} hides pages rather than dropping them. So a card that is folded shut, or whose switch is off,
     * still holds live controls, and opening it or switching it back on hands back exactly the panel that was
     * there before.
     */
    public final class Card {

        private final Node root;
        private final Node rows;
        private final Node caret;
        private final dev.vexelray.gui.icons.IconView caretIcon;
        private final Node badgeNode;
        private final Toggle toggle;
        private final List<Property> own = new ArrayList<>();
        private boolean open;

        private Card(Gui gui, String id, String title, String badge) {
            // The switch moves under the finger and the rows dim with it, as a switch does: the user's control, showing
            // what they just chose. A value told later wins.
            this.toggle = new Toggle(gui, false).onChange(v -> {
                paint();
                edited(id, v);
            }).transition(knobs);
            Node name = gui.text(title).width(Length.grow(1f))
                    .textSize(TITLE_SIZE).wordWrap(false)
                    .textColor(Role.INK);
            this.badgeNode = gui.text(badge).textSize(BADGE_SIZE).wordWrap(false)
                    .textColor(Role.FAINT);
            this.caretIcon = dev.vexelray.gui.icons.IconView.of(gui, FOLDED).size(Length.rem(0.75f)).tint(Role.FAINT);
            this.caret = caretIcon.node();
            Node head = gui.row().role("inspector-card-head")
                    .width(Length.grow(1f)).gap(ROW_GAP)
                    .padding(Length.rem(0.3f), Length.rem(0.4f))
                    .alignItems(AlignItems.CENTER)
                    .children(this.toggle.node(), name, badgeNode, caret);
            // The name and the caret fold the card; the switch does not. Clicking a switch means "turn this off",
            // and a card that also folded shut under the same click would take the rows away at the moment the
            // user is most likely to want to look at them.
            for (Node opener : List.of(name, caret)) {
                gui.focusable(opener, true);
                gui.cursor(opener, CursorShape.POINTER);
                Seams.of(gui).onClick(opener, this::fold);
            }
            Seams.of(gui).onState(head, s -> head.background(
                    s == InteractionState.NORMAL ? Role.PANEL : Role.RAISED));
            this.rows = gui.column().width(Length.grow(1f))
                    .padding(Length.rem(0.2f), Length.rem(0.5f))
                    .background(Role.WELL)
                    .visible(false);
            this.root = gui.column().role("inspector-card")
                    .width(Length.grow(1f))
                    .margin(Length.rem(0.15f))
                    .corner(Length.rem(0.35f))
                    .background(Role.PANEL)
                    .border(Length.dp(1), Role.EDGE)
                    .clip(true)
                    .children(head, rows);
            paint();
        }

        /** The card's node, already appended to the inspector that made it. */
        public Node node() {
            return root;
        }

        /** Add a row inside the card. */
        public Card add(Property property) {
            rows.append(Inspector.this.row(property, rows));
            own.add(property);
            return this;
        }

        /** Add several rows inside the card. */
        public Card add(Property... properties) {
            for (Property p : properties) {
                add(p);
            }
            return this;
        }

        /** Whether the card is folded open. */
        public boolean open() {
            return open;
        }

        /** Fold the card open or shut. */
        public Card open(boolean value) {
            this.open = value;
            rows.visible(value);
            caretIcon.icon(value ? OPEN : FOLDED);
            return this;
        }

        /** Set the badge text. */
        public Card badge(String text) {
            badgeNode.text(text);
            return this;
        }

        /**
         * Give this card's switch, and the rows inside it, their time — one card's worth of what
         * {@link Inspector#motion} says for the whole panel. A later panel-wide call wins, which is the point:
         * the panel is where a reduced-motion setting is honoured in one place.
         */
        public Card motion(Timing ramp) {
            toggle.transition(ramp);
            for (Property p : own) {
                p.motion(ramp);
            }
            return this;
        }

        private void fold() {
            open(!open);
        }

        void show(Object value) {
            if (value instanceof Boolean on) {
                // show, not on: this is the card being told what the model says, not the user flipping it. See
                // Toggle.show -- reporting a sync as an edit made a told value write back to the model, which
                // told the panel again, without bound.
                toggle.show(on);
                paint();
            }
        }

        private void paint() {
            // Off is dimmer, not gone: the rows stay readable so it is clear what turning it back on would do.
            rows.opacity(toggle.on() ? 1f : 0.45f);
        }
    }
}
