package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Unclip}: a cut-off node shown whole while the pointer is on it, and the guarantee that makes that
 * admissible under the hover rule — the drawn-out part is never the pointer target, so what lies under it stays
 * exactly as reachable as it was.
 *
 * <p>The page is the arrangement the widget exists for: a 100px column clipping a name three times its width,
 * and a clickable column beside it where the rest of the name would draw.
 */
class UnclipTest {

    private static final String LONG = "a-name-much-wider-than-its-column";   // 330px at the harness's 10px cell
    private static final String SHORT = "fits";

    private record Page(Node column, Node name, Node next) { }

    private static Page page(HeadlessGui h, Unclip unclip, String text) {
        Node name = h.gui.text(text);
        Node column = h.gui.row().width(Length.dp(100)).height(Length.dp(30))
                .scroll(false, false).clip(true).children(name);
        Node next = h.gui.box().width(Length.dp(400)).height(Length.dp(30));
        h.gui.root().children(h.gui.row().children(column, next));
        unclip.attach(column, name);
        h.frame();
        return new Page(column, name, next);
    }

    private static float amount(HeadlessGui h, Node n) {
        return h.retained(n).unclip();
    }

    @Test
    void hoveringACutOffNodeDrawsItOutAndLeavingPutsItBack() {
        try (HeadlessGui h = new HeadlessGui()) {
            Page p = page(h, new Unclip(h.gui), LONG);
            assertTrue(p.name().layout().clipped(), "the premise: the column cuts the name off");

            h.hover(50f, 15f);
            h.frame();
            assertEquals(1f, amount(h, p.name()), 0.001f, "on the column: the name is drawn whole");

            h.hover(300f, 15f);
            h.frame();
            assertEquals(0f, amount(h, p.name()), 0.001f, "off it: back under its clip");
        }
    }

    @Test
    void aNodeThatFitsIsLeftAlone() {
        try (HeadlessGui h = new HeadlessGui()) {
            Page p = page(h, new Unclip(h.gui), SHORT);

            h.hover(20f, 15f);
            h.frame();
            assertEquals(0f, amount(h, p.name()), 0.001f, "nothing is cut off, so there is nothing to show");
        }
    }

    /**
     * The admissibility condition. Where the overhang is drawn, the pointer is on the column beside it — not on
     * the name — so moving there leaves the name, and a click there lands on what the page has there.
     */
    @Test
    void theOverhangIsNeverThePointerTarget() {
        try (HeadlessGui h = new HeadlessGui()) {
            Page p = page(h, new Unclip(h.gui), LONG);
            int[] nextClicked = {0};
            h.gui.onClick(p.next(), () -> nextClicked[0]++);
            h.frame();

            h.hover(50f, 15f);
            h.frame();
            assertEquals(1f, amount(h, p.name()), 0.001f);

            float overhang = 200f;   // inside the name's 330px, past its 100px column
            h.click(overhang, 15f);
            h.frame();
            assertEquals(1, nextClicked[0], "the click went to the column the name was drawn over");
            assertEquals(0f, amount(h, p.name()), 0.001f, "and getting there was leaving the name");
        }
    }

    @Test
    void aPressDoesNotDismissIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            Page p = page(h, new Unclip(h.gui), LONG);

            h.hover(50f, 15f);
            h.frame();
            h.click(50f, 15f);
            h.frame();
            assertEquals(1f, amount(h, p.name()), 0.001f,
                    "it is the thing being acted on, not help in front of it, so it stays");
        }
    }

    /** A ramp the test steps by hand: each run's progress sink and settle, in the order they were started. */
    private static final class Steps implements Ramp {
        final List<DoubleConsumer> progress = new ArrayList<>();
        final List<Runnable> done = new ArrayList<>();

        @Override
        public void run(DoubleConsumer p, Runnable d) {
            progress.add(p);
            done.add(d);
        }
    }

    @Test
    void itFadesInAndOutOnTheRampItIsGiven() {
        try (HeadlessGui h = new HeadlessGui()) {
            Steps ramp = new Steps();
            Page p = page(h, new Unclip(h.gui).motion(ramp), LONG);

            h.hover(50f, 15f);
            h.frame();
            ramp.progress.get(0).accept(0.5);
            h.frame();
            assertEquals(0.5f, amount(h, p.name()), 0.001f, "half-way in");
            ramp.done.get(0).run();
            h.frame();
            assertEquals(1f, amount(h, p.name()), 0.001f, "and settled whole");

            h.hover(300f, 15f);
            h.frame();
            ramp.progress.get(1).accept(0.25);
            h.frame();
            assertEquals(0.75f, amount(h, p.name()), 0.001f, "a quarter of the way out");
        }
    }

    /**
     * Leaving half-way through a fade-in fades out from half-way, and the abandoned fade-in has no say in it any
     * more — not its later samples, and not its settle, which would otherwise flash the name whole after it left.
     */
    @Test
    void aSupersededFadeDoesNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            Steps ramp = new Steps();
            Page p = page(h, new Unclip(h.gui).motion(ramp), LONG);

            h.hover(50f, 15f);
            h.frame();
            ramp.progress.get(0).accept(0.5);
            h.hover(300f, 15f);
            h.frame();

            ramp.progress.get(0).accept(0.9);
            ramp.done.get(0).run();
            h.frame();
            assertEquals(0.5f, amount(h, p.name()), 0.001f, "the fade-in lost the node when the pointer left");

            ramp.progress.get(1).accept(0.5);
            h.frame();
            assertEquals(0.25f, amount(h, p.name()), 0.001f, "the fade-out runs from where the fade-in got to");
            ramp.done.get(1).run();
            h.frame();
            assertFalse(amount(h, p.name()) > 0f, "and ends with the name back under its clip");
        }
    }
}
