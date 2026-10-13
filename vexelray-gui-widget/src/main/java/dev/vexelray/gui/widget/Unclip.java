package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InteractionState;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Show a node that is cut off by its container whole, where it already is, while the pointer is on it — a name
 * wider than its column, a tree label narrower than the tree.
 *
 * <pre>{@code
 * Unclip unclip = new Unclip(gui).motion((p, done) -> krono.ramp(Dur.ms(90), Ease.OUT_CUBIC, p, done));
 * unclip.attach(row, label);   // hovering the row shows the label whole, if the row cuts it off
 * }</pre>
 *
 * <h2>Why this is not a tooltip, and why it may appear on hover</h2>
 *
 * A tooltip is help: something new, somewhere else. This is the node's own pixels, in the place they already were,
 * with nothing added — the clip is lifted from it for as long as the pointer is there ({@link Node#unclip}). What
 * it covers was already covering the node; hovering only chooses which of the two is hidden. And it changes nothing
 * the pointer is on: the drawn-out part is never hit, so leaving the node's own box is leaving it, and what was
 * under the overhang is reachable the moment the pointer gets there.
 *
 * <p>It is asked at hover time whether the node is cut off at all ({@code NodeLayout.clipped}), because whether it
 * is depends on depth, width and zoom, all of which move under it; a node that fits is left alone. A press does
 * not dismiss it, because unlike help it is not in the way of acting on the thing — it <em>is</em> the thing.
 *
 * <p>Without {@link #motion} it snaps. Safe from any thread: state changes arrive on input workers and a ramp's
 * samples on the clock's.
 *
 * <h2>One per window</h2>
 *
 * How a reveal fades is a fact about the window, not about the tree or the table it happens in: a tree label
 * that fades and a table cell beside it that snaps is the same gesture answered two ways. So the widgets that
 * reveal ({@link TreeView}, {@link Table}) all use {@link #of}, the window's own, and the application says how it
 * moves once — where its clock is attached — rather than once per widget it remembered. An application that
 * wants a reveal that moves differently constructs one of its own.
 */
public final class Unclip {

    private static final Map<Gui, Unclip> WINDOWS = new ConcurrentHashMap<>();

    private final Gui gui;
    private volatile Ramp motion;

    public Unclip(Gui gui) {
        this.gui = Objects.requireNonNull(gui, "gui");
    }

    /**
     * The window's reveal: the one every widget here that reveals uses, so giving it a {@link #motion} gives every
     * reveal in the window that motion.
     */
    public static Unclip of(Gui gui) {
        return WINDOWS.computeIfAbsent(Objects.requireNonNull(gui, "gui"), Unclip::new);
    }

    /** How the drawn-out node fades in and out, or null to snap. One ramp for both directions. */
    public Unclip motion(Ramp ramp) {
        this.motion = ramp;
        return this;
    }

    /** Draw {@code node} out from under its clips while the pointer is on it, if they cut it off. */
    public Unclip attach(Node node) {
        return attach(node, node);
    }

    /**
     * Draw {@code subject} out from under its clips while the pointer is on {@code over} — the row a label is in,
     * when it is the row that is hovered. Attach once per pair: each call adds a state observer.
     */
    public Unclip attach(Node over, Node subject) {
        Objects.requireNonNull(over, "over");
        Objects.requireNonNull(subject, "subject");
        Lift lift = new Lift(subject);
        gui.onState(over, state -> {
            if (state == InteractionState.NORMAL) {
                lift.to(0f);
            } else if (lift.target == 0f && subject.layout().clipped()) {
                lift.to(1f);
            }
        });
        return this;
    }

    /** One subject's amount, and which run of the ramp currently owns it. */
    private final class Lift {

        private final Node subject;
        /** Where it is now and where it is going; written under this. */
        private float amount;
        private volatile float target;
        /** Bumped on every new run, so a superseded ramp's samples and its settle do nothing. */
        private int run;

        Lift(Node subject) {
            this.subject = subject;
        }

        void to(float goal) {
            Ramp ramp = motion;
            float from;
            int mine;
            synchronized (this) {
                if (goal == target) {
                    return;
                }
                target = goal;
                from = amount;
                mine = ++run;
            }
            if (ramp == null) {
                set(mine, goal);
                return;
            }
            // From wherever it is, not from the far end, so leaving half-way through a fade-in fades out from
            // half-way rather than flashing to full first.
            ramp.run(p -> set(mine, from + (goal - from) * (float) p), () -> set(mine, goal));
        }

        private void set(int owner, float value) {
            synchronized (this) {
                if (owner != run) {
                    return;   // a newer run owns the subject now
                }
                amount = value;
                subject.unclip(value);   // under the lock, so two runs cannot land out of order
            }
        }
    }
}
