package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.input.Menu;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application's own menus in the title bar ({@link TitleBar#menu}): the menu bar beside the window's app menu.
 *
 * <p>What is pinned: they sit <b>after the lockup</b>, in the order first declared, and before the title; a click or
 * {@link TitleBar#open} hangs the menu under its target; an item <b>sends its message</b> and runs nothing else; a
 * second declaration <b>replaces in place</b>; and an empty menu stays where it is, inert.
 */
class TitleBarMenuTest {

    record Build(String goal) { }

    private static final Topic<Build> BUILD = Topic.edge("test.titlebar.build", Build.class);

    private static Sender<Build> sender(HeadlessGui h) {
        return h.bus.tick(() -> { }).inbox("view").sender(BUILD);
    }

    private static TitleBar bar(HeadlessGui h) {
        TitleBar bar = new TitleBar(h.gui, WindowControls.NONE, "app").wordmark("App");
        h.gui.root().children(bar.node());
        h.frame();
        return bar;
    }

    private static Rect rect(HeadlessGui h, String landmark) {
        dev.vexelray.gui.core.Node node = h.gui.landmarkNode(landmark).orElseThrow(() ->
                new AssertionError("no " + landmark));
        return h.gui.layoutSnapshot().node(h.retained(node).id).rect();
    }

    private static void click(HeadlessGui h, String landmark) {
        Rect r = rect(h, landmark);
        h.click(r.x() + r.w() / 2f, r.y() + r.h() / 2f);
        h.frame();
    }

    private static List<String> labels(ContextMenu menu) {
        return menu.items().stream().map(i -> i.separator() ? "-" : i.label()).toList();
    }

    @Test
    void menusSitAfterTheLockupInTheOrderDeclared() {
        try (HeadlessGui h = new HeadlessGui()) {
            Sender<Build> build = sender(h);
            Menu one = Menu.builder().item("Compile", build, new Build("compile")).build();
            bar(h).menu("run", "Run", one).menu("view", "View", one);
            h.frame();

            Rect lockup = rect(h, TitleBar.MARK);
            Rect run = rect(h, TitleBar.menuLandmark("run"));
            Rect view = rect(h, TitleBar.menuLandmark("view"));
            assertTrue(lockup.x() + lockup.w() <= run.x(), "the application's menus come after the window's identity");
            assertTrue(run.x() + run.w() <= view.x(), "in the order first declared");
        }
    }

    @Test
    void aClickHangsTheMenuAndAnItemSendsItsMessage() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Build> heard = new ArrayList<>();
            h.bus.subscribe(BUILD, heard::add);
            Sender<Build> build = sender(h);
            ContextMenu menu = new ContextMenu(h.gui);
            bar(h).menu("run", "Run", Menu.builder()
                    .item("Compile", build, new Build("compile"))
                    .separator()
                    .item("Package", build, new Build("package"))
                    .build());
            h.frame();

            click(h, TitleBar.menuLandmark("run"));
            assertTrue(menu.isOpen(), "the target's click opens its menu");
            assertEquals(List.of("Compile", "-", "Package"), labels(menu));
            assertTrue(heard.isEmpty(), "opening the menu sends nothing");

            click(h, "menu.package");
            h.frame();
            assertEquals(List.of(new Build("package")), heard);
            assertFalse(menu.isOpen(), "choosing a line closes the menu");
        }
    }

    /**
     * One press goes from one open menu to the next, as a menu bar does: a click on another menu's target opens that
     * menu in place of the open one, rather than only closing it. A press on the open menu's own target still closes it.
     */
    @Test
    void oneClickGoesFromOneMenuToTheNext() {
        try (HeadlessGui h = new HeadlessGui()) {
            Sender<Build> build = sender(h);
            ContextMenu menu = new ContextMenu(h.gui);
            TitleBar bar = bar(h)
                    .menu("run", "Run", Menu.builder().item("Compile", build, new Build("compile")).build())
                    .menu("view", "View", Menu.builder().item("Zoom", build, new Build("zoom")).build());
            bar.onMark("About App", () -> { });
            h.frame();

            click(h, TitleBar.menuLandmark("run"));
            assertEquals(List.of("Compile"), labels(menu));

            click(h, TitleBar.menuLandmark("view"));
            h.frame();
            assertTrue(menu.isOpen(), "the press on View opened View, not merely closed Run");
            assertEquals(List.of("Zoom"), labels(menu));

            click(h, TitleBar.MARK);
            h.frame();
            assertTrue(menu.isOpen(), "and from an application menu to the app menu");
            assertEquals("About App", labels(menu).getFirst());

            click(h, TitleBar.menuLandmark("run"));
            h.frame();
            assertTrue(menu.isOpen(), "and back");
            assertEquals(List.of("Compile"), labels(menu));

            click(h, TitleBar.menuLandmark("run"));
            h.frame();
            assertFalse(menu.isOpen(), "its own target's press still closes it");

            click(h, TitleBar.MARK);
            click(h, TitleBar.MARK);
            h.frame();
            assertFalse(menu.isOpen(), "and so does the lockup's, for the app menu");
        }
    }

    @Test
    void openDropsItAsAClickWouldAndClosesItAgain() {
        try (HeadlessGui h = new HeadlessGui()) {
            ContextMenu menu = new ContextMenu(h.gui);
            TitleBar bar = bar(h).menu("run", "Run",
                    Menu.builder().item("Compile", sender(h), new Build("compile")).build());
            h.frame();

            bar.open("run");
            h.frame();
            assertTrue(menu.isOpen(), "a key can drop the menu");
            assertEquals("0.0", menu.marked(), "with its first line under the keyboard");
            bar.open("run");
            h.frame();
            assertFalse(menu.isOpen(), "and the same key puts it away");
            bar.open("nothing-by-this-name");
            h.frame();
            assertFalse(menu.isOpen());
        }
    }

    @Test
    void aSecondDeclarationReplacesInPlace() {
        try (HeadlessGui h = new HeadlessGui()) {
            Sender<Build> build = sender(h);
            ContextMenu menu = new ContextMenu(h.gui);
            TitleBar bar = bar(h)
                    .menu("run", "Run", Menu.builder().item("Compile", build, new Build("compile")).build())
                    .menu("view", "View", Menu.builder().item("Zoom", build, new Build("zoom")).build());
            h.frame();
            float at = rect(h, TitleBar.menuLandmark("run")).x();

            bar.menu("run", "Run", Menu.builder().item("Package", build, new Build("package")).build());
            h.frame();
            assertEquals(at, rect(h, TitleBar.menuLandmark("run")).x(), "a menu keeps its place in the bar");
            click(h, TitleBar.menuLandmark("run"));
            assertEquals(List.of("Package"), labels(menu), "and holds what was declared last");
        }
    }

    @Test
    void anEmptyMenuStaysAndOpensNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            ContextMenu menu = new ContextMenu(h.gui);
            TitleBar bar = bar(h).menu("run", "Run", Menu.NONE);
            h.frame();

            assertTrue(h.gui.landmarkNode(TitleBar.menuLandmark("run")).isPresent(), "it stays where it is found");
            click(h, TitleBar.menuLandmark("run"));
            assertFalse(menu.isOpen(), "an empty menu opens nothing");
            bar.open("run");
            h.frame();
            assertFalse(menu.isOpen());
        }
    }
}
