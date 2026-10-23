package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.ImageRegion;
import dev.vexelray.gui.core.Viewport;
import dev.vexelray.gui.core.input.FocusCause;
import dev.vexelray.gui.core.input.FocusState;
import dev.vexelray.gui.core.layout.LayoutContext;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.style.FocusRing;
import dev.vexelray.gui.core.style.PaintMotion;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.core.text.Styling;
import dev.vexelray.gui.core.text.TextFaces;
import dev.vexelray.gui.core.text.TextMetrics;
import dev.vexelray.gui.core.text.Whitespace;
import dev.vexelray.gui.draw.CanvasSink;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.target.ImageHandle;
import dev.vexelray.text.TextLayout;

/**
 * Walks a {@link RetainedNode} tree and draws it with VexelRay's native {@link Canvas} — the whole of the GUI's
 * "rendering". Submission order is paint order: a node draws its background, then its border, then its text, then
 * its children on top, depth-first. There is no Vulkan, shader, or vertex code here (nor anywhere in the GUI);
 * this only translates model props into {@code Canvas} calls.
 *
 * <p>Depth and light are translated the same way: a node's box is one element whose look is the core node painters,
 * front to back: its border a ring hugging the inside of the edge, its background a fill (lit, for {@code lit}), and
 * its {@code elevation} a soft shadow under it. All of them are functions of the one rounded-box distance the engine
 * already evaluates, so a whole lit, shadowed, outlined panel is still zero extra textures, one quad and one draw.
 */
public final class TreeRenderer {

    /**
     * The colours the renderer paints that no node declares — scrollbars, the shadow under an elevated box, line
     * numbers, the selection wash, and the ink a text node that never stated one falls back to. Chrome is the
     * framework's own furniture, so it comes from the theme rather than from a prop, and all of it resolves once
     * per frame: one small object per frame, against a vertex array.
     */
    private final Color scrollEdge;
    private final Color scrollTrough;
    private final Color scrollGrip;
    private final Color shadow;
    private final Color gutterInk;
    private final Color marks;
    private final Color selection;
    private final Color ink;
    /** The plate under an unclipped node: its edge, and the surface when nothing under the node has one. */
    private final Color plateEdge;
    private final Color plateSurface;

    /**
     * The nodes met on the walk that asked to be drawn out from under their clips ({@code PropKey.UNCLIP}), with
     * the opacity and displacement they inherited there — the two things a second walk starting at the node itself
     * could not know. Painted after the whole tree, in the order they were met, which is paint order.
     */
    private final java.util.List<Lift> lifts = new java.util.ArrayList<>();
    /** The node whose unclipped pass is running, or null during the ordinary walk. Its plate goes under it. */
    private RetainedNode plating;

    /** Far enough out to stand for "no edge" in a mask, and near enough that a float still resolves a pixel there. */
    private static final float FAR = 1e6f;

    /**
     * A node to draw again out from under its clips: what it inherited at the walk, and the mask it was seen through
     * there ({@code clipL..clipB}, infinite where nothing clipped), which is where it is already on the screen.
     */
    private record Lift(RetainedNode node, float alpha, float transX, float transY,
                        float clipL, float clipT, float clipR, float clipB) { }

    /**
     * The opacity inherited at the node currently being drawn — the product of every {@code OPACITY} prop on the
     * path from the root. Every colour this class hands to the {@link Canvas} goes through {@link #fade}, so the
     * transform reaches the chrome the tree never declared (a scrollbar, a shadow, the selection wash) as well as
     * the props a node carries. Walk-scoped rather than a parameter because it applies to *every* colour, and a
     * value threaded through fifteen signatures is a value that will eventually be forgotten on the sixteenth.
     */
    private float alpha = 1f;
    /**
     * The displacement inherited at the node currently being drawn — the sum of every {@code TRANSLATE} on the
     * path from the root, and so the offset between where a node's box says it is and where it is actually
     * painted. Kept because a clip is in <em>screen</em> coordinates while a node's box is not: a mask that must
     * travel with the node it belongs to has to be pushed at the displaced position.
     */
    private float transX;
    private float transY;
    // The live clip, in the coordinates the nodes' own boxes are in: what masked() judges a child against. Starts
    // unbounded, narrows on every clip pushed, and is restored on the way back out of each node.
    private float clipL = Float.NEGATIVE_INFINITY;
    private float clipT = Float.NEGATIVE_INFINITY;
    private float clipR = Float.POSITIVE_INFINITY;
    private float clipB = Float.POSITIVE_INFINITY;

    /** The application's fonts: which face each character is drawn in, the same answer the measurer gave. */
    private final TextFaces faces;

    // What every node's paint and finish resolve against this frame, and the focus ring's role with them. Focus is
    // the dispatcher's, handed in as a value; the ring's lengths resolve against the frame's context like any other.
    private final Theme theme;
    /** The colour each node is drawn in on its way to the one it was given. */
    private PaintMotion paint = PaintMotion.NONE;
    /**
     * Simplified graphics ({@code Gui.simplifiedGraphics}): every box this frame is drawn flat — a lit fill plain, no
     * shadow — and letterpressed text once. Decided here, before a look is chosen, so a flat box asks for one of the
     * looks that already exist and the static cache below needs no mode in its key.
     */
    private boolean simplified;
    private final FocusState focus;
    private final LayoutContext ctx;
    /** The tree's top node, which hosts a ring that no clipping ancestor does. */
    private RetainedNode top;
    /** The nearest ancestor of the node being walked that pushes a clip: where a ring found here is drawn. */
    private RetainedNode host;
    /**
     * The rings waiting for their hosts: recorded when the walk passes their node and drawn when its host closes.
     * Two at most — the focused node's, and the one fading out round the node focus just left.
     */
    private final java.util.List<Ring> rings = new java.util.ArrayList<>(2);

    /**
     * A focused node's ring, waiting for its host. The box is in screen terms (box plus the displacement inherited
     * at the node, its own included), because it is drawn under the host's displacement, not the node's.
     * {@code travel} is how much further out than its resting place it stands while it is arriving or leaving.
     */
    private record Ring(RetainedNode host, float x, float y, float w, float h, float rTop, float rBottom,
                        float width, float offset, float travel, Color color) { }

    private TreeRenderer(Theme theme, TextFaces faces, FocusState focus, LayoutContext ctx) {
        this.faces = faces;
        this.theme = theme;
        this.focus = focus == null ? FocusState.NONE : focus;
        this.ctx = ctx == null ? LayoutContext.of(0f, 0f) : ctx;
        this.scrollEdge = theme.color(Role.EDGE);
        this.scrollTrough = theme.color(Role.CHROME);
        this.scrollGrip = theme.color(Role.GRIP);
        this.shadow = theme.color(Role.SHADOW);
        this.gutterInk = theme.color(Role.FAINT);
        // Whitespace and line-end marks: the gutter's ink, lighter still. A line number is read; these are only
        // noticed, and at full faint strength every indented line carries a row of them.
        this.marks = Color.withAlpha(gutterInk, gutterInk.a() * 0.6f);
        this.selection = theme.color(Role.HIGHLIGHT);
        // The fallback for a text node that never declared a colour: the model has no opinion (RetainedNode.textColor
        // answers null), so the renderer supplies the theme's.
        this.ink = theme.color(Role.INK);
        this.plateEdge = theme.color(Role.EDGE);
        this.plateSurface = theme.color(Role.RAISED);
    }

    /** As {@link #emit(RetainedNode, Canvas, TextFaces, Theme)}, in the framework's default theme. */
    public static void emit(RetainedNode node, Canvas canvas, TextFaces faces) {
        emit(node, canvas, faces, Theme.DARK);
    }

