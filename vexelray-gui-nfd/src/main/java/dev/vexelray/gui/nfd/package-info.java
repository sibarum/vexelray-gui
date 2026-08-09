/**
 * The GUI's one native binding: a Panama (FFM) facade over nativefiledialog-extended
 * (open / save / pick-folder). Invoked on the GUI thread (NFD is modal/main-thread); results are
 * delivered back to a worker through the core event queue.
 */
package dev.vexelray.gui.nfd;
