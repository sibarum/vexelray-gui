# Discovery: finding running applications, and what each one offers

Status: **Landed 2026-10-17**, all four steps of §7. Where the build differs from the proposal, the text below says
what landed, and §9's questions are answered. [automation.md](../reference/automation.md) §5, *Finding a running
application*, is the reference. This plan adds two things to the automation socket
([automation.md](../reference/automation.md)):

1. **A file per running application**, in one known folder per user. A client can list what is running and which
   port each application listens on without having launched it.
2. **A `hello` verb.** It reports the protocol version and what the application offers in this build and in this
   run.

It belongs with [flight-recorder.md](flight-recorder.md), which turns the socket into an instrument panel, and it
serves the [Inspector](../../../vexelray-inspector/docs/roadmap.md) as much as agents.

---

## 1. What is missing

- **Only whoever launched an application can find its port.** On binding, the server prints
  `automation: localhost:<port>`, and `ottermate --launch` reads that line. An application started from a shortcut,
  from MainFrame, by another tool or by an earlier session prints its port into nobody's view. To reach it, you
  either guess 7654 or relaunch it.
- **With two applications, 7654 is ambiguous.** `--automation=0` exists so that two runs can coexist, and nothing
  then says which port is which.
- **A client cannot tell what a server offers.** Verbs are arriving across many steps (flight-recorder.md §9), and a
  domain verb answers `err not available yet` only once you try it. The Inspector should be able to grey out a
  panel before asking. Its command menu should be able to say why an item is disabled. A client built against a
  newer protocol should be able to refuse, saying why, rather than fail halfway through a script.

## 2. The folder

Each running application writes one file into this folder:

```
~/.vexelray/automation/
```

The folder is `AppHome.of("vexelray").folder("automation")`. That follows `AppHome`'s rule of one layout on every
platform, a dot-directory under the user's home, rather than `AppData` on Windows and `XDG_RUNTIME_DIR` on Linux. It
is redirected the way `AppHome` already allows, with `-Dvexelray.home=<dir>` on the application. A client is a
different process and cannot see that property, so both sides also honour the environment variable
`VEXELRAY_HOME` (Q1). A test rig sets the variable once and every process it starts agrees on the folder.

The folder is per user, the same as the socket's reach: any process running as the user can already connect to a
loopback port. The files reveal ports, not access, and they contain no credentials.

## 3. The file

### Name and format

The file is named `<app>-<pid>.auto` (for example `calculator-18244.auto`). It contains `key=value` lines, the same
shape as a socket reply's first line. `ottermate` has no dependencies by design
([automation-cli.md](../reference/automation-cli.md)), so the format has to be one it can split by hand. JSON would
need a parser.

This is a real file, written by the gui demo:

```
protocol=1.0
app=demo
title=demo
pid=26356
started=2026-10-17T22:54:06.804Z
port=61184
cwd=C:\Users\User\Documents\GitHub\vexelray-gui\vexelray-gui-demo
command=C:\JDK\graalvm-jdk-25.0.3+9.1\bin\java.exe
build=vexelray-gui-automation -
```

**As landed, two lines of the proposal are not in the file:**

- **`edition` is left out.** Only a debug edition carries the automation module, so every file would have said
  `debug`.
- **`verbs` moved to `help`.** The server cannot ask for the verb list before a window exists, and the file is
  written before that. `hello` points at `help`.

The file holds **only facts that are fixed for the life of the process**. What changes while the application runs
(windows, the hand, whether the recorder is on) comes from `hello` (§4). A file that had to be rewritten would be
a file that could be caught half-written.

- `protocol` is the socket protocol version, explained in §4.
- `cwd` is where relative paths in commands resolve. The Inspector's command menu opens its MainFrame terminal there
  (roadmap, *The command menu*).
- `app` is `@VexelApp`'s name for an application on the framework. An application that starts the server itself and
  names nothing is named after its main class, so `dev.vexelray.gui.demo.Demo` is `demo`.
- `command` is the process's command line, escaped onto one line, so a person can tell two runs of the same
  application apart. **On Windows it is only the executable.** The JDK does not read another process's arguments
  there (`ProcessHandle.Info.commandLine()` is empty), so the file falls back to `command()`.
- `build` carries the automation module's version when its jar has one in its manifest, and `-` otherwise, as in a
  checkout.
- When the Inspector's link lands (flight-recorder.md §6, step 5b), its socket is a further line here
  (`stream=uds:<path>` or `stream=tcp:<port>`).

### Lifecycle

