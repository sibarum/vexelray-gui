package dev.vexelray.gui.iconplugin;

import java.nio.file.Path;

/**
 * One drawing of one icon: where it is, and how to read the shape from it.
 *
 * @param file     the art
 * @param coverage which part of it is the shape
 * @param invert   whether the shape is the other part
 */
record Drawing(Path file, Mask.Coverage coverage, boolean invert) {
}
