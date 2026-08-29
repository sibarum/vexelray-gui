package dev.vexelray.gui.demo.chapter;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.typeset.Box;
import dev.vexelray.gui.typeset.FaceKeys;
import dev.vexelray.gui.typeset.Placed;
import dev.vexelray.gui.typeset.Profile;
import dev.vexelray.gui.typeset.Recipes;
import dev.vexelray.gui.typeset.Typeset;
import dev.vexelray.gui.typeset.TypesetBlock;
import dev.vexelray.text.AtlasData;

import java.util.List;

/**
 * Structured text: a second composition world, embedded in the first as an atom.
 *
 * <p>A typeset block is not an extension of the flex engine and is deliberately not reachable from a text field.
 * Flex is one-dimensional and deterministic, which is what makes {@code TextField} fast; admitting arbitrary
 * two-dimensional composition into it forfeits that. So a block is a separate component with an intrinsic size,
 * flex places it the way it places anything else with a size, and what happens inside it is its own business.
 *
 * <p><b>Size is a ratio, not a number.</b> Every box declares its size relative to its parent's — a numerator is
 * some fraction of the fraction it is in, a superscript some fraction of its base — and the engine tone-maps the
 * whole block into a legible range at the end. That is what stops a script inside a script inside a fraction
 * from vanishing: the ladder is compressed globally rather than each step being clamped locally.
 *
 * <p>There is no parser here, and there is not going to be one. The application builds the structure, which is
 * what lets a new notation be pioneered without the framework learning about it — and what lets each run be
 * stamped with the application's own source reference, so a selection can later return offsets the application
 * already understands.
 */
public final class TypesetChapter implements Chapter {

    /** The atlas the window renders from. The block measures against the same metrics it will be drawn with. */
    private static final String ATLAS = "/dev/vexelray/text/atlas/primary.json";

    @Override
    public String title() {
        return "Typeset";
    }

    @Override
    public String blurb() {
        return "Fractions, scripts, fences and grids composed from an open set of boxes, sized as ratios and "
                + "tone-mapped into a legible range.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Console console = stage.console();

        Profile profile = Profile.math();
        Typeset engine;
        try {
            // One face, because that is what this application baked. An unbound key resolves to face 0 rather
            // than failing — a missing face is a degraded render, in the primary face, and that is strictly
            // better than a blank block or an exception on the GUI thread.
            engine = new Typeset(AtlasData.loadFromResource(ATLAS), profile, FaceKeys.single());
        } catch (RuntimeException e) {
            console.refused("typeset: no atlas on the classpath (" + e.getMessage() + ")");
            return Ui.card(gui, Ui.heading(gui, "Typeset"),
                    Ui.prose(gui, "This chapter needs the shipped font atlas on the classpath and did not find "
                            + "it. Everything else in the gallery is unaffected: the module is optional, and an "
                            + "application that does not want it does not depend on it."));
        }

        TypesetBlock quadratic = new TypesetBlock(gui, engine, quadratic(profile));
        TypesetBlock series = new TypesetBlock(gui, engine, series(profile));
        TypesetBlock piecewise = new TypesetBlock(gui, engine, piecewise(profile));
        TypesetBlock matrix = new TypesetBlock(gui, engine, matrix(profile));
        quadratic.ink(gui.theme().color(Role.INK));
        series.ink(gui.theme().color(Role.INK));
        piecewise.ink(gui.theme().color(Role.INK));
        matrix.ink(gui.theme().color(Role.INK));

        return Ui.card(gui,
                Ui.heading(gui, "Four constructs, one atom each"),
                specimen(gui, "A fraction inside a fence, with a script on the base", quadratic.node()),
                specimen(gui, "Nested fractions — the ratio ladder, compressed rather than clamped",
                        series.node()),
                specimen(gui, "Cases: a grid with a fence on one side only", piecewise.node()),
                specimen(gui, "A matrix, which is the same grid with both fences", matrix.node()),
                Ui.controls(gui, Ui.button(gui, "Measure the first block", () -> {
                    // The one rule the framework enforces on a box: do not draw outside the box you declare.
                    // That is the whole of what a parent needs in order to place a child it knows nothing
                    // about, and it is what a custom Box implementation is held to.
                    Placed measured = quadratic.placed();
                    console.note(String.format("declares %.1f x %.1f px, and draws %d marks inside it",
                            measured.width(), measured.ascent() + measured.descent(),
                            measured.draws().size()));
                })),
                Ui.prose(gui, "Zoom the window with Ctrl+= and each block is laid out again rather than "
                        + "scaled — the projection resolves in device pixels, and the layout basis it resolves "
                        + "against already has zoom and density baked into it."));
    }

    /** A labelled specimen: the caption, then the block, in a row that does not grow. */
    private static Node specimen(Gui gui, String caption, Node block) {
        return gui.column().width(Length.FILL).height(Length.AUTO).gap(Length.rem(0.125f))
                .children(Ui.label(gui, caption, Length.FILL), block);
    }

    /** {@code x = (-b + c) / 2a}, fenced, with a superscript on the base. */
    private static Box quadratic(Profile p) {
        Box numerator = Box.row(
                Recipes.operator("-"), Recipes.variable("b"),
                Recipes.operator("+"),
                Recipes.script(p, Recipes.variable("c"), Recipes.number("2"), null));
        Box denominator = Box.row(Recipes.number("2"), Recipes.variable("a"));
        return Box.row(
                Recipes.variable("x"), Recipes.relation("="),
                Recipes.fenced(p, "(", ")", Recipes.fraction(p, numerator, denominator)));
    }

    /** A continued fraction, three deep — the case that a locally-clamped size ladder loses. */
    private static Box series(Profile p) {
        Box inner = Recipes.fraction(p, Recipes.number("1"),
                Box.row(Recipes.number("1"), Recipes.operator("+"), Recipes.variable("x")));
        Box middle = Recipes.fraction(p, Recipes.number("1"),
                Box.row(Recipes.number("1"), Recipes.operator("+"), inner));
        return Box.row(Recipes.variable("f"), Recipes.relation("="),
                Recipes.fraction(p, Recipes.number("1"),
                        Box.row(Recipes.number("1"), Recipes.operator("+"), middle)));
    }

    /** A piecewise definition. */
    private static Box piecewise(Profile p) {
        return Box.row(
                Recipes.function("sgn"),
                Recipes.fenced(p, "(", ")", Recipes.variable("x")),
                Recipes.relation("="),
                Recipes.cases(p, List.of(
                        List.of(Recipes.number("1"), Box.row(Recipes.variable("x"), Recipes.relation(">"),
                                Recipes.number("0"))),
                        List.of(Recipes.number("0"), Box.row(Recipes.variable("x"), Recipes.relation("="),
                                Recipes.number("0"))),
                        List.of(Box.row(Recipes.operator("-"), Recipes.number("1")),
                                Box.row(Recipes.variable("x"), Recipes.relation("<"), Recipes.number("0"))))));
    }

    /** A two-by-two matrix. */
    private static Box matrix(Profile p) {
        return Box.row(
                Recipes.variable("M"), Recipes.relation("="),
                Recipes.matrix(p, List.of(
                        List.of(Recipes.variable("a"), Recipes.variable("b")),
                        List.of(Recipes.variable("c"), Recipes.variable("d"))), "[", "]"));
    }
}