    /**
     * Emit the whole tree rooted at {@code node} into {@code canvas}. Each character is drawn in the face
     * {@code faces} resolves for it — the node's family and style, and its spans — which is the same resolution the
     * measurer made, so every glyph lands on the caret position the metrics baked for it.
     *
     * <p>{@code theme} supplies the chrome the tree does not declare (see the fields above), and is what every
     * node's {@code Paint} and {@code Finish} resolve against — so the same tree drawn in another theme is that
     * theme's picture of it, with nothing rewritten.
     */
    public static void emit(RetainedNode node, Canvas canvas, TextFaces faces, Theme theme) {
        emit(node, canvas, faces, theme, FocusState.NONE, null);
    }

    /**
     * As {@link #emit(RetainedNode, Canvas, TextFaces, Theme)}, in the theme {@code gui}'s tree is drawn in, and its
     * focus ring around whichever node holds keyboard focus, each node's colours as its {@link PaintMotion} has them
     * on their way. Everything is read from {@code gui} here, once, and handed to the walk as values.
     *
     * <p>The tree's theme, not {@code gui.theme()}: a swap lands in the tree with the frame's other edits, which is
     * where the fades it starts are reported from, so drawing the asked-for theme a frame early would show the
     * destination and then fade to it from where it was.
     */
    public static void emit(RetainedNode node, Canvas canvas, TextFaces faces, Gui gui) {
        Viewport v = gui.viewport().value();
        LayoutContext ctx = new LayoutContext(gui.rootEmPx(), gui.zoom().value(), gui.dpi().value(),
                v == null ? 0f : v.width(), v == null ? 0f : v.height());
        emit(node, canvas, faces, gui.drawnTheme(), gui.focusState(), ctx, gui.paintMotion(),
                gui.simplifiedGraphics());
    }

    /**
     * As {@link #emit(RetainedNode, Canvas, TextFaces, Theme)}, drawing the focus ring for {@code focus}: the
     * focused node's own {@code FocusRing} if it has one, else {@code theme}'s, with its lengths resolved against
     * {@code ctx}.
     */
    public static void emit(RetainedNode node, Canvas canvas, TextFaces faces, Theme theme, FocusState focus,
                            LayoutContext ctx) {
        emit(node, canvas, faces, theme, focus, ctx, PaintMotion.NONE);
    }

    /** As {@link #emit(RetainedNode, Canvas, TextFaces, Theme, FocusState, LayoutContext)}, colours via {@code paint}. */
    public static void emit(RetainedNode node, Canvas canvas, TextFaces faces, Theme theme, FocusState focus,
                            LayoutContext ctx, PaintMotion paint) {
        emit(node, canvas, faces, theme, focus, ctx, paint, false);
    }

    /**
     * As {@link #emit(RetainedNode, Canvas, TextFaces, Theme, FocusState, LayoutContext, PaintMotion)}, flat when
     * {@code simplified} — see {@code Gui.simplifiedGraphics}: lit fills drawn plain, no shadows, letterpress once.
     */
    public static void emit(RetainedNode node, Canvas canvas, TextFaces faces, Theme theme, FocusState focus,
                            LayoutContext ctx, PaintMotion paint, boolean simplified) {
        TreeRenderer renderer = new TreeRenderer(theme == null ? Theme.DARK : theme, faces, focus, ctx);
        renderer.paint = paint == null ? PaintMotion.NONE : paint;
        renderer.simplified = simplified;
        renderer.top = node;
        renderer.host = node;
        renderer.walk(node, canvas);
        renderer.unclipped(canvas);
    }

    /**
     * Paint every node that asked to be drawn out from under its clips, again, over everything — see
     * {@code PropKey.UNCLIP}. The ordinary walk has popped every clip it pushed by now, so the only mask left is
     * the window's, and each node is walked exactly as before from the opacity and displacement it inherited:
     * its own clips still hold for its own children. One level only: a node met inside one of these passes is
     * already out from under everything above the node being lifted, and is not queued again.
     *
     * <p><b>Where the node was already seen, nothing is drawn over it.</b> A fade drawn as its parts — a plate, then
     * the node over it, each at the fade's opacity — is not a faded picture: half-way through, the plate (the
     * surface's own colour) has dimmed the text already on the screen and the copy has only half brought it back,
     * and the text flickers dark at the start of every fade. Drawing that part again on top swaps the dimming for
     * text drawn over itself, whose anti-aliased edges thicken. But inside the mask the node was seen through, the
     * finished unclip is the same pixels that are there already — the same text, on a plate the colour of what it
     * stands on — so the exact fade there is no change at all. So the lift is drawn in parts by where they fall:
     * <ul>
     *   <li>outside that mask, the overhang: plate and node whole, fading in together over whatever covered them;</li>
     *   <li>inside it, the plate's edge round its whole outline, and its shadow only where it falls outside the
     *       plate — the shadow is a solid shape under the plate's fill, and without the fill it would dim the text
     *       as the plate did;</li>
     *   <li>and the node itself not at all: the ordinary walk's draw of it is already the right one.</li>
     * </ul>
     * The canvas clips to a rectangle and cannot exclude one, so "outside" is drawn as up to four strips round it.
     */
    private void unclipped(Canvas canvas) {
        for (Lift lift : lifts) {
            RetainedNode n = lift.node();
            alpha = lift.alpha() * n.unclip();
            transX = lift.transX();
            transY = lift.transY();
            unbound();
            boolean moved = transX != 0f || transY != 0f;
            if (moved) {
                canvas.pushTranslate(transX, transY);
            }
            plating = n;
            if (!seen(lift) || n.rotation() != 0f || n.scale() != 1f) {
                walk(n, canvas);   // no mask to keep out of, or none that holds under a turn: it fades as its parts
            } else {
                float[] hole = {lift.clipL(), lift.clipT(), lift.clipR(), lift.clipB()};
                for (float[] band : around(new float[] {-FAR, -FAR, FAR, FAR}, hole)) {
                    clipTo(canvas, band);
                    walk(n, canvas);   // the overhang: the plate and the node, out of the mask they were cut by
                    canvas.popClip();
                }
                unbound();
                // The node's own displacement, which the walk applies inside itself and the plate is drawn under.
                float dx = n.translateX() * n.emPx;
                float dy = n.translateY() * n.emPx;
                float[] plate = plateRect(n);
                float[] seenPlate = {plate[0] + dx, plate[1] + dy, plate[0] + plate[2] + dx, plate[1] + plate[3] + dy};
                clipTo(canvas, hole);
                drawPlate(n, canvas, dx, dy, false, true, false);   // the edge, round the whole of it
                canvas.popClip();
                for (float[] band : around(hole, seenPlate)) {
                    clipTo(canvas, band);
                    drawPlate(n, canvas, dx, dy, false, false, true);   // the shadow, only where it is outside
                    canvas.popClip();
                }
            }
            plating = null;
            if (moved) {
                canvas.popTranslate();
            }
        }
        lifts.clear();
    }

    /** No mask: what the unclipped pass starts every node from. */
    private void unbound() {
        clipL = Float.NEGATIVE_INFINITY;
        clipT = Float.NEGATIVE_INFINITY;
        clipR = Float.POSITIVE_INFINITY;
        clipB = Float.POSITIVE_INFINITY;
    }

