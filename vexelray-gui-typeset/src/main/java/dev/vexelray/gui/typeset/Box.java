package dev.vexelray.gui.typeset;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A composable box: the unit this module lays out. Seven implementations ship built in (below), but the interface
 * is <b>open</b> — an application defines a new kind of composition by implementing it, with no framework change
 * and no privileged access. A structured-text tree is built from these by the calling application; the framework
 * holds no opinion about what markup produced it (docs/typeset.md §2).
 *
 * <p>Notation constructs are not implementations: a fraction, a radical, a matrix are <em>compositions</em>,
 * assembled by {@link Recipes} from the built-ins and parameterised by a {@link Profile}. That factoring is why
 * {@link Attach} alone subsumes superscript, subscript, pre-scripts and over/under limits — the difference is
 * which {@link Slot} a satellite occupies — and why {@link Grid} covers a matrix and a piecewise block, differing
 * only in {@link Align}.
 *
 * <h2>The contract</h2>
 * A box declares five things the framework reads, and writes one method that does the work.
 *
 * <p><b>The only hard rule is containment: do not draw outside the box you declare.</b> {@link #arrange} returns
 * a {@link Placed} carrying a width, an ascent and a descent, and every draw in it must fit inside them. That is
 * the whole of what a parent needs in order to place a child it knows nothing about, and the whole of what the
 * framework enforces. Everything else — how children are sized, where they physically land, whether marks are
 * drawn that correspond to no child at all — is the implementation's business. Physical and logical position are
 * free to diverge: a slash struck through a relation, a combining mark, an arrow derived from two other boxes'
 * positions have no logical child position, and the SPI does not pretend otherwise.
 *
 * <p><b>What that means for the tone map.</b> The block-wide tone map (docs/typeset.md §4) fits <em>declared</em>
 * sizes into a legible range. A box that chooses a size itself has opted out for that content and owns its
 * legibility — a scope boundary, not a loophole. The one thing an unpredictable implementation can do is make the
 * block larger than the declared ratios predict, and the size ceiling is already the constraint that yields.
 *
 * <p><b>Purity.</b> {@link #arrange} must be a pure function of its inputs — no side effects, no reliance on how
 * many times it is called. The engine may memoise it, call it twice in a frame, or call it repeatedly while an
 * enclosing box iterates toward a solution.
 *
 * <h2>Three properties every box carries</h2>
 * <b>{@link #size()}</b> is declared <b>relative to its parent</b>. The absolute value carries no meaning; only
 * the ratios do. It lives on every box rather than on {@link Run} alone because a whole superscripted subtree
 * scales as a unit — the ratio has to attach to any node, not just a leaf.
 *
 * <p><b>{@link #spacingClass()}</b> is what a {@link Row} looks up when it spaces this box against its neighbour.
 * Universal for the same reason: a fraction sitting in a row spaces as one atom.
 *
 * <p><b>{@link #fillsCrossExtent()}</b> says whether this box grows to its container's cross extent. It exists so
 * a container can run the two-pass that growable content needs — measure, then re-lay the growers — without
 * asking what kind of box it holds, which would be the type inspection this module does not do.
 */
public interface Box {

    /** This box's size <b>relative to its parent</b>; 1.0 is "the same as the parent". Never a pixel value. */
    double size();

    /** This box's index into the profile's spacing table, for adjacency spacing inside a {@link Row}. */
    int spacingClass();

    /**
     * This box's children in <b>reading order</b> — the order a reader encounters them, which is not always the
     * order they are drawn. {@link Attach} yields its pre-scripts before its nucleus and its post-scripts after,
     * because that is how the construct is read.
     *
     * <p>The ordering is a published guarantee, not an implementation detail: it is what a future selection walks,
     * and what makes each {@link Run#sourceRef} recoverable in a sensible sequence (docs/typeset.md §8). A box
     * that draws marks of its own with no logical position simply does not list them here.
     */
    List<Box> children();

    /** This box with a different size and spacing class. Recipes use it to scale a whole subtree, and to declare
     *  that a composite spaces as one atom. */
    Box with(double size, int spacingClass);

    /**
     * Whether this box grows to fill {@link Arrangement#crossExtent()} — height inside a {@link Row}, width
     * inside a {@link Stack}. Default {@code false}: a container lays a non-filling child once.
     */
    default boolean fillsCrossExtent() {
        return false;
    }

    /**
     * Lay this box out and return its geometry and draws. Children are laid out through {@code a} — at their
     * declared size, or at one this box picks — as many times as needed.
     *
     * <p>Must honour containment and purity; see the class documentation.
     */
    Placed arrange(Arrangement a);

    /** The default spacing class — the profile's class 0, which every profile is expected to make its ordinary
     *  atom. Used by the terse factories so a caller only names a class when it differs. */
    int ORDINARY = 0;

    /** This box at a different size, keeping its spacing class. */
    default Box withSize(double size) {
        return with(size, spacingClass());
    }

    /** This box in a different spacing class, keeping its size. */
    default Box withClass(int spacingClass) {
        return with(size(), spacingClass);
    }

    // --- the built-in seven ------------------------------------------------------------------------------------
    // Each arranges itself inline, over exactly the public SPI an application would use — no framework back door,
    // no shared helper that inspects box kinds. VocabularyTest asserts the first; the second is a rule to keep.

    /**
     * Glyphs plus a style. {@code face} is a <em>key</em> the profile resolves, never an atlas index — see
     * {@link FaceKeys} for why. A {@code null} face means the profile's {@link Profile#defaultFace()}.
     *
     * <p>{@code sourceRef} is an opaque token the application attaches and the framework never interprets. It is
     * the whole cost of keeping selection possible later: because the app built this tree, it can stamp each run
     * with a range into its own source, and a future selection returns offsets the app already understands rather
     * than a reconstruction. May be {@code null}.
     */
    record Run(double size, int spacingClass, String text, String face, Object sourceRef) implements Box {

        @Override
        public List<Box> children() {
            return List.of();
        }

        @Override
        public Box with(double size, int spacingClass) {
            return new Run(size, spacingClass, text, face, sourceRef);
        }

        /** Accumulate advances across the run and take the extreme plane bounds; one draw for the whole run. */
        @Override
        public Placed arrange(Arrangement a) {
            if (text == null || text.isEmpty()) {
                return Placed.empty();
            }
            String resolved = face == null ? a.profile().defaultFace() : face;
            double px = a.sizePx();
            double advance = 0;
            double top = 0;
            double bottom = 0;
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                Arrangement.Glyph g = a.glyph(resolved, cp);
                advance += g.advance();
                top = Math.max(top, g.top());
                bottom = Math.min(bottom, g.bottom());
            }
            List<Placed.Draw> draws = List.of(new Placed.Glyphs(text, resolved, 0, 0, px, sourceRef));
            return new Placed(advance * px, top * px, -bottom * px, draws);
        }
    }

    /** A horizontal sequence. Gaps come <b>entirely</b> from the profile's class-pair spacing table, applied to
     *  each adjacent pair — a row is exactly where adjacency spacing is the right model. A construct needing a
     *  specific kern uses {@link Stretch#padBefore}, not a row gap. */
    record Row(double size, int spacingClass, List<Box> items) implements Box {

        @Override
        public List<Box> children() {
            return items;
        }

        @Override
        public Box with(double size, int spacingClass) {
            return new Row(size, spacingClass, items);
        }

        /**
         * Two passes. The first lays every child that does not grow, which is what establishes how tall the row
         * is; the second re-lays only the growers, now that there is a height to grow to. This is the first real
         * exercise of {@link Arrangement#lay} being re-callable, and the reason it is in the SPI.
         */
        @Override
        public Placed arrange(Arrangement a) {
            int n = items.size();
            if (n == 0) {
                return Placed.empty();
            }
            Placed[] laid = new Placed[n];
            double ascent = 0;
            double descent = 0;
            for (int i = 0; i < n; i++) {
                if (!items.get(i).fillsCrossExtent()) {
                    laid[i] = a.lay(items.get(i));
                    ascent = Math.max(ascent, laid[i].ascent());
                    descent = Math.max(descent, laid[i].descent());
                }
            }
            // Not simply ascent + descent. A grower in a row centres on the math axis, and content is rarely
            // symmetric about it — a fraction sits higher than it hangs. A delimiter merely as tall as its content
            // would therefore poke out of one end and fall short at the other, so the extent offered is the
            // symmetric height that actually covers the row: twice the further of the two distances from the axis.
            double axis = a.axis();
            double target = 2 * Math.max(ascent - axis, descent + axis);
            for (int i = 0; i < n; i++) {
                if (laid[i] == null) {
                    laid[i] = a.layFilling(items.get(i), target);
                    ascent = Math.max(ascent, laid[i].ascent());
                    descent = Math.max(descent, laid[i].descent());
                }
            }

            List<Placed.Draw> draws = new ArrayList<>();
            double x = 0;
            double px = a.sizePx();
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    x += a.profile().spacing()
                            .gap(items.get(i - 1).spacingClass(), items.get(i).spacingClass()) * px;
                }
                draws.addAll(laid[i].shifted(x, 0));
                x += laid[i].width();
            }
            return new Placed(x, ascent, descent, draws);
        }
    }

    /**
     * A vertical sequence with an explicit gap between each adjacent pair, and an {@link Anchor} deciding where
     * the composite's own baseline sits. Gaps are explicit here (unlike {@link Row}) because there is no
     * adjacency table for the vertical axis: the space above a fraction bar and the space below it are different
     * numbers from the profile, not a function of what happens to sit there.
     *
     * <p>Items are centred horizontally. A construct wanting them aligned to an edge uses a {@link Grid} with one
     * column, which is what {@code Recipes.cases} does.
     *
     * @param gaps space before each item after the first, so length is {@code items.size() - 1}; a shorter list
     *             is padded with zeroes
     */
    record Stack(double size, int spacingClass, List<Box> items, List<Double> gaps, Anchor anchor) implements Box {

        @Override
        public List<Box> children() {
            return items;
        }

        @Override
        public Box with(double size, int spacingClass) {
            return new Stack(size, spacingClass, items, gaps, anchor);
        }

        /** The vertical counterpart of {@link Row#arrange}: measure the non-growers to find the width, then let
         *  the growers span it — which is exactly how a fraction bar reaches across its numerator. */
        @Override
        public Placed arrange(Arrangement a) {
            int n = items.size();
            if (n == 0) {
                return Placed.empty();
            }
            Placed[] laid = new Placed[n];
            double width = 0;
            for (int i = 0; i < n; i++) {
                if (!items.get(i).fillsCrossExtent()) {
                    laid[i] = a.lay(items.get(i));
                    width = Math.max(width, laid[i].width());
                }
            }
            for (int i = 0; i < n; i++) {
                if (laid[i] == null) {
                    laid[i] = a.layFilling(items.get(i), width);
                    width = Math.max(width, laid[i].width());
                }
            }

            double px = a.sizePx();
            double[] tops = new double[n];
            double y = 0;
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    y += gapAt(i - 1) * px;
                }
                tops[i] = y;
                y += laid[i].height();
            }
            double total = y;
            final double[] finalTops = tops;
            final Placed[] finalLaid = laid;
            double baseline = anchor.baseline(total, a.axis(), new Anchor.Children() {
                public double baseline(int i) {
                    int k = Math.floorMod(i, n);
                    return finalTops[k] + finalLaid[k].ascent();
                }

                public double centre(int i) {
                    int k = Math.floorMod(i, n);
                    return finalTops[k] + finalLaid[k].height() / 2;
                }
            });

            List<Placed.Draw> draws = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                draws.addAll(laid[i].shifted((width - laid[i].width()) / 2, tops[i] + laid[i].ascent() - baseline));
            }
            return new Placed(width, baseline, total - baseline, draws);
        }

        private double gapAt(int index) {
            return index < gaps.size() ? gaps.get(index) : 0.0;
        }
    }

    /**
     * A nucleus with satellites at named corners. One box kind for every attachment: {@link Slot#NE} and
     * {@link Slot#SE} are a superscript and subscript, {@link Slot#NW} and {@link Slot#SW} are pre-scripts,
     * {@link Slot#N} and {@link Slot#S} are limits above and below. Each {@link Slot} carries its own geometry,
     * so nothing here asks which slot it has.
     */
    record Attach(double size, int spacingClass, Box nucleus, Map<Slot, Box> satellites) implements Box {

        /** Pre-scripts, then the nucleus, then limits and post-scripts — reading order, not draw order. */
        @Override
        public List<Box> children() {
            List<Box> out = new ArrayList<>();
            add(out, Slot.NW);
            add(out, Slot.SW);
            out.add(nucleus);
            add(out, Slot.N);
            add(out, Slot.S);
            add(out, Slot.NE);
            add(out, Slot.SE);
            return List.copyOf(out);
        }

        private void add(List<Box> out, Slot slot) {
            Box satellite = satellites.get(slot);
            if (satellite != null) {
                out.add(satellite);
            }
        }

        @Override
        public Box with(double size, int spacingClass) {
            return new Attach(size, spacingClass, nucleus, satellites);
        }

        /**
         * Built in two stages. First a <em>core</em>: the nucleus with its stacked satellites centred over and
         * under it, which may be wider than the nucleus alone. Then the side satellites are hung off that core,
         * pre-scripts right-aligned before it and post-scripts after. Composing it this way is what lets a big
         * operator carrying limits also carry a subscript without either placement knowing about the other.
         */
        @Override
        public Placed arrange(Arrangement a) {
            double px = a.sizePx();
            Profile.Metrics m = a.profile().metrics();
            Map<Slot, Placed> laid = new EnumMap<>(Slot.class);
            for (Slot slot : Slot.values()) {
                Box satellite = satellites.get(slot);
                if (satellite != null) {
                    laid.put(slot, a.lay(satellite));
                }
            }

            Placed core = a.lay(nucleus);
            double coreWidth = core.width();
            for (Slot slot : Slot.values()) {
                if (slot.stacked() && laid.containsKey(slot)) {
                    coreWidth = Math.max(coreWidth, laid.get(slot).width());
                }
            }
            List<Placed.Draw> draws = new ArrayList<>(core.shifted((coreWidth - core.width()) / 2, 0));
            double ascent = core.ascent();
            double descent = core.descent();

            for (Slot slot : Slot.values()) {
                Placed s = laid.get(slot);
                if (s == null || !slot.stacked()) {
                    continue;
                }
                double gap = m.limitGap() * px;
                double y = slot.above()
                        ? -(core.ascent() + gap + s.descent())
                        : core.descent() + gap + s.ascent();
                draws.addAll(s.shifted((coreWidth - s.width()) / 2, y));
                ascent = Math.max(ascent, s.ascent() - y);
                descent = Math.max(descent, y + s.descent());
            }

            double preWidth = sideWidth(laid, true);
            double postWidth = sideWidth(laid, false);
            List<Placed.Draw> out = new ArrayList<>(shift(draws, preWidth, 0));

            for (Slot slot : Slot.values()) {
                Placed s = laid.get(slot);
                if (s == null || slot.stacked()) {
                    continue;
                }
                double y = slot.sideShift(m) * px;
                // Pre-scripts are right-aligned against the core; post-scripts start where it ends.
                double x = slot.leading() ? preWidth - s.width() : preWidth + coreWidth;
                out.addAll(s.shifted(x, y));
                ascent = Math.max(ascent, s.ascent() - y);
                descent = Math.max(descent, y + s.descent());
            }

            double trailing = postWidth > 0 ? m.scriptGapAfter() * px : 0;
            return new Placed(preWidth + coreWidth + postWidth + trailing, ascent, descent, out);
        }

        private static double sideWidth(Map<Slot, Placed> laid, boolean leading) {
            double w = 0;
            for (Slot slot : Slot.values()) {
                Placed s = laid.get(slot);
                if (s != null && !slot.stacked() && slot.leading() == leading) {
                    w = Math.max(w, s.width());
                }
            }
            return w;
        }

        private static List<Placed.Draw> shift(List<Placed.Draw> draws, double dx, double dy) {
            List<Placed.Draw> out = new ArrayList<>(draws.size());
            for (Placed.Draw d : draws) {
                out.add(d.shifted(dx, dy));
            }
            return out;
        }
    }

    /** A filled bar — a fraction bar, a radical vinculum, an underline, a table border. {@code thickness} is in em
     *  of this box's own resolved size; {@link Extent} decides its length. */
    record Rule(double size, int spacingClass, Extent extent, double thickness) implements Box {

        @Override
        public List<Box> children() {
            return List.of();
        }

        @Override
        public Box with(double size, int spacingClass) {
            return new Rule(size, spacingClass, extent, thickness);
        }

        @Override
        public boolean fillsCrossExtent() {
            return extent == Extent.FILL;
        }

        /** Sits on the baseline, its thickness hanging below — a stack places it, so its own vertical position
         *  carries no meaning beyond occupying the right amount of room. */
        @Override
        public Placed arrange(Arrangement a) {
            double px = a.sizePx();
            double height = Math.max(1e-6, thickness * px);
            double width = extent.resolve(px, a.crossExtent());
            return new Placed(width, 0, height, List.of(new Placed.Bar(0, 0, width, height)));
        }
    }

    /**
     * A glyph scaled to a target extent — a growable delimiter, a surd, an over-brace. Growth is by scaling the
     * ordinary glyph, not by selecting size variants or assembling pieces, which is why no size-variant ranges are
     * needed in the atlas.
     *
     * <p>{@code padBefore}/{@code padAfter} (em) are the construct's own kerns — the tuck under a surd's hook, the
     * padding just inside a delimiter pair. They live here rather than as row gaps so that {@link Row} keeps a
     * single spacing mechanism, and they are part of this box's own width.
     */
    record Stretch(double size, int spacingClass, String glyph, String face, Extent extent,
                   double padBefore, double padAfter) implements Box {

        @Override
        public List<Box> children() {
            return List.of();
        }

        @Override
        public Box with(double size, int spacingClass) {
            return new Stretch(size, spacingClass, glyph, face, extent, padBefore, padAfter);
        }

        @Override
        public boolean fillsCrossExtent() {
            return extent == Extent.FILL;
        }

        /**
         * The glyph is scaled so its own height matches the target, then centred on the math axis so it straddles
         * the content rather than sitting on the baseline — which is what makes a tall paren look right beside a
         * fraction.
         */
        @Override
        public Placed arrange(Arrangement a) {
            if (glyph == null || glyph.isEmpty()) {
                return Placed.empty();
            }
            double px = a.sizePx();
            String resolved = face == null ? a.profile().defaultFace() : face;
            Arrangement.Glyph g = a.glyph(resolved, glyph.codePointAt(0));
            double natural = Math.max(1e-6, g.height() * px);
            double drawPx = px * (extent.resolve(natural, a.crossExtent()) / natural);

            double before = padBefore * px;
            double width = before + g.advance() * drawPx + padAfter * px;
            // Centre the glyph's own box on the axis. The baseline that achieves this is offset by the glyph's
            // vertical midpoint, not by its top — a surd and a paren have very different midpoints, and centring
            // on the top edge would leave one of them visibly high.
            double half = g.height() * drawPx / 2;
            double centre = -a.axis();
            double baseline = centre + (g.top() + g.bottom()) * drawPx / 2;
            return new Placed(width, half - centre, centre + half,
                    List.of(new Placed.Glyphs(glyph, resolved, before, baseline, drawPx, null)));
        }
    }

    /**
     * Cells in row-major order, with per-column alignment and explicit gaps. A matrix and a piecewise block are
     * the same box kind: a matrix centres its columns ({@link Align#CENTER}), a piecewise block left-aligns them
     * ({@link Align#START}).
     */
    record Grid(double size, int spacingClass, List<List<Box>> rows, Align columnAlign,
                double colGap, double rowGap, Anchor anchor) implements Box {

        @Override
        public List<Box> children() {
            List<Box> out = new ArrayList<>();
            for (List<Box> row : rows) {
                out.addAll(row);
            }
            return List.copyOf(out);
        }

        @Override
        public Box with(double size, int spacingClass) {
            return new Grid(size, spacingClass, rows, columnAlign, colGap, rowGap, anchor);
        }

        @Override
        public Placed arrange(Arrangement a) {
            int nRows = rows.size();
            if (nRows == 0) {
                return Placed.empty();
            }
            int nCols = 0;
            for (List<Box> row : rows) {
                nCols = Math.max(nCols, row.size());
            }
            Placed[][] cell = new Placed[nRows][nCols];
            double[] colWidth = new double[nCols];
            double[] rowAscent = new double[nRows];
            double[] rowDescent = new double[nRows];
            for (int r = 0; r < nRows; r++) {
                List<Box> row = rows.get(r);
                for (int c = 0; c < row.size(); c++) {
                    Placed p = a.lay(row.get(c));
                    cell[r][c] = p;
                    colWidth[c] = Math.max(colWidth[c], p.width());
                    rowAscent[r] = Math.max(rowAscent[r], p.ascent());
                    rowDescent[r] = Math.max(rowDescent[r], p.descent());
                }
            }

            double px = a.sizePx();
            double width = colGap * px * Math.max(0, nCols - 1);
            for (double w : colWidth) {
                width += w;
            }
            double[] tops = new double[nRows];
            double y = 0;
            for (int r = 0; r < nRows; r++) {
                if (r > 0) {
                    y += rowGap * px;
                }
                tops[r] = y;
                y += rowAscent[r] + rowDescent[r];
            }
            double total = y;
            final double[] finalTops = tops;
            final double[] finalAsc = rowAscent;
            final double[] finalDesc = rowDescent;
            final int rowCount = nRows;
            double baseline = anchor.baseline(total, a.axis(), new Anchor.Children() {
                public double baseline(int i) {
                    int k = Math.floorMod(i, rowCount);
                    return finalTops[k] + finalAsc[k];
                }

                public double centre(int i) {
                    int k = Math.floorMod(i, rowCount);
                    return finalTops[k] + (finalAsc[k] + finalDesc[k]) / 2;
                }
            });

            List<Placed.Draw> draws = new ArrayList<>();
            for (int r = 0; r < nRows; r++) {
                double x = 0;
                for (int c = 0; c < nCols; c++) {
                    Placed p = cell[r][c];
                    if (p != null) {
                        draws.addAll(p.shifted(x + columnAlign.offset(p.width(), colWidth[c]),
                                tops[r] + rowAscent[r] - baseline));
                    }
                    x += colWidth[c] + colGap * px;
                }
            }
            return new Placed(width, baseline, total - baseline, draws);
        }
    }

    // --- vocabulary shared by the built-ins ----------------------------------------------------------------------
    // Each constant carries its own behaviour. An enum switched on for behaviour is the same failure as a sealed
    // switch, so there is nothing here for a caller to switch over.

    /** How a {@link Rule} or {@link Stretch} takes its size from context. */
    enum Extent {
        /** The glyph's or content's own natural size. */
        NATURAL {
            @Override
            public double resolve(double natural, double available) {
                return natural;
            }
        },
        /**
         * Fill the enclosing container's cross extent — a bar spanning its stack, a delimiter matching its row's
         * height. This is what makes a fraction bar and a growable paren fall out of the same two box kinds.
         * Falls back to the natural size when the container has not measured yet.
         */
        FILL {
            @Override
            public double resolve(double natural, double available) {
                return available > 0 ? available : natural;
            }
        };

        /** The extent to use, given this box's natural size and whatever the container offered. */
        public abstract double resolve(double natural, double available);
    }

    /** Horizontal alignment of a {@link Grid}'s cells within their column. */
    enum Align {
        START {
            @Override
            public double offset(double content, double column) {
                return 0;
            }
        },
        CENTER {
            @Override
            public double offset(double content, double column) {
                return (column - content) / 2;
            }
        },
        END {
            @Override
            public double offset(double content, double column) {
                return column - content;
            }
        };

        /** How far to inset content of {@code content} width inside a column of {@code column} width. */
        public abstract double offset(double content, double column);
    }

    /**
     * Where a composite's own baseline sits, for {@link Stack} and {@link Grid}.
     *
     * @param child the child index for {@link Kind#BASELINE_OF}; {@code -1} for the other kinds
     */
    record Anchor(Kind kind, int child) {

        /** A composite's children, measured down from its top, for an anchor to align against. */
        public interface Children {
            /** The local baseline of child {@code index}. */
            double baseline(int index);

            /** The local vertical midpoint of child {@code index}. */
            double centre(int index);
        }

        public enum Kind {
            /** Centre the whole composite on the profile's math axis — a matrix, a piecewise block. */
            AXIS {
                @Override
                double baseline(double totalHeight, double axis, int child, Children children) {
                    return totalHeight / 2 + axis;
                }
            },
            /**
             * Put <em>one child</em> on the math axis — a fraction, whose bar is what the axis runs through.
             *
             * <p>Distinct from {@link #AXIS} for a reason that only shows up with lopsided content: centring the
             * whole stack puts the bar on the axis only when the numerator and denominator happen to be the same
             * height. {@code a/b} is enough to separate them.
             */
            AXIS_ON {
                @Override
                double baseline(double totalHeight, double axis, int child, Children children) {
                    return children.centre(child) + axis;
                }
            },
            /** Centre the composite on its own vertical middle. */
            CENTER {
                @Override
                double baseline(double totalHeight, double axis, int child, Children children) {
                    return totalHeight / 2;
                }
            },
            /** Adopt the baseline of one child — a radicand under its vinculum, text above an underline. */
            BASELINE_OF {
                @Override
                double baseline(double totalHeight, double axis, int child, Children children) {
                    return children.baseline(child);
                }
            };

            abstract double baseline(double totalHeight, double axis, int child, Children children);
        }

        /** This anchor's baseline within a composite, measured down from its top. */
        public double baseline(double totalHeight, double axis, Children children) {
            return kind.baseline(totalHeight, axis, child, children);
        }

        public static Anchor axis() {
            return new Anchor(Kind.AXIS, -1);
        }

        /** Put child {@code child}'s midpoint on the math axis — what a fraction does with its bar. */
        public static Anchor axisOn(int child) {
            return new Anchor(Kind.AXIS_ON, child);
        }

        public static Anchor center() {
            return new Anchor(Kind.CENTER, -1);
        }

        public static Anchor baselineOf(int child) {
            return new Anchor(Kind.BASELINE_OF, child);
        }
    }

    // --- terse factories -----------------------------------------------------------------------------------------
    // Size defaults to 1.0 (same as the parent) and class to ORDINARY; use withSize / withClass to change either
    // on a box already built.

    static Box run(String text, String face, int spacingClass) {
        return new Run(1.0, spacingClass, text, face, null);
    }

    static Box run(String text, String face, int spacingClass, Object sourceRef) {
        return new Run(1.0, spacingClass, text, face, sourceRef);
    }

    static Box row(Box... items) {
        return new Row(1.0, ORDINARY, List.of(items));
    }

    static Box row(List<Box> items) {
        return new Row(1.0, ORDINARY, List.copyOf(items));
    }

    static Box stack(List<Box> items, List<Double> gaps, Anchor anchor) {
        return new Stack(1.0, ORDINARY, List.copyOf(items), List.copyOf(gaps), anchor);
    }

    static Box attach(Box nucleus, Map<Slot, Box> satellites) {
        return new Attach(1.0, ORDINARY, nucleus, Map.copyOf(satellites));
    }

    static Box rule(Extent extent, double thickness) {
        return new Rule(1.0, ORDINARY, extent, thickness);
    }

    static Box stretch(String glyph, String face, Extent extent, double padBefore, double padAfter) {
        return new Stretch(1.0, ORDINARY, glyph, face, extent, padBefore, padAfter);
    }

    static Box grid(List<List<Box>> rows, Align columnAlign, double colGap, double rowGap, Anchor anchor) {
        return new Grid(1.0, ORDINARY, List.copyOf(rows), columnAlign, colGap, rowGap, anchor);
    }

    // --- walking ---------------------------------------------------------------------------------------------------

    /** Every leaf of {@code box} in reading order — the sequence a selection would run over. Works for any
     *  implementation, built-in or application-defined, because it only uses {@link #children()}. */
    static List<Box> leaves(Box box) {
        List<Box> out = new ArrayList<>();
        collectLeaves(box, out);
        return List.copyOf(out);
    }

    private static void collectLeaves(Box box, List<Box> out) {
        List<Box> kids = box.children();
        if (kids.isEmpty()) {
            out.add(box);
            return;
        }
        for (Box kid : kids) {
            collectLeaves(kid, out);
        }
    }
}
