package dev.vexelray.gui.demo;

import dev.vexelray.gui.core.Node;

/**
 * One page of the gallery: a title for the rail on the left, a sentence saying what the page is for, and the
 * page itself.
 *
 * <p>The unit of the demo is a chapter rather than a control, which is the whole of the redesign. The old
 * showcase was one screen that grew a button every time the framework grew a feature, and a screen that grows
 * that way ends up demonstrating that the framework has many features and nothing about any of them. A chapter
 * has room to make one point, show the thing making it, and say in prose beside the thing what the framework
 * did and what the application had to say for itself.
 *
 * <p><b>Adding a subsystem to the demo is adding a file.</b> Implement this, add it to {@link Gallery}, and it
 * has a page, a place in the rail, a share of the crossfade, and the activity rail. Nothing else is touched —
 * which is the property the old single-method {@code buildUi} had lost.
 */
public interface Chapter {

    /** The name in the navigation rail. Two words at most: the rail is narrow and it is a list, not prose. */
    String title();

    /** One sentence, shown under the title at the top of the page. What this page is <em>about</em>. */
    String blurb();

    /**
     * Build the page. Called once, on the thread that builds the UI, before the window exists — see
     * {@link Stage#onApp} for the parts that cannot be done until it does.
     */
    Node build(Stage stage);

    /**
     * The page has come on screen, or gone off it. Called with {@code true} for the opening chapter, then for
     * both ends of every change, on the handler executor.
     *
     * <p>For motion that has no end of its own — a looping animation, a ticking readout. Every page is built at
     * startup and lives for the whole run, so a loop started in {@link #build} runs behind every other page too,
     * and a timeline that never goes quiet is one {@code settle} cannot wait out and render-on-demand cannot
     * park on. Started and stopped here, the gallery is quiet whenever what is on screen is.
     */
    default void shown(boolean showing) {
    }
}
