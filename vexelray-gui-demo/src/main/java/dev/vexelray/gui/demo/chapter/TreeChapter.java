package dev.vexelray.gui.demo.chapter;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.widget.TreeView;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * A tree over the real filesystem: lazy children, per-row commands, a search that walks, and a subtree that
 * takes the room it needs over time rather than teleporting.
 *
 * <p>Every awkward property of a real tree is here on purpose. The hierarchy is <em>not</em> the widget's — it
 * is read through a {@code Source} — the children of a folder are not known until it opens, listing a directory
 * is I/O and must not happen on an input stage, a deep count takes long enough to be worth stopping, and the
 * user can outrun a search. A tree that only handles the in-memory case is a list with indentation.
 */
public final class TreeChapter implements Chapter {

    @Override
    public String title() {
        return "Trees";
    }

    @Override
    public String blurb() {
        return "Lazy children off the input stages, a context menu built at the moment of the click, a "
                + "cancellable command per row, and Ctrl+F searching a tree by walking it.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Console console = stage.console();

        // hasChildren answers from the directory bit without listing, so a folder gets a disclosure control for
        // free; children() runs its listing on the handler executor the first time the folder opens. The ordered
        // input stages never touch the disk, which is the whole reason those two questions are separate.
        TreeView<Path> files = new TreeView<>(gui, new TreeView.Source<Path>() {

            @Override
            public List<Path> roots() {
                return List.of(Path.of(".").toAbsolutePath().normalize());
            }

            @Override
            public String label(Path p) {
                Path name = p.getFileName();
                return name == null ? p.toString() : name.toString();
            }

            @Override
            public boolean hasChildren(Path p) {
                return Files.isDirectory(p);
            }

            @Override
            public List<Path> children(Path p) {
                try (var kids = Files.list(p)) {
                    return kids.sorted(Comparator
                                    .comparing((Path k) -> !Files.isDirectory(k))
                                    .thenComparing(k -> k.getFileName().toString().toLowerCase()))
                            .toList();
                } catch (IOException e) {
                    return List.of();   // an unreadable directory shows as empty, not as a crash
                }
            }
        });
        files.node().width(Length.FILL).height(Length.FILL);

        // Expanding a folder slides the rows below it down instead of teleporting them. OUT_CUBIC here, where
        // the page crossfade is linear, and the difference is the point: this is a distance being covered, and
        // decelerating into the place it stops is what reads as weight.
        files.motion((progress, done) -> stage.krono().ramp(Dur.ms(200), Ease.OUT_CUBIC, progress, done));

        // Ctrl+F with the tree focused opens its find bar; typing walks the real directory tree, fetching as it
        // goes, stopping at the first match and opening only the path to it. Nothing here wires that up. The one
        // thing an application supplies is what "matches" means for its own items — a whole path, say, rather
        // than the name the row happens to show.
        files.matcher((path, query) -> String.valueOf(path).toLowerCase().contains(query.toLowerCase()));

        files.onSelect(path -> console.note("selected " + path));
        files.onActivate(path -> console.good("activated " + path));

        // A command that belongs to the row rather than to the menu: same mark as the tree's own Expand and
        // Collapse, greyed on a row it does not apply to, and cancellable. It is a walk on purpose — counting a
        // deep directory takes long enough to be worth stopping, and choosing anything else on any row's menu
        // stops it, because the tree runs one action at a time.
        files.action(TreeView.Action.<Path>of("»", "Count files", (path, job) -> {
            int found = countFiles(path, job);
            if (job.live()) {
                console.good("count: " + found + " files under " + path.getFileName());
            } else {
                console.refused("count: stopped");
            }
        }).shownWhen(Files::isDirectory));

        // And the free-form door, for lines that are not commands on the item in that sense. They land after the
        // tree's own, and choosing one still takes the tree over — which an application does not have to know in
        // order to get it.
        files.onContextMenu((path, menu) -> menu
                .item("›", "Open", () -> console.say("open: " + path))
                .item("•", "Copy path", () -> {
                    gui.clipboard().set(String.valueOf(path));
                    console.good("copied path to the clipboard");
                })
                .separator()
                .item("…", "Properties", () -> console.note("properties: " + path)));

        Node tools = Ui.strip(gui,
                Ui.controls(gui,
                        Ui.label(gui, "Try:", Length.rem(3)),
                        Ui.button(gui, "Focus the tree", files::focus),
                        Ui.button(gui, "Collapse all", () -> {
                            for (Path root : List.of(Path.of(".").toAbsolutePath().normalize())) {
                                files.collapseAll(root);
                            }
                            console.note("collapsed every open folder");
                        })),
                Ui.prose(gui, "Right-click a row for its own menu. Ctrl+F with the tree focused searches it, "
                        + "and the search walks — press Enter repeatedly and the tree opens only the paths it "
                        + "had to open to find each match. Expanding a folder slides what is below it."));

        return gui.column().width(Length.FILL).height(Length.FILL).gap(Ui.GAP)
                .children(Ui.card(gui, Ui.heading(gui, "This repository"), files.node()), tools);
    }

    /**
     * Count the ordinary files under {@code dir}, giving up the moment the tree hands its job to something else.
     *
     * <p>The interesting line is the one in the {@code while}: a long piece of application work asks, between
     * the steps it is made of, whether it is still the action the tree is running. Nothing interrupts it — it
     * stops because it looked.
     */
    private static int countFiles(Path dir, TreeView.Job job) {
        int found = 0;
        Deque<Path> pending = new ArrayDeque<>();
        pending.push(dir);
        while (job.live() && !pending.isEmpty()) {
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
