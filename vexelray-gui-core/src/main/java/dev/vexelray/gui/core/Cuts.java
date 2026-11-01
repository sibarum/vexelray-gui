package dev.vexelray.gui.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.style.PaintMotion;
import dev.vexelray.gui.core.style.Theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A watch for what changes on screen in one frame: the check that motion is still there. Nothing an application shows
 * should change in a single frame (vexelray-gui's docs/plans/motion-by-default.md), and the framework fades colours,
 * opacities, words, images, and nodes arriving, leaving, hidden and shown. This sees, frame by frame, any of those that
 * landed at once instead, so a test fails when motion is lost rather than when somebody happens to notice.
 *
 * {@snippet :
 * Cuts cuts = Cuts.watch(gui);
 * // ... drive the window, running frames ...
 * assertEquals(List.of(), cuts.found(), "something changed in one frame");
 * }
 *
 * <h2>What is a cut</h2>
 *
 * <p>Between one frame and the next:
 * <ul>
 *   <li>on a node on screen in both, a background, border or text colour, or an opacity, given a new value and drawn
 *       at it already, with nothing fading there;</li>
 *   <li>on such a node, words replaced (not a field's, which are the user's to see at once), or an image replaced, with
 *       nothing crossfading them;</li>
 *   <li>a node appearing under one that was already on screen, drawn whole in its first frame;</li>
 *   <li>a node gone from under one still on screen without having been seen leaving.</li>
 * </ul>
 *
 * <p><b>What is not.</b> A node that says its look is data ({@code paintCuts}), and the children of a container that
 * moves its own ({@code childMotion(false)}). A value given anew at two frames running, which is driven by a clock of
 * its own and followed rather than faded behind. The first frame watched, which has nothing before it. Anything under
 * what arrived or left, which is the top's to have done. A node at no opacity, which nobody sees change.
 *
 * <p>A watch walks the tree at every frame, so it is for tests and checks, not for a running application. Positions
 * are not watched: a layout move already slides, and a resize or a zoom moves everything on purpose.
 */
public final class Cuts {

    /** One thing changed in one frame: which node (its landmark, else its role, else its id), and what. */
    public record Cut(long node, String name, String what) {

        @Override
        public String toString() {
            return name + ": " + what;
        }
    }

    private final Gui gui;
    private final List<Cut> found = new ArrayList<>();
    /** What each node on screen was at the last frame watched; null before the first. */
    private Map<Long, Seen> last;

    private Cuts(Gui gui) {
        this.gui = gui;
    }

    /** Start watching {@code gui}'s frames. What is found accumulates until {@link #clear}. */
    public static Cuts watch(Gui gui) {
        Cuts cuts = new Cuts(gui);
        gui.cuts(cuts);
        return cuts;
    }

    /** Stop watching. */
    public void close() {
        gui.cuts(null);
    }

    /** Every cut found since the watch began or was last cleared, in the order found. */
    public synchronized List<Cut> found() {
        return List.copyOf(found);
    }

    /** Forget what was found so far. */
    public synchronized void clear() {
        found.clear();
    }

    /**
     * A node as one frame drew it: each look as given ({@code given*}) and as drawn, what it said and showed, whether it
     * was seen leaving or crossfading, and when each look was last given anew ({@code changedAt}, by the bits below).
     */
    private record Seen(long parent, boolean parentOwnsChildren, String name, Color givenBackground, Color background,
                        Color givenBorder, Color border, Color givenInk, Color ink, float givenOpacity, float opacity,
                        float presence, String words, Object image, boolean leaving, boolean replacing,
                        long[] changedAt) {
    }

    private static final int BACKGROUND = 1;
    private static final int BORDER = 2;
    private static final int INK = 4;
    private static final int OPACITY = 8;
    private static final int WORDS = 16;
    private static final int IMAGE = 32;

    /**
     * How close together two writes of a look may come and still be a value being driven, followed rather than faded:
     * the paint motion's own rule ({@code Fades.DRIVEN_GAP_NANOS}), so a look it draws at once on purpose is not a cut.
     */
    private static final long DRIVEN_GAP_NANOS = 40_000_000L;

    /** When the frame being watched was. */
    private long nowNanos;

    private static final long NEVER = Long.MIN_VALUE / 2;

    /** One frame, after the paint motion was asked for it. GUI thread. */
    synchronized void frame(RetainedNode root, PaintMotion paint, Theme theme) {
        nowNanos = System.nanoTime();
        Map<Long, Seen> now = new HashMap<>();
        walk(root, paint, theme, now);
        if (last != null) {
            for (Map.Entry<Long, Seen> e : last.entrySet()) {
                Seen s = e.getValue();
                if (!now.containsKey(e.getKey()) && now.containsKey(s.parent) && !s.parentOwnsChildren && !s.leaving
                        && s.presence > 0f && s.opacity > 0f) {
                    found.add(new Cut(e.getKey(), s.name, "gone in one frame"));
                }
            }
        }
        last = now;
    }

    private void walk(RetainedNode n, PaintMotion paint, Theme theme, Map<Long, Seen> now) {
        if (!n.visible() || n.layoutCtx == null) {
            return;   // not on screen, nor anything under it
        }
        Seen before = last == null ? null : last.get(n.id);
        boolean ownsMe = n.parent != null && n.parent.ownsChildMotion();
        Color givenBackground = n.background(theme);
        Color givenBorder = n.borderColor(theme);
        Color givenInk = n.textColor(theme);
        float givenOpacity = n.opacity();
        float opacity = n.raw(PropKey.OPACITY) == null ? 1f
                : paint.drawn(n.id, PaintMotion.Channel.OPACITY, PaintMotion.amount(givenOpacity)).a();
        Color background = drawn(paint, n, PaintMotion.Channel.BACKGROUND, givenBackground);
        Color border = drawn(paint, n, PaintMotion.Channel.BORDER, givenBorder);
        Color ink = drawn(paint, n, PaintMotion.Channel.TEXT, givenInk);
        String words = n.textString();
        Object image = n.image();
        String name = name(n);

        int changed = 0;
        if (before != null) {
            changed |= Objects.equals(before.givenBackground, givenBackground) ? 0 : BACKGROUND;
            changed |= Objects.equals(before.givenBorder, givenBorder) ? 0 : BORDER;
            changed |= Objects.equals(before.givenInk, givenInk) ? 0 : INK;
            changed |= before.givenOpacity == givenOpacity ? 0 : OPACITY;
            changed |= Objects.equals(before.words, words) ? 0 : WORDS;
            changed |= before.image == image ? 0 : IMAGE;
            if (!ownsMe && !n.paintCuts() && opacity > 0f) {
                judge(n, name, before, changed, background, givenBackground, border, givenBorder, ink, givenInk,
                        opacity, givenOpacity);
            }
        } else if (last != null && n.parent != null && last.containsKey(n.parent.id) && !ownsMe
                && n.presence >= 1f && opacity > 0f) {
            found.add(new Cut(n.id, name, "appeared whole in one frame"));
        }
        now.put(n.id, new Seen(n.parent == null ? -1L : n.parent.id, ownsMe, name, givenBackground, background,
                givenBorder, border, givenInk, ink, givenOpacity, opacity, n.presence, words, image, n.leaving,
                n.replacing, changedAt(changed, before)));
        if (before == null) {
            // Arrived this frame (or the first frame watched): what is under it arrived with it.
            for (RetainedNode c : n.children) {
                note(c, paint, theme, now);
            }
            return;
        }
        for (RetainedNode c : n.children) {
            walk(c, paint, theme, now);
        }
    }

    /** Note {@code n} and what is under it as seen, judging none of it: it arrived with something above it. */
    private void note(RetainedNode n, PaintMotion paint, Theme theme, Map<Long, Seen> now) {
        if (!n.visible() || n.layoutCtx == null) {
            return;
        }
        float opacity = n.raw(PropKey.OPACITY) == null ? 1f
                : paint.drawn(n.id, PaintMotion.Channel.OPACITY, PaintMotion.amount(n.opacity())).a();
        now.put(n.id, new Seen(n.parent == null ? -1L : n.parent.id, n.parent != null && n.parent.ownsChildMotion(),
                name(n), n.background(theme), drawn(paint, n, PaintMotion.Channel.BACKGROUND, n.background(theme)),
                n.borderColor(theme), drawn(paint, n, PaintMotion.Channel.BORDER, n.borderColor(theme)),
                n.textColor(theme), drawn(paint, n, PaintMotion.Channel.TEXT, n.textColor(theme)), n.opacity(),
                opacity, n.presence, n.textString(), n.image(), n.leaving, n.replacing,
                changedAt(0, null)));
        for (RetainedNode c : n.children) {
            note(c, paint, theme, now);
        }
    }

    /**
     * {@code n} against what it was: a look given anew and already drawn at it, with nothing fading there, is a cut,
     * unless it was also given anew at the frame before, which is a look being driven and followed.
     */
    private void judge(RetainedNode n, String name, Seen before, int changed, Color background, Color givenBackground,
                       Color border, Color givenBorder, Color ink, Color givenInk, float opacity, float givenOpacity) {
        colour(n, name, "background", before, changed, BACKGROUND, before.background, background, givenBackground);
        colour(n, name, "border", before, changed, BORDER, before.border, border, givenBorder);
        colour(n, name, "text colour", before, changed, INK, before.ink, ink, givenInk);
        if (fresh(changed, before, OPACITY) && opacity == givenOpacity && before.opacity != opacity) {
            found.add(new Cut(n.id, name, "opacity " + before.opacity + " -> " + opacity + " in one frame"));
        }
        // Replaced again while the last was still crossfading is a readout being driven, followed at once on purpose.
        if (fresh(changed, before, WORDS) && !n.replacing && !before.replacing && !n.editable()) {
            found.add(new Cut(n.id, name, "words \"" + before.words + "\" -> \"" + n.textString() + "\" in one frame"));
        }
        if (fresh(changed, before, IMAGE) && !n.imageReplaced && !before.replacing) {
            found.add(new Cut(n.id, name, "image replaced in one frame"));
        }
    }

    private void colour(RetainedNode n, String name, String which, Seen before, int changed, int bit, Color was,
                        Color drawn, Color given) {
        if (fresh(changed, before, bit) && was != null && drawn != null && !was.equals(drawn) && drawn.equals(given)) {
            found.add(new Cut(n.id, name, which + " " + was + " -> " + drawn + " in one frame"));
        }
    }

    /** Whether {@code bit} was given anew at this frame, and not so soon after the last time that it is being driven. */
    private boolean fresh(int changed, Seen before, int bit) {
        long last = before.changedAt[Integer.numberOfTrailingZeros(bit)];
        return (changed & bit) != 0 && nowNanos - last > DRIVEN_GAP_NANOS;
    }

    /** When each look was last given anew, as of this frame: now for those in {@code changed}. */
    private long[] changedAt(int changed, Seen before) {
        long[] at = new long[6];
        for (int i = 0; i < at.length; i++) {
            at[i] = (changed & (1 << i)) != 0 ? nowNanos : before == null ? NEVER : before.changedAt[i];
        }
        return at;
    }

    private static Color drawn(PaintMotion paint, RetainedNode n, PaintMotion.Channel channel, Color given) {
        return given == null ? null : paint.drawn(n.id, channel, given);
    }

    /** What a cut calls {@code n}: its landmark, else its role, else its id. */
    private String name(RetainedNode n) {
        String landmark = gui.landmarkName(n.id);
        if (landmark != null && !landmark.isEmpty()) {
            return landmark;
        }
        return n.role().isEmpty() ? "node " + n.id : n.role() + " " + n.id;
    }
}
