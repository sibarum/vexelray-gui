package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The cursor rule (§8.3): the pointer advertises what it can do here.
 *
 * <p>Almost all of it is <b>inferred rather than declared</b>, which is the part worth protecting. A node is
 * clickable because it has a click handler; a text node is editable because it says so; the framework knows where
 * its own scrollbars are. The affordance therefore follows from registering the behaviour, and no widget can
 * forget to describe itself. Only the case the framework genuinely cannot tell apart — a slider's drag versus a
 * text field's click-to-caret, which use the same seam — needs a declaration.
 */
class CursorRuleTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.clicks", ClickEvent.class);

    private static RetainedNode box(long id, float x, float y, float w, float h) {
        RetainedNode n = new RetainedNode(id, NodeKind.BOX);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        return n;
    }

    private static void attach(RetainedNode parent, RetainedNode child) {
        child.parent = parent;
        parent.children.add(child);
    }

    private record Harness(InputDispatcher dispatcher, Atchung bus, List<CursorShape> seen) {

        /** Move the pointer to (x, y) and report the cursor in force afterwards. */
        CursorShape at(RetainedNode root, float x, float y) {
            bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved((int) x, (int) y, 0, 0, 0));
            dispatcher.dispatch(root);
            return last();
        }

        void press(RetainedNode root, float x, float y) {
            bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, (int) x, (int) y, 0));
            dispatcher.dispatch(root);
        }

        void release(RetainedNode root, float x, float y) {
            bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, (int) x, (int) y, 0));
            dispatcher.dispatch(root);
        }

        CursorShape last() {
            return seen.isEmpty() ? CursorShape.DEFAULT : seen.get(seen.size() - 1);
        }
    }

    private static Harness harness() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run, () -> { });
        List<CursorShape> seen = new ArrayList<>();
        d.cursorSink(seen::add);
        return new Harness(d, bus, seen);
    }

    private static RetainedNode editable(long id, float w, float h) {
        RetainedNode n = new RetainedNode(id, NodeKind.TEXT);
        n.w = w;
        n.h = h;
        n.set(PropKey.EDITABLE, Boolean.TRUE);
        return n;
    }

    private static RetainedNode scroller() {
        RetainedNode root = box(0, 0, 0, 100, 100);
        root.overflowY = true;
        root.viewX = 0;
        root.viewY = 0;
        root.viewW = 90;      // the right 10px are the reserved scrollbar strip
        root.viewH = 100;
        root.contentH = 200;
        root.scrollbarPx = 10;
        return root;
    }

    @Test
    void aClickHandlerMakesItAPointer() {
        Harness h = harness();
        RetainedNode root = box(0, 0, 0, 200, 200);
        RetainedNode button = box(1, 0, 0, 50, 50);
        attach(root, button);
        h.dispatcher().onClick(1, () -> { });

        assertEquals(CursorShape.POINTER, h.at(root, 10, 10), "over the button");
        assertEquals(CursorShape.DEFAULT, h.at(root, 150, 150), "and back to the arrow off it");
    }

    /** A button's label is part of the button, by the same ancestor walk clicks bubble by. */
    @Test
    void aDescendantOfAClickableNodeIsAlsoAPointer() {
        Harness h = harness();
        RetainedNode root = box(0, 0, 0, 200, 200);
        RetainedNode button = box(1, 0, 0, 50, 50);
        RetainedNode label = box(2, 5, 5, 20, 20);
        attach(root, button);
        attach(button, label);
        h.dispatcher().onClick(1, () -> { });

        assertEquals(CursorShape.POINTER, h.at(root, 10, 10), "the hit is the label, the handler is the button");
    }

    /** The rule the request turns on: clickable beats editable, so a control reads as a control. */
    @Test
    void clickableOverridesTheTextCursor() {
        Harness h = harness();
        RetainedNode root = box(0, 0, 0, 200, 200);
        RetainedNode field = editable(1, 50, 50);
        attach(root, field);

        assertEquals(CursorShape.TEXT, h.at(root, 10, 10), "editable text alone is an I-beam");

        h.dispatcher().onClick(1, () -> { });
        assertEquals(CursorShape.POINTER, h.at(root, 20, 20), "once it is clickable, the pointer wins");
    }

    @Test
    void aScrollbarStripIsGrabbable() {
        Harness h = harness();
        RetainedNode root = scroller();

        assertEquals(CursorShape.DEFAULT, h.at(root, 50, 50), "over the content");
        assertEquals(CursorShape.GRAB, h.at(root, 95, 50), "over the scrollbar strip");
    }

    /** A scrollbar over editable text still reads as a scrollbar — the strip is not part of the text. */
    @Test
    void aScrollbarBeatsTheTextCursor() {
        Harness h = harness();
        RetainedNode root = scroller();
        RetainedNode field = editable(1, 100, 100);
        attach(root, field);

        assertEquals(CursorShape.TEXT, h.at(root, 50, 50), "over the text itself");
        assertEquals(CursorShape.GRAB, h.at(root, 95, 50), "over its scrollbar");
    }

    /** Grabbing outranks grabbable, and holds while the pointer wanders — the capture is still driving it. */
    @Test
    void grabbingAScrollbarClosesTheHandUntilRelease() {
        Harness h = harness();
        RetainedNode root = scroller();

        assertEquals(CursorShape.GRAB, h.at(root, 95, 5), "over the thumb");
        h.press(root, 95, 5);
        assertEquals(CursorShape.GRABBING, h.last(), "pressing it closes the hand");

        assertEquals(CursorShape.GRABBING, h.at(root, 20, 60),
                "and it stays closed out over the content, because the drag is still live");

        h.release(root, 20, 60);
        assertEquals(CursorShape.DEFAULT, h.last(), "released, over content: back to the arrow");
    }

    /** A declared shape covers what the framework cannot infer, and beats the inferred rules. */
    @Test
    void aDeclaredCursorWinsOverTheInferredOnes() {
        Harness h = harness();
        RetainedNode root = box(0, 0, 0, 200, 200);
        RetainedNode slider = box(1, 0, 0, 50, 50);
        attach(root, slider);
        h.dispatcher().onClick(1, () -> { });          // clickable, so POINTER by inference
        h.dispatcher().setCursor(1, CursorShape.GRAB); // but it declares itself grabbable

        assertEquals(CursorShape.GRAB, h.at(root, 10, 10), "the declaration is more specific than the inference");
    }

    /** Dragging something declared grabbable closes the hand, the same as a scrollbar. */
    @Test
    void draggingADeclaredGrabClosesTheHand() {
        Harness h = harness();
        RetainedNode root = box(0, 0, 0, 200, 200);
        RetainedNode slider = box(1, 0, 0, 50, 50);
        attach(root, slider);
        h.dispatcher().onDrag(1, e -> { });
        h.dispatcher().setCursor(1, CursorShape.GRAB);

        assertEquals(CursorShape.GRAB, h.at(root, 10, 10));
        h.press(root, 10, 10);
        assertEquals(CursorShape.GRABBING, h.last(), "the drag has it");
        h.release(root, 10, 10);
        assertEquals(CursorShape.GRAB, h.last(), "released but still over it");
    }

    /** A text field's drag handler must not read as grabbable — the case that forced declaration over inference. */
    @Test
    void aTextFieldsDragHandlerDoesNotMakeItGrabbable() {
        Harness h = harness();
        RetainedNode root = box(0, 0, 0, 200, 200);
        RetainedNode field = editable(1, 50, 50);
        attach(root, field);
        h.dispatcher().onDrag(1, e -> { });   // click-to-caret, exactly as TextField registers it

        assertEquals(CursorShape.TEXT, h.at(root, 10, 10),
                "a drag handler alone is not an affordance — this is why GRAB is declared, not inferred");
    }

    /** The sink fires only on change — a cursor is set once, not every frame the pointer moves. */
    @Test
    void theSinkFiresOnlyOnChange() {
        Harness h = harness();
        RetainedNode root = box(0, 0, 0, 200, 200);
        RetainedNode button = box(1, 0, 0, 50, 50);
        attach(root, button);
        h.dispatcher().onClick(1, () -> { });

        h.at(root, 10, 10);
        h.at(root, 12, 12);
        h.at(root, 14, 14);
        assertEquals(1, h.seen().size(), "three moves across one button is one cursor change");
    }
}
