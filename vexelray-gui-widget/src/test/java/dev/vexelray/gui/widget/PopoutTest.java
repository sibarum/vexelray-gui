package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.LayoutContext;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.nav.Address;
import dev.vexelray.gui.core.nav.NavTopics;
import dev.vexelray.gui.core.nav.Navigation;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A docked panel is a child of the host tree, and everything that follows from that: it takes space the host's
 * content then does not have, collapsing gives that space back, and a landmark inside it is reachable because
 * the panel declares how to un-hide it.
 *
 * <p>Nothing here opens a window — that needs a {@code GuiApp}, and a window is not a thing a headless test has.
 * The two halves of the pop that do not need one are still checked: the tree the window would show is built and
 * laid out directly (chrome, content, theme), and the docked side is asserted through the property the window
 * half rests on — popped-out and collapsed leave the <em>same</em> footprint, so the geometry proved here is the
 * geometry either state produces.
 */
class PopoutTest {

    private static final float W = 800f;
    private static final float H = 600f;
    private static final LayoutContext CTX = LayoutContext.of(W, H);

    private static float px(Length length) {
        return length.scalarPx(CTX, 0f);
    }

    /** The panel's own rule, which is part of its declared footprint. */
    private static final float RULE = px(Length.dp(1));

    /** Step frames until the navigation finishes, then answer where it arrived. */
    private static Node arrive(HeadlessGui h, Navigation nav) {
        for (int i = 0; i < 32 && !nav.done(); i++) {
            h.frame();
        }
        try {
            return nav.arrival().get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new AssertionError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Whether any text node in {@code root} is showing exactly {@code s}. */
    private static boolean showsText(RetainedNode root, String s) {
        if (root == null) {
            return false;
        }
        if (s.equals(root.textString())) {
            return true;
        }
        for (RetainedNode child : root.children) {
            if (showsText(child, s)) {
                return true;
            }
        }
        return false;
    }

    /** A host row of [content, panel] with the panel docked right — the arrangement the edge asks for. */
    private static Popout dockRight(HeadlessGui h, Node content, Popout.Content builds) {
        Popout panel = new Popout(h.gui, "Outline", Popout.Edge.RIGHT, builds).size(Length.rem(14));
        h.gui.root().direction(dev.vexelray.gui.core.layout.LayoutEnums.Direction.ROW)
                .children(content.width(Length.grow(1)).height(Length.FILL), panel.node());
        return panel;
    }

    @Test
    void theDockedPanelTakesSpaceTheHostContentThenDoesNotHave() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node content = h.gui.column();
            Popout panel = dockRight(h, content, (gui, into) -> into.append(gui.text("outline")));
            h.frame().frame();

            float expected = px(Length.rem(14)) + RULE;
            assertEquals(expected, panel.node().layout().rect().w(), 0.5f,
                    "the panel is as wide as it was told, plus its own rule");
            assertEquals(W - expected, content.layout().rect().w(), 0.5f,
                    "and the host's content is narrower by exactly that — this is layout, not an overlay");
        }
    }

    @Test
    void collapsingHidesTheBodyAndGivesTheSpaceBack() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node content = h.gui.column();
            Popout panel = dockRight(h, content, (gui, into) -> into.append(gui.text("outline")));
            h.frame().frame();
            float open = panel.node().layout().rect().w();

            panel.collapse(true);
            h.frame().frame();

            assertTrue(panel.collapsed(), "the panel says it is collapsed");
            assertFalse(h.retained(panel.body()).visible(), "the body is hidden, not removed");
            float railed = panel.node().layout().rect().w();
            assertTrue(railed < open, "the rail is narrower than the open panel");
            assertEquals(px(Length.dp(24)) + px(Length.dp(6)) * 2 + RULE, railed, 0.5f,
                    "and it is exactly one control wide — a side rail carries no title, because the atlas has "
                            + "no rotated text to lay one out with");
            assertEquals(W - railed, content.layout().rect().w(), 0.5f,
                    "the space the panel stopped using is the host's again");