    /** Push {@code r} ({@code l, t, r, b}) as the canvas's mask and the walk's, so culling agrees with drawing. */
    private void clipTo(Canvas canvas, float[] r) {
        clipL = r[0];
        clipT = r[1];
        clipR = r[2];
        clipB = r[3];
        canvas.pushClip(r[0], r[1], r[2] - r[0], r[3] - r[1], 0f);
    }

    /**
     * {@code outer} less {@code hole}, as up to four strips that do not overlap — the full-width bands above and
     * below the hole, and the bands either side of it between them. Each rect is {@code l, t, r, b}; an empty strip
     * is left out, and a hole that misses {@code outer} leaves {@code outer} whole.
     */
    private static java.util.List<float[]> around(float[] outer, float[] hole) {
        float l = Math.max(outer[0], hole[0]);
        float t = Math.max(outer[1], hole[1]);
        float r = Math.min(outer[2], hole[2]);
        float b = Math.min(outer[3], hole[3]);
        java.util.List<float[]> out = new java.util.ArrayList<>(4);
        if (l >= r || t >= b) {
            out.add(outer);
            return out;
        }
        strip(out, outer[0], outer[1], outer[2], t);   // above
        strip(out, outer[0], b, outer[2], outer[3]);   // below
        strip(out, outer[0], t, l, b);                 // left, between them
        strip(out, r, t, outer[2], b);                 // right, between them
        return out;
    }

    private static void strip(java.util.List<float[]> out, float l, float t, float r, float b) {
        if (r > l && b > t) {
            out.add(new float[] {l, t, r, b});
        }
    }

    /**
     * Whether a lifted node was seen through a mask that can be kept out of: a finite one with room in it. Under a
     * turn or a scale the walk gives up its mask (nothing is culled there), so there is none, and the node fades as
     * its parts.
     */
    private static boolean seen(Lift lift) {
        return Float.isFinite(lift.clipL()) && Float.isFinite(lift.clipT()) && Float.isFinite(lift.clipR())
                && Float.isFinite(lift.clipB()) && lift.clipR() > lift.clipL() && lift.clipB() > lift.clipT();
    }

    /** The plate's box round {@code n}, {@code x, y, w, h}: a little larger than the node, by its own em. */
    private static float[] plateRect(RetainedNode n) {
        float em = plateEm(n);
        float padX = em * 0.4f;
        float padY = em * 0.1f;
        return new float[] {n.x - padX, n.y - padY, n.w + padX * 2f, n.h + padY * 2f};
    }

    private static float plateEm(RetainedNode n) {
        return n.textSizePx > 0f ? n.textSizePx : n.emPx;
    }

    /**
     * The surface drawn under an unclipped node: a rounded plate a little larger than its box, in whatever it was
     * sitting on, so the part that escapes reads as the same thing carried on rather than as a second thing laid
     * over the page. Edged and shadowed because it <em>is</em> over the page, and covers what it overhangs.
     */
    private void drawPlate(RetainedNode n, Canvas canvas) {
        drawPlate(n, canvas, 0f, 0f, true, true, true);
    }

    /** The plate, or only some of its parts, displaced by {@code (dx, dy)}. */
    private void drawPlate(RetainedNode n, Canvas canvas, float dx, float dy, boolean fill, boolean edge,
                           boolean shade) {
        float[] p = plateRect(n);
        float em = plateEm(n);
        float r = em * 0.25f;
        float lift = em * 0.4f;
        Color surface = null;
        if (fill) {
            for (Color layer : surfaceUnder(n)) {
                surface = over(fade(layer), surface);
            }
        }
        box(canvas, p[0] + dx, p[1] + dy, p[2], p[3], r, r, edge ? fade(plateEdge) : null, 1f,
                surface == null ? NO_FILL : PLAIN_FILL, surface, 0f, 0f, shade ? fade(shadow) : null, lift);
    }

    /** {@code top} laid over {@code under} (null for nothing), straight alpha: what blending one on the other gives. */
    private static Color over(Color top, Color under) {
        if (under == null) {
            return top;
        }
        float a = top.a() + under.a() * (1f - top.a());
        if (a <= 0f) {
            return top;
        }
        float k = under.a() * (1f - top.a());
        return new Color((top.r() * top.a() + under.r() * k) / a, (top.g() * top.a() + under.g() * k) / a,
                (top.b() * top.a() + under.b() * k) / a, a);
    }

    /**
     * What {@code n} is drawn on, as the layers that make it, bottom first: its ancestors' backgrounds from the
     * first opaque one inward, so a row with a translucent hover wash over a panel is the panel and then the wash.
     * The plate mixes them into the one colour it is filled with. The theme's raised surface stands under them all
     * for a tree that never paints one.
     */
    private java.util.List<Color> surfaceUnder(RetainedNode n) {
        java.util.ArrayDeque<Color> layers = new java.util.ArrayDeque<>();
        for (RetainedNode p = n.parent; p != null; p = p.parent) {
            Color bg = p.background(theme);
            if (bg == null || bg.a() <= 0f) {
                continue;
            }
            layers.push(bg);   // nearest first in, so it comes out last and lands on top
            if (bg.a() >= 0.999f) {
                return java.util.List.copyOf(layers);
            }
        }
        layers.push(plateSurface);
        return java.util.List.copyOf(layers);
    }

