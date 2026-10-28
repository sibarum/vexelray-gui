package dev.vexelray.gui.demo.chapter;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.MenuAction;
import dev.vexelray.gui.widget.TreeView;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A tree over the real filesystem: lazy children, per-row commands, a search that walks, and a subtree that
 * takes the room it needs over time rather than teleporting.
 *
 * <p>Every awkward property of a real tree is here on purpose. The hierarchy is <em>not</em> the widget's — it
 * is told it by message, a level at a time — the children of a folder are not known until it opens, listing a
 * directory is I/O and must not happen in the frame, a deep count takes long enough to be worth stopping, and the
 * user can outrun a search. A tree that only handles the in-memory case is a list with indentation.
 */
public final class TreeChapter implements Chapter {

    /** The tree's name on the bus: what its requests carry, and what the listing answers to. */
    private static final String FILES = "files";

    /** Where directories are listed: one thread, the demo's I/O, off the frame. */
    private static final Executor LISTING = Executors.newSingleThreadExecutor(
            r -> Thread.ofPlatform().daemon().name("tree-listing").unstarted(r));

    /** What is in {@code dir}: folders first, then files, each by name. */
    private static List<Path> list(Path dir) {
        try (var kids = Files.list(dir)) {
            return kids.sorted(Comparator
                            .comparing((Path k) -> !Files.isDirectory(k))
                            .thenComparing(k -> k.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (IOException e) {
            return List.of();   // an unreadable directory shows as empty, not as a crash
        }
    }

    /**
     * {@code p} as the tree draws it: its name, a folder that opens or a file that does not, what its menu offers,
     * and what Ctrl+F finds it by.
     */
    private static TreeView.Entry entry(Path p) {
        Path name = p.getFileName();
        String label = name == null ? p.toString() : name.toString();
        TreeView.Entry entry = Files.isDirectory(p) ? TreeView.Entry.branch(p, label).icon(Icon.FOLDER)
                : TreeView.Entry.leaf(p, label).icon(Icon.FILE).hides(COUNT);
        return entry.find(p.toString());   // Ctrl+F looks in the whole path, not only the name the row shows
    }

    @Override
    public String title() {
        return "Trees";
    }

    @Override
    public String blurb() {
        return "Lazy children off the input stages, checkboxes whose cascade is the application's, a context "
                + "menu whose lines each row declares, and Ctrl+F searching a tree by walking it.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Console console = stage.console();

        // The tree asks for each folder's contents the first time it opens, and the listing answers on a thread of its
        // own: listing a directory is I/O, and the frame never touches the disk. Subscribed before the tree is built,
        // because building it is what asks for the roots. Whether a row opens comes from the directory bit, in the
        // answer, so a folder gets a disclosure control without anything under it being listed.
        gui.bus().subscribeAsync(TreeView.CHILDREN_WANTED, wanted -> {
            if (!wanted.tree().equals(gui.address(FILES))) {
                return;
            }
            Path parent = (Path) wanted.parent();
            List<Path> level = parent == null ? List.of(ROOT) : list(parent);
            // What is ticked on the level goes with it, so its rows are drawn marked from their first frame.
            tell(gui, level);
            gui.bus().publish(TreeView.CHILDREN, new TreeView.Children(wanted.tree(), parent,
                    level.stream().map(TreeChapter::entry).toList()));
        }, LISTING);
        TreeView<Path> files = new TreeView<>(gui, FILES);
        files.node().width(Length.FILL).height(Length.FILL);

        // Expanding a folder slides the rows below it down instead of teleporting them. OUT_CUBIC here, where
        // the page crossfade is linear, and the difference is the point: this is a distance being covered, and
        // decelerating into the place it stops is what reads as weight.
        files.motion((progress, done) -> stage.krono().ramp(Dur.ms(200), Ease.OUT_CUBIC, progress, done));

        // A name the tree is too narrow for is shown whole while its row is hovered — in place, over the pane
        // beside the tree, and never the thing under the pointer there. It fades as every reveal in the window
        // does: see Demo, where the window's reveal is given its motion.

        // Ctrl+F with the tree focused opens its find bar; typing walks the real directory tree, fetching as it
        // goes, stopping at the first match and opening only the path to it. Nothing here wires that up. The one
        // thing an application supplies is what "matches" means for its own items — a whole path, say, rather
        // than the name the row happens to show — and it says so on each entry, in entry() above.

        // The pane beside the tree: what a tree on the left of an application usually sits next to, and what a
        // long name on hover is drawn over.
        Node selected = Ui.prose(gui, "Select a row. The tree is narrow on purpose: rest on a name it cuts "
                + "off and the whole of it is drawn over this pane, where it was, until the pointer leaves the row.");
        gui.mailbox(TreeView.SELECTED, files.address(), s -> {
            console.note("selected " + s.item());
            selected.text(String.valueOf(s.item()));
        });
        gui.mailbox(TreeView.ACTIVATED, files.address(), a -> console.good("activated " + a.item()));

        // The row's commands: declared once, with a mark from the set like the tree's own. Which rows offer them
        // is in entry() above, where the listing already knows what each path is: Count only on a folder. Choosing
        // one runs nothing in the tree; it is announced on CHOSEN, and heard here.
        files.action(MenuAction.of(COUNT, Icon.SEARCH, "Count files"))
                .action(MenuAction.of("open", Icon.OPEN, "Open"))
                .action(MenuAction.of("copy-path", Icon.COPY, "Copy path"))
                .action(MenuAction.of("properties", Icon.INFO, "Properties"));
        gui.mailbox(TreeView.CHOSEN, files.address(), chosen -> {
            Path path = (Path) chosen.item();
            // Any line chosen stops a count still running, and Count starts a new one. The count is a walk of the
            // disk, so it runs on a thread of its own, and stops because it looks: between directories it asks
            // whether it is still the latest. Nothing interrupts it.
            long mine = counting.incrementAndGet();
            switch (chosen.action()) {
                case COUNT -> COUNTING.execute(() -> {
                    int found = countFiles(path, mine);
                    String said = counting.get() == mine
                            ? "count: " + found + " files under " + path.getFileName() : null;
                    gui.batch(() -> {
                        if (said != null) {
                            console.good(said);
                        } else {
                            console.refused("count: stopped");
                        }
                    });
                });
                case "open" -> console.say("open: " + path);
                case "copy-path" -> {
                    gui.clipboard().set(String.valueOf(path));   // the clipboard is the window's: this is the view
                    console.good("copied path to the clipboard");
                }
                case "properties" -> console.note("properties: " + path);
                default -> { }
            }
        });

        files.searchable(true);   // Ctrl+F: the find bar, which a tree has only when it asks for one

        // Checkboxes, with a staging area's policy: ticking a folder ticks everything under it, and a folder
        // whose contents are partly ticked reads MIXED. None of that is in the widget — it is all in the methods
        // below, on the listing thread, which is the point. A filter would mark the one item clicked and cascade
        // nothing; a permission tree would propagate upward instead. A tree that picked one of those for
        // everybody would be wrong for the other two and would have to be worked around.
        files.checkable();
        gui.bus().subscribeAsync(TreeView.TOGGLED, toggled -> {
            if (!toggled.tree().equals(files.address())) {
                return;
            }
            Path path = (Path) toggled.item();
            boolean turningOn = state(path) != TreeView.Check.ON;
            List<Path> changed = new ArrayList<>();
            stage(path, turningOn, changed);
            // Everything under it changed, and so may every folder above it: one message, so it shows at once.
            changed.addAll(ancestors(path));
            tell(gui, changed);
            String note = (turningOn ? "staged " : "unstaged ") + path.getFileName()
                    + "  ·  " + staged.size() + " files staged";
            gui.batch(() -> console.note(note));
        }, LISTING);

        Node tools = Ui.strip(gui,
                Ui.controls(gui,
                        Ui.label(gui, "Try:", Length.rem(3)),
                        Ui.button(gui, "Focus the tree", files::focus),
                        Ui.button(gui, "Clear the ticks", () -> {
                            // The ticks are the application's, exactly as the hierarchy is, so a change made from
                            // outside the tree is one the application has to tell it: every file that was staged and
                            // every folder above one, which are all the boxes that were not empty.
                            LISTING.execute(() -> {
                                List<Path> changed = new ArrayList<>(staged);
                                for (Path file : staged) {
                                    changed.addAll(ancestors(file));
                                }
                                staged.clear();
                                tell(gui, changed);
                            });
                            console.note("cleared every tick");
                        }),
                        Ui.button(gui, "Collapse all", () -> {
                            for (Path root : List.of(ROOT)) {
                                files.collapseAll(root);
                            }
                            console.note("collapsed every open folder");
                        })),
                Ui.prose(gui, "Tick a folder and everything under it is staged; tick some of its contents and "
                        + "it reads as a bar rather than a tick, which is a different shape and not merely a "
                        + "smaller one — a checkbox is read at a glance. Space toggles the selected row. "
                        + "Right-click a row for its own menu, and Ctrl+F searches by walking: press Enter "
                        + "repeatedly and the tree opens only the paths it had to open to find each match."));

        return gui.column().width(Length.FILL).height(Length.FILL).gap(Ui.GAP)
                .children(gui.row().width(Length.FILL).height(Length.FILL).gap(Ui.GAP).children(
                        Ui.card(gui, Ui.heading(gui, "This repository"), files.node()).width(Length.rem(15)),
                        Ui.card(gui, Ui.heading(gui, "Selected"), selected).width(Length.grow(1))), tools);
    }

    /** The folder the tree starts at. */
    private static final Path ROOT = Path.of(".").toAbsolutePath().normalize();

    /** What is staged — the model behind the ticks, and the only place any of it lives. The listing thread's. */
    private final Set<Path> staged = new HashSet<>();

    /** Tell the tree what is ticked for each of {@code paths}. */
    private void tell(Gui gui, List<Path> paths) {
        Map<Object, TreeView.Check> marks = new HashMap<>();
        for (Path p : paths) {
            marks.put(p, state(p));
        }
        gui.bus().publish(TreeView.MARKS, new TreeView.Marks(gui.address(FILES), marks));
    }

    /**
     * What {@code path}'s box says. A folder is answered from what is under it rather than from a tick of its own,
     * so the answer stays right when a child is ticked from somewhere else — including by the button below.
     */
    private TreeView.Check state(Path path) {
        if (!Files.isDirectory(path)) {
            return staged.contains(path) ? TreeView.Check.ON : TreeView.Check.OFF;
        }
        boolean any = false;
        boolean all = true;
        for (Path kid : listing(path)) {
            if (state(kid) == TreeView.Check.OFF) {
                all = false;
            } else {
                any = true;
            }
        }
        return !any ? TreeView.Check.OFF : all ? TreeView.Check.ON : TreeView.Check.MIXED;
    }

    /** Stage or unstage {@code path} and everything under it, naming each in {@code changed}. The cascade. */
    private void stage(Path path, boolean on, List<Path> changed) {
        changed.add(path);
        if (!Files.isDirectory(path)) {
            if (on) {
                staged.add(path);
            } else {
                staged.remove(path);
            }
            return;
        }
        for (Path kid : listing(path)) {
            stage(kid, on, changed);
        }
    }

    /** The folders above {@code path}, up to the tree's root. */
    private static List<Path> ancestors(Path path) {
        List<Path> up = new ArrayList<>();
        for (Path p = path.getParent(); p != null && p.startsWith(ROOT); p = p.getParent()) {
            up.add(p);
        }
        return up;
    }

    /**
     * One directory's contents, or nothing.
     *
     * <p>Read again on every ask rather than cached, which is honest for a demo over a live filesystem and is
     * the reason a real staging area would keep its own model of the tree instead: {@code state} is asked for
     * every item the tree is told about, and a folder's answer walks everything under it.
     */
    private static List<Path> listing(Path dir) {
        try (var kids = Files.list(dir)) {
            return kids.toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The id of the Count files line. */
    private static final String COUNT = "count";

    /** Where a count walks the disk: off the frame, and off the listing, which it would hold up. */
    private static final Executor COUNTING = Executors.newSingleThreadExecutor(
            r -> Thread.ofPlatform().daemon().name("tree-count").unstarted(r));

    /** Which count is the latest: bumped by every line chosen, and read by the count between directories. */
    private final AtomicLong counting = new AtomicLong();

    /**
     * Count the ordinary files under {@code dir}, giving up the moment a later line is chosen.
     *
     * <p>The interesting line is the one in the {@code while}: a long piece of application work asks, between
     * the steps it is made of, whether it is still the latest. Nothing interrupts it — it stops because it looked.
     */
    private int countFiles(Path dir, long mine) {
        int found = 0;
        Deque<Path> pending = new ArrayDeque<>();
        pending.push(dir);
        while (counting.get() == mine && !pending.isEmpty()) {
            try (var kids = Files.list(pending.poll())) {
                for (Path kid : (Iterable<Path>) kids::iterator) {
                    if (Files.isDirectory(kid)) {
                        pending.push(kid);
                    } else {
                        found++;
                    }
                }
            } catch (IOException e) {
                // An unreadable directory contributes nothing, exactly as it lists as empty in the tree.
            }
        }
        return found;
    }
}
