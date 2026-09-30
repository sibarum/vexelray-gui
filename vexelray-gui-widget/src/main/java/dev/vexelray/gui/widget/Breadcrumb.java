package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Where you are, as a chain you can step back along: {@code This PC > Users > james > Downloads}.
 *
 * <h2>A projection, not a state</h2>
 * The breadcrumb owns nothing. It is handed the chain from the root to the current place — {@link #path} — and
 * draws it; the application owns which place that is, so a breadcrumb cannot disagree with the tree or the list
 * beside it. Every ancestor is a {@linkplain Button.Kind#GHOST ghost button}, which is what makes each one
 * reachable from the keyboard for free; the last is plain text, because the place you are already in is not an
 * action.
 *
 * <h2>Long paths collapse from the left</h2>
 * Past {@link #maxSegments} the oldest ancestors are replaced by one ellipsis that navigates to the nearest of
 * them. The rule is a count and not a measurement, on purpose: a bar that measured its own width would re-decide
 * what it shows as the window moved, and a path whose leading segments come and go under a resizing pointer is
 * the movement the standing rule forbids. The item type is the caller's — a {@code Path}, a record, a string — and
 * comes back untouched through {@link #onNavigate}.
 *
 * <p>Handlers run on a worker thread, like every other widget's.
 */
public final class Breadcrumb<T> {

    private static final String ELLIPSIS = "…";
    private static final String SEPARATOR = "›";

    private final Gui gui;
    private final Function<T, String> label;
    private final Node bar;
    private final List<Node> built = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private volatile Consumer<T> onNavigate = t -> { };
    private volatile int maxSegments = 6;
    private volatile int face = 0;
    private List<T> chain = List.of();

    /** An empty breadcrumb; {@code label} says how an item reads. */
    public Breadcrumb(Gui gui, Function<T, String> label) {
        this.gui = gui;
        this.label = label;
        this.bar = gui.row().role("navigation")
                .alignItems(AlignItems.CENTER)
                .gap(Length.rem(0.15f))
                .scroll(false, false);
    }

    /** The node to place in a layout. */
    public Node node() {
        return bar;
    }

    /** The font face for every segment (the application's own face index). */
    public Breadcrumb<T> font(int face) {
        this.face = face;
        return this;
    }

    /** How many segments show before the oldest collapse into an ellipsis. At least two. */
    public synchronized Breadcrumb<T> maxSegments(int max) {
        this.maxSegments = Math.max(2, max);
        rebuild();
        return this;
    }

    /** React to a click on an ancestor, or Enter on a focused one. Runs on a worker thread. */
    public Breadcrumb<T> onNavigate(Consumer<T> handler) {
        this.onNavigate = handler == null ? t -> { } : handler;
        return this;
    }

    /** Show the chain from the root to the current place. The last item is the current place. */
    public synchronized Breadcrumb<T> path(List<T> items) {
        this.chain = List.copyOf(items);
        rebuild();
        return this;
    }

    /** The chain being shown, root first. */
    public synchronized List<T> path() {
        return chain;
    }

    /** The ancestor buttons currently on show, left to right — for a test or an agent to address. */
    public synchronized List<Button> ancestors() {
        return List.copyOf(buttons);
    }

    private void rebuild() {
        for (Node n : built) {
            n.remove();
        }
        built.clear();
        buttons.clear();
        int n = chain.size();
        if (n == 0) {
            return;
        }
        int hidden = Math.max(0, n - maxSegments);
        if (hidden > 0) {
            // The ellipsis stands for the hidden ancestors and goes to the nearest of them: the one whose
            // children are the first segment still on show.
            add(button(ELLIPSIS, chain.get(hidden - 1)));
            add(separator());
        }
        for (int i = hidden; i < n; i++) {
            T item = chain.get(i);
            if (i == n - 1) {
                add(gui.text(label.apply(item)).font(face)
                        .textSize(Length.rem(0.875f))
                        .textColor(gui.theme().color(Role.INK))
                        .padding(Length.rem(0.35f), Length.rem(0.5f))
                        .wordWrap(false).role("current"));
            } else {
                add(button(label.apply(item), item));
                add(separator());
            }
        }
    }

    private Node button(String text, T target) {
        Button b = new Button(gui, text).kind(Button.Kind.GHOST).onPress(() -> onNavigate.accept(target));
        b.node().font(face);
        buttons.add(b);
        return b.node();
    }

    private Node separator() {
        return gui.text(SEPARATOR).font(face)
                .textSize(Length.rem(0.875f))
                .textColor(gui.theme().color(Role.FAINT))
                .role("separator");
    }

    private void add(Node node) {
        built.add(node);
        bar.append(node);
    }
}