            panel.collapse(false);
            h.frame().frame();
            assertEquals(open, panel.node().layout().rect().w(), 0.5f, "and opening it puts it back");
        }
    }

    @Test
    void clickingTheHeaderControlCollapsesAndOpensThePanel() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node content = h.gui.column();
            Popout panel = dockRight(h, content, (gui, into) -> into.append(gui.text("outline")));
            h.frame().frame();

            // Where the user actually presses: the last control in the header strip, inside its padding.
            Rect header = panel.header().layout().rect();
            float x = header.x() + header.w() - px(Length.dp(6)) - px(Length.dp(12));
            float y = header.y() + header.h() / 2f;

            h.click(x, y).frame();
            assertTrue(panel.collapsed(), "one click collapses it");

            // Collapsed, the header is a column running the height of the panel, and its controls sit at the
            // top of that rail — which is where the user looks for the thing that opens it again.
            Rect rail = panel.header().layout().rect();
            h.click(rail.x() + rail.w() / 2f, rail.y() + px(Length.dp(12))).frame();
            assertFalse(panel.collapsed(), "and the rail carries the control that undoes it");
        }
    }

    @Test
    void aLandmarkInsideACollapsedPanelIsRevealedByThePanel() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node[] buried = new Node[1];
            Node content = h.gui.column();
            Popout panel = dockRight(h, content, (gui, into) -> {
                buried[0] = gui.text("symbols").width(Length.em(6)).height(Length.em(2));
                into.append(buried[0]);
            });
            h.gui.landmark("outline.symbols", buried[0]);
            panel.collapse(true);
            h.frame().frame();
            assertFalse(h.retained(panel.body()).visible(), "the landmark is inside something hidden");

            Node arrived = arrive(h, h.gui.navigate("outline.symbols"));

            assertEquals(buried[0].id(), arrived.id(), "navigation arrives at the node the name was bound to");
            assertFalse(panel.collapsed(), "and it got there by opening the panel");
            assertTrue(h.retained(panel.body()).visible(), "so the landmark is actually on screen");
        }
    }

    @Test
    void contentIsBuiltAgainstTheTreeItIsBuiltInto() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node[] built = new Node[1];
            dockRight(h, h.gui.column(), (gui, into) -> {
                assertEquals(h.gui, gui, "the docked build is handed the host's own tree");
                built[0] = gui.text("outline");
                into.append(built[0]);
            });
            h.frame().frame();

            assertTrue(built[0].layout().rect().w() > 0f,
                    "and a node minted from it lays out — which a node from the wrong tree would not");
        }
    }

    @Test
    void theWindowTreeIsBuiltWithTheApplicationsOwnChrome() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node[] away = new Node[1];
            Popout panel = dockRight(h, h.gui.column(), (gui, into) -> {
                if (gui != h.gui) {
                    away[0] = gui.text("outline");
                    into.append(away[0]);
                } else {
                    into.append(gui.text("outline"));
                }
            });
            h.frame().frame();
            panel.buildAway();   // what the first pop does; there is no window to open here

            RetainedNode root = h.frame(panel.awayGui());
            assertNotNull(HeadlessGui.find(root, panel.awayBar().node()),
                    "the popped-out window draws the application's own title bar, not the desktop's");
            assertNotNull(HeadlessGui.find(root, away[0]),
                    "and the content was built a second time, into that tree");
            assertEquals(h.gui.theme(), panel.awayGui().theme(),
                    "against the host's theme — a second window is not a second opinion about how the "
                            + "application looks");

            panel.title("Symbols");
            assertTrue(showsText(h.frame(panel.awayGui()), "Symbols"),
                    "renaming the panel reaches the window that came out of it, not just the docked header");
        }
    }

    @Test
    void aPanelWithNoWindowNamedStaysWhereItIs() {
        try (HeadlessGui h = new HeadlessGui()) {
            Popout panel = dockRight(h, h.gui.column(), (gui, into) -> into.append(gui.text("outline")));
            h.frame().frame();

            panel.pop();
            h.frame().frame();

            assertFalse(panel.popped(),
                    "nothing to pop into is not a pop that half-happened: the docked panel is untouched");
            assertTrue(h.retained(panel.body()).visible(), "and its content is still showing");
        }
    }

    /** Content that says which tree it was built into, so a tree showing the other's text is visible as such. */
    private static Popout.Content saysWhichTree(HeadlessGui h) {
        return (gui, into) -> into.append(gui.text(gui == h.gui ? "docked" : "away"));
    }

    @Test
    void buildingTheWindowTreeLeavesTheHostTreeAlone() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node content = h.gui.column();
            Popout panel = dockRight(h, content, saysWhichTree(h));
            h.frame().frame();
            float open = panel.node().layout().rect().w();

            panel.buildAway();   // what the first pop does
            RetainedNode away = h.frame(panel.awayGui());
            h.frame().frame();

            RetainedNode host = h.retained(h.gui.root());
            assertTrue(showsText(host, "docked"), "the host still shows its own copy of the content");
            assertFalse(showsText(host, "away"),
                    "and none of the window's: node ids start at 1 in every tree, so the window's mutations "
                            + "applied here would rewrite the host's nodes");
            assertFalse(showsText(away, "docked"), "nor the other way round");
            assertTrue(h.retained(panel.body()).visible(), "the docked body is where it was");
            assertEquals(open, panel.node().layout().rect().w(), 0.5f, "and the same size");
            assertEquals(W - open, content.layout().rect().w(), 0.5f, "with the host's content beside it");
            assertNotSame(h.gui.bus(), panel.awayGui().bus(),
                    "two trees, two buses: Gui's topics are static, so a shared bus is a shared tree");
        }
    }

    @Test
    void inputForTheWindowDoesNotReachTheHost() {
        try (HeadlessGui h = new HeadlessGui()) {
            Popout panel = dockRight(h, h.gui.column(), saysWhichTree(h));
            h.frame().frame();
            // The away content is deliberately not built: on a shared bus that alone wrecks the host tree, and
            // this is about where input goes, which is decided by the bus and not by what is in the window.

            // A press in the popped-out window, at the point in the host where the collapse control is. The
            // window's input backend publishes on its tree's bus; were that the host's bus, the host would read
            // the press in its own coordinates and act on a click the user made somewhere else.
            Rect header = panel.header().layout().rect();
            int x = (int) (header.x() + header.w() - px(Length.dp(6)) - px(Length.dp(12)));
            int y = (int) (header.y() + header.h() / 2f);
            panel.awayGui().bus().publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, x, y, 0));
            h.frame();
            panel.awayGui().bus().publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, x, y, 0));
            h.frame().frame();

            assertFalse(panel.collapsed(), "a click in the window is not a click in the host");
        }
    }

    @Test
    void navigationStillCrossesBetweenTheTwoWindows() {
        try (HeadlessGui h = new HeadlessGui()) {
            Popout panel = dockRight(h, h.gui.column(), saysWhichTree(h));
            h.frame().frame();
            panel.buildAway();
            // What GuiApp does for the main window and for every window it opens by name.
            h.gui.windowKey("main");
            panel.awayGui().windowKey("outline");

            List<Address> atHost = new CopyOnWriteArrayList<>();
            List<Address> atAway = new CopyOnWriteArrayList<>();
            h.gui.bus().subscribe(NavTopics.GO, atHost::add);
            panel.awayGui().bus().subscribe(NavTopics.GO, atAway::add);

            // Outbound: a link inside the popped-out panel to another window has to reach the bus GuiApp routes
            // windows from — the host's, for a panel docked in the main window.
            Address toMain = Address.of("main", "editor");
            panel.awayGui().navigate(toMain);
            assertEquals(List.of(toMain), atHost, "a request from the window reaches the host's bus, once");

            // Inbound: a request naming the window, published in the host, reaches the tree that has the landmark.
            Address toOutline = Address.of("outline", "symbols");
            h.gui.navigate(toOutline);
            assertEquals(List.of(toMain, toOutline), atAway, "a request for the window reaches its bus, once");
            assertEquals(List.of(toMain, toOutline), atHost, "and nothing echoes back and forth between the two");
        }
    }
}
