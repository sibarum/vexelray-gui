/**
 * Driving a real application the way a person does, for troubleshooting. See {@code docs/automation.md}.
 *
 * <p>Three classes. {@link dev.vexelray.gui.automation.Cursor} is a pointer with a position that
 * <b>never teleports</b>; {@link dev.vexelray.gui.automation.Automation} is the command surface and the only
 * place a ref becomes a place on screen; {@link dev.vexelray.gui.automation.AutomationServer} puts those
 * commands on a loopback socket.
 *
 * <h2>Switching it on</h2>
 *
 * {@snippet :
 * Automation driver = new Automation(gui, app.controls());
 * AutomationServer server = AutomationServer.start(driver);   // loopback :7654
 * }
 *
 * <p>With {@code -Dprobe=all -Dprobe.format=csv -Dprobe.out=run.csv}, the run writes the correlation log that
 * {@code sibarum.probe.CsvView} reads back. Without it everything still works and records nothing.
 *
 * <h2>The one rule</h2>
 *
 * The pointer travels. Clicking something across the window publishes a path of moves to it and only then the
 * press, so enter, leave and hover fire because they were <b>provoked</b> — not because this package remembered
 * to simulate them. A bug whose only evidence is a hover picked up in passing cannot be reproduced by a driver
 * that teleports, and worse, such a driver reports a clean run.
 *
 * <p>Everything goes out on the ordinary Tactroller topic, so an agent is not a mode the application can behave
 * differently in: there is no automation path to drift out of step with the real one, because there is no other
 * one. Nothing here reads GUI-thread state — the two published read-models are lock-free snapshots — and
 * nothing here speaks anything but bus message types, which is what makes the same code a remote peer once the
 * bridge lands.
 *
 * <h2>What it is not</h2>
 *
 * Not a test harness. {@code vexelray-gui-harness} drives a loop under a test's control; this drives an
 * application under an agent's, and its output is an explanation rather than a verdict. An instrument that is
 * approximate is worse than none, because it produces confident wrong answers about bugs that are already
 * confusing — so where a choice was between convenient and cannot-lie, this package took the second.
 */
package dev.vexelray.gui.automation;
