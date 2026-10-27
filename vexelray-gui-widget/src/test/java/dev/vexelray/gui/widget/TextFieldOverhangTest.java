package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A field whose text is cut off grows over its neighbours while it holds focus, and gives the room back when it loses
 * it (docs/plans/scrolling.md §7.3). Nothing around it moves either way.
 */
class TextFieldOverhangTest {

    private static final String LONG = "x".repeat(30);   // 300px at the harness's 10px per character

    /** A 100px field between two boxes, in a column across the window. */
    private static Node[] build(HeadlessGui h, TextField field) {
        field.node().width(Length.rem(100f / 16f));
        Node above = h.gui.box().width(Length.FILL).height(Length.rem(2));
        Node below = h.gui.box().width(Length.FILL).height(Length.rem(2));
        h.gui.root().children(h.gui.column().width(Length.FILL).height(Length.FILL)
                .children(above, field.node(), below));
        h.frame();
        return new Node[]{above, below};
    }

    @Test
    void focusShowsTheWholeLineAndBlurGivesTheRoomBack() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField field = new TextField(h.gui, LONG);
            Node[] around = build(h, field);
            float below = around[1].layout().rect().y();
            assertEquals(100f, field.node().layout().rect().w(), 0.5f, "unfocused: its slot");

            h.focus(field.node()).frame().frame();
            NodeLayout f = field.node().layout();
            assertEquals(true, f.rect().w() > 300f, "focused: the whole line, and its insets, are showing");
            assertEquals(below, around[1].layout().rect().y(), 0.5f, "and nothing below it moved");

            TextField other = new TextField(h.gui, "");
            h.gui.root().append(other.node());
            h.frame();
            h.focus(other.node()).frame().frame();
            assertEquals(100f, field.node().layout().rect().w(), 0.5f, "blurred: back in its slot");
        }
    }

    /** The press that focuses a field must land where it was aimed: the field grows when it is released. */
    @Test
    void aPressThatFocusesTheFieldGrowsItOnRelease() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField field = new TextField(h.gui, LONG);
            build(h, field);
            NodeLayout before = field.node().layout();
            float x = before.rect().x() + 50f;
            float y = before.rect().y() + before.rect().h() / 2f;

            h.press(x, y);
            assertEquals(100f, field.node().layout().rect().w(), 0.5f, "held: nothing under the pointer has moved");
            h.release(x, y).frame();
            assertEquals(true, field.node().layout().rect().w() > 300f, "released: now it grows");
        }
    }

    @Test
    void aFieldThatSaysNoScrollsInsideItsBox() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField field = new TextField(h.gui, LONG).overhang(false);
            build(h, field);
            h.focus(field.node()).frame().frame();
            assertEquals(100f, field.node().layout().rect().w(), 0.5f);
        }
    }

    /** An editor its pane sizes already has the room it is given, and does not spread over the window. */
    @Test
    void anEditorItsPaneSizesDoesNotGrow() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField editor = new TextField(h.gui, "line\n".repeat(40)).multiline(true);
            editor.node().width(Length.FILL).height(Length.FILL);
            Node pane = h.gui.column().width(Length.rem(15)).height(Length.rem(10)).children(editor.node());
            h.gui.root().children(h.gui.row().width(Length.FILL).height(Length.FILL).children(pane));
            h.frame();
            h.focus(editor.node()).frame().frame();
            assertEquals(pane.layout().rect().h(), editor.node().layout().rect().h(), 0.5f, "it scrolls in its pane");
        }
    }
}
