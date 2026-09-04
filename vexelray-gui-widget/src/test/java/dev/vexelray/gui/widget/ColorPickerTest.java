package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import sibarum.tactroller.api.Key;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.style.Hex;
import dev.vexelray.gui.core.style.Hsv;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colour picker, and the one distinction the whole widget turns on: <b>a drag is not a choice until it ends</b>.
 * Everything about the recents strip follows from that — a strip fed from every intermediate colour of a drag is a
 * strip of colours nobody picked.
 */
class ColorPickerTest {

    private static final double EPS = 1e-3;

    // --- the spaces underneath ---------------------------------------------------------------------------

    @Test
    void hsvRoundTripsThroughColor() {
        for (double hue = 0; hue < 360; hue += 37) {
            Hsv start = new Hsv(hue, 0.6, 0.8);
            Hsv back = Hsv.of(start.toColor());
            assertEquals(start.hue(), back.hue(), 0.05, "hue at " + hue);
            assertEquals(start.saturation(), back.saturation(), EPS);
            assertEquals(start.value(), back.value(), EPS);
        }
    }

    /** The reason the picker holds an {@link Hsv} and not a {@link Color}: grey cannot say which hue it was. */
    @Test
    void greyForgetsItsHueButTheRecordDoesNot() {
        Hsv blue = new Hsv(240, 0.0, 0.5);
        assertEquals(240.0, blue.hue(), EPS);
        assertEquals(0.0, Hsv.of(blue.toColor()).hue(), EPS);
    }

    /** 360 is not folded to 0: they are one colour but opposite ends of a strip. */
    @Test
    void hueKeepsItsFarEnd() {
        assertEquals(360.0, new Hsv(360, 1, 1).hue(), EPS);
        assertEquals(new Hsv(0, 1, 1).toColor(), new Hsv(360, 1, 1).toColor());
    }

    @Test
    void hexParsesEveryAcceptedForm() {
        Color orange = Hex.parse("#ff8800").orElseThrow();
        assertEquals(orange, Hex.parse("ff8800").orElseThrow());
        assertEquals(orange, Hex.parse("#f80").orElseThrow());
        assertEquals(orange, Hex.parse("  #FF8800 ").orElseThrow());
        assertEquals(1f, Hex.parse("#f80f").orElseThrow().a(), EPS);
        assertEquals(0.5f, Hex.parse("#ff880080").orElseThrow().a(), 0.01);
    }

    @Test
    void hexRejectsWhatIsNotAColor() {
        for (String bad : List.of("", "#", "#ff", "#fffff", "#gggggg", "12345", "#ff88001")) {
            assertTrue(Hex.parse(bad).isEmpty(), bad + " should not parse");
        }
        assertTrue(Hex.parse(null).isEmpty());
    }

    @Test
    void hexFormatsTheShortestFormThatSaysEverything() {
        assertEquals("#ff8800", Hex.format(Color.rgb(0xFF8800)));
        assertEquals("#000000", Hex.format(Color.BLACK));
        assertEquals("#ff880080", Hex.format(Color.rgba(1f, 0x88 / 255f, 0f, 128 / 255f)));
    }

    // --- the history -------------------------------------------------------------------------------------

    @Test
    void historyIsMostRecentFirstAndBounded() {
        ColorHistory history = new ColorHistory(3);
        history.use(Color.rgb(0x110000));
        history.use(Color.rgb(0x220000));
        history.use(Color.rgb(0x330000));
        history.use(Color.rgb(0x440000));
        assertEquals(List.of("#440000", "#330000", "#220000"), hexes(history.current()));
    }

    @Test
    void reusingAColorMovesItRatherThanRepeatingIt() {
        ColorHistory history = new ColorHistory(4);
        history.use(Color.rgb(0x110000));
        history.use(Color.rgb(0x220000));
        history.use(Color.rgb(0x110000));
        assertEquals(List.of("#110000", "#220000"), hexes(history.current()));
    }

    /** Colours off a drag differ in the sixth decimal and in nothing an eye or an #rrggbb can see. */
    @Test
    void deduplicationIsAtDisplayResolution() {
        ColorHistory history = new ColorHistory(8);
        history.use(new Color(0.5f, 0.25f, 0.125f, 1f));
        history.use(new Color(0.5f + 1e-5f, 0.25f, 0.125f, 1f));
        assertEquals(1, history.current().size());
    }

    /** Alpha is part of the identity: the same colour at half coverage is a different choice. */
    @Test
    void alphaSeparatesEntries() {
        ColorHistory history = new ColorHistory(8);
        history.use(Color.rgba(1f, 0f, 0f, 1f));
        history.use(Color.rgba(1f, 0f, 0f, 0.5f));
        assertEquals(2, history.current().size());
    }

