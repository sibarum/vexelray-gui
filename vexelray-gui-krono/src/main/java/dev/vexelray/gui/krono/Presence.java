package dev.vexelray.gui.krono;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.EnterMotion;
import dev.vexelray.gui.core.layout.ExitMotion;
import dev.vexelray.gui.core.model.RetainedNode;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nodes arriving and leaving: an {@link EnterMotion} and {@link ExitMotion} in one, that fades a node up from nothing
 * as it is added and down to nothing before it is removed, timed on the GUI's frame clock.
 *
 * <pre>{@code
 * Presence presence = Presence.on(krono).rise(0.35f);
 * presence.followSubtree(page, Dur.ms(140), Ease.OUT_CUBIC);   // anything added under page fades in; removed, out
 * gui.enterMotion(presence).exitMotion(presence);
 *
 * row.remove();       // fades out where it stands, stops taking the pointer at once, then leaves the tree
 * list.append(row);   // put back before it had gone: comes back up from where it got to
 * }</pre>
 *
 * <h2>The rule it completes</h2>
 *
 * <p>Things fade in and out; everything else slides to make room. {@link Fades} is a colour changing,
 * {@link Transitions} a node moving, and this is a node arriving or going — the two changes that have no "from" for a
 * transition to start at. An arriving node takes its place in the layout at once, invisible, and its neighbours slide
 * aside; a leaving one keeps its place until it has faded, and then they slide into the gap. Only one clock ever moves
 * a position, so the two never pull the picture apart.
 *
 * <h2>Interruption does not jump</h2>
 *
 * <p>A node removed while it is still arriving leaves from the presence it had reached, and one put back while it is
 * leaving arrives from where it got to, without rising again: the picture is continuous across any number of
 * changes of mind. That is the case the framework's {@code remove()} path exists for — a node taken back before it
 * had gone is the same node, its handlers and widget state intact, not a stranger built to look like it.
 *
 * <h2>Enrolment is opt-in, per node</h2>
 *
 * <p>The same three granularities as {@link Transitions}, with the same reason for each and the same rule that the
 * nearest enrolment wins: {@link #follow} for one node, {@link #followChildren} for a container's rows, and
 * {@link #followSubtree} for everything under a page. Most additions should not fade: a virtualised list adding rows as
 * it scrolls is showing what was always there, so enrol the containers whose contents genuinely come and go.
 *
 * <p>{@link Dur#ZERO} enrols nodes that appear and disappear at once — the honest reduced-motion setting.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #entered} and {@link #leave} are called on the GUI thread, the ramps run on the timeline, and the presence
 * is published between them without a lock, as {@link Transitions} publishes a displacement: one float that only ever
 * heads toward its end, which a reader a frame late draws a shade off.
 */
public final class Presence implements EnterMotion, ExitMotion {

    /** How a node is enrolled: how long it takes to arrive or go, and on what curve. */
    private record Terms(Dur over, Ease ease) {
    }

    /**
     * One node's presence on its way somewhere. {@code epoch} lets a superseded ramp find out it has lost and stop
     * writing, as {@code Transitions.Lag} does.
     */
    private static final class Fade {
        volatile float value;
        /** How far below its place the node is drawn, in multiples of its em; decays with the arrival. */
        volatile float riseEm;
        volatile int epoch;
    }

    private final KronoGui krono;
    private final Map<Long, Terms> followed = new ConcurrentHashMap<>();
    private final Map<Long, Terms> followedChildren = new ConcurrentHashMap<>();
    private final Map<Long, Terms> followedSubtree = new ConcurrentHashMap<>();
    private final Map<Long, Fade> fades = new ConcurrentHashMap<>();
    private volatile float rise;

    private Presence(KronoGui krono) {
        this.krono = Objects.requireNonNull(krono, "krono");
    }

    /**
     * Presence timed on {@code krono}'s frame clock. Attach with {@link dev.vexelray.gui.core.Gui#enterMotion} and
     * {@link dev.vexelray.gui.core.Gui#exitMotion} — usually both, the same object.
     */
    public static Presence on(KronoGui krono) {
        return new Presence(krono);
    }

    /**
     * Have an arriving node rise into place from {@code em} below it, a little as it fades in: what makes an arrival
     * read as something coming rather than something lighting up. In root ems, so it scales with zoom. 0 (the
     * default) only fades. A departure never sinks: it is going, not going somewhere.
     */
    public Presence rise(float em) {
        this.rise = em;
        return this;
    }

    /** Enrol {@code node} itself: added, it fades in; removed, it fades out first. */
    public Presence follow(Node node, Dur over, Ease ease) {
        followed.put(node.id(), terms(over, ease));
        return this;
    }

    /**
     * Enrol {@code container}'s direct children, whenever they were added — what a list wants, since its rows are what
     * come and go. The container itself is not enrolled by this.
     */
    public Presence followChildren(Node container, Dur over, Ease ease) {
        followedChildren.put(container.id(), terms(over, ease));
        return this;
    }

    /**
     * Enrol everything under {@code root} at any depth — what a page wants. Only the top of anything added arrives
     * (a subtree built and then attached fades in as one), so this costs nothing per descendant. {@code root} itself
     * is not enrolled.
     */
    public Presence followSubtree(Node root, Dur over, Ease ease) {
        followedSubtree.put(root.id(), terms(over, ease));
        return this;
    }

    /** Stop fading {@code node} and what it enrolled; anything of its own on the way finishes at once. */
    public Presence unfollow(Node node) {
        long id = node.id();
        followed.remove(id);
        followedChildren.remove(id);
        followedSubtree.remove(id);
        Fade fade = fades.remove(id);
        if (fade != null) {
            fade.epoch++;
        }
        return this;
    }

    /** Whether nothing is arriving or leaving: for a test, or a host asking whether the picture is at rest. */
    public boolean settled() {
        return fades.isEmpty();
    }

    // ------------------------------------------------------------ EnterMotion

    @Override
    public void entered(RetainedNode node) {
        Terms terms = termsFor(node);
        Fade was = fades.get(node.id);
        if (terms == null || terms.over().nanos() <= 0L) {
            if (was != null) {
                was.epoch++;
                fades.remove(node.id, was);
            }
            return;
        }
        // A node coming back is drawn at whatever it had got down to, and arrives from there without rising again:
        // it never went anywhere. A new one starts from nothing, below its place.
        Fade fade = was != null ? was : new Fade();
        float from = was != null ? was.value : 0f;
        float riseFrom = was != null ? was.riseEm : rise;
        int epoch = ++fade.epoch;   // GUI thread is the only writer of the epoch
        fade.value = from;
        fade.riseEm = riseFrom;
        fades.put(node.id, fade);
        krono.ramp(terms.over(), terms.ease(),
                progress -> {
                    if (fade.epoch == epoch) {
                        fade.value = (float) (from + (1d - from) * progress);
                        fade.riseEm = (float) (riseFrom * (1d - progress)) + 0f;
                    }
                },
                () -> {
                    if (fade.epoch == epoch) {
                        fade.value = 1f;
                        fade.riseEm = 0f;
                        fades.remove(node.id, fade);
                    }
                });
    }

    @Override
    public float presence(RetainedNode node) {
        Fade fade = fades.get(node.id);
        return fade == null ? (node.leaving ? 0f : 1f) : fade.value;
    }

    @Override
    public float rise(RetainedNode node) {
        Fade fade = fades.get(node.id);
        return fade == null ? 0f : fade.riseEm * node.emPx;
    }

    // ------------------------------------------------------------ ExitMotion

    @Override
    public boolean leave(RetainedNode node) {
        Terms terms = termsFor(node);
        if (terms == null || terms.over().nanos() <= 0L) {
            return false;
        }
        Fade was = fades.get(node.id);
        Fade fade = was != null ? was : new Fade();
        // From where it is drawn: mid-arrival, that is part of the way up, and going down from full would flash.
        float from = was != null ? was.value : node.presence;
        int epoch = ++fade.epoch;
        fade.value = from;
        fade.riseEm = 0f;   // it stops rising where it is: a departure is not a reversed arrival
        fades.put(node.id, fade);
        if (from <= 0f) {
            return true;    // already nothing: the frame detaches it at once
        }
        // Linear whatever the enrolment says: a fade out has nowhere to arrive, and an eased one lingers near the end.
        krono.ramp(terms.over(), Ease.LINEAR,
                progress -> {
                    if (fade.epoch == epoch) {
                        fade.value = (float) (from * (1d - progress)) + 0f;
                    }
                },
                () -> {
                    if (fade.epoch == epoch) {
                        fade.value = 0f;   // stays, at nothing, until the node is released: see left()
                    }
                });
        return true;
    }

    @Override
    public void left(RetainedNode node) {
        Fade fade = fades.remove(node.id);
        if (fade != null) {
            fade.epoch++;
        }
    }

    /** The terms that apply to {@code node}: its own, else its parent's {@code followChildren}, else the nearest
     * enclosing {@code followSubtree} — the rule {@link Transitions} uses, for the same reasons. */
    private Terms termsFor(RetainedNode node) {
        Terms own = followed.get(node.id);
        if (own != null) {
            return own;
        }
        RetainedNode parent = node.parent;
        if (parent == null) {
            return null;
        }
        Terms asChild = followedChildren.get(parent.id);
        if (asChild != null) {
            return asChild;
        }
        for (RetainedNode up = parent; up != null; up = up.parent) {
            Terms subtree = followedSubtree.get(up.id);
            if (subtree != null) {
                return subtree;
            }
        }
        return null;
    }

    private static Terms terms(Dur over, Ease ease) {
        return new Terms(Objects.requireNonNull(over, "over"), Objects.requireNonNull(ease, "ease"));
    }

    @Override
    public String toString() {
        return "Presence[following=" + (followed.size() + followedChildren.size() + followedSubtree.size())
                + ", inFlight=" + fades.size() + "]";
    }
}
