package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.style.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * One settable thing: an id, a name, the section it belongs under, and the control that shows and edits it.
 *
 * <h2>A property builds its own editor, and that is what keeps the set open</h2>
 *
 * There is no {@code Kind} enum here and no switch anywhere in {@link Inspector} — an inspector asks a property
 * for a node and places it, and has no opinion about what the node turns out to be. Five kinds are built in as
 * static factories; an application that needs a sixth writes a {@code Property} and hands it over, without this
 * module being touched or knowing it happened. That is the same shape {@code dev.vexelray.gui.plot.Expr} takes
 * for the same reason, and the alternative — a tagged union the inspector dispatches on — makes every new editor
 * a change to the widget that displays it. Building a node is presentation, which a view may do on the GUI thread
 * (vexelray-framework's threading.md, T7.4).
 *
 * <h2>The value column is the inspector's, not the property's</h2>
 *
 * A slider's number sits in a fixed column so that a stack of rows lines up, and a column that appeared only for
 * the rows that have one would move the controls beside it as values came and went. So the inspector reserves the
 * column for every row and hands the property a {@link Readout} to write into; a property that has nothing to put
 * there simply never calls it, and the column stays blank rather than absent.
 *
 * <h2>Told values, announced edits</h2>
 *
 * The model is the truth and it is the application's, so a property holds no getter or setter onto it: reading the
 * model from the frame, or writing it from the dispatch, would reach into the logic's state from the GUI thread
 * (T7.2). Whoever owns the values tells the inspector them ({@link Inspector#VALUES}), and each reaches its row's
 * {@link #show}; what the user does leaves through the {@link Edits} the row is given, as {@link Inspector#EDITED}.
 * A value told is shown, never reported back as an edit, so the two cannot chase each other.
 */
public interface Property {

    /** What {@link Inspector.Edited} and {@link Inspector.Values} name this row by: unique in its inspector. */
    String id();

    /** The label shown at the head of the row. */
    String name();

    /**
     * Build the control. Called once per inspector the property is placed in, on the GUI thread. What the user does
     * to it goes out through {@code edits}; a value is shown through {@code value}.
     */
    Node editor(Gui gui, Readout value, Edits edits);

    /** The section heading this row sits under; empty for an ungrouped row. */
    default String section() {
        return "";
    }

    /**
     * Show {@code value}, which the model now holds: told, not edited, so nothing is published. Called after
     * {@link #editor} with the last value the inspector was told for this row, and whenever a new one is told. A
     * value of a type the row does not show is ignored. The default does nothing, which suits a property with no
     * state.
     */
    default void show(Object value) {
    }

    /**
     * Offer this property the time for whatever its editor moves — a switch knob crossing its track, say. The
     * default ignores it, which suits an editor with nothing to animate, and that is most of them.
     *
     * <p><b>The same bargain as {@link Readout}</b>, and here for the same reason. The panel owns something a row
     * might want, hands it to every row, and has no opinion about which rows use it — so an inspector still never
     * asks what kind a property is, and an application that wants its switches to travel says it once
     * ({@link Inspector#motion}) rather than per row. A property with no use for a ramp is not asked to have one.
     *
     * <p>Called with whatever the panel has, whenever it has it: before {@link #editor} for a row added after the
     * panel was given its motion, after it for one that was already there, and with null to mean no motion —
     * which is the default and the whole of the reduced-motion path.
     */
    default void motion(Ramp ramp) {
    }

    /** Where a property writes the number, word or nothing that belongs in the row's value column. */
    @FunctionalInterface
    interface Readout {
        void show(String text);
    }

    /**
     * Where a property says the user changed it: the inspector publishes each as an {@link Inspector.Edited}. Called
     * by the property's own control, as the edit happens; never for a value it was told.
     */
    @FunctionalInterface
    interface Edits {
        void edited(Object value);
    }

    /** One choice in a {@link #choice} row: what it is called, and what it is. */
    record Option<T>(String label, T value) {
    }

    /**
     * One choice in a {@link #swatches} row: what it is called, and the colours that depict it. The stops are the
     * caller's — this module holds one palette and mints nothing, so a colour map is data an application brings.
     */
    record Swatch<T>(String label, T value, List<Color> stops) {
    }

    /** A number dragged along a track, shown in the value column. A {@code Double}; at {@code min} until told. */
    static Property range(String id, String section, String name, double min, double max, double step) {
        return new Rows.Range(id, section, name, min, max, step);
    }

    /** A number typed or stepped. A {@code Double}; the nearest to zero it may be until told. */
    static Property number(String id, String section, String name, double step, double min, double max) {
        return new Rows.Number(id, section, name, step, min, max);
    }

    /** An on/off switch. A {@code Boolean}; off until told. */
    static Property flag(String id, String section, String name) {
        return new Rows.Flag(id, section, name);
    }

    /** One of a handful of options, all on show. One of the options' values; none chosen until told. */
    static <T> Property choice(String id, String section, String name, List<Option<T>> options) {
        return new Rows.Choice<>(id, section, name, options);
    }

    /** One of a handful of colour maps, each shown as the colours it is. None chosen until told. */
    static <T> Property swatches(String id, String section, String name, List<Swatch<T>> options) {
        return new Rows.Swatches<>(id, section, name, options);
    }
}

/** The five built-in rows. Package-private: the way to get one is {@link Property}'s factories. */
final class Rows {

    private Rows() {
    }

    /** What every built-in row shares: its id, its heading and its section. */
    private abstract static class Base implements Property {
        private final String id;
        private final String section;
        private final String name;

        Base(String id, String section, String name) {
            this.id = java.util.Objects.requireNonNull(id, "id");
            this.section = section == null ? "" : section;
            this.name = name;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String section() {
            return section;
        }
    }

    static final class Range extends Base {
        private final double min;
        private final double max;
        private final double step;
        private Slider slider;
        private Readout value;
        /** Kept, not just forwarded: the panel may be given its motion before this row is asked for a slider. */
        private Ramp knob;

        Range(String id, String section, String name, double min, double max, double step) {
            super(id, section, name);
            this.min = min;
            this.max = max;
            this.step = step;
        }

        @Override
        public Node editor(Gui gui, Readout value, Edits edits) {
            this.value = value;
            // The step is the slider's to show and to rest on, so it is handed over as a lattice; the row only
            // turns the point the slider settled on back into a value.
            this.slider = new Slider(gui, 0f)
                    .lattice(max > min && step > 0 ? Lattice.every(step / (max - min)) : Lattice.none())
                    .transition(knob);
            slider.onChange(f -> {
                double v = exact(min + f * (max - min));
                edits.edited(v);
                value.show(Text.of(v, step));
            });
            value.show(Text.of(min, step));
            return slider.node().width(Length.grow(1f));
        }

        @Override
        public void show(Object told) {
            if (slider == null || !(told instanceof java.lang.Number n)) {
                return;
            }
            double current = n.doubleValue();
            // show, not value: a row told its model is not the user dragging it. See Toggle.show.
            slider.show((float) fraction(current));
            value.show(Text.of(current, step));
        }

        private double fraction(double v) {
            return max <= min ? 0 : (v - min) / (max - min);
        }

        @Override
        public void motion(Ramp ramp) {
            this.knob = ramp;
            if (slider != null) {
                slider.transition(ramp);
            }
        }

        /**
         * The value a lattice point names, without the float dust a fraction picks up on the way: a point a
         * quarter of the way along is {@code 0.25}, not {@code 0.25000000372}. Rounding to the step decides
         * nothing here — the slider already chose the point.
         */
        private double exact(double v) {
            if (step <= 0) {
                return v;
            }
            double snapped = min + Math.round((v - min) / step) * step;
            return Math.clamp(snapped, min, max);
        }
    }

    static final class Number extends Base {
        private final double step;
        private final double min;
        private final double max;
        private NumberField field;

        Number(String id, String section, String name, double step, double min, double max) {
            super(id, section, name);
            this.step = step;
            this.min = min;
            this.max = max;
        }

        @Override
        public Node editor(Gui gui, Readout value, Edits edits) {
            this.field = new NumberField(gui, Math.clamp(0.0, min, max), step, min, max);
            field.onChange(edits::edited);
            return field.node();
        }

        @Override
        public void show(Object told) {
            if (field != null && told instanceof java.lang.Number n) {
                // show, not value: a told value is not an edit. See Toggle.show.
                field.show(n.doubleValue());
            }
        }
    }

    static final class Flag extends Base {
        private Toggle toggle;
        /** Kept, not just forwarded: the panel may be given its motion before this row is asked for a switch. */
        private Ramp knob;

        Flag(String id, String section, String name) {
            super(id, section, name);
        }

        @Override
        public Node editor(Gui gui, Readout value, Edits edits) {
            this.toggle = new Toggle(gui, false);
            toggle.onChange(edits::edited);
            toggle.transition(knob);
            return toggle.node();
        }

        @Override
        public void show(Object told) {
            if (toggle != null && told instanceof Boolean on) {
                // show, not on: a row told its model is not the user flipping the switch. See Toggle.show.
                toggle.show(on);
            }
        }

        @Override
        public void motion(Ramp ramp) {
            this.knob = ramp;
            if (toggle != null) {
                toggle.transition(ramp);
            }
        }
    }

    static final class Choice<T> extends Base {
        private final List<Property.Option<T>> options;
        private Segment<T> segment;

        Choice(String id, String section, String name, List<Property.Option<T>> options) {
            super(id, section, name);
            this.options = List.copyOf(options);
        }

        @Override
        public Node editor(Gui gui, Readout value, Edits edits) {
            this.segment = new Segment<>(gui);
            for (Property.Option<T> o : options) {
                segment.option(o.label(), o.value());
            }
            segment.onChange(edits::edited);
            return segment.node();
        }

        @Override
        @SuppressWarnings("unchecked")
        public void show(Object told) {
            if (segment != null && told != null) {
                // show, not select: a told value is not an edit. See Toggle.show. One that is no option is ignored.
                segment.show((T) told);
            }
        }
    }

    /**
     * A stack of named colour maps, each drawn as the colours it is.
     *
     * <p>The strip is the stops laid side by side rather than a gradient, and deliberately: a gradient is a fill
     * this module cannot express without minting colour between the stops, and the stops <em>are</em> the map. A
     * reader picking between Blurple and Steel is reading which hues are in it, which is exactly what a row of
     * stops says.
     *
     * <p>The label takes the width of its own text and the strip takes whatever is left, rather than the label
     * being given a fixed column. A fixed column is wider than the whole card in a narrow panel, and a card
     * whose children do not fit is not merely cramped here: see {@code editor} for what a card that overflows
     * turns into.
     *
     * <p>A click shows the card it chose at once, as a switch moves under the finger: the row is the user's
     * control, and the value it stands for is the one they just picked. A value told later wins.
     */
    static final class Swatches<T> extends Base {
        private final List<Property.Swatch<T>> options;
        private final List<Node> cards = new ArrayList<>();
        private Gui gui;
        /** The value the row shows as chosen, or null for none. */
        private Object chosen;

        Swatches(String id, String section, String name, List<Property.Swatch<T>> options) {
            super(id, section, name);
            this.options = List.copyOf(options);
        }

        @Override
        public Node editor(Gui gui, Readout value, Edits edits) {
            this.gui = gui;
            Node column = gui.column().role("swatches").gap(Length.rem(0.2f)).width(Length.grow(1f));
            for (Property.Swatch<T> s : options) {
                Node strip = gui.row().height(Length.rem(0.95f)).width(Length.grow(1f))
                        .corner(Length.rem(0.2f)).clip(true);
                // The stops are the map's own colours, not the look's: they stay what they are when the theme
                // changes, which is the one place in this editor a literal paint is right.
                for (Color stop : s.stops()) {
                    strip.append(gui.box().width(Length.grow(1f)).height(Length.percent(100))
                            .background(Paint.of(stop)));
                }
                Node label = gui.text(s.label()).width(Length.AUTO);
                Node card = gui.row().role("swatch")
                        .gap(Length.rem(0.5f)).padding(Length.rem(0.2f))
                        .corner(Length.rem(0.3f))
                        .alignItems(AlignItems.CENTER)
                        // A card is a control, not a container with a view onto something larger. Every node may
                        // scroll by default, and a node that may scroll and does not fit becomes one: the layout
                        // reserves a scrollbar strip on each overflowing axis, and the dispatcher *consumes* a
                        // press anywhere in that strip -- before focus, before the click walk -- as a press on
                        // the bar. A card narrower than its label therefore stopped being clickable at all, with
                        // no exception and nothing in the tree to say why, and drew a scrollbar where its colours
                        // should have been. Saying it here is the declaration; the framework has no other word
                        // for "this box is a control", and infers scrolling from overflow alone.
                        .scroll(false, false)
                        .children(strip, label);
                gui.focusable(card, true);
                gui.cursor(card, CursorShape.POINTER);
                Seams.of(gui).onClick(card, () -> {
                    chosen = s.value();
                    repaint();
                    edits.edited(s.value());
                });
                Seams.of(gui).onState(card, st -> paint(card, s.value(), st == InteractionState.HOVER));
                cards.add(card);
                column.append(card);
            }
            repaint();
            return column;
        }

        @Override
        public void show(Object told) {
            this.chosen = told;
            repaint();
        }

        private void repaint() {
            if (gui == null) {
                return;
            }
            for (int i = 0; i < cards.size(); i++) {
                paint(cards.get(i), options.get(i).value(), false);
            }
        }

        private void paint(Node card, T value, boolean hover) {
            boolean isChosen = value.equals(chosen);
            card.border(Length.dp(1), isChosen ? Role.ACCENT : hover ? Role.GRIP : Role.EDGE);
            card.background(isChosen ? Role.SELECTION : hover ? Role.CHROME : Role.NONE);
            card.textColor(isChosen ? Role.INK : Role.DIM);
        }
    }

    /** How a number reaches the value column. The step says how much of it is worth showing. */
    static final class Text {
        private Text() {
        }

        static String of(double v, double step) {
            if (step >= 1 && v == Math.rint(v)) {
                return String.valueOf((long) Math.rint(v));
            }
            int places = step <= 0 ? 3 : Math.max(0, Math.min(6, (int) Math.ceil(-Math.log10(step))));
            return String.format("%." + places + "f", v);
        }
    }
}
