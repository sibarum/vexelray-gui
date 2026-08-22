package dev.vexelray.gui.typeset.app;

import dev.vexelray.gui.typeset.Arrangement;
import dev.vexelray.gui.typeset.Box;
import dev.vexelray.gui.typeset.Placed;

import java.util.ArrayList;
import java.util.List;

/**
 * An application-defined box kind, living <b>outside</b> {@code dev.vexelray.gui.typeset} on purpose: if this
 * compiles and composes with the built-ins, then nothing package-private is required to write one, and "an
 * application can extend the vocabulary" is a fact rather than an intention.
 *
 * <p>It is also a case the built-in seven genuinely cannot express, which is why it is this and not a toy. A
 * negated relation — {@code ≠} drawn as {@code =} with a slash through it, or {@code ∉}, or a struck-through
 * symbol — superimposes a mark on a nucleus at a position derived from that nucleus's measured box. The mark is
 * not a child: it has no logical position, it is not read, and it must not appear in a selection. Physical and
 * logical structure diverge, and the SPI is built to allow exactly that.
 *
 * <p>Note what it does <em>not</em> do: {@link #children()} lists only the nucleus, so reading order and any
 * future selection see the relation and not the slash.
 */
public record Superimposed(double size, int spacingClass, Box nucleus, String mark, String face) implements Box {

    public Superimposed(Box nucleus, String mark, String face) {
        this(1.0, nucleus.spacingClass(), nucleus, mark, face);
    }

    /** Only the nucleus is read; the mark is decoration with no logical position. */
    @Override
    public List<Box> children() {
        return List.of(nucleus);
    }

    @Override
    public Box with(double size, int spacingClass) {
        return new Superimposed(size, spacingClass, nucleus, mark, face);
    }

    @Override
    public Placed arrange(Arrangement a) {
        Placed base = a.lay(nucleus);
        Arrangement.Glyph g = a.glyph(face, mark.codePointAt(0));

        // Centre the mark on the nucleus's box — a position no child relationship could express.
        double x = (base.width() - g.advance()) / 2.0;
        double y = (base.ascent() - base.descent()) / 2.0 - (g.top() + g.bottom()) / 2.0;

        List<Placed.Draw> draws = new ArrayList<>(base.draws());
        draws.add(new Placed.Glyphs(mark, face, x, y, 1.0, null));
        // The declared box is the nucleus's: the mark is superimposed, so it adds no extent. Containment holds
        // because the mark is centred inside it.
        return new Placed(base.width(), base.ascent(), base.descent(), draws);
    }
}
