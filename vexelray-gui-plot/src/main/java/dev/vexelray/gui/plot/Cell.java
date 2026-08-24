package dev.vexelray.gui.plot;

/**
 * The region of the domain an expression is enclosed over: which interval each parameter ranges across. A
 * <b>column</b> in one variable, a <b>cell</b> in two, and the name is the two-variable one because that is the
 * case that needed a type at all.
 *
 * <p>Single-variable evaluation used to be written as {@code enclose(Interval)}, with {@link Expr.Param}
 * answering the column <em>whatever its name was</em>. That clause is exactly what a second variable breaks: the
 * answer now depends on which parameter is asking. So the binding becomes a value, {@link Expr#enclose(Cell)}
 * becomes the operation every node implements, and {@link Expr#enclose(Interval)} stays as the convenience it
 * always was — {@link #column} binds every name to the column, which is the old semantics exactly.
 *
 * <p>Nothing about soundness moves. An enclosure over a cell contains every value the expression takes anywhere
 * in that cell, by the same arithmetic and for the same reason; a divisor whose range straddles zero is still a
 * pole, now a pole somewhere in a patch rather than somewhere in a column.
 */
@FunctionalInterface
public interface Cell {

    /**
     * The interval {@code parameter} ranges over here.
     *
     * @throws IllegalArgumentException if the parameter is not bound — an expression evaluated against a cell
     *         that does not cover its variables is a mistake made by whoever paired them, and guessing an
     *         interval for it would answer a question about a different expression
     */
    Interval of(String parameter);

    /** Every parameter bound to {@code column} — one variable, whatever it is called. */
    static Cell column(Interval column) {
        if (column == null) {
            throw new IllegalArgumentException("a column is needed to bind against");
        }
        return parameter -> column;
    }

    /** Two named axes: the cell a surface is evaluated over. */
    static Cell of(String first, Interval firstRange, String second, Interval secondRange) {
        if (first == null || second == null || firstRange == null || secondRange == null) {
            throw new IllegalArgumentException("both axes need a name and a range");
        }
        if (first.equals(second)) {
            throw new IllegalArgumentException("both axes are called " + first);
        }
        return parameter -> {
            if (first.equals(parameter)) {
                return firstRange;
            }
            if (second.equals(parameter)) {
                return secondRange;
            }
            throw new IllegalArgumentException(parameter + " is not bound by this cell");
        };
    }
}
