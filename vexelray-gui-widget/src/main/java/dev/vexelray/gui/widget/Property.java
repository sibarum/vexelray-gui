package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * One settable thing: a name, the section it belongs under, and the control that reads and writes it.
 *
 * <h2>A property builds its own editor, and that is what keeps the set open</h2>
 *
 * There is no {@code Kind} enum here and no switch anywhere in {@link Inspector} — an inspector asks a property
 * for a node and places it, and has no opinion about what the node turns out to be. Five kinds are built in as
 * static factories; an application that needs a sixth writes a {@code Property} and hands it over, without this
 * module being touched or knowing it happened. That is the same shape {@code dev.vexelray.gui.plot.Expr} takes
 * for the same reason, and the alternative — a tagged union the inspector dispatches on — makes every new editor
 * a change to the widget that displays it.
 *
 * <h2>The value column is the inspector's, not the property's</h2>
 *
 * A slider's number sits in a fixed column so that a stack of rows lines up, and a column that appeared only for
 * the rows that have one would move the controls beside it as values came and went. So the inspector reserves the
 * column for every row and hands the property a {@link Readout} to write into; a property that has nothing to put
 * there simply never calls it, and the column stays blank rather than absent.
 *
 * <h2>Getters, not values</h2>
 *
 * Every factory takes a supplier and a consumer rather than an initial value and a callback, because the model is
 * the truth and a property is a view onto it. The supplier is read when the editor is built and whenever
 * {@link Inspector#refresh()} is called, so a change made somewhere else — a preset button, a keystroke, a fit —
 * shows up in the panel without anything having to remember to push it there.
 */
public interface Property {

    /** The label shown at the head of the row. */
    String name();

    /** Build the control. Called once per inspector the property is placed in. */
    Node editor(Gui gui, Readout value);

    /** The section heading this row sits under; empty for an ungrouped row. */
    default String section() {
        return "";
    }

    /** Re-read the model into the control. The default does nothing, which suits a property with no state. */
    default void refresh() {
    }

    /** Where a property writes the number, word or nothing that belongs in the row's value column. */
    @FunctionalInterface
    interface Readout {
        void show(String text);
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

    /** A number dragged along a track, shown in the value column. */
    static Property range(String section, String name, double min, double max, double step,
                          DoubleSupplier get, DoubleConsumer set) {
        return new Rows.Range(section, name, min, max, step, get, set);
    }

    /** A number typed or stepped. */
    static Property number(String section, String name, double step, double min, double max,
                           DoubleSupplier get, DoubleConsumer set) {
        return new Rows.Number(section, name, step, min, max, get, set);
    }

    /** An on/off switch. */
    static Property flag(String section, String name, BooleanSupplier get, Consumer<Boolean> set) {
        return new Rows.Flag(section, name, get, set);
    }

    /** One of a handful of options, all on show. */
    static <T> Property choice(String section, String name, List<Option<T>> options,
                               Supplier<T> get, Consumer<T> set) {
        return new Rows.Choice<>(section, name, options, get, set);
    }

    /** One of a handful of colour maps, each shown as the colours it is. */
    static <T> Property swatches(String section, String name, List<Swatch<T>> options,
                                 Supplier<T> get, Consumer<T> set) {
        return new Rows.Swatches<>(section, name, options, get, set);
    }
}

/** The five built-in rows. Package-private: the way to get one is {@link Property}'s factories. */
final class Rows {

    private Rows() {
    }

    /** What every built-in row shares: its heading and its section. */
    private abstract static class Base implements Property {
        private final String section;
        private final String name;

        Base(String section, String name) {
            this.section = section == null ? "" : section;
            this.name = name;
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
        private final DoubleSupplier get;
        private final DoubleConsumer set;
        private Slider slider;
        private Readout value;

        Range(String section, String name, double min, double max, double step,
              DoubleSupplier get, DoubleConsumer set) {
            super(section, name);
            this.min = min;
            this.max = max;
            this.step = step;
            this.get = get;
            this.set = set;
        }

        @Override
        public Node editor(Gui gui, Readout value) {
            this.value = value;
            double current = get.getAsDouble();
            this.slider = new Slider(gui, (float) fraction(current));
            slider.onChange(f -> {
                double v = snap(min + f * (max - min));
                set.accept(v);
                value.show(Text.of(v, step));
            });
            value.show(Text.of(current, step));
            return slider.node().width(Length.grow(1f));
        }

        @Override
        public void refresh() {
            if (slider == null) {
                return;
            }
            double current = get.getAsDouble();
            slider.value((float) fraction(current));
            value.show(Text.of(current, step));
        }

        private double fraction(double v) {
            return max <= min ? 0 : (v - min) / (max - min);
        }

        /** A slider's fraction is continuous; the value it names is not, if a step was asked for. */
        private double snap(double v) {
            if (step <= 0) {
                return v;
            }
            double snapped = min + Math.round((v - min) / step) * step;
            return Math.clamp(snapped, min, max);
        }
    }

    static final class Number extends Base {
        private final DoubleSupplier get;
        private final DoubleConsumer set;
        private final double step;
        private final double min;
        private final double max;
        private NumberField field;

        Number(String section, String name, double step, double min, double max,
               DoubleSupplier get, DoubleConsumer set) {
            super(section, name);
            this.step = step;
            this.min = min;
            this.max = max;
            this.get = get;
            this.set = set;
        }

        @Override
        public Node editor(Gui gui, Readout value) {
            this.field = new NumberField(gui, get.getAsDouble(), step, min, max);
            field.onChange(set);
            return field.node();
        }

        @Override
        public void refresh() {
            if (field != null) {
                field.value(get.getAsDouble());
            }
        }
    }

    static final class Flag extends Base {
        private final BooleanSupplier get;
        private final Consumer<Boolean> set;
        private Toggle toggle;

        Flag(String section, String name, BooleanSupplier get, Consumer<Boolean> set) {
            super(section, name);
            this.get = get;
            this.set = set;
        }

        @Override
        public Node editor(Gui gui, Readout value) {
            this.toggle = new Toggle(gui, get.getAsBoolean());
            toggle.onChange(set);
            return toggle.node();
        }

        @Override
        public void refresh() {
            if (toggle != null) {
                toggle.on(get.getAsBoolean());
            }
        }
    }

    static final class Choice<T> extends Base {
        private final List<Property.Option<T>> options;
        private final Supplier<T> get;
        private final Consumer<T> set;
        private Segment<T> segment;

        Choice(String section, String name, List<Property.Option<T>> options, Supplier<T> get, Consumer<T> set) {
            super(section, name);
            this.options = List.copyOf(options);
            this.get = get;
            this.set = set;
        }

        @Override
        public Node editor(Gui gui, Readout value) {
            this.segment = new Segment<>(gui);
            for (Property.Option<T> o : options) {
                segment.option(o.label(), o.value());
            }
            segment.select(get.get());
            segment.onChange(set);
            return segment.node();
        }

        @Override
        public void refresh() {
            if (segment != null) {
                segment.select(get.get());
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
     */
    static final class Swatches<T> extends Base {
        private final List<Property.Swatch<T>> options;
        private final Supplier<T> get;
        private final Consumer<T> set;
        private final List<Node> cards = new ArrayList<>();
        private Gui gui;

        Swatches(String section, String name, List<Property.Swatch<T>> options, Supplier<T> get, Consumer<T> set) {
            super(section, name);
            this.options = List.copyOf(options);
            this.get = get;
            this.set = set;
        }

        @Override
        public Node editor(Gui gui, Readout value) {
            this.gui = gui;
            Node column = gui.column().role("swatches").gap(Length.rem(0.2f)).width(Length.grow(1f));
            for (Property.Swatch<T> s : options) {
                Node strip = gui.row().height(Length.rem(0.95f)).width(Length.grow(1f))
                        .corner(Length.rem(0.2f)).clip(true);
                for (Color stop : s.stops()) {
                    strip.append(gui.box().width(Length.grow(1f)).height(Length.percent(100)).background(stop));
                }
                Node label = gui.text(s.label()).width(Length.rem(4f));
                Node card = gui.row().role("swatch")
                        .gap(Length.rem(0.5f)).padding(Length.rem(0.2f))
                        .corner(Length.rem(0.3f))
                        .alignItems(AlignItems.CENTER)
                        .children(strip, label);
                gui.focusable(card, true);
                gui.cursor(card, CursorShape.POINTER);
                gui.onClick(card, () -> {
                    set.accept(s.value());
                    refresh();
                });
                gui.onState(card, st -> paint(card, s.value(), st == InteractionState.HOVER));
                cards.add(card);
                column.append(card);
            }
            refresh();
            return column;
        }

        @Override
        public void refresh() {
            if (gui == null) {
                return;
            }
            for (int i = 0; i < cards.size(); i++) {
                paint(cards.get(i), options.get(i).value(), false);
            }
        }

        private void paint(Node card, T value, boolean hover) {
            boolean chosen = value.equals(get.get());
            card.border(Length.dp(1), gui.theme().color(chosen ? Role.ACCENT : hover ? Role.GRIP : Role.EDGE));
            card.background(gui.theme().color(chosen ? Role.SELECTION : hover ? Role.CHROME : Role.NONE));
            card.textColor(gui.theme().color(chosen ? Role.INK : Role.DIM));
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
