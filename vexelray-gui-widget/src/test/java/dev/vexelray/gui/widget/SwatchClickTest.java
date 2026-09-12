package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A swatch card is clickable at the widths a real panel gives it — including the ones too narrow for its own
 * label.
 *
 * <h2>What this is about</h2>
 *
 * <p>Every node in this framework may scroll unless it says otherwise, and a node that may scroll and does not
 * fit <b>becomes a scrolling container</b>: the layout reserves a scrollbar strip on each overflowing axis, and
 * {@code InputDispatcher} consumes a press anywhere in that strip as a press on the bar — ahead of focus and
 * ahead of the click walk. So a card one pixel too narrow for its contents does not merely look cramped; it
 * stops being a control, silently, with no exception and nothing in the tree that names the cause.
 *
 * <p>That is what these swatches were: in a 284dp inspector panel each card's fixed 4rem label left the colour
 * strip no width at all, the card overflowed, and clicking one changed nothing. Both halves are pinned below,
 * because either alone comes back green while the control is still dead — a card that fits by luck is not a
 * card that declared it does not scroll.
 */
class SwatchClickTest {

    /**
     * A panel width at which the old fixed 4rem label did not fit the card the inspector gave it.
     *
     * <p>The real one was a 284dp rail panel at 1.25 density, where the label resolved to 80px against a 93px
     * card. The arithmetic here is the harness's -- 10px per character, density 1 -- so the number is not the
     * application's; what it reproduces is the shape: a card whose contents overflow it while its viewport is
     * still a positive size, which is exactly the case that reserves a bar and swallows the press.
     */
    private static final float NARROW = 200f;

    private record Ramp(String label) { }

    /** Two maps whose labels are longer than {@link #NARROW} allows for at the harness's 10px cell. */
    private static final Ramp BLURPLE = new Ramp("Blurple");
    private static final Ramp STEEL = new Ramp("Steel");

    private static List<Property.Swatch<Ramp>> maps() {
        return List.of(
                new Property.Swatch<>(BLURPLE.label(), BLURPLE,
                        List.of(Color.rgb(0.4f, 0.4f, 0.9f), Color.rgb(0.6f, 0.5f, 1f))),
                new Property.Swatch<>(STEEL.label(), STEEL,
                        List.of(Color.rgb(0.3f, 0.4f, 0.5f), Color.rgb(0.6f, 0.7f, 0.8f))));
    }

    /** Every {@code swatch} card in the laid-out tree, in the order they were declared. */
    private static List<RetainedNode> cards(RetainedNode n) {
        List<RetainedNode> out = new ArrayList<>();
        collect(n, out);
        return out;
    }

    private static void collect(RetainedNode n, List<RetainedNode> out) {
        if ("swatch".equals(n.role())) {
            out.add(n);
        }
        for (RetainedNode c : n.children) {
            collect(c, out);
        }
    }

    /** An inspector holding one swatches row, in a column {@code width} wide. */
    private static Node panel(HeadlessGui h, AtomicReference<Ramp> chosen, float width) {
        Inspector inspector = new Inspector(h.gui);
        inspector.add(Property.swatches("", "", maps(), chosen::get, chosen::set));
        Node panel = h.gui.column().width(Length.dp(width)).children(inspector.node());
        h.gui.root().append(panel);
        return panel;
    }

    @Test
    @DisplayName("clicking a card selects its map, in a panel too narrow for the label")
    void clickingACardSelectsItsMap() {
        try (HeadlessGui h = new HeadlessGui()) {
            AtomicReference<Ramp> chosen = new AtomicReference<>(BLURPLE);
            Node panel = panel(h, chosen, NARROW);
            h.frame();

            List<RetainedNode> cards = cards(h.retained(panel));
            assertEquals(2, cards.size(), "the premise: one card per map");
            RetainedNode steel = cards.get(1);
            h.click(steel.x + steel.w / 2f, steel.y + steel.h / 2f);

            assertEquals(STEEL, chosen.get(), "the click should have reached the card's handler");
        }
    }

    /**
     * The declaration, separately from its effect. A card that happens to fit is clickable whether or not it
     * says this, so a test that only clicked would go green again the day a label got shorter.
     */
    @Test
    @DisplayName("a card never turns itself into a scrolling container")
    void aCardIsNeverAScrollbar() {
        try (HeadlessGui h = new HeadlessGui()) {
            AtomicReference<Ramp> chosen = new AtomicReference<>(BLURPLE);
            Node panel = panel(h, chosen, NARROW);
            h.frame();

            for (RetainedNode card : cards(h.retained(panel))) {
                assertFalse(card.overflowX || card.overflowY,
                        "a swatch card must not reserve scrollbars: a press in the strip they reserve is "
                                + "consumed by the bar and never reaches the card");
            }
        }
    }

    /** And the strip is still the wider half wherever there is room for one, which is the point of the row. */
    @Test
    @DisplayName("the colour strip takes the width the label does not")
    void theStripTakesWhatTheLabelDoesNot() {
        try (HeadlessGui h = new HeadlessGui()) {
            AtomicReference<Ramp> chosen = new AtomicReference<>(BLURPLE);
            Node panel = panel(h, chosen, 420f);
            h.frame();

            RetainedNode card = cards(h.retained(panel)).get(0);
            RetainedNode strip = card.children.get(0);
            RetainedNode label = card.children.get(1);
            assertTrue(strip.w > label.w,
                    "the stops are the map; the label only names it (strip " + strip.w + ", label " + label.w + ")");
        }
    }
}