    private void walk(RetainedNode node, Canvas canvas) {
        if (!node.visible()) {
            return;   // hidden: nothing drawn, and the subtree is not walked
        }
        float inherited = alpha;
        alpha = inherited * node.opacity();
        // Fully transparent: skip the subtree exactly as a hidden node does. Not the same as hiding it, though —
        // it was laid out, so its box is real and it is still hit-testable unless it also said hitInert.
        if (alpha > 0f) {
            if (plating == null && node.unclip() > 0f) {
                // Drawn here as usual, and again later
                lifts.add(new Lift(node, inherited, transX, transY, clipL, clipT, clipR, clipB));
            }
            // Displacement, in multiples of this node's own baked em, so it tracks zoom and density with no
            // layout context to consult. Pushed around the node and its whole subtree, and cumulative.
            float dx = node.translateX() * node.emPx;
            float dy = node.translateY() * node.emPx;
            boolean moved = dx != 0f || dy != 0f;
            float keepTransX = transX;
            float keepTransY = transY;
            if (moved) {
                canvas.pushTranslate(dx, dy);
                transX += dx;
                transY += dy;
            }
            // A turn and a scale, both about the centre of the box where it is drawn: inside the displacement,
            // around the node and its whole subtree. The two commute, about one centre.
            float turn = node.rotation();
            float grow = node.scale();
            boolean turned = turn != 0f || grow != 1f;
            if (turn != 0f) {
                canvas.pushRotate((float) Math.toRadians(turn), node.x + node.w * 0.5f, node.y + node.h * 0.5f);
            }
            if (grow != 1f) {
                canvas.pushScale(grow, node.x + node.w * 0.5f, node.y + node.h * 0.5f);
            }
            if (node == plating) {
                drawPlate(node, canvas);
            }
            drawSelf(node, canvas, faceFor(node));
            if (plating == null && node.id == focus.nodeId()) {
                recordRing(node, focus.cause(), focus.shown());   // drawn by its host, not here: see drawRings
            } else if (plating == null && node.id == focus.leavingId()) {
                recordRing(node, focus.leavingCause(), focus.leavingShown());   // the ring focus left, fading
            }
            boolean scrollClip = node.overflowX || node.overflowY;
            // This node hosts the rings found under it if it clips them; the top of the tree hosts the rest.
            boolean hosts = scrollClip || node.clip() || node == top;
            RetainedNode keepHost = host;
            if (hosts) {
                host = node;
            }
            // An overflowing container clips to its scroll viewport; a container that merely said so clips to its
            // whole border box. Both are the same mask, asked for two different ways, so only one is pushed.
            float keepL = clipL;
            float keepT = clipT;
            float keepR = clipR;
            float keepB = clipB;
            if (moved) {
                // The subtree draws dx/dy from where its boxes say it is, so the mask moves the other way in the
                // space those boxes are expressed in.
                clipL -= dx;
                clipT -= dy;
                clipR -= dx;
                clipB -= dy;
            }
            if (turned) {
                // The subtree's boxes cannot be judged against the mask once they are drawn turned or scaled, so
                // nothing under either is culled: its clips still cut it on the screen, where it lands.
                clipL = Float.NEGATIVE_INFINITY;
                clipT = Float.NEGATIVE_INFINITY;
                clipR = Float.POSITIVE_INFINITY;
                clipB = Float.POSITIVE_INFINITY;
            }
            float flowL = clipL;
            float flowT = clipT;
            float flowR = clipR;
            float flowB = clipB;
            if (scrollClip) {
                // Clip children to the scroll viewport, honouring the container's (inset) rounded corner.
                float inset = node.viewX - node.x;
                float radius = Math.max(0f, node.cornerPx - inset);
                canvas.pushClip(node.viewX, node.viewY, node.viewW, node.viewH, radius);
                narrow(node.viewX, node.viewY, node.viewW, node.viewH);
            } else if (node.clip()) {
                canvas.pushClip(node.x, node.y, node.w, node.h, node.cornerPx);
                narrow(node.x, node.y, node.w, node.h);
            }
            for (RetainedNode child : node.children) {
                if (!child.floating() && !masked(child)) {
                    walk(child, canvas);
                }
            }
            // After every flow child, so no later sibling paints over the ring; before the floats, so every popup
            // still covers it; and under the host's own clip, rounded corners and all.
            if (hosts) {
                drawRings(node, canvas);
            }
            if (scrollClip) {
                canvas.popClip();
                drawScrollbars(node, canvas);   // the container's chrome, so still at the container's alpha
                // The scroll mask hides what scrolled out of the flow, and a float is not in the flow: it was
                // placed against the settled box and stays there while the content moves under it. Clipping it to
                // the viewport would cut chrome by the padding, the line-number gutter and the scrollbar strip —
                // an overlay trimmed by facts about the content it sits over. An explicit clip() is left to reach
                // it, because that one is a statement about the whole subtree rather than about the flow.
                clipL = flowL;
                clipT = flowT;
                clipR = flowR;
                clipB = flowB;
            }
            // Floating children last, out of the flow's mask as they were out of its layout — so they paint over
            // every sibling, which is what makes one an overlay.
            for (RetainedNode child : node.children) {
                if (child.floating() && !masked(child)) {
                    walk(child, canvas);
                }
            }
            if (hosts) {
                // A focused node inside one of this host's floats (a popup's own field) was met after the flow
                // pass, so its ring is drawn here, over the float it is in.
                drawRings(node, canvas);
                host = keepHost;
            }
            if (!scrollClip && node.clip()) {
                canvas.popClip();
            }
            // The decoration slot, last of everything this node puts on the screen — over its own border and text
            // and over every child, because something that says "this just happened to this box" that a label can
            // cover has failed at the one thing it does. Inside the node's translate, like its own picture: an
            // overlay decorates where the node is drawn, not where it was laid out.
            drawOverlay(node, canvas, faceFor(node));
            if (grow != 1f) {
                canvas.popScale();
            }
            if (turn != 0f) {
                canvas.popRotate();
            }
            if (moved) {
                canvas.popTranslate();
            }
            clipL = keepL;
            clipT = keepT;
            clipR = keepR;
            clipB = keepB;
            transX = keepTransX;   // restored rather than subtracted, so a deep tree accumulates no drift
            transY = keepTransY;
        }
        alpha = inherited;   // one restore point, on every path out
    }

    /**
     * Note a ring round {@code n} at {@code shown} of its opacity, if the ring in force for it shows for the way
     * focus arrived ({@code cause}). Its own {@code FocusRing} wins over the theme's, and is read from this node
     * only.
     */
    private void recordRing(RetainedNode n, FocusCause cause, float shown) {
        FocusRing spec = n.focusRing() != null ? n.focusRing() : theme.focusRing();
        if (shown <= 0f || !spec.shows(cause, n.editable()) || n.w <= 0f || n.h <= 0f) {
            return;
        }
        // In the node's own context, as layout resolved its other lengths, so a ring in em follows its text.
        LayoutContext own = n.layoutCtx != null ? n.layoutCtx : ctx;
        float width = spec.width().scalarPx(own, n.w);
        if (width <= 0f) {
            return;
        }
        float offset = spec.offset().scalarPx(own, n.w);
        // At the node's inherited opacity, so a ring on a fading page fades with it; at half that while the
        // window is in the background, the way the caret stops blinking there.
        float a = alpha * shown * (focus.windowActive() ? 1f : 0.5f);
        Color c = theme.color(spec.color());
        // And it closes in as it comes up: from two widths further out, eased out so it settles rather than stops.
        // Opacity stays linear and only the distance eases, the two curves a transition wants. A ring leaving runs
        // the same way backwards, opening out as it fades. At rest the travel is zero, so a ring that is up is
        // exactly where it always was.
        float behind = 1f - shown;
        float travel = 2f * width * behind * behind * behind;
        rings.add(new Ring(host, n.x + transX, n.y + transY, n.w, n.h, n.cornerPx, n.cornerBottomPx, width, offset,
                travel, Color.withAlpha(c, c.a() * a)));
    }

    /** Draw the waiting rings whose host is {@code at}, and let go of them. */
    private void drawRings(RetainedNode at, Canvas canvas) {
        for (int i = 0; i < rings.size(); ) {
            Ring r = rings.get(i);
            if (r.host() == at) {
                rings.remove(i);
                drawRing(r, canvas);
            } else {
                i++;
            }
        }
    }

    /**
     * Draw one waiting ring under its host. The ring stands {@code offset} clear of the box and is
     * {@code width} thick, its corners following the box's own. If that outset does not fit inside the host's
     * clip it is drawn just inside the box instead: a control that fills its container would otherwise have its
     * ring clipped away on every side, and the field that most needs to show focus would show none. One rule for
     * all four sides, so the ring is never lopsided.
     */
    private void drawRing(Ring r, Canvas canvas) {
        // Into the host's frame: the canvas is under the host's displacement now, not the node's.
        float x = r.x() - transX;
        float y = r.y() - transY;
        float out = r.offset() + r.width();
        // Whether it fits is judged at rest, so a ring does not flip inside half-way through arriving. A ring that
        // comes inside does not travel: there is no room for it to close in from.
        if (out > 0f && !inside(x - out, y - out, r.w() + 2f * out, r.h() + 2f * out)) {
            out = 0f;
        } else {
            out += r.travel();
        }
        box(canvas, x - out, y - out, r.w() + 2f * out, r.h() + 2f * out, Math.max(0f, r.rTop() + out),
                Math.max(0f, r.rBottom() + out), r.color(), r.width(), NO_FILL, null, 0f, 0f, null, 0f);
    }

    /** Whether the rect lies within the live clip — the window's box when nothing clips there. */
    private boolean inside(float x, float y, float w, float h) {
        boolean unbounded = clipL == Float.NEGATIVE_INFINITY;
        float l = unbounded ? top.x : clipL;
        float t = unbounded ? top.y : clipT;
        float rr = unbounded ? top.x + top.w : clipR;
        float b = unbounded ? top.y + top.h : clipB;
        float slack = 0.5f;
        return x >= l - slack && y >= t - slack && x + w <= rr + slack && y + h <= b + slack;
    }

