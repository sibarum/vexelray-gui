package dev.vexelray.gui.typeset;

import dev.vexelray.text.AtlasData;
import dev.vexelray.text.GlyphData;
import dev.vexelray.text.Rect;

/**
 * The engine: solves a block's tone map, then walks the tree letting every {@link Box} arrange itself.
 *
 * <p>There is remarkably little here, and that is the point. The engine owns the three things that must be the
 * same everywhere in a block — the size transfer, the spacing table, the glyph metrics — and owns no geometry at
 * all. It never asks what kind of box it is holding; each box arranges itself over the same {@link Arrangement}
 * an application-defined box would get. Adding a composition changes one file and cannot make that file behave
 * differently from the rest of the application, because it does not own any of the decisions that would let it.
 *
 * <h2>Sizes</h2>
 * The tone map maps an authored ratio to pixels as a power law, {@code px(r) = rootPx · r^slope}, so a child's
 * pixel size composes multiplicatively from its parent's: {@code childPx = parentPx · toneMap.relative(ratio)}.
 * No path products need carrying down the walk.
 *
 * <h2>Two-pass, where it is needed</h2>
 * A container that holds growable content ({@link Box#fillsCrossExtent()}) lays its other children first to learn
 * its cross extent, then lays the growers with {@link Arrangement#layFilling}. Only growers are laid twice, so a
 * tree of ordinary content costs exactly one pass.
 *
 * <p>Results are <b>not memoised</b>. {@code arrange} is contractually pure, which keeps memoisation available as
 * an optimisation, but nothing here depends on it and a first cut is easier to trust without a cache.
 */
public final class Typeset {

    /** Deep enough for any real notation; shallow enough that a box laying itself out fails in milliseconds. */
    private static final int MAX_DEPTH = 64;

    /** Stand-in metrics when the atlas has neither the glyph nor a missing-glyph box, so layout always has real
     *  numbers. Roughly a lowercase letter: half an em wide, most of an x-height tall. */
    private static final Arrangement.Glyph FALLBACK = new Arrangement.Glyph(0.5, 0.7, -0.2);

    private final AtlasData atlas;
    private final Profile profile;
    private final FaceKeys faces;

    public Typeset(AtlasData atlas, Profile profile, FaceKeys faces) {
        this.atlas = atlas;
        this.profile = profile;
        this.faces = faces;
    }

    /**
     * Lay {@code root} out with its authored ratio 1.0 rendering at {@code basePx}, unless the tone map's floor
     * lifts it. The returned {@link Placed} is in pixels, with x right, the baseline at y = 0 and y growing down.
     */
    public Placed layout(Box root, double basePx) {
        ToneMap tone = toneMapFor(root, basePx);
        return new Frame(tone, tone.px(root.size()), 0, 0).arrange(root);
    }

    /** The transfer {@link #layout} would solve for this block — exposed so a caller can inspect or assert it. */
    public ToneMap toneMapFor(Box root, double basePx) {
        return ToneMap.solve(ToneMap.Stats.of(root, profile.tone().ratioFloor()), profile.tone(), basePx);
    }

    /** Metrics for one glyph of one face key, in em; never {@code null}. */
    public Arrangement.Glyph glyphOf(String faceKey, int codepoint) {
        AtlasData face = atlas.face(faces.indexOf(faceKey));
        GlyphData g = face.glyph(codepoint);
        if (g == null) {
            g = face.notdef();
        }
        if (g == null) {
            return FALLBACK;
        }
        Rect bounds = g.planeBounds();
        return bounds == null
                ? new Arrangement.Glyph(g.advance(), 0, 0)          // whitespace: an advance and nothing to draw
                : new Arrangement.Glyph(g.advance(), bounds.top(), bounds.bottom());
    }

    /**
     * One box's view of the engine while it arranges itself. A frame is created per box, carrying that box's
     * resolved pixel size, the cross extent its container offered, and the depth — so nothing mutable is shared
     * and {@code arrange} stays as pure as its contract claims.
     */
    private final class Frame implements Arrangement {

        private final ToneMap tone;
        private final double sizePx;
        private final double crossExtent;
        private final int depth;

        Frame(ToneMap tone, double sizePx, double crossExtent, int depth) {
            this.tone = tone;
            this.sizePx = sizePx;
            this.crossExtent = crossExtent;
            this.depth = depth;
        }

        Placed arrange(Box box) {
            if (depth > MAX_DEPTH) {
                throw new IllegalStateException(
                        "typeset tree deeper than " + MAX_DEPTH + " — a box is very likely laying itself out; "
                                + "arrange() must lay children, never its own box");
            }
            return box.arrange(this);
        }

        @Override
        public Placed lay(Box child) {
            return lay(child, child.size());
        }

        @Override
        public Placed lay(Box child, double size) {
            return child(size, 0).arrange(child);
        }

        @Override
        public Placed layFilling(Box child, double extent) {
            return child(child.size(), extent).arrange(child);
        }

        private Frame child(double ratio, double extent) {
            return new Frame(tone, sizePx * tone.relative(ratio), extent, depth + 1);
        }

        @Override
        public double sizePx() {
            return sizePx;
        }

        @Override
        public double crossExtent() {
            return crossExtent;
        }

        @Override
        public double toneMapped(double authoredSize) {
            return tone.px(authoredSize);
        }

        @Override
        public Profile profile() {
            return profile;
        }

        @Override
        public double axis() {
            return profile.metrics().axisHeight() * sizePx;
        }

        @Override
        public Glyph glyph(String faceKey, int codepoint) {
            return glyphOf(faceKey, codepoint);
        }
    }
}
