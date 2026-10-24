package dev.vexelray.gui.core;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A box scrolling sideways that never declared it is reported, naming what is too wide (docs/reference/scrolling.md
 * §5): a horizontal bar reads as a style, so without the warning only a person looking at it says it was not meant.
 */
class UnaskedScrollTest {

    @BeforeEach
    void silence() {
        Diagnostics.reset();
    }

    private static List<String> unasked() {
        return Diagnostics.recorded().stream().filter(m -> m.startsWith("a horizontal scrollbar nobody asked for"))
                .toList();
    }

    /** Ten pixels a character, so a label's width is its length. */
    private static float tenPerChar(dev.vexelray.gui.core.model.RetainedNode n,
                                    dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        String s = n.textString();
        return axis == dev.vexelray.gui.core.layout.LayoutEnums.Axis.HORIZONTAL ? (s == null ? 0f : s.length() * 10f) : 20f;
    }

    /**
     * The About window's: a column of rows, one of them a label told not to wrap and longer than the window. The
     * warning names the label, not the row or the column holding it.
     */
    @Test
    void aLineThatCannotWrapWidensItsBoxAndIsNamed() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node value = gui.text("d920925cfa80 + uncommitted changes").wordWrap(false);
            Node facts = gui.column().children(gui.row().children(gui.text("Build").wordWrap(false), value));
            Node body = gui.column().width(Length.FILL).height(Length.dp(300))
                    .alignItems(dev.vexelray.gui.core.layout.LayoutEnums.AlignItems.CENTER).children(facts);
            gui.root().direction(Direction.COLUMN).children(body);
            gui.frame(300f, 400f, UnaskedScrollTest::tenPerChar);

            assertTrue(body.layout().overflowX(), "the picture: a sideways bar");
            List<String> said = unasked();
            assertEquals(1, said.size(), Diagnostics.recorded().toString());
            String m = said.getFirst();
            assertTrue(m.contains("box " + body.id() + " scrolls sideways, and nothing declared that it should"), m);
            assertTrue(m.contains("What does not fit is text " + value.id() + " (\"d920925cfa80 + uncommitted changes\")"),
                    m);
            assertTrue(m.contains("a label told not to wrap (wordWrap(false))"), m);
        }
    }

    @Test
    void theSameLineAllowedToWrapFitsAndNothingIsSaid() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node body = gui.column().width(Length.FILL).height(Length.dp(300)).children(gui.row().width(Length.FILL)
                    .children(gui.text("Build").wordWrap(false),
                            gui.text("d920925cfa80 + uncommitted changes").width(Length.grow(1f))));
            gui.root().direction(Direction.COLUMN).children(body);
            gui.frame(300f, 400f, UnaskedScrollTest::tenPerChar);

            assertEquals(List.of(), unasked(), Diagnostics.recorded().toString());
        }
    }

    /** A table or a timeline is meant to scroll sideways; saying so at the box is the whole answer. */
    @Test
    void aBoxThatDeclaredItScrollsSidewaysQuietly() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node strip = gui.row().width(Length.FILL).height(Length.dp(100)).scroll(true, false)
                    .children(gui.box().size(Length.dp(2000), Length.dp(50)));
            gui.root().direction(Direction.COLUMN).children(strip);
            gui.frame(300f, 400f, UnaskedScrollTest::tenPerChar);

            assertTrue(strip.layout().overflowX(), "it scrolls");
            assertEquals(List.of(), unasked());
        }
    }

    /** Down is how a long page is read: only sideways is reported. */
    @Test
    void scrollingDownUndeclaredIsOrdinary() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node page = gui.column().width(Length.FILL).height(Length.dp(200))
                    .children(gui.box().size(Length.dp(100), Length.dp(1000)));
            gui.root().direction(Direction.COLUMN).children(page);
            gui.frame(300f, 400f, UnaskedScrollTest::tenPerChar);

            assertTrue(page.layout().overflowY(), "it scrolls down");
            assertEquals(List.of(), unasked());
        }
    }

    /** A root wider than its window because of Gui.minSize was asked for (scrolling.md §2). */
    @Test
    void aRootOnACanvasWiderThanTheWindowWasAskedFor() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.root().direction(Direction.COLUMN).children(gui.box().size(Length.FILL, Length.dp(50)));
            gui.minSize(Length.dp(600), Length.dp(100));
            gui.frame(300f, 400f, UnaskedScrollTest::tenPerChar);

            assertTrue(gui.root().layout().overflowX(), "the root scrolls on its canvas");
            assertEquals(List.of(), unasked());
        }
    }
}
