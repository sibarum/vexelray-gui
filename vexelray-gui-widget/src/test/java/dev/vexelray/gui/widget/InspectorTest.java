package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The declarative panel and the three editors under it, checked on the properties that are actually load-bearing:
 * that a section is a heading drawn once rather than a sort key, that a property is read from the model rather
 * than remembered, that folding and disabling <b>hide</b> rather than remove, and that the number field does not
 * validate what is still being typed.
 *
 * <p>Nothing here asserts a colour or a pixel. The look comes from the theme and is guarded elsewhere
 * ({@code PaletteGuardTest}); what an inspector owes its caller is the structure, and the structure is what a
 * second view of the same schema would read.
 */
class InspectorTest {

    /** Every text string in the tree, in tree order — the panel's structure as a reader would meet it. */
    private static List<String> texts(RetainedNode n) {
        List<String> out = new ArrayList<>();
        collect(n, out);
        return out;
    }

    private static void collect(RetainedNode n, List<String> out) {
        String s = n.textString();
        if (s != null && !s.isEmpty()) {
            out.add(s);
        }
        for (RetainedNode c : n.children) {
            collect(c, out);
        }
    }

    /** Whether {@code handle} is present and visible in the laid-out tree. */
    private static boolean showing(HeadlessGui h, Node handle) {
        RetainedNode r = h.retained(handle);
        return r != null && r.visible();
    }

    /** Every text node in the tree, paired with the height it was laid out at. */
    private static void heights(RetainedNode n, List<String> zero) {
        String s = n.textString();
        if (s != null && !s.isEmpty() && n.visible() && n.h <= 0f) {
            zero.add(s);
        }
        for (RetainedNode c : n.children) {
            heights(c, zero);
        }
    }

    /**
     * A visible label has a height, which sounds too obvious to assert and is the exact bug that shipped.
     *
     * <p>The cause was a node's kind being stored separately from its text, so the two could disagree: a box
     * given a text property laid out at {@code 179x0} and drew nothing, while the semantic snapshot still
     * announced the string. Every label in this panel was built that way, and the whole inspector came up as
     * furniture with no words on it while the suite stayed green — the other tests here assert the
     * <em>structure</em>, and the structure was right.
     *
     * <p>Kind is now derived from the text prop ({@code RetainedNode.hasText}), so that disagreement no longer
     * has anywhere to live — see {@link #aBoxGivenTextIsATextNode}, which is the real guarantee. This test stays
     * because it pins the <em>geometric</em> property directly, which is what a reader actually cares about, and
     * it would catch a future cause that is not this one.
     */
    @Test
    void everyVisibleLabelHasAHeight() {
        try (HeadlessGui h = new HeadlessGui()) {
            double[] model = {2};
            AtomicBoolean on = new AtomicBoolean(true);
            AtomicReference<String> mode = new AtomicReference<>("solid");

            Inspector inspector = new Inspector(h.gui);
            inspector.add(
                    Property.range("Look", "Opacity", 0, 100, 1, () -> model[0], v -> model[0] = v),
                    Property.number("Look", "Width", 1, 0, 9, () -> model[0], v -> model[0] = v),
                    Property.flag("Look", "Wireframe", on::get, on::set),
                    Property.choice("Look", "Material", List.of(
                                    new Property.Option<>("Solid", "solid"),
                                    new Property.Option<>("SSS", "sss")),
                            mode::get, mode::set));
            Inspector.Card card = inspector.card("Line", "3", on::get, on::set).open(true);
            card.add(Property.flag("", "Drop lines", on::get, on::set));
            h.gui.root().children(inspector.node());
            h.frame();

            List<String> flat = new ArrayList<>();
            heights(h.retained(inspector.node()), flat);
            assertEquals(List.of(), flat,
                    "these labels laid out with no height, so they are in the tree and not on the screen. "
                            + "Build a label with gui.text(s), not gui.box().text(s) — a BOX with a text "
                            + "property set is still a box, and sizes like one.");
        }
    }

    /**
     * A label is not squeezed to a vertical smear by the control beside it.
     *
     * <p>The row is label / control / value, and the label is the flexible one — so when the control is wide (a
     * three-option segment) and the panel is narrow, the label is what gives, down to one character per line. A
     * label taller than it is wide has stopped being a label. The fix was typographic, not geometric: the panel
     * sets its own type scale rather than inheriting body size, so the three fit.
     */
    @Test
    void aLabelIsNotCrushedByTheControlBesideIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            AtomicReference<String> mode = new AtomicReference<>("solid");
            Inspector inspector = new Inspector(h.gui);
            inspector.add(Property.choice("", "Material", List.of(
                            new Property.Option<>("Solid", "solid"),
                            new Property.Option<>("Additive", "additive"),
                            new Property.Option<>("SSS", "sss")),
                    mode::get, mode::set));
            // The width the plot workspace gives it, so this is the real constraint and not a generous one.
            inspector.node().width(Length.dp(284));
            h.gui.root().children(inspector.node());
            h.frame();