1. **Written after the socket binds and before the stdout line is printed.** It is written to a temporary name and
   then renamed into place, so a reader never sees half a file. Anyone who has seen `automation: localhost:<port>`
   can therefore rely on the file existing.
2. **Deleted when the server stops**, from the same shutdown path that closes the socket, and by a shutdown hook
   when the process exits without closing it.
3. **Left behind by a crash.** A crash is detected, not prevented (below). Checked for real: the demo was killed
   with `Stop-Process -Force`, its two files stayed, and the next `ottermate list` deleted them and did not list it.

Release editions carry no automation module, so they write nothing.

### Is it live?

The application holds an OS file lock for its whole life. The operating system releases the lock when the process
dies, by any means, crashes included. A reader that manages to take the lock knows the writer is gone. It may
then delete the file, and it reports nothing from it.

- **Lock a sidecar, not the file being read.** Windows locks are mandatory, so a locked byte range cannot be read.
  The lock goes on a companion file, `<app>-<pid>.lock`, created before the info file and deleted after it. An info
  file with no lock beside it is therefore stale too.
- **Not process IDs.** Windows reuses process IDs quickly, so "is pid 18244 alive?" can answer yes for a different
  process. The lock answers the question actually being asked.
- **A reader in the same JVM** gets `OverlappingFileLockException` from `tryLock`, which means the lock is held. That
  happens when the Inspector reads its own file. The reader treats it as live.

## 4. `hello`

`hello` is an ordinary verb, and it reads.

**The server still does not speak first.** If it greeted each connection, today's `ottermate` would read the greeting
as the reply to its first command and every script would break. A client that wants the version asks for it.

`hello` replies with the process's facts, plus what is true right now. This is a real reply from the gui demo:

```
$ ottermate --app demo hello
ok protocol=1.0 app=demo pid=26356 port=61184 connections=1/4 hand=free this=1 file=C:\Users\User\.vexelray\automation\demo-26356.auto
windows main
probe   on
recorder on rows=6772 capacity_rows=262144 lost_total=0 frame_stamp=yes
domain  loop=yes recorder=yes stream=text bus=missing(the atchung topic registry and tap) kron=missing(the kronometer snapshot) gpu=missing(presenter statistics from GuiApp) ledger=missing(the public probe Ledger snapshot) threads=missing(ProcessCpu in vexelray-os) heap=missing(the native-image heap and GC spike (flight-recorder.md Q5)) meter=missing(the meter verbs (meter.md)) diagnose=missing(the domain verbs it reads)
see     help
```

The first line is the server's: the protocol, the process, how many connections are open out of the cap, who holds
the hand, which connection is asking (`this`), and the discovery file. The other lines are the host's
(`Commands.describe`). The recorder line reads the ring's counters only, so `hello` never scans the ring.

### Versions

The protocol version is `major.minor`, and it describes the socket's verbs and reply shapes.

- **Minor:** bumped by every addition, whether a verb, a field or a form. A client may use anything up to the minor
  the server reports, and should check the minor before using something newer.
- **Major:** bumped by anything that can break an existing client, such as a renamed verb, a removed field, or a
  reply shape that changed meaning. A client refuses a major it does not know, with
  `err protocol 2.x: this ottermate speaks 1.x`. It never guesses.
- **Starting point:** `hello` landing makes the protocol 1.0. Everything that exists at that point is 1.0, and each
  later step of flight-recorder.md §9 bumps the minor.

### Resources

The `domain` line lists each domain as `yes`, `no` (not in this build), or `missing(<what>)`, where the parenthesis
names the upstream hook that would supply it. That is the same text a domain verb's `err not available yet` gives,
and the same text the Inspector shows in a disabled menu item. All three read from one table in the server, so they
cannot drift apart. As landed, the table is `dev.vexelray.gui.automation.Domains`. It lists the six domain verbs, plus
`meter` and `diagnose`, and each of those verbs already answers from it:

```
$ ottermate --app demo bus
err not available yet: bus needs the atchung topic registry and tap
```

An entry leaves the table in the change that lands its verb.

`hello` says what exists. `help --schema` (flight-recorder.md §5) describes each verb's arguments and fields. A
client reads `hello` once on connecting, and reads the schema only when it needs the detail.

## 5. Clients

### ottermate

- **`ottermate list`** reads the folder, deletes stale files, and prints one line per live application: app, pid,
  port, protocol, started, cwd. It is a client-side verb, since no server is involved.
