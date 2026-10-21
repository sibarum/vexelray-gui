package dev.vexelray.gui.krono;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;
import sibarum.kronometer.anim.Ease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * Arrival and departure on a real frame loop, driven headlessly: a {@link Gui} framed by hand and a driven clock
 * ticked to explicit moments, so every presence asserted is the one the renderer would have drawn that frame.
 */
class PresenceTest {

    private static final Dur OVER = ms(100);

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    private static RetainedNode find(RetainedNode from, long id) {
        if (from.id == id) {
            return from;
        }
        for (RetainedNode c : from.children) {
            RetainedNode hit = find(c, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** One frame at logical time {@code at}: the clock first, then the frame, as {@code GuiApp.run} orders them. */
    private static RetainedNode at(KronoGui krono, long at) {
        krono.tick(ms(at));
        return krono.gui().frame(400f, 300f, PresenceTest::noText);
    }

    private static Node row(Gui gui) {
        return gui.box().width(Length.dp(40)).height(Length.dp(20));
    }

    private record Scene(KronoGui krono, Presence presence, Node list) {
    }

    private static Scene scene() {
        Gui gui = new Gui(Atchung.create(), Runnable::run);
        KronoGui krono = KronoGui.attach(gui, Kron.driven());
        Presence presence = Presence.on(krono);
        gui.enterMotion(presence).exitMotion(presence);
        Node list = gui.column();
        gui.root().children(list);
        presence.followChildren(list, OVER, Ease.LINEAR);
        at(krono, 0);
        return new Scene(krono, presence, list);
    }

    @Test
    @DisplayName("an added row comes up from nothing, rising into place, and arrives exactly")
    void arrives() {
        Scene s = scene();
        try (KronoGui krono = s.krono()) {
            s.presence().rise(0.5f);
            Node row = row(krono.gui());
            s.list().append(row);
            RetainedNode r = find(at(krono, 0), row.id());
            assertEquals(0f, r.presence, "not seen in the frame it appears");
            assertEquals(0.5f * r.emPx, r.presenceRise, 0.01f, "drawn half an em below its place");

            at(krono, 50);
            assertEquals(0.5f, r.presence, 0.02f);
            assertEquals(0.25f * r.emPx, r.presenceRise, 0.2f);

            at(krono, 100);
            at(krono, 116);
            assertEquals(1f, r.presence, "the resting state is one, not nearly one");
            assertEquals(0f, r.presenceRise);
            assertTrue(s.presence().settled());
        }
    }

    @Test
    @DisplayName("a removed row fades where it stands, then leaves the tree")
    void leaves() {
        Scene s = scene();
        try (KronoGui krono = s.krono()) {
            Node row = row(krono.gui());
            s.list().children(row);
            at(krono, 0);
            at(krono, 100);
            at(krono, 116);   // arrived

            row.remove();
            RetainedNode r = find(at(krono, 116), row.id());
            assertNotNull(r, "still there while it goes");
            assertTrue(r.hitInert());
            at(krono, 166);
            assertEquals(0.5f, r.presence, 0.02f);

            assertNull(find(at(krono, 216), row.id()), "gone once it is drawn at nothing");
            at(krono, 232);
            assertTrue(s.presence().settled(), "and forgotten once released");
        }
    }

    @Test
    @DisplayName("an interrupted exit followed by re-entry continues from where it is drawn")
    void returnsFromWhereItGot() {
        Scene s = scene();
        try (KronoGui krono = s.krono()) {
            s.presence().rise(0.5f);
            Node row = row(krono.gui());
            s.list().children(row);
            at(krono, 0);
            at(krono, 100);
            RetainedNode r = find(at(krono, 116), row.id());
            assertEquals(1f, r.presence);

            row.remove();
            at(krono, 116);
            float drawn = find(at(krono, 146), row.id()).presence;
            assertEquals(0.7f, drawn, 0.02f, "three tenths of the way out");

            s.list().append(row);   // changed their mind
            RetainedNode back = find(at(krono, 146), row.id());
            assertSame(r, back, "the same node, not one built to look like it");
            assertFalse(back.leaving);
            assertEquals(drawn, back.presence, 0.02f, "no jump: it comes back up from where it was drawn");
            assertEquals(0f, back.presenceRise, "and does not rise again, since it never went anywhere");

            at(krono, 196);
            assertTrue(back.presence > drawn, "on its way back up");
            at(krono, 246);
            at(krono, 262);
            assertEquals(1f, back.presence);
            assertTrue(s.presence().settled());
        }
    }

    @Test
    @DisplayName("a row removed while still arriving leaves from the presence it reached")
    void leavesFromPartWayUp() {
        Scene s = scene();
        try (KronoGui krono = s.krono()) {
            Node row = row(krono.gui());
            s.list().append(row);
            at(krono, 0);
            RetainedNode r = find(at(krono, 40), row.id());
            assertEquals(0.4f, r.presence, 0.02f);

            row.remove();
            at(krono, 40);
            assertEquals(0.4f, r.presence, 0.02f, "no flash up to full before it goes");
            at(krono, 90);
            assertEquals(0.2f, r.presence, 0.02f);
        }
    }

    @Test
    @DisplayName("an unenrolled row, or one enrolled at zero duration, comes and goes at once")
    void unenrolledAndZeroAreImmediate() {
        Scene s = scene();
        try (KronoGui krono = s.krono()) {
            Node other = krono.gui().column();
            krono.gui().root().append(other);
            at(krono, 0);
            Node row = row(krono.gui());
            other.append(row);
            assertEquals(1f, find(at(krono, 0), row.id()).presence);
            row.remove();
            assertNull(find(at(krono, 0), row.id()));

            s.presence().followChildren(other, Dur.ZERO, Ease.LINEAR);
            Node quick = row(krono.gui());
            other.append(quick);
            assertEquals(1f, find(at(krono, 0), quick.id()).presence, "reduced motion: there at once");
            quick.remove();
            assertNull(find(at(krono, 0), quick.id()), "and gone at once");
        }
    }
}
