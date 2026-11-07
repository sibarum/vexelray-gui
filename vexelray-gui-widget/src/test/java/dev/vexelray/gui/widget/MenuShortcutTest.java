package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.Commands;
import dev.vexelray.gui.core.input.Menu;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.input.Trigger;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.spi.Seams;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;
import sibarum.probe.Cause;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A menu line says the chord that does the same, at its right, in the chord's own spelling, and binds nothing by it
 * (IDE framework-notes FN-6).
 */
class MenuShortcutTest {

    private static final Shortcut SAVE = Shortcut.of(Key.S, Modifier.CONTROL);

    /** Every text node under {@code n}, with the row it sits in. */
    private static void texts(RetainedNode n, List<RetainedNode> out) {
        if (n.textString() != null && !n.textString().isEmpty()) {
            out.add(n);
        }
        for (RetainedNode c : n.children) {
            texts(c, out);
        }
    }

    private static RetainedNode text(List<RetainedNode> all, String words) {
        return all.stream().filter(n -> words.equals(n.textString())).findFirst().orElse(null);
    }

    @Test
    void aLineSaysItsChordBesideItsLabelAndALineWithoutOneSaysNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node target = h.gui.box().width(Length.FILL).height(Length.FILL);
            new ContextMenu(h.gui);
            Seams.of(h.gui).onContextMenu(target, m -> m
                    .item(MenuItem.of("Save", () -> { }).shortcut(SAVE))
                    .item("Rename", () -> { }));
            h.gui.root().children(target);
            h.frame();
            h.rightClick(200f, 150f);
            h.frame();

            List<RetainedNode> all = new ArrayList<>();
            texts(h.retained(h.gui.root()), all);
            RetainedNode chord = text(all, "Ctrl+S");
            RetainedNode save = text(all, "Save");
            assertSame(save.parent, chord.parent, "the chord is on the Save line");
            assertEquals(1, all.stream().filter(n -> n.textString().startsWith("Ctrl")).count(),
                    "Rename has none");
        }
    }

    @Test
    void aLineNamingACommandSaysItsChordAsItIsNowAndNothingWhenItHasNone() {
        try (HeadlessGui h = new HeadlessGui()) {
            Shortcut rebound = Shortcut.of(Key.K, Modifier.CONTROL, Modifier.ALT);
            Seams.of(h.gui).commands(new Commands() {
                @Override
                public List<Trigger> triggers(String command) {
                    return command.equals("file.save") ? List.of(rebound) : List.of();
                }
            });
            Node target = h.gui.box().width(Length.FILL).height(Length.FILL);
            new ContextMenu(h.gui);
            Seams.of(h.gui).onContextMenu(target, m -> m
                    .item(MenuItem.of("Save", () -> { }).shortcut(SAVE).command("file.save"))
                    .item(MenuItem.of("Close", () -> { }).shortcut(SAVE).command("file.close")));
            h.gui.root().children(target);
            h.frame();
            h.rightClick(200f, 150f);
            h.frame();

            List<RetainedNode> all = new ArrayList<>();
            texts(h.retained(h.gui.root()), all);
            assertSame(text(all, "Save").parent, text(all, "Ctrl+Alt+K").parent, "the chord the person set");
            assertNull(text(all, "Ctrl+S"), "not the one the code started with, and none for an unbound command");
        }
    }

    @Test
    void aBuilderSaysTheChordOfTheLineBeforeItAndOnlyALine() {
        Sender<String> to = Sender.outside(Atchung.create(), Topic.of("test.menu", String.class),
                Cause.source("test.menu", Cause.Kind.IO));
        Menu menu = Menu.builder()
                .shortcut(SAVE)                       // no line yet: nothing to say it of
                .item("Save", to, "save").shortcut(SAVE)
                .separator().shortcut(SAVE)           // a rule says nothing
                .item("Close", to, "close")
                .build();
        assertEquals(SAVE, menu.items().get(0).shortcut());
        assertNull(menu.items().get(1).shortcut());
        assertNull(menu.items().get(2).shortcut());
    }
}