    @Test
    void aSeedKeepsItsOrder() {
        ColorHistory history = new ColorHistory(4,
                List.of(Color.rgb(0x110000), Color.rgb(0x220000)));
        assertEquals(List.of("#110000", "#220000"), hexes(history.current()));
    }

    // --- the widget --------------------------------------------------------------------------------------

    @Test
    void dragAnnouncesChangesAndOnlyTheEndIsAChoice() {
        try (HeadlessGui h = new HeadlessGui()) {
            Recorder rec = new Recorder();
            ColorPicker picker = mount(h, Color.WHITE, new ColorHistory(4), rec);

            Rect hue = rectOf(h, "colorpicker.hue");
            drag(h, hue.x() + hue.w() * 0.25f, hue.y() + hue.h() / 2f,
                    hue.x() + hue.w() * 0.5f, hue.y() + hue.h() / 2f);

            assertTrue(rec.changed.size() >= 1, "a drag should preview");
            assertEquals(1, rec.committed.size(), "a drag is one choice, at its end");
            assertEquals(180.0, picker.hsv().hue(), 2.0, "half way along the strip is cyan");
            assertEquals(1, picker.history().current().size(), "only the choice is recorded");
        }
    }

    @Test
    void theSquareSetsSaturationAndValue() {
        try (HeadlessGui h = new HeadlessGui()) {
            ColorPicker picker = mount(h, Color.WHITE, new ColorHistory(4), new Recorder());

            Rect field = rectOf(h, "colorpicker.field");
            drag(h, field.x() + field.w() * 0.75f, field.y() + field.h() * 0.25f,
                    field.x() + field.w() * 0.75f, field.y() + field.h() * 0.25f);

            assertEquals(0.75, picker.hsv().saturation(), 0.02);
            assertEquals(0.75, picker.hsv().value(), 0.02);
        }
    }

    /** The application saying "the selection is now this" is not the user choosing it. */
    @Test
    void settingTheColorProgrammaticallyAnnouncesNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            Recorder rec = new Recorder();
            ColorPicker picker = mount(h, Color.WHITE, new ColorHistory(4), rec);

            picker.color(Color.rgb(0x3366CC));
            h.frame();

