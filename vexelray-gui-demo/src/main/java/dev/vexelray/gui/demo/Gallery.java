package dev.vexelray.gui.demo;

import dev.vexelray.gui.demo.chapter.ColorChapter;
import dev.vexelray.gui.demo.chapter.DragChapter;
import dev.vexelray.gui.demo.chapter.DrawChapter;
import dev.vexelray.gui.demo.chapter.ImageChapter;
import dev.vexelray.gui.demo.chapter.LayoutChapter;
import dev.vexelray.gui.demo.chapter.MotionChapter;
import dev.vexelray.gui.demo.chapter.PlotChapter;
import dev.vexelray.gui.demo.chapter.TableChapter;
import dev.vexelray.gui.demo.chapter.TextChapter;
import dev.vexelray.gui.demo.chapter.TreeChapter;
import dev.vexelray.gui.demo.chapter.TypesetChapter;
import dev.vexelray.gui.demo.chapter.WindowChapter;

import java.util.List;

/**
 * The gallery's table of contents, in the order the rail shows it.
 *
 * <p>Order is an argument, not an accident: text first because a text field is the deepest vertical in the
 * framework and the one most applications reach for first; then trees, then what a tree gained when drags
 * arrived; then the same selection worked out one dimension further, in a table long enough that most of its
 * rows do not exist; then the modules that draw rather than lay out, with colour among them because a picker is
 * mostly a drawing, and images last of those because they are the one kind of content the framework does not
 * make — it takes them; then windows, motion and layout, which
 * are about the frame rather than about anything in it.
 */
public final class Gallery {

    /** Every chapter, built fresh for the {@link Shell} that will hold them. */
    public static List<Chapter> chapters() {
        return List.of(
                new TextChapter(),
                new TreeChapter(),
                new DragChapter(),
                new TableChapter(),
                new DrawChapter(),
                new ColorChapter(),
                new PlotChapter(),
                new ImageChapter(),
                new TypesetChapter(),
                new WindowChapter(),
                new MotionChapter(),
                new LayoutChapter());
    }

    private Gallery() {
    }
}