    /**
     * Bring the live mask in to {@code (x, y, w, h)} — the same rectangle just pushed on the canvas, tracked here
     * so {@link #masked} can answer without asking the canvas about its own state.
     */
    private void narrow(float x, float y, float w, float h) {
        clipL = Math.max(clipL, x);
        clipT = Math.max(clipT, y);
        clipR = Math.min(clipR, x + w);
        clipB = Math.min(clipB, y + h);
    }

    /**
     * Whether {@code child} is far enough outside the live mask that nothing it draws could land inside it.
     *
     * <p><b>Why this is worth the test.</b> A clip hides what it covers; it does not stop the geometry underneath
     * being built. A tailing log keeps thousands of line nodes and shows twenty of them, and without this every
     * one of the rest is turned into glyph quads, every frame, to be masked out — which is not merely wasteful:
     * it is what makes a scrollback overrun a fixed vertex buffer and take the window down with it. This is the
     * same reasoning {@code drawText} already applies to the visual lines <em>inside</em> one text node, applied
     * one level up to the nodes inside one container.
     *
     * <p><b>Why the slack.</b> A node is not guaranteed to draw inside its own box: text given a fixed height
     * smaller than it needs spills past the bottom of it rather than being cut off. So a box has to be a whole
     * box-length clear of the mask before it is dropped, which is far more than any spill and still culls all but
     * a line or two either side of a long list.
     *
     * <p>A box with no area is never culled. Its own rectangle says nothing about where its children are, and a
     * zero-sized wrapper around real content is a shape the layout is allowed to produce.
     */
    private boolean masked(RetainedNode child) {
        if (clipL == Float.NEGATIVE_INFINITY || child.w <= 0f || child.h <= 0f) {
            return false;   // nothing masks here, or nothing to judge it by
        }
        // A turned or scaled child reaches as far as its drawn corners, which may be well past its own box.
        float reach = child.rotation() == 0f && child.scale() <= 1f
                ? 0f : (float) Math.hypot(child.w, child.h) * Math.max(1f, child.scale());
        return child.x + child.w + child.w + reach < clipL
                || child.x - child.w - reach > clipR
                || child.y + child.h + child.h + reach < clipT
                || child.y - child.h - reach > clipB;
    }

    /**
     * {@code c} at the opacity inherited here. The single place the transform is applied: nothing in this class
     * passes a raw {@link Color} to the canvas.
     */
    private Color fade(Color c) {
        return c == null || alpha >= 1f ? c : Color.withAlpha(c, c.a() * alpha);
    }

    /** The node's own face — its family and style, before any span — for what it draws that is not its text. */
    private TextLayout faceFor(RetainedNode n) {
        return faces.layout(Styling.base(n));
    }

    /** Draw the reserved-space scrollbars (outlined track + pill thumb) for an overflowing container — chrome. */
    private void drawScrollbars(RetainedNode n, Canvas canvas) {
        float sb = n.scrollbarPx;
        if (n.overflowY) {
            track(canvas, n.viewX + n.viewW, n.viewY, sb, n.viewH);
            thumb(canvas, n.vThumbRect(), sb);
        }
        if (n.overflowX) {
            track(canvas, n.viewX, n.viewY + n.viewH, n.viewW, sb);
            thumb(canvas, n.hThumbRect(), sb);
        }
    }

    /** A lit pill floating just off its track: small shadow underneath, edge light on top — grabbable at a glance. */
    private void thumb(Canvas canvas, float[] t, float sb) {
        float r = sb * 0.3f;
        box(canvas, t[0], t[1], t[2], t[3], r, r, null, 0f, LIT_FILL, fade(scrollGrip), 2f, 0.08f, fade(shadow), 2.5f);
    }

    /** An outlined track: a border-coloured rounded rect with a subtle inner fill, so it reads against any panel. */
    private void track(Canvas canvas, float x, float y, float w, float h) {
        float r = Math.min(w, h) * 0.5f;
        canvas.fillRoundRect(x, y, w, h, r, fade(scrollEdge));
        canvas.fillRoundRect(x + 1.5f, y + 1.5f, Math.max(0f, w - 3f), Math.max(0f, h - 3f),
                Math.max(0f, r - 1.5f), fade(scrollTrough));
    }

    /**
     * The node's decoration, emitted after its whole subtree — see {@link dev.vexelray.gui.core.model.PropKey#OVERLAY}.
     * A separate call rather than a second line in {@link #drawSelf} because <em>when</em> is the whole of what
     * distinguishes the two slots: same value, same frame, same clip; the other end of the node.
     */
    private void drawOverlay(RetainedNode n, Canvas canvas, TextLayout text) {
        emitPicture(n, n.overlay(), canvas, text);
    }

    /**
     * Paint {@code picture} in {@code n}'s own pixel frame — {@code (0, 0)} at the box's top-left — clipped to the
     * border box.
     *
     * <p>Clipped because nothing measured it: a picture is authored geometry and there is no measure pass to catch
     * a mark that overshoots, so the clip is what makes "it stays in its box" true rather than hoped for. At the
     * <b>displaced</b> position, because a picture is the node's own paint and travels with it — a container's
     * clip is deliberately anchored where it was pushed (that is what lets a child slide inside it), and a node's
     * own picture is not a child, so it must not be trimmed by where the node would have been.
     */
    private void emitPicture(RetainedNode n, Picture picture, Canvas canvas, TextLayout text) {
        if (picture == null || picture.isEmpty() || n.w <= 0f || n.h <= 0f) {
            return;
        }
        canvas.pushClip(n.x + transX, n.y + transY, n.w, n.h, Math.max(n.cornerPx, n.cornerBottomPx));
        canvas.pushTranslate(n.x, n.y);
        picture.emitTo(new CanvasSink(canvas, text, alpha));
        canvas.popTranslate();
        canvas.popClip();
    }

    static final int NO_FILL = 0;
    static final int PLAIN_FILL = 1;
    static final int LIT_FILL = 2;

    /**
     * The looks a node's box is drawn with, by which of its parts it has: border or not, no fill or a plain or a lit
     * one, shadow or not. Twelve at most, whatever the colours, since every value is the element's own.
     */
    private static final dev.vexelray.canvas.shadelet.Look[] BOX_LOOKS = new dev.vexelray.canvas.shadelet.Look[12];

    /** {@code fill} as simplified graphics draws it: a lit fill plain, anything else as it is. */
    static int fillDrawn(int fill, boolean simplified) {
        return simplified && fill == LIT_FILL ? PLAIN_FILL : fill;
    }

    /** Whether a box asked to cast a shadow casts one: never in simplified graphics. */
    static boolean shadowDrawn(boolean shadow, boolean simplified) {
        return shadow && !simplified;
    }

    /** Whether letterpressed text is pressed (three passes) or drawn once, as simplified graphics draws it. */
    static boolean letterpressDrawn(boolean sunken, boolean simplified) {
        return sunken && !simplified;
    }

