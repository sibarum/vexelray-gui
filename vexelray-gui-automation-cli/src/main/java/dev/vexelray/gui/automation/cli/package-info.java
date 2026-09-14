/**
 * The client for the automation protocol: a command line that drives a running application.
 *
 * <p>Designed in {@code docs/reference/automation-cli.md}, which is the companion to {@code docs/reference/automation.md} —
 * the first designs the instrument, this ships the thing that reaches it.
 *
 * <p>{@link dev.vexelray.gui.automation.cli.Ottermate} is the entry point;
 * {@link dev.vexelray.gui.automation.cli.AutomationClient} is the wire and the part worth depending on;
 * {@code Launch} starts an application and learns its port from its own output; {@code Session} runs
 * commands and decides the exit status.
 *
 * <h2>Why it is here and not in the module it talks to</h2>
 *
 * Beside the writer, because a reader that drifts from the format is worse than none — the same reason
 * {@code sibarum.probe.CsvView} lives beside the log it reads. One repo means a protocol change is one commit
 * touching both sides, and this client cannot advertise a verb the server does not implement.
 *
 * <p>Not <em>inside</em> {@code vexelray-gui-automation}, because that module depends on {@code -core} only
 * and speaks nothing but bus message types, which is what makes it a remote peer across a bridge with no
 * change to its own code. A {@code main}, an argv parser and a {@code ProcessBuilder} in there would be
 * weight linked into every application that merely wants to <em>be</em> driven. The server is a bus peer; the
 * client is a tool.
 *
 * <h2>The empty dependency block</h2>
 *
 * A socket, line I/O and {@code ProcessBuilder}. Nothing from {@code -core}, nothing from the framework, no
 * Vulkan — so this runs as a standalone jar with none of the stack on its classpath, which is the difference
 * between a tool and a test fixture, and is what structurally keeps automation out of a shipped application.
 * The one thing it costs is the default port, restated here rather than imported; see
 * {@link dev.vexelray.gui.automation.cli.AutomationClient#DEFAULT_PORT}.
 *
 * <h2>What it does not know</h2>
 *
 * Any verb. Commands are relayed as typed and replies printed as returned, so the vocabulary is whatever the
 * application implements and {@code help} lists. And any application: {@code --launch} runs a command line it
 * was handed. Knowing how each application on the stack is started is somebody else's job, and the empty
 * dependency block is what stops it becoming this one's.
 */
package dev.vexelray.gui.automation.cli;
