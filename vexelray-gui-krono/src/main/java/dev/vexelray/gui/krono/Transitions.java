package dev.vexelray.gui.krono;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutMotion;
import dev.vexelray.gui.core.model.RetainedNode;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nodes catching up with their own layout: a {@link LayoutMotion} that turns every move into a decaying
 * displacement, timed on the GUI's frame clock.
 *
 * <pre>{@code
 * Transitions moves = Transitions.on(krono);
 * moves.followChildren(treeBody, Dur.ms(180), Ease.OUT_CUBIC);   // rows slide when the tree is reordered
 * gui.motion(moves);
 * }</pre>
 *
 * <h2>What it is for, and what it is not</h2>
 *
 * <p>It animates a node <em>arriving where the model already says it is</em>. The model change is instantaneous
 * and has already happened; only the picture is behind. That is the right shape for a reorder, an expand, a
 * removal — and it is the reason undo animates for free: a {@code Change} that puts a row back is a layout
 * change like any other, and nothing here knows or cares which direction the user was going.
 *
 * <p>It is <b>not</b> for a thing the pointer is currently carrying. A dragged element must track the pointer
 * exactly, and a transition is precisely a lag — routing a drag through here would add smoothing to the one
 * motion the user is steering directly, which reads as the element being slippery. Drag the overlay; transition
 * the hole it left behind.
 *
 * <h2>Interruption does not jump</h2>
 *
 * <p>A node that moves again mid-flight continues from where it is currently <em>drawn</em>, not from where the
 * previous transition was heading. The new displacement is measured from the drawn position, so the picture is
 * continuous across any number of interruptions — which is what lets a user drop a row, change their mind, and
 * drop it again before the first slide has finished without the row ever teleporting. A transition that restarted
 * from the layout position would snap the node to its old slot on every re-drop, and the faster the user worked
 * the worse it would look.
 *
 * <h2>Enrolment is opt-in, per node</h2>
 *
 * <p>Nothing transitions unless it was enrolled, because most layout changes should not be animated: a window
 * resize moves everything, and a UI that slides its entire contents on every drag of the window edge is seasick
 * rather than smooth. {@link #follow} enrols one node, {@link #followChildren} enrols a container's direct
 * children — what a list or tree wants, since its rows come and go and enrolling each one as it appeared would
 * be bookkeeping the container already does — and {@link #followSubtree} enrols everything under a node at any
 * depth, which is what a whole page wants.
 *
 * <p>The three exist because the granularity has to match the <em>consequence</em> of the change, not the place
 * it happened. A layout change does not stop at the container it happened in: open a panel and the rows below it
 * move, and so does the card below them, and the strip below that. Enrolling one container animates part of that
 * and snaps the rest, and the two halves moving the same distance at different times is a page visibly tearing
 * along the boundary. Either everything covers the distance or nothing does — mixed is worse than neither.
 * Nearest enrolment wins, so a page can say one thing and a list inside it another.
 *
 * <p>A duration of {@link Dur#ZERO} enrols a node that moves instantly. That is the honest reduced-motion
 * setting, and it is why an application can route every enrolment through one duration and turn the whole class
 * of them off in one place.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #moved} is called on the GUI thread; the ramp that decays a displacement runs on the timeline. The
 * displacement itself is published between them without a lock, because it is two floats that only ever move
 * toward zero — a reader that catches one frame's value late draws the node a pixel behind where it might have
 * been, which is indistinguishable from the animation it is already watching.
 */
public final class Transitions implements LayoutMotion {

    /** How a node is enrolled: how long it takes to catch up, and on what curve. */
    private record Terms(Dur over, Ease ease) {
    }

    /**
     * One node's outstanding lag. Displacements are volatile because they cross from the timeline thread to the
     * GUI thread; {@code epoch} is what lets a superseded ramp discover it has lost and stop writing, rather
     * than two ramps fighting over the same two floats and the tree twitching between their answers.
     */
    private static final class Lag {
        volatile float dx;
        volatile float dy;
        volatile int epoch;
    }

    private final KronoGui krono;
    private final Map<Long, Terms> followed = new ConcurrentHashMap<>();
    private final Map<Long, Terms> followedChildren = new ConcurrentHashMap<>();
    private final Map<Long, Terms> followedSubtree = new ConcurrentHashMap<>();
    private final Map<Long, Lag> lags = new ConcurrentHashMap<>();

    private Transitions(KronoGui krono) {
        this.krono = Objects.requireNonNull(krono, "krono");
    }

    /** Transitions timed on {@code krono}'s frame clock. Attach with {@link dev.vexelray.gui.core.Gui#motion}. */
    public static Transitions on(KronoGui krono) {
        return new Transitions(krono);
    }

    /** Enrol {@code node} itself: when layout moves it, it slides rather than jumps. */
    public Transitions follow(Node node, Dur over, Ease ease) {
        followed.put(node.id(), terms(over, ease));
        return this;
    }

    /**
     * Enrol {@code container}'s direct children — what a list, a tree body or a toolbar wants, since its rows are
     * created and destroyed by the model and enrolling each one on appearance would duplicate bookkeeping the
     * container is already doing. The container itself is not enrolled by this; say so separately if it should be.
     */
    public Transitions followChildren(Node container, Dur over, Ease ease) {
        followedChildren.put(container.id(), terms(over, ease));
        return this;
    }

    /**
     * Enrol everything under {@code root}, at any depth, however deeply nested and whenever it was added.
     *
     * <p>This is what a whole page wants, and the reason it exists is that mixed motion is worse than no motion.
     * A layout change does not stop at the container it happened in: open a panel and the rows below it move,
     * and so does the card below <em>them</em>, and the strip below that. Enrolling one container therefore
     * animates part of the consequence and snaps the rest — and because the two halves are moving the same
     * distance at different times, the page visibly tears along the boundary. Either everything covers the
     * distance or nothing does.
     *
     * <p>Nearest enrolment wins, so a list inside an enrolled page can still say something different about its
     * own rows. {@code root} itself is not enrolled — say {@link #follow} for that — which keeps "the page's
     * contents move" from also meaning "the page slides around inside its parent".
     *
     * <p>{@link Dur#ZERO} enrols a subtree that moves instantly, which is the honest reduced-motion setting and
     * the reason an application can route every enrolment through one duration and turn the whole class off in
     * one place.
     */
    public Transitions followSubtree(Node root, Dur over, Ease ease) {
        followedSubtree.put(root.id(), terms(over, ease));
        return this;
    }

    /** Stop transitioning {@code node} and its children, and drop any lag it is carrying, so it snaps to where
     * layout puts it from now on. */
    public Transitions unfollow(Node node) {
        long id = node.id();
        followed.remove(id);
        followedChildren.remove(id);
        followedSubtree.remove(id);
        Lag lag = lags.remove(id);
        if (lag != null) {
            lag.epoch++;        // whatever ramp is still running has lost; its writes go nowhere
            lag.dx = 0f;
            lag.dy = 0f;
        }
        return this;
    }

    /** Whether anything is currently short of where layout put it. For a test, or a host asking whether the
     * picture has settled. */
    public boolean settled() {
        for (Lag lag : lags.values()) {
            if (lag.dx != 0f || lag.dy != 0f) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void moved(RetainedNode node, float fromX, float fromY, float toX, float toY) {
        Terms terms = termsFor(node);
        if (terms == null) {
            return;
        }
        Lag lag = lags.computeIfAbsent(node.id, k -> new Lag());
        // Measure from where the node is drawn, not from where layout last put it: mid-flight, those differ by
        // exactly the lag it is still carrying, and ignoring it is what makes an interrupted transition jump.
        float startX = fromX + lag.dx - toX;
        float startY = fromY + lag.dy - toY;
        int epoch = ++lag.epoch;   // GUI thread is the only writer, so this needs no atomic
        if (terms.over().nanos() <= 0L || (startX == 0f && startY == 0f)) {
            lag.dx = 0f;
            lag.dy = 0f;
            return;
        }
        lag.dx = startX;
        lag.dy = startY;
        krono.ramp(terms.over(), terms.ease(),
                progress -> {
                    if (lag.epoch != epoch) {
                        return;   // superseded by a later move; this ramp no longer speaks for this node
                    }
                    float remaining = (float) (1d - progress);
                    // Adding zero normalises the negative zero that a negative start times a zero remainder
                    // produces. It compares equal to zero either way, so nothing downstream misbehaves — but
                    // the resting displacement is a value other code and tests read, and it should be the one
                    // obvious value rather than the one that prints with a minus sign.
                    lag.dx = startX * remaining + 0f;
                    lag.dy = startY * remaining + 0f;
                },
                () -> {
                    if (lag.epoch != epoch) {
                        return;
                    }
                    // Land exactly on zero rather than on whatever the last sample rounded to: the resting state
                    // is a value, not a neighbourhood, and Displacement reports motion on any non-zero at all.
                    lag.dx = 0f;
                    lag.dy = 0f;
                    lags.computeIfPresent(node.id, (k, v) -> v == lag && v.epoch == epoch ? null : v);
                });
    }

    @Override
    public float displacementX(RetainedNode node) {
        Lag lag = lags.get(node.id);
        return lag == null ? 0f : lag.dx;
    }

    @Override
    public float displacementY(RetainedNode node) {
        Lag lag = lags.get(node.id);
        return lag == null ? 0f : lag.dy;
    }

    /** A node's own enrolment wins over its container's, so one row can be given its own timing without the
     * container having to know about it. */
    /**
     * The terms that apply to {@code node}: its own, else its parent's {@code followChildren}, else the nearest
     * enclosing {@code followSubtree}.
     *
     * <p>Most specific wins, which is what lets a page say "everything here moves" and a list inside it say
     * "except my rows, which move faster".
     *
     * <p>The walk to the root is why {@code followSubtree} is a separate call rather than the only one: it is
     * O(depth) for every node that moved, on every frame something is moving. That is nothing against a layout
     * pass, and it is still worth not paying for a list that only ever wanted its own rows.
     */
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
        return "Transitions[following=" + (followed.size() + followedChildren.size())
                + ", inFlight=" + lags.size() + "]";
    }
}