    static dev.vexelray.canvas.shadelet.Look boxLook(boolean border, int fill, boolean shadow) {
        int key = (border ? 1 : 0) + 2 * fill + 6 * (shadow ? 1 : 0);   // 2 x 3 x 2, densely
        dev.vexelray.canvas.shadelet.Look look = BOX_LOOKS[key];
        if (look == null) {
            java.util.List<dev.vexelray.canvas.shadelet.Look.Use> painters = new java.util.ArrayList<>();
            if (border) {
                painters.add(use(dev.vexelray.canvas.shadelet.CoreShadelets.NODE_STROKE));
            }
            if (fill == PLAIN_FILL) {
                painters.add(use(dev.vexelray.canvas.shadelet.CoreShadelets.NODE_FILL));
            } else if (fill == LIT_FILL) {
                painters.add(use(dev.vexelray.canvas.shadelet.CoreShadelets.NODE_LIT));
            }
            if (shadow) {
                painters.add(use(dev.vexelray.canvas.shadelet.CoreShadelets.NODE_SHADOW));
            }
            look = new dev.vexelray.canvas.shadelet.Look(use(dev.vexelray.canvas.shadelet.CoreShadelets.BOX),
                    java.util.List.of(), painters);
            BOX_LOOKS[key] = look;
        }
        return look;
    }

    private static dev.vexelray.canvas.shadelet.Look.Use use(dev.vexelray.canvas.shadelet.Shadelet shadelet) {
        return dev.vexelray.canvas.shadelet.Look.Use.of(shadelet.name());
    }

    /** A node's box: {@link #box(Canvas, float, float, float, float, float, float, Color, float, int, Color, float,
     *  float, Color, float)} at its own rect and corners, a lit fill with the node's gentle gradient. */
    private void box(Canvas canvas, RetainedNode n, Color border, float width, int fill, Color fillColour,
                     float bevel, Color shadow, float lift) {
        box(canvas, n.x, n.y, n.w, n.h, n.cornerPx, n.cornerBottomPx, border, width, fill, fillColour, bevel, 0.05f,
                shadow, lift);
    }

    /**
     * A box as one element: its border ({@code border} null for none) {@code width} wide, its fill ({@code fill} with
     * its colour; a lit fill bevelled {@code bevel} wide with a vertical {@code gradient}), its shadow ({@code shadow}
     * null for none) at {@code lift}, front to back, every value in the element's record in that order. A node's box,
     * and the renderer's own furniture: the plate, the focus ring, a scrollbar's grip.
     *
     * <p>Every box passes here, so this is where simplified graphics flattens them: a lit fill drawn plain and the
     * shadow dropped, before the record is written and the look chosen.
     */
    private void box(Canvas canvas, float x, float y, float w, float h, float rTop, float rBottom,
                     Color border, float width, int fill, Color fillColour, float bevel, float gradient,
                     Color shadow, float lift) {
        fill = fillDrawn(fill, simplified);
        if (!shadowDrawn(shadow != null, simplified)) {
            shadow = null;
        }
        if (border == null && fill == NO_FILL && shadow == null) {
            return;   // a shadow was all it had
        }
        float[] v = new float[16];
        int k = 0;
        if (border != null) {
            k = colour(v, k, border);
            v[k++] = width;
        }
        if (fill != NO_FILL) {
            k = colour(v, k, fillColour);
            if (fill == LIT_FILL) {
                v[k++] = bevel;
                v[k++] = gradient;
            }
        }
        if (shadow != null) {
            k = colour(v, k, shadow);
            v[k++] = lift;
        }
        // The node painters read their colours from the record, never the vertex's; it carries one of them anyway.
        Color vertex = fillColour != null ? fillColour : border != null ? border : shadow;
        canvas.box(x, y, w, h, rTop, rBottom, vertex, boxLook(border != null, fill, shadow != null),
                java.util.Arrays.copyOf(v, k));
    }

    private static int colour(float[] v, int k, Color c) {
        v[k] = c.r();
        v[k + 1] = c.g();
        v[k + 2] = c.b();
        v[k + 3] = c.a();
        return k + 4;
    }

    /** {@code settled}, the colour {@code n} was given, as the paint motion has it on its way there; null stays null. */
    private Color drawn(RetainedNode n, PaintMotion.Channel channel, Color settled) {
        return settled == null ? null : paint.drawn(n.id, channel, settled);
    }

    /**
     * {@code n}'s text colour on its way, the theme's ink when it never stated one. The ink fades too: it only ever
     * changes when the theme does, and a theme swap reports it (Reconciler.retheme).
     */
    private Color inkOf(RetainedNode n) {
        Color stated = n.textColor(theme);
        return paint.drawn(n.id, PaintMotion.Channel.TEXT, stated == null ? ink : stated);
    }

    private void drawSelf(RetainedNode n, Canvas canvas, TextLayout text) {
        // Border width, corner radius and text size were resolved to px by the layout pass (border-box), so the
        // renderer needs no units or layout context — it just paints the computed rect.
        Color bg = drawn(n, PaintMotion.Channel.BACKGROUND, n.background(theme));
        float rTop = n.cornerPx;
        float rBottom = n.cornerBottomPx;
        float bw = n.borderPx;
        Color border = drawn(n, PaintMotion.Channel.BORDER, n.borderColor(theme));
        boolean hasBorder = bw > 0f && border != null;
        // Elevation: an analytic soft shadow under the border-box, dropped slightly with the light overhead.
        boolean hasShadow = n.elevationPx > 0f && (bg != null || border != null);
        // Bevel scales with the root em (not this node's text size) so the edge light stays proportionate under zoom.
        float bevel = Math.max(2f, n.emPx * 0.22f);
        // An image, a field and a picture sit between the background and the border, so a border over any of them is
        // drawn after them, on its own; without them, the box is one element: border, fill and shadow in one quad.
        Picture picture = n.picture();
        boolean between = n.image() != null || n.field() != null || (picture != null && !picture.isEmpty());
        boolean borderNow = hasBorder && !between;
        int fill = bg == null ? NO_FILL : n.lit(theme) ? LIT_FILL : PLAIN_FILL;
        if (borderNow || fill != NO_FILL || hasShadow) {
            box(canvas, n, borderNow ? fade(border) : null, bw, fill, fill == NO_FILL ? null : fade(bg), bevel,
                    hasShadow ? fade(shadow) : null, n.elevationPx);
        }
        // An image sits between the background and the border: a background shows through anything transparent in
        // it, and a border frames it. It takes the node's own radius, so a viewport rounds with the card it is in.
        ImageHandle image = n.image();
        if (image != null && n.w > 0f && n.h > 0f) {
            // An alpha, not a colour: the tint multiplies the texel, so there is no shade to choose here — only
            // the subtree opacity this class applies to everything it draws.
            ImageRegion region = n.imageRegion();
            dev.vexelray.gui.core.ImageScaling scaling = n.imageScaling();
            // A texture whose sampler decodes sRGB gives linear light, which the shader must encode again.
            boolean linear = image instanceof dev.vexelray.vulkan.present.SampledImage s && s.linear();
            if (scaling != null || linear) {
                ImageRegion r = region == null ? ImageRegion.WHOLE : region;
                canvas.image(n.x, n.y, n.w, n.h, rTop, rBottom, image, r.u0(), r.v0(), r.u1(), r.v1(), alpha,
                        scaling == null ? null : scaling.filter(),
                        scaling == null ? 0 : scaling.textureWidth(), scaling == null ? 0 : scaling.textureHeight(),
                        linear);
            } else if (region == null || region.whole()) {
                canvas.image(n.x, n.y, n.w, n.h, rTop, rBottom, image, alpha);
            } else {
                // One cell of a sheet. Same primitive, same run — only the four texture coordinates differ, which
                // is why an animation costs no upload and no extra draw.
                canvas.image(n.x, n.y, n.w, n.h, rTop, rBottom, image,
                        region.u0(), region.v0(), region.u1(), region.v1(), alpha);
            }
        }
        // A distance-field shape, over the image: across the box, and out over its margin for a glow or a shadow.
        dev.vexelray.gui.core.ImageField field = n.field();
        if (field != null && n.w > 0f && n.h > 0f) {
            dev.vexelray.gui.core.ImageRegion cell = field.region();
            if (field.look() == null) {
                canvas.field(n.x, n.y, n.w, n.h, field.texture(), cell.u0(), cell.v0(), cell.u1(), cell.v1(),
                        field.side(), field.spread(), field.margin(), fade(field.tint()));
            } else {
                canvas.field(n.x, n.y, n.w, n.h, field.texture(), cell.u0(), cell.v0(), cell.u1(), cell.v1(),
                        field.side(), field.spread(), field.margin(), fade(field.tint()), field.look());
            }
        }
        // A drawing goes over the image and under the border: an application's own marks belong inside the frame
        // the node draws around them.
        emitPicture(n, picture, canvas, text);
        if (hasBorder && !borderNow) {
            box(canvas, n, fade(border), bw, NO_FILL, null, bevel, null, 0f);
        }

        String s = n.textString();
        float pad = n.textPadXPx;   // resolved by the layout — the renderer resolves no units of its own
        boolean hasText = s != null && !s.isEmpty();
        java.util.List<Span> spans = hasText ? n.spans() : java.util.List.of();

        drawText(n, s, hasText, spans, pad, canvas, text);
    }