- **`--app <name>` and `--pid <n>`** choose an application from the folder. `--app` must match exactly one live
  application, or it errors and lists the candidates with the `--pid` that would pick each. This is otter.md §2's
  rule 1, a target resolves to one thing or fails, applied to applications.
- **With no `--port`, `--app` or `--pid`:**
  - one live application is used without asking;
  - several make it error and list them;
  - with none in the folder, ottermate falls back to 7654, as today.

  Fixed scripts with a known port keep working. When it uses the one live application, ottermate names it on
  stderr (`ottermate: driving calculator-vexel-demo (pid 25984, port 61183)`), unless `--quiet`.
- **ottermate checks the version before sending anything**, and refuses an unknown major. As landed, it reads the
  version from the discovery file rather than sending `hello`. That costs no round trip, and it adds no `hello` row to
  every recording, since each one-off `ottermate` is a connection of its own. A `--port` with no file in the folder
  is taken on trust, as before.

### The Inspector

- **An attach picker** watches the folder, so applications appear and disappear live. Choosing one reads its file,
  connects, and sends `hello`.
- **`hello`'s domain line** decides which panels are live and which are greyed out with their reason.
- **`cwd`** gives the command menu its working directory. `hello` replaces the `process` verb the roadmap first
  proposed for this.

### Agents

An agent runs `ottermate list` instead of launching an application only to learn its port, and attaches to what a
person already has open. This pairs with MainFrame's planned stack environment
([stack-env.md](../../../mainframe/docs/stack-env.md)), which sets `VEXELRAY_AUTOMATION=0` in agents' shells. Every
application started there then gets a free port, and the folder says which one.

## 6. Where the code goes

- **`vexelray-gui-automation`**, in `AutomationServer` or a small class beside it: writing the file, the sidecar
  lock, removal on stop, and the `hello` verb. Applications that start the server directly, without the framework,
  are covered too. The server takes the application's name and title as optional arguments.
- **`vexelray-framework-automation`** (`AutomationStarter`) passes `@VexelApp`'s `name` and `title`.
- **`vexelray-gui-automation-cli`** (ottermate): `list`, `--app` and `--pid`, the default resolution, the version
  check, and its own copy of the folder rule and the liveness check. It has no dependencies, so it cannot share the
  server's code, and the shared contract is this document. As landed, each side's test checks the layout from its
  own side: the server's test reads what it wrote, and the client's test reads files written in that layout. The two
  were checked against each other by the live run behind §3 and §4's transcripts.
- **Test runs** move the folder under `target` (`-Dvexelray.home` in both modules' surefire configuration), so a
  test never writes into, or clears, the real `~/.vexelray/automation`.

## 7. Order of work

1. **The file**, with the sidecar lock and removal on stop, and a test that a crashed writer's file is detected as
   stale (kill a child JVM, then read).
2. **`hello`**, with the protocol version set to 1.0, and the domain table the `err not available yet` replies
   already use.
3. **ottermate:** `list`, `--app`/`--pid`, default resolution, and the version check. Document them in
   [automation.md](../reference/automation.md) §5 and the ottermate guide, with real transcripts.
4. **The framework passes `name` and `title`.**

This can land at any point after flight-recorder.md step 4 (many observers). It does not depend on the recorder,
although `hello`'s recorder line fills in once the recorder exists.

## 8. What it deliberately does not do

- **No registry service and no broadcast.** A folder of files needs nothing running, survives any client crashing,
  and can be inspected with `ls`.
- **No credentials in the file, and no non-loopback ports.** Discovery changes who can *find* the socket. Who can
  *reach* it stays exactly as before.
- **No greeting on connect** (§4).
- **No dynamic facts in the file** (§3).

## 9. Open questions

- **Q1. `VEXELRAY_HOME` beside `vexelray.home`.** `AppHome` reads only the system property. Honouring an environment
  variable as well is new for `AppHome`. It could be done in `AppHome` for every application, or only for this
  folder. **Decided: only for this folder**, until a second use appears. ottermate also honours `-Dvexelray.home`, so
  the property moves both sides when one JVM runs both, as in a test.
- **Q2. A sidecar `.lock` file, or a byte range past the end of the info file.** **Decided: the sidecar.** It is
  simpler to explain, and it held on Windows: a live lock read as held from another process, and a killed process's
  lock could be taken at once. Not yet run on Linux.
- **Q3. Should `ottermate list` also show applications that are installed but not running?** The installer's records
  (`Apps.registryDir()`) know what is installed. That would make `list` a launcher too. Proposed: no, `list` shows
  what is running.