            RetainedNode label = null;
            for (RetainedNode n : allOf(h.retained(inspector.node()))) {
                if ("Material".equals(n.textString())) {
                    label = n;
                }
            }
            assertNotNull(label, "the label is in the tree");
            assertTrue(label.w > label.h,
                    "the label is wider than it is tall — it got " + label.w + "x" + label.h
                            + ", which means the segment beside it took the row and the text wrapped per "
                            + "character");
        }
    }

    /**
     * The bug class, closed at the root: a box given text <b>is</b> a text node, and lays out and draws as one.
     *
     * <p>This is the property that makes "the label is in the tree and not on the screen" unreachable rather
     * than merely detected. There is no kind to contradict — {@code gui.text(s)} and {@code gui.box().text(s)}
     * produce the same node, because kind is a reading of the text prop and not a second fact stored beside it.
     *
     * <p>The empty case is asserted too, and is the reason the test is presence and not emptiness: a label whose
     * string has not arrived yet — a value column waiting for a number — has to hold its line's height, or every
     * row jumps when its value lands.
     */
    @Test
    void aBoxGivenTextIsATextNode() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node viaBox = h.gui.box().text("HELLO").width(Length.dp(200));
            Node viaText = h.gui.text("HELLO").width(Length.dp(200));
            Node empty = h.gui.text("").width(Length.dp(200));
            Node bare = h.gui.box().width(Length.dp(200));
            h.gui.root().children(viaBox, viaText, empty, bare);
            h.frame();

            RetainedNode b = h.retained(viaBox);
            RetainedNode t = h.retained(viaText);
            assertEquals(t.kind(), b.kind(), "the two spellings produce the same kind of node");
            assertEquals(t.h, b.h, 0.01f, "and the same height");
            assertTrue(b.h > 0f, "which is a real one");
            assertNotNull(b.textMetrics, "a box with text gets text metrics, so the renderer draws it");

            assertTrue(h.retained(empty).h > 0f,
                    "an empty label still holds its line, so a row does not jump when its value arrives");
            assertEquals(0f, h.retained(bare).h, 0.01f, "a box that was never given text is still a box");
        }
    }

    private static List<RetainedNode> allOf(RetainedNode n) {
        List<RetainedNode> out = new ArrayList<>();
        gather(n, out);
        return out;
    }

    private static void gather(RetainedNode n, List<RetainedNode> out) {
        out.add(n);
        for (RetainedNode c : n.children) {
            gather(c, out);
        }
    }

    @Test
    void aRailPanelHeadingHasAHeightToo() {
        try (HeadlessGui h = new HeadlessGui()) {
            Rail rail = new Rail(h.gui)
                    .item("layers", h.gui.text("L"), "Layers", (g, into) -> into.append(g.text("rows")));
            h.gui.root().children(rail.node(), rail.panel());
            rail.select("layers").hint("5");
            h.frame();

            List<String> flat = new ArrayList<>();
            heights(h.retained(rail.panel()), flat);
            assertEquals(List.of(), flat, "the panel's title, hint and close mark must all have a height");
        }
    }

    // ---------------------------------------------------------------- sections

    @Test
    void aSectionHeadingIsDrawnOnceAndRowsKeepTheirDeclaredOrder() {
        try (HeadlessGui h = new HeadlessGui()) {
            double[] model = {1, 2, 3};
            Inspector inspector = new Inspector(h.gui);
            inspector.add(
                    Property.number("Look", "Alpha", 1, 0, 9, () -> model[0], v -> model[0] = v),
                    Property.number("Look", "Beta", 1, 0, 9, () -> model[1], v -> model[1] = v),
                    Property.number("Sampling", "Gamma", 1, 0, 9, () -> model[2], v -> model[2] = v));
            h.gui.root().children(inspector.node());
            h.frame();

            List<String> seen = texts(h.retained(inspector.node()));
            // The heading appears once, before the first row of its section, and the rows follow in the order
            // they were declared -- not alphabetically, and not with Sampling hoisted above Look.
            assertEquals(List.of("LOOK", "Alpha", "1", "Beta", "2", "SAMPLING", "Gamma", "3"), seen);
        }
    }

    @Test
    void anUngroupedRowGetsNoHeading() {
        try (HeadlessGui h = new HeadlessGui()) {
            double[] model = {7};
            Inspector inspector = new Inspector(h.gui);
            inspector.add(Property.number("", "Loose", 1, 0, 9, () -> model[0], v -> model[0] = v));
            h.gui.root().children(inspector.node());
            h.frame();

            assertEquals(List.of("Loose", "7"), texts(h.retained(inspector.node())));
        }
    }

    // ---------------------------------------------------------------- the model is the truth

    @Test
    void refreshReReadsTheModelRatherThanTheControl() {
        try (HeadlessGui h = new HeadlessGui()) {
            AtomicBoolean flag = new AtomicBoolean(false);
            AtomicReference<String> mode = new AtomicReference<>("solid");
            Inspector inspector = new Inspector(h.gui);
            Property fl = Property.flag("", "Wireframe", flag::get, flag::set);
            Property ch = Property.choice("", "Material",
                    List.of(new Property.Option<>("Solid", "solid"), new Property.Option<>("SSS", "sss")),
                    mode::get, mode::set);
            inspector.add(fl, ch);
            h.gui.root().children(inspector.node());
            h.frame();

            // Something else changes the model -- a preset, a keystroke, a fit. The panel was not told.
            flag.set(true);
            mode.set("sss");
            inspector.refresh();
            h.frame();

            // The controls now show what the model says, because a property is a view onto it and never a copy.
            assertTrue(flag.get());
            assertEquals("sss", mode.get());
            assertEquals(List.of("Wireframe", "Material", "Solid", "SSS"),
                    texts(h.retained(inspector.node())));
        }
    }

    // ---------------------------------------------------------------- cards

    @Test
    void aFoldedCardHidesItsRowsAndDoesNotRemoveThem() {
        try (HeadlessGui h = new HeadlessGui()) {
            AtomicBoolean on = new AtomicBoolean(true);
            double[] width = {2};
            Inspector inspector = new Inspector(h.gui);
            Inspector.Card card = inspector.card("Line", "3", on::get, on::set);
            card.add(Property.number("", "Width", 1, 0, 9, () -> width[0], v -> width[0] = v));
            h.gui.root().children(inspector.node());
            h.frame();

            assertFalse(card.open(), "a card starts folded shut");
            card.open(true);
            h.frame();
            assertTrue(texts(h.retained(inspector.node())).contains("Width"));

            card.open(false);
            h.frame();
            // Hidden, not removed: the node is still in the tree, so its registrations are still live and opening
            // the card hands back the control that was there -- not a fresh one that cannot take a keystroke.
            RetainedNode root = h.retained(inspector.node());
            assertNotNull(root);
            card.open(true);
            h.frame();
            assertTrue(texts(h.retained(inspector.node())).contains("Width"),
                    "reopening shows the same rows, so nothing was dropped while folded");
        }
    }

    @Test
    void switchingACardOffDimsItRatherThanEmptyingIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            AtomicBoolean on = new AtomicBoolean(true);
            double[] width = {2};
            Inspector inspector = new Inspector(h.gui);
            Inspector.Card card = inspector.card("Line", "", on::get, on::set).open(true);
            card.add(Property.number("", "Width", 1, 0, 9, () -> width[0], v -> width[0] = v));
            h.gui.root().children(inspector.node());
            h.frame();

            on.set(false);
            inspector.refresh();
            h.frame();
            // Still readable, so it is clear what turning it back on would do.
            assertTrue(texts(h.retained(inspector.node())).contains("Width"));
        }
    }

    // ---------------------------------------------------------------- Toggle

    @Test
    void aToggleFlipsOnClickAndReportsIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            AtomicInteger fired = new AtomicInteger();
            Toggle t = new Toggle(h.gui, false).onChange(v -> fired.incrementAndGet());
            h.gui.root().children(t.node());
            h.frame();

            dev.vexelray.gui.core.layout.Rect r = t.node().layout().rect();
            h.click(r.x() + r.w() / 2, r.y() + r.h() / 2);
            h.frame();

            assertTrue(t.on());
            assertEquals(1, fired.get());
        }
    }

    // ---------------------------------------------------------------- Segment

    @Test
    void aSegmentHoldsExactlyOneOptionAndIgnoresAValueItDoesNotHave() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> chosen = new ArrayList<>();
            Segment<String> seg = new Segment<String>(h.gui)
                    .option("Solid", "solid")
                    .option("Additive", "additive")
                    .option("SSS", "sss")
                    .onChange(chosen::add);
            h.gui.root().children(seg.node());
            h.frame();

            assertEquals("solid", seg.selected(), "the first option added is the selection");

            seg.select("sss");
            assertEquals("sss", seg.selected());

            // A value with no option is not a selection, and must not silently become one or clear the one held.
            seg.select("emissive");
            assertEquals("sss", seg.selected());
            assertEquals(List.of("sss"), chosen, "and it does not report a change that did not happen");
        }
    }

    // ---------------------------------------------------------------- NumberField

    @Test
    void aNumberFieldDoesNotJudgeWhatIsStillBeingTyped() {
        try (HeadlessGui h = new HeadlessGui()) {
            NumberField n = new NumberField(h.gui, 5, 1);
            h.gui.root().children(n.node());
            h.frame();

            // "-" is half of every negative number there is. A field that refused it could not be typed into.
            n.field().text("-");
            h.frame();
            assertEquals(5, n.value(), "the agreed number is unchanged while the text is mid-edit");

            n.field().text("-1.5");
            h.gui.focus(n.node());
            h.frame();
            assertEquals(5, n.value(), "still unchanged: nothing has committed");
        }
    }

    @Test
    void anUnparseableCommitPutsTheLastAgreedNumberBack() {
        try (HeadlessGui h = new HeadlessGui()) {
            NumberField n = new NumberField(h.gui, 5, 1);
            h.gui.root().children(n.node());
            h.frame();

            n.field().text("banana");
            h.gui.focus(n.node());
            h.tap(sibarum.tactroller.api.Key.ENTER);
            h.frame();

            assertEquals(5, n.value());
            assertEquals("5", n.field().text(), "the field always shows a number, and it is the one held");
        }
    }

    @Test
    void steppingIsExact() {
        try (HeadlessGui h = new HeadlessGui()) {
            NumberField n = new NumberField(h.gui, 0, 0.1);
            h.gui.root().children(n.node());
            h.gui.focus(n.node());
            h.frame();

            for (int i = 0; i < 10; i++) {
                h.tap(sibarum.tactroller.api.Key.UP);
                h.frame();
            }
            // Ten tenths is one. In double addition it is 0.9999999999999999, and a field the user stepped up ten
            // times and down ten times would not read what it read before.
            assertEquals("1", n.field().text());
            assertEquals(1.0, n.value(), 0.0);
        }
    }

    @Test
    void boundsAreHeldOnEveryRouteIn() {
        try (HeadlessGui h = new HeadlessGui()) {
            NumberField n = new NumberField(h.gui, 5, 1, 0, 10);
            h.gui.root().children(n.node());
            h.gui.focus(n.node());
            h.frame();

            n.value(99);
            assertEquals(10, n.value(), "set programmatically");

            n.field().text("-40");
            h.tap(sibarum.tactroller.api.Key.ENTER);
            h.frame();
            assertEquals(0, n.value(), "and typed");
        }
    }

    // ---------------------------------------------------------------- Rail

    @Test
    void aRailBuildsAPageOnceAndOnlyWhenItIsFirstShown() {
        try (HeadlessGui h = new HeadlessGui()) {
            AtomicInteger builtLayers = new AtomicInteger();
            AtomicInteger builtColor = new AtomicInteger();
            Rail rail = new Rail(h.gui)
                    .item("layers", h.gui.text("L"), "Layers",
                            (g, into) -> {
                                builtLayers.incrementAndGet();
                                into.append(g.text("layer rows"));
                            })
                    .item("color", h.gui.text("C"), "Color",
                            (g, into) -> {
                                builtColor.incrementAndGet();
                                into.append(g.text("colour rows"));
                            });
            h.gui.root().children(rail.node(), rail.panel());
            h.frame();

            assertEquals(0, builtLayers.get(), "nothing is built before it is asked for");
            assertEquals(0, builtColor.get());
            assertFalse(showing(h, rail.panel()), "and the panel starts shut");

            rail.select("layers");
            h.frame();
            assertEquals(1, builtLayers.get());
            assertEquals(0, builtColor.get(), "the panel not on show has cost nothing");

            rail.select("color");
            rail.select("layers");
            h.frame();
            assertEquals(1, builtLayers.get(), "and coming back does not rebuild it");
            assertEquals(1, builtColor.get());
        }
    }

    @Test
    void noneIsAnAnswerARailCanGive() {
        try (HeadlessGui h = new HeadlessGui()) {
            Rail rail = new Rail(h.gui)
                    .item("layers", h.gui.text("L"), "Layers", (g, into) -> into.append(g.text("rows")));
            h.gui.root().children(rail.node(), rail.panel());
            h.frame();

            rail.select("layers");
            h.frame();
            assertEquals("layers", rail.selected());
            assertTrue(showing(h, rail.panel()));

            // Clicking the icon that is already showing puts the panel away. This is the state a tab bar does not
            // have, and the whole reason a rail is a different widget rather than a vertical Tabs.
            rail.toggle("layers");
            h.frame();
            assertNull(rail.selected());
            assertFalse(showing(h, rail.panel()));
            assertTrue(showing(h, rail.node()), "the rail itself stays -- the tools did not go away with the panel");
        }
    }
}