    /**
     * Draw a text node from its published {@link TextMetrics} — the single source of truth for where every glyph,
     * selection rect and caret sits (docs/reference/layout-read-model.md §11.4). The compute phase resolved scroll and
     * alignment and baked each visual line's absolute caret x, so the renderer measures and aligns nothing.
     *
     * <p>Labels and fields share this path. They used to differ: a label drew through {@code canvas.text} with
     * its own align and wrap while its metrics described a left-aligned block, so a centred label's published
     * geometry was a lie to every consumer except this renderer. That was a broken invariant, not a missing
     * feature — the read-model is supposed to describe what is drawn, and the only way to guarantee it is for the
     * drawing to come from the read-model.
     */
    private void drawText(RetainedNode n, String s, boolean hasText, java.util.List<Span> spans, float pad,
                                 Canvas canvas, TextLayout text) {
        TextMetrics m = n.textMetrics;
        if (m == null) {
            return;   // not a text node, or a measurer with no glyph metrics resolved none
        }
        // The metrics were baked by the compute phase, which runs *before* the frame's layout displacement is
        // applied — so every coordinate in them is in the node's settled frame, while n.x and n.viewX are in its
        // drawn one. Drawing the two together is what leaves a moving label's glyphs behind its box: the fill
        // slides, the text does not, and only for nodes a motion source has enrolled, which is the tearing.
        //
        // So the whole text block is drawn in the settled frame and carried to the drawn one by a translate.
        // That is also exactly how Node.translate has always moved text, and the reason that one never had this
        // bug — a transform around the drawing moves everything the drawing is made of, which is the property a
        // per-coordinate fix would have had to re-earn at every call site below.
        float mdx = displacedX(n);
        float mdy = displacedY(n);
        boolean displaced = mdx != 0f || mdy != 0f;
        if (displaced) {
            canvas.pushTranslate(mdx, mdy);
        }
        try {
            drawTextSettled(n, m, s, hasText, spans, canvas, text);
        } finally {
            if (displaced) {
                canvas.popTranslate();
            }
        }
    }

    /**
     * How far this node is drawn from where layout settled it — the displacement a {@link
     * dev.vexelray.gui.core.layout.LayoutMotion} is carrying, re-derived rather than asked for, because the
     * renderer holds no motion source and the two positions are both on the node.
     *
     * <p>Zero for a node that has never been settled: {@code layoutX} is then not a position it was ever at, and
     * displacing from it would throw the node across the window on its first frame.
     */
    private static float displacedX(RetainedNode n) {
        return n.layoutRectKnown ? n.x - n.layoutX : 0f;
    }

    private static float displacedY(RetainedNode n) {
        return n.layoutRectKnown ? n.y - n.layoutY : 0f;
    }

    /** The settled-frame origin of the border box — where the metrics think the node is. */
    private static float settledX(RetainedNode n) {
        return n.x - displacedX(n);
    }

    private static float settledY(RetainedNode n) {
        return n.y - displacedY(n);
    }

    /** And of the content viewport, which the displacement moves by the same amount. */
    private static float settledViewX(RetainedNode n) {
        return n.viewX - displacedX(n);
    }

    private static float settledViewY(RetainedNode n) {
        return n.viewY - displacedY(n);
    }

    /**
     * Everything from here down is in the settled frame: metric coordinates and box coordinates agree, and the
     * caller's translate is what puts the result where the node is actually drawn.
     */
    private void drawTextSettled(RetainedNode n, TextMetrics m, String s, boolean hasText,
                                 java.util.List<Span> spans, Canvas canvas, TextLayout text) {
        // An empty document still publishes metrics — one empty visual line — so a focused empty field draws its
        // caret and a numbered editor its "1". The glyph/selection/span loops below are naturally no-ops on "".
        if (!hasText) {
            s = "";
        }
        // The gutter sits outside the text viewport, so it is drawn first, under its own clip: it scrolls
        // vertically with the lines but never horizontally with them.
        if (n.gutterPx > 0f) {
            drawGutter(n, m, canvas, text);
        }

        // Only an editable node masks: it is the one that scrolls, and clipping a label would newly hide text
        // that a too-short fixed height has always been allowed to spill. The viewport excludes the h-scrollbar
        // strip when there is one, so text never draws underneath the bar.
        boolean clip = n.editable();
        if (clip) {
            canvas.pushClip(settledViewX(n), settledViewY(n), Math.max(1f, n.viewW), Math.max(1f, n.viewH), 0f);
        }

        // Cull lines outside the scroll viewport: the clip would mask them anyway, but their glyph quads would
        // still be generated — an unbounded document must not translate into unbounded per-frame vertex data.
        float cullLo = clip ? settledViewY(n) : Float.NEGATIVE_INFINITY;
        float cullHi = clip ? settledViewY(n) + n.viewH : Float.POSITIVE_INFINITY;
        int selLo = Math.min(n.selectStart(), n.selectEnd());
        int selHi = Math.max(n.selectStart(), n.selectEnd());
        // The face of every character, resolved once for the node — the same resolution the measurer made, so a
        // bold run is drawn in the face whose advances placed it.
        int[] faceIds = faces.faceIds(new Styling(n.font(), n.fontWeight(), n.fontSlope(), spans), s);
        for (TextMetrics.VisualLine line : m.lines()) {
            if (line.top() + line.height() < cullLo || line.top() > cullHi) {
                continue;
            }
            // Narrow the span set to this line once: drawLineText consults the spans per character run, so
            // handing it the whole document's spans makes a densely highlighted document O(chars x spans)
            // per frame. Intersecting here keeps that inner loop proportional to what is on the line.
            java.util.List<Span> lineSpans = spansOn(spans, line);
            for (Span sp : lineSpans) {
                if (sp.bg() != null) {
                    fillLineRange(line, sp.start(), sp.end(), fade(sp.bg()), canvas);
                }
            }
            if (selHi > selLo) {
                fillLineRange(line, selLo, selHi, fade(selection), canvas);
            }
            drawLineText(n, s, line, lineSpans, faceIds, canvas);
            drawMarks(n, s, line, canvas);
            for (Span sp : lineSpans) {
                if (sp.underline()) {
                    underlineLineRange(n, line, sp.start(), sp.end(),
                            fade(sp.fg() != null ? sp.fg() : inkOf(n)), canvas, text);
                }
            }
        }

        // The caret: a thin bar on its own visual line, only while shown this blink phase.
        if (n.caret() >= 0 && n.caretOn()) {
            int caret = n.caret();
            float w = Math.max(1f, n.textSizePx * 0.07f);
            canvas.fillRoundRect(m.caretX(caret), m.caretTop(caret), w, m.caretHeight(caret), 0f,
                    fade(inkOf(n)));
        }
        if (clip) {
            canvas.popClip();
        }
    }