            assertEquals("#3366cc", Hex.format(picker.color()));
            assertTrue(rec.changed.isEmpty(), "no preview");
            assertTrue(rec.committed.isEmpty(), "no choice");
            assertTrue(picker.history().current().isEmpty(), "nothing recorded");
        }
    }

    @Test
    void clickingARecentChoosesItAndMovesItToTheFront() {
        try (HeadlessGui h = new HeadlessGui()) {
            ColorHistory history = new ColorHistory(4,
                    List.of(Color.rgb(0x110000), Color.rgb(0x220000)));
            Recorder rec = new Recorder();
            ColorPicker picker = mount(h, Color.WHITE, history, rec);

            Rect second = swatchRect(h, 1);
            h.click(second.x() + second.w() / 2f, second.y() + second.h() / 2f);

            assertEquals("#220000", Hex.format(picker.color()));
            assertEquals(1, rec.committed.size());
            assertEquals(List.of("#220000", "#110000"), hexes(history.current()));
        }
    }

    /** An empty slot holds its place in the strip and is not a control. */
    @Test
    void emptySlotsReserveSpaceAndDoNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            ColorHistory history = new ColorHistory(4);
            Recorder rec = new Recorder();
            ColorPicker picker = mount(h, Color.WHITE, history, rec);

            assertEquals(4, swatches(h).size(), "every slot exists from the start");
            Rect empty = swatchRect(h, 3);
            assertTrue(empty.w() > 0f && empty.h() > 0f, "an empty slot still takes its space");

            h.click(empty.x() + empty.w() / 2f, empty.y() + empty.h() / 2f);
            assertTrue(rec.committed.isEmpty(), "clicking nothing chooses nothing");
            assertEquals("#ffffff", Hex.format(picker.color()));
        }
    }

    /** The strip is fed by the history, not by the picker, so a sibling picker's choice shows up here. */
    @Test
    void aSharedHistoryReachesEveryPicker() {
        try (HeadlessGui h = new HeadlessGui()) {
            ColorHistory shared = new ColorHistory(4);
            ColorPicker a = mount(h, Color.WHITE, shared, new Recorder());
            ColorPicker b = mount(h, Color.WHITE, shared, new Recorder());

            shared.use(Color.rgb(0x00FF00));
            h.frame();

            assertEquals(Color.rgb(0x00FF00), swatchOf(h, a, 0).background());
            assertEquals(Color.rgb(0x00FF00), swatchOf(h, b, 0).background());
        }
    }

    @Test
    void submittingHexChoosesTheColorAndBadHexIsPutBack() {
        try (HeadlessGui h = new HeadlessGui()) {
            Recorder rec = new Recorder();
            ColorPicker picker = mount(h, Color.WHITE, new ColorHistory(4), rec);

            // Submitting a colour is a choice; submitting a non-colour is not.
            retypeHex(h, "#3366cc");
            assertEquals("#3366cc", Hex.format(picker.color()));
            assertEquals(1, rec.committed.size());

            retypeHex(h, "nonsense");
            assertEquals("#3366cc", Hex.format(picker.color()), "the colour is unchanged");
            assertEquals(1, rec.committed.size(), "and nothing was chosen");
        }
    }

    @Test
    void theRampsAreDrawnAgainstTheirBoxes() {
        try (HeadlessGui h = new HeadlessGui()) {
            mount(h, Color.rgb(0xFF8800), new ColorHistory(4), new Recorder());

            for (String role : List.of("colorpicker.field", "colorpicker.hue", "colorpicker.alpha")) {
                RetainedNode ramp = byRole(h, role);
                assertNotNull(ramp.picture(), role + " has a gradient");
                assertTrue(ramp.picture().marks().size() > 4, role + " is a ramp, not a block");
                assertNotNull(ramp.overlay(), role + " has a marker");
            }
        }
    }

    /** Hiding the alpha strip pins alpha, so the picker cannot hand back a transparency nobody can see. */
    @Test
    void hidingAlphaPinsIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            ColorPicker picker = mount(h, Color.rgba(1f, 0f, 0f, 0.25f), new ColorHistory(4), new Recorder());
            assertEquals(0.25f, picker.color().a(), 0.01);

            picker.alpha(false);
            h.frame();

            assertEquals(1f, picker.color().a(), EPS);
        }
    }

    // --- harness -----------------------------------------------------------------------------------------

    private static final class Recorder {
        final List<Color> changed = new ArrayList<>();
        final List<Color> committed = new ArrayList<>();
    }

    private static ColorPicker mount(HeadlessGui h, Color initial, ColorHistory history, Recorder rec) {
        ColorPicker picker = new ColorPicker(h.gui, initial, history)
                .onChange(rec.changed::add)
                .onCommit(rec.committed::add);
        h.gui.root().append(picker.node());
        h.frame();          // first layout
        h.frame();          // the resize handlers' pictures land in the next one
        return picker;
    }

    /** Press, move, release — a drag, which the harness has no helper for. */
    private static void drag(HeadlessGui h, float x0, float y0, float x1, float y1) {
        h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, (int) x0, (int) y0, 0));
        h.frame();
        h.bus.publish(InputTopics.INPUT,
                new InputEvent.PointerMoved((int) x1, (int) y1, (int) (x1 - x0), (int) (y1 - y0), 0));
        h.frame();
        h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, (int) x1, (int) y1, 0));
        h.frame();
    }

    /** Click into the hex box, replace what is there, and submit — the way a user changes it. */
    private static void retypeHex(HeadlessGui h, String text) {
        Rect box = rectOf(h, "textfield");
        h.click(box.x() + box.w() / 2f, box.y() + box.h() / 2f);
        h.chord(Key.A, Key.LEFT_CONTROL);           // select all
        h.type(text);
        h.tap(Key.ENTER);
        h.frame();
    }

    private static List<String> hexes(List<Color> colors) {
        return colors.stream().map(Hex::format).toList();
    }

    private static RetainedNode byRole(HeadlessGui h, String role) {
        RetainedNode found = findRole(h.retained(h.gui.root()), role);
        assertNotNull(found, "no node with role " + role);
        return found;
    }

    private static Rect rectOf(HeadlessGui h, String role) {
        Rect r = h.gui.layoutSnapshot().nodes().get(byRole(h, role).id).rect();
        assertTrue(r.w() > 0f && r.h() > 0f, role + " has no box");
        return r;
    }

    private static List<RetainedNode> swatches(HeadlessGui h) {
        List<RetainedNode> out = new ArrayList<>();
        collectRole(h.retained(h.gui.root()), "colorpicker.swatch", out);
        return out;
    }

    private static RetainedNode swatchOf(HeadlessGui h, ColorPicker picker, int index) {
        List<RetainedNode> out = new ArrayList<>();
        collectRole(h.retained(picker.node()), "colorpicker.swatch", out);
        return out.get(index);
    }

    private static Rect swatchRect(HeadlessGui h, int index) {
        return h.gui.layoutSnapshot().nodes().get(swatches(h).get(index).id).rect();
    }

    private static RetainedNode findRole(RetainedNode n, String role) {
        if (n == null) {
            return null;
        }
        if (role.equals(n.role())) {
            return n;
        }
        for (RetainedNode child : n.children) {
            RetainedNode hit = findRole(child, role);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static void collectRole(RetainedNode n, String role, List<RetainedNode> into) {
        if (n == null) {
            return;
        }
        if (role.equals(n.role())) {
            into.add(n);
        }
        for (RetainedNode child : n.children) {
            collectRole(child, role, into);
        }
    }
}
