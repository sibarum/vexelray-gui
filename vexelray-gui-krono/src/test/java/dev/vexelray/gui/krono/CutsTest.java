package dev.vexelray.gui.krono;

import dev.vexelray.gui.core.Cuts;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.style.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.kronometer.Kron;
import sibarum.kronometer.anim.Ease;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * The cut watch, against the framework's own motion: with colours fading and nodes arriving and leaving, every kind of
 * change a view makes passes; with that motion taken away, each of them is a cut, named.
 */
class CutsTest {

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    /** A window's worth: a column with a label, a swatch and a row in it. */
    private record Scene(KronoGui krono, Node list, Node label, Node swatch, Node row) {
    }

    private static Scene scene(boolean motion) {
        Gui gui = new Gui(Atchung.create());
        KronoGui krono = KronoGui.attach(gui, Kron.driven());
        if (motion) {
            gui.paintMotion(Fades.on(krono, ms(120), Ease.LINEAR));
            Presence presence = Presence.on(krono).followSubtree(gui.root(), ms(140), Ease.OUT_CUBIC);
            gui.enterMotion(presence).exitMotion(presence);
        }
        Node label = gui.text("one");
        Node swatch = gui.box().size(Length.dp(20), Length.dp(20)).background(Role.PANEL);
        Node row = gui.box().size(Length.dp(40), Length.dp(10)).background(Role.LINE);
        Node list = gui.column().children(label, swatch, row);
        gui.root().children(list);
        Scene s = new Scene(krono, list, label, swatch, row);
        at(s, 0);
        return s;
    }

    private static void at(Scene s, long at) {
        s.krono().tick(ms(at));
        Seams.of(s.krono().gui()).frame(400f, 300f, CutsTest::noText);
    }

    /** Every kind of change a view makes, one frame apart, and frames until all of it has settled. */
    private static void change(Scene s) {
        long t = 0;
        at(s, t);   // the first frame watched, which the rest are judged against
        s.label().text("two");
        at(s, t += 16);
        s.swatch().background(Role.RAISED);
        at(s, t += 16);
        s.swatch().opacity(0.5f);
        at(s, t += 16);
        s.row().visible(false);
        at(s, t += 16);
        s.list().append(s.krono().gui().box().size(Length.dp(30), Length.dp(10)).background(Role.LINE));
        at(s, t += 16);
        s.label().remove();
        for (int i = 0; i < 30; i++) {
            at(s, t += 16);
        }
    }

    @Test
    @DisplayName("with the framework's motion, nothing a view does lands in one frame")
    void withMotionNothingCuts() {
        Scene s = scene(true);
        try (KronoGui krono = s.krono()) {
            Cuts cuts = Cuts.watch(krono.gui());
            change(s);
            assertEquals(List.of(), cuts.found());
        }
    }

    @Test
    @DisplayName("without it, each change is a cut, and says which node and what")
    void withoutMotionEachChangeIsACut() {
        Scene s = scene(false);
        try (KronoGui krono = s.krono()) {
            Cuts cuts = Cuts.watch(krono.gui());
            change(s);
            String found = cuts.found().toString();
            assertTrue(found.contains("words \"one\" -> \"two\""), found);
            assertTrue(found.contains("background"), found);
            assertTrue(found.contains("opacity 1.0 -> 0.5"), found);
            assertTrue(found.contains("appeared whole"), found);
            assertTrue(found.contains("gone in one frame"), found);
            assertEquals(6, cuts.found().size(), "the row hidden, and the label removed, are each gone: " + found);
        }
    }
}