    /**
     * Right-aligned hard-line numbers in the gutter. Only lines that <em>begin</em> a hard line carry a number
     * (the metrics decided that, not the renderer), so wrapped continuations are blank — which is what makes a
     * wrapped line read as one line.
     */
    private void drawGutter(RetainedNode n, TextMetrics m, Canvas canvas, TextLayout text) {
        float px = n.textSizePx;
        float left = settledX(n) + n.textPadXPx;
        float right = left + n.gutterPx - n.gutterPadPx;
        canvas.pushClip(left, settledViewY(n), Math.max(1f, n.gutterPx), Math.max(1f, n.viewH), 0f);
        for (TextMetrics.VisualLine line : m.lines()) {
            if (line.number() <= 0
                    || line.top() + line.height() < settledViewY(n) || line.top() > settledViewY(n) + n.viewH) {
                continue; // unnumbered continuation, or culled outside the gutter's viewport (same as the text)
            }
            String label = Integer.toString(line.number());
            float w = text.glyphLayout().measure(label, px);
            TextLayout.TextStyle style = TextLayout.TextStyle.of(px)
                    .withWrap(TextLayout.WrapMode.NONE)
                    .withAlign(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
            canvas.text(text, label, right - w, line.top(), Math.max(1f, w), line.height(), style, fade(gutterInk));
        }
        canvas.popClip();
    }

    /** The spans whose range intersects {@code line} — the only ones any per-line drawing can need. */
    private static java.util.List<Span> spansOn(java.util.List<Span> spans, TextMetrics.VisualLine line) {
        if (spans.isEmpty()) {
            return spans;
        }
        java.util.List<Span> out = new java.util.ArrayList<>();
        for (Span sp : spans) {
            if (sp.start() < line.end() && sp.end() > line.start()) {
                out.add(sp);
            }
        }
        return out;
    }

    /** Fill the part of character range {@code [start, end)} that falls on {@code line}, using its baked xs. */
    private static void fillLineRange(TextMetrics.VisualLine line, int start, int end, Color color, Canvas canvas) {
        int lo = Math.max(line.start(), Math.min(start, end));
        int hi = Math.min(line.end(), Math.max(start, end));
        if (hi <= lo) {
            return;
        }
        float x0 = line.caretX(lo);
        float x1 = line.caretX(hi);
        canvas.fillRoundRect(x0, line.top(), Math.max(1f, x1 - x0), line.height(), 0f, color);
    }

    /**
     * Draw one visual line's glyphs, split into maximal runs of one effective foreground colour in one face. Each
     * run starts at its baked x, which already counts every earlier run's own face's advances, so a bold word
     * pushes what follows it exactly as far as the metrics said.
     */
    private void drawLineText(RetainedNode n, String s, TextMetrics.VisualLine line,
                                     java.util.List<Span> spans, int[] faceIds, Canvas canvas) {
        int from = Math.max(0, line.start());
        int to = Math.min(s.length(), line.end());
        int i = from;
        Color base = inkOf(n);
        while (i < to) {
            Color fg = fgAt(spans, i, base);
            int face = faceIds[i];
            int j = i + 1;
            while (j < to && faceIds[j] == face && java.util.Objects.equals(fgAt(spans, j, base), fg)) {
                j++;
            }
            TextLayout text = faces.layout(face);
            // Each run is drawn at its exact baked x, in a box exactly one line high, so the canvas does no
            // alignment or wrapping of its own — the metrics already decided all of it.
            TextLayout.TextStyle style = TextLayout.TextStyle.of(n.textSizePx)
                    .withWrap(TextLayout.WrapMode.NONE)
                    .withAlign(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
            float x = line.caretX(i);
            float wRun = Math.max(1f, settledX(n) + n.w - x);
            // Runs are split on the unfaded colour: the fade is uniform across the line, so it cannot merge or
            // divide a run, and comparing before it keeps the grouping independent of the current opacity.
            Color faded = fade(fg);
            if (letterpressDrawn(n.textSunken(theme), simplified)) {
                // Letterpress: the press depth scales with the type size, so the effect survives zoom.
                canvas.textSunken(text, s.substring(i, j), x, line.top(), wRun, line.height(), style, faded,
                        Math.max(1f, n.textSizePx * 0.07f));
            } else {
                canvas.text(text, s.substring(i, j), x, line.top(), wRun, line.height(), style, faded);
            }
            i = j;
        }
    }

    /**
     * The node's whitespace dots and line-end marks on one visual line, in the faint ink. Both are shapes rather
     * than glyphs: a middle dot or a pilcrow is only as present as the atlas the application baked, and a mark
     * that renders as the missing-glyph box is worse than none.
     */
    private void drawMarks(RetainedNode n, String s, TextMetrics.VisualLine line, Canvas canvas) {
        Whitespace mode = n.whitespace();
        boolean ends = n.lineEnds() && line.end() < s.length() && s.charAt(line.end()) == '\n';
        if (mode == Whitespace.NONE && !ends) {
            return;
        }
        float px = n.textSizePx;
        Color ink = fade(marks);
        float mid = line.top() + line.height() * 0.55f;
        float dot = Math.max(1.5f, px * 0.12f);
        mode.mark(s, line.start(), line.end(), i -> {
            float cx = (line.caretX(i) + line.caretX(i + 1)) * 0.5f;
            canvas.fillRoundRect(cx - dot * 0.5f, mid - dot * 0.5f, dot, dot, dot * 0.5f, ink);
        });
        if (ends) {
            // A "¬": a bar at mid-height with a short drop at its right end, a small gap past the last character.
            float stroke = Math.max(1f, px * 0.07f);
            float x = line.caretX(line.end()) + px * 0.15f;
            float w = px * 0.4f;
            canvas.fillRoundRect(x, mid - stroke * 0.5f, w, stroke, 0f, ink);
            canvas.fillRoundRect(x + w - stroke, mid - stroke * 0.5f, stroke, px * 0.2f, 0f, ink);
        }
    }

    /** Underline the part of {@code [start, end)} falling on {@code line}, just below its baseline. */
    private static void underlineLineRange(RetainedNode n, TextMetrics.VisualLine line, int start, int end,
                                           Color color, Canvas canvas, TextLayout text) {
        int lo = Math.max(line.start(), Math.min(start, end));
        int hi = Math.min(line.end(), Math.max(start, end));
        if (hi <= lo) {
            return;
        }
        float thickness = Math.max(1f, n.textSizePx * 0.06f);
        float baseline = line.top() + text.glyphLayout().ascent(n.textSizePx);
        canvas.fillRoundRect(line.caretX(lo), baseline + thickness,
                Math.max(1f, line.caretX(hi) - line.caretX(lo)), thickness, 0f, color);
    }

    /** The effective foreground colour at character {@code i}: the last fg span covering it, else {@code base}. */
    private static Color fgAt(java.util.List<Span> spans, int i, Color base) {
        Color fg = base;
        for (Span sp : spans) {
            if (sp.fg() != null && sp.covers(i)) {
                fg = sp.fg();
            }
        }
        return fg;
    }
}
