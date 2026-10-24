# Driving the app for real: `vexelray-gui-automation`

Status: **A0–A6 landed** (see §8). A module that lets an out-of-process agent run the actual application — real window, real
Vulkan, real frame loop — and interact with it the way a person does, while emitting one correlation log that
explains what happened.

This is a **troubleshooting instrument**, not a test harness. That distinction sets every constraint below: a
test may be approximate as long as it is green or red, but an instrument that is approximate is worse than no
instrument, because it produces confident wrong answers about bugs that are already confusing. Where a choice
is between "convenient" and "cannot lie", this document picks the second one every time.

---

## 1. Why this is nearly free

Three of the four primitives already exist, built for other reasons:

| Need | What supplies it | Status |
|---|---|---|
| **Act** — inject input | Publishing `InputEvent` on the Tactroller topic *is* the input path (architecture.md §13.3). `HeadlessGui` already drives real widgets this way. | Exists |
| **Read** — inspect state | `LayoutSnapshot`: immutable, **versioned**, keyed by stable node id, transport-serializable. | Exists |
| **Settle** — know when a mutation landed | `LayoutSnapshot.version` increments per published frame. | Exists |
| **See** — pixels | The presented frame, read back from the image the presenter put on the screen (`WindowPresenter.readFrame`; DXGI on Windows). A presenter that cannot read back falls back to an offscreen drawing of the tree, which shows nothing of how the window presents it. | Exists (see §8 A0) |
| **Understand** — role, name, structure, state | — | **Missing (§3)** |

Because injection is the ordinary input path, an agent is not a special mode the application can behave
differently in. There is no automation branch to drift out of sync with the real one. This is the
"all input through Tactroller" rule paying a dividend it wasn't designed for.

---

## 2. Fidelity: the pointer has a position, and it moves

**Rule: the automation cursor is stateful, and it never teleports.**

Clicking a node at `(412, 88)` from a cursor resting at `(90, 640)` does not publish a click at `(412, 88)`. It
publishes a *path* of `MouseMove` events from where the cursor actually is to where the target is, stepped and
paced like a hand, and only then the press and release. Enter/leave/hover transitions fire because they are
genuinely provoked, not because the module remembered to simulate them.

This is not decoration. It is the requirement:

> We have had bugs whose only evidence was a hover event fired while crossing an unrelated widget. A tool that
> teleports the pointer cannot produce that evidence, and worse, it produces a clean log that says nothing
> happened.

Consequences, all deliberate:

- **The module owns a virtual cursor.** Position is state, persisted across commands, reported by `where`.
- **Hover is recorded, not just motion.** `state.hover` / `state.normal` rows name the node whose interaction
  state changed, which is what says a crossing *mattered*; `pointer.move` alone never does.
- **A path can have side effects.** Crossing an open menu, arming a hover-delay timer, dragging a splitter under
  a pressed button. This is correct — a person's pointer does the same. The path is deterministic and every
  step is logged, so a spurious hover is *visible in the CSV* rather than an invisible confound.
- **Paths are reproducible.** Same start, same target, same step count and pacing, same events. No jitter, no
  randomness: an instrument that is not reproducible cannot be used to bisect anything.
- **Pacing is real time, not frames.** Hover delays, double-click windows and repeat thresholds are wall-clock
  contracts; stepping them by frame count would silently pass cases that a person fails.
- **`settle` between phases, not within a path.** Motion is continuous, and the frame loop is allowed to drop
  intermediate positions exactly as it does for a real mouse. Settling mid-path would fabricate a fidelity the
  real device never has.

The same rule generalises: `type` publishes per-character key down/up and `CharTyped` through the ordinary path
(never a text-set shortcut, and never auto-repeat for single characters — that is command-keys-only by design);
`scroll` publishes wheel deltas; `drag` is press, path, release with the button held.

---

## 3. The missing half: `SemanticSnapshot`

`NodeLayout` carries geometry and text metrics. It carries no **role**, no **name**, no **parent/child**, no
**enabled / focused / checked**. An agent needs "the enabled button labelled Save, inside the toolbar"; today
the snapshot can only say "a rect at 412,88".

Filling that in by reaching into `RetainedNode` would be wrong. That is GUI-thread state, not a published
read-model, and touching it from outside re-opens precisely the seam resolved decision 7 closed — it is how you
get behaviour that differs on screen and off it, silently, for as long as nobody looks.

So: **a second published read-model**, `SemanticSnapshot`, keyed by the *same node ids* and carrying the *same*
`version`, so it joins to `LayoutSnapshot` by id and by frame. Pure data, transport-serializable, published from
the compute phase by the same single writer, guarded by `vexelray-gui-architecture` like everything else.

This is not automation scaffolding that the framework then has to carry. It is the missing half of **C4**: a
remote peer with no atlas needs role and structure to draw and to be navigable, and M5's thin client will need
it. Building the instrument forces the framework in the direction it was already going.

---

## 4. The one output file

One run produces **one artifact**: `correlations.csv`. Not a debug log plus a profile log plus an interaction
log — those are *views*, and they are derived from this file, never written beside it.

**Why one writer, not one directory.** Parallel writers are the thing that makes correlation lie: separate
buffers flush at different times, so the order on disk is not the order that happened. A single append-only
writer makes the file's own order the truth. Everything else follows from that.

### Schema

**The writer is `sibarum.probe.Probe`, at `-Dprobe.format=csv`.** Not a new one: Probe already had the single
serialised sink, the per-line flush, and lanes that are exactly this file's `stream` column — and a second
writer in this repo would have recreated the very interleaving problem this section exists to prevent. Turning
it on is `-Dprobe=all -Dprobe.format=csv -Dprobe.out=run.csv`; `csv` implies tracing, because a correlation log
holding only the spans that happened to be slow is not one.

```
seq,t_mono_ns,t_wall,thread,lane,kind,detail
```

| Column | What it is for |
|---|---|
| `seq` | Row number from the single writer. Gapless — **a gap is detectable loss**, which is the whole reason to have it. Not the sort key; it breaks ties when two threads land in the same nanosecond. |
| `t_mono_ns` | Monotonic nanos since the process started. **The sort key**, and the only one — a wall clock can step sideways and sort two events into an order that never happened. |
| `t_wall` | ISO-8601 UTC. For people, and for joining against anything outside this process. |
| `thread` | Who. Essential and easy to forget: the same `kind` on the GUI thread and on a worker are different events. |
| `lane` | Where: `frame`, `layout`, `input`, `bus`, `state`, `draw`, `gpu`, `anim`, `time`, `shader`, `app`. What a derived view filters on. |
| `kind` | What: `frame.present`, `loop.park`, `layout.publish`, a span's name, `open`/`close`. |
| `detail` | The producer's own, escaped, **always last** so a naive split keeps working when it contains commas. |

**Frame, version and node ride in `detail`, not in columns of their own.** Probe is the whole stack's seam and
a message bus has no frames, so bending its schema to one consumer would be the wrong trade. The convention is
`#<frame>`, `v<version>`, `node=<id>`, and a derived view extracts them — `awk -F, '$6=="layout.publish"'` and
so on.

**And this is weaker than a column would have been**, which is worth stating rather than glossing. A version
appears only on the row that published it, so no other row carries the version it happened at: "did my click
land before or after that layout?" is answered by finding the nearest preceding `layout.publish` in the
time-sorted file. That is inference from ordering, not a join, and it is only as good as the ordering — which
is why the monotonic clock is the sort key and why `seq` exists to prove nothing went missing in between. If
this ever proves too weak, the fix is for producers to put the version in `detail` on the rows that matter, not
for Probe to grow a column that means nothing to a message bus.

### Reading it: sort by time, hunt for gaps

The file is read by sorting on `t_mono_ns` alone and looking for **long stretches with no frame in them**. That
is the highest-value query the format has to serve, and it imposes one requirement the schema does not get for
free:

- **`frame.present` is emitted every frame, unconditionally** — including frames where nothing happened. A
  heartbeat you only get when there is news cannot show you the silence.
- **`loop.park` is emitted whenever the loop parks**, carrying the budget it parked for (`forever` for an
  unfocused window). Without this, a gap is ambiguous: the loop deliberately parks indefinitely when nobody is
  looking, and a 30-second nap would read exactly like a 30-second hang.

With both, the rule is mechanical: **a gap preceded by a `loop.park` that covers it is expected; a gap that is
not is a stall**, and the last row before it is the suspect. This is why the frame loop must be instrumented in
A2 even though it is not something the agent drives.

`frame` and `version` stay in the schema, but as *causality* columns rather than ordering ones — once the
timestamp sort has shown you where to look, they answer "did this land before or after that layout", which
timestamps across threads cannot.

### Format rules

- **One event per physical line. Newlines in `detail` are escaped, never raw.** The entire value of this format
  is that `sort`, `grep -n` and `awk` operate on it; one raw stack trace destroys that for every row after it.
- Writes are serialised on the one sink. Producers hand it an event; they never touch the writer.
- **Flushed per line, not buffered.** An instrument used to debug crashes must have already written the last
  line before the crash. This costs throughput and is worth it.
- Emitting must never be able to fail the app: a full disk degrades to a dropped record and a `watchdog` line,
  and `seq` makes the drop visible.

### Derived views

Derived views come from `sibarum.probe.CsvView` — beside the writer, so it cannot drift from the format.
`csvview run.csv --gaps [ms]` is the one that matters: it applies the rule above mechanically, judging every
stretch without a frame against the park that precedes it and naming the last row before the silence. The rest
is projection — `--lane`, `--kind`, `--thread`, `--grep`, `--tail` — generated on demand from the one file and
so consistent with it by construction. No parallel writers, ever.

It reports missing `seq` numbers before anything else and unconditionally: every other answer it gives is
computed from the rows that are present, and a reader who is not told about the absent ones is being invited to
conclude something from a hole.

---

## 5. Module shape

`vexelray-gui-automation`, depending on `-core` only, **speaking nothing but bus message types**. That is the
invariant that makes the module worth having rather than a pile of helpers: it is a bus peer that happens to be
in the same JVM today. When M1–M3 land it becomes a remote peer across an `ElektroBridge` with no change to its
own code — which makes this a more demanding **M4** than the planned one, and one that gets used daily.

### Switching it on

Two lines at the application edge, and a flag on the command line:

```java
Automation driver = new Automation(gui, app.controls());   // controls: for `shot`
AutomationServer server = AutomationServer.start(driver);  // loopback :7654, daemon thread
```

```
-Dprobe=all -Dprobe.format=csv -Dprobe.out=run.csv
```

`AutomationServer` binds **loopback only**, deliberately and not configurably: this hands anyone who can reach
it full control of the application's input, so it is a debugging instrument and not a service. Pass `0` as the
port to be given a free one and ask `server.port()`.

### Two capabilities, switched while it runs

A socket offers **remote control**, **diagnostics**, or both (`Capability`). Each verb surface answers which one a
line needs (`Commands.needs`), so the server holds no list:

| Capability | Verbs |
|---|---|
| `control` | the hand and what it is aimed through: `move`, `click`, `drag`, `touch`, `tap`, `type`, `key`, `keydown`, `keyup`, `go`, …, `hide`/`show`, `zoom`/`dpi`/`resize`, and the view reads `tree`, `find`, `where`, `size`, `windows`, `window`, `shot`, `settle`, `await`, `hand` |
| `diagnostics` | what the process recorded about itself: `recorder`, `rows`, `gaps`, `frames`, `why`, `stream`, `log`, `mark`, `bus graph`, the planned domains (`bus`, `kron`, …), and the Inspector link |
| both | `hello` and `help`, which say what this socket is |

A line a socket does not serve is refused, and nothing of it runs. The refusal says where the other one is:
`err recorder is diagnostics, and this socket serves control; diagnostics is on localhost:61185`, or `… is off`.

`Sockets` owns the process's sockets and switches either capability **while the application runs**:

- **A port names a socket.** `enable(c, port)` with the port the other capability already listens on joins that
  socket, and it serves both. Any other port, or `0` for a free one, binds a socket of its own.
- **Off narrows rather than cuts.** `disable(c)` takes a shared socket down to the other capability, and nobody
  connected to it is dropped. A socket that served only `c` closes.
- **A busy port changes nothing.** The new socket is bound before the old one is let go, so what was listening
  still is.
- **Attachments follow their capability.** `attach(DIAGNOSTICS, server -> InspectorLink.start(server, windows))`
  starts the link whenever diagnostics comes on, beside whichever socket serves it, and closes it when it goes off.

The launch settings are `--automation=<port>` (both on one socket, as before), `--automation.control=<port>` and
`--automation.diagnostics=<port>` (each alone; the same port twice is one socket). `Sockets.enableRequested` reads
all three, and the framework's `Driver` and the gui demo both use it.

**Sockets open at launch, not by hand.** There used to be switches, and they could not work for the Inspector: the
probe it reads is fixed when the process starts (below), so diagnostics switched on later reached a run with nothing
to stream. So the **automation button** in the title bar, beside the key bindings (§7), restarts instead
(`vexelray-framework`'s `Relaunch`):

- **A normal run** wears the plug. Its menu has one item, *Restart in debug mode*: the application closes through
  its own close gate (so unsaved work is asked about, and cancelling cancels the restart), and starts again with
  `--automation=0`, the probe armed from frame 0 with the `bus` lane, and `--automation.inspector`, which opens the
  Inspector attached to the new process (`--attach=pid:<n>`) as soon as its link listens. One click, connected.
- **A debug run** (diagnostics open at launch) wears a bug between the two lamps. Its menu lists both sockets and
  their ports, *Open the Inspector*, and *Restart without debugging*.

The new process waits for the old one to exit (`--restart.after=<pid>`) before it opens anything, so the settings
and window placement the old one saves on the way out are the new one's. A run with remote control alone (an agent
or a macro driving) is not a debug run, and still offers the restart.

### In release builds too, and never out of sight

Applications ship with the sockets, off. Macros and agents drive an application with the same hand a person has
(Maistro's tools are the user's tools), so leaving the module out of a release would leave them out too. What keeps
that honest is that **whatever turned a socket on, the title bar says so**.

A run is started in one of three ways:

- **Available and off**, the default: nothing listens until a person restarts it in debug mode from the plug.
- **Not available**: `--automation.available=none`, `control` or `diagnostics` (or `-D`, or
  `VEXELRAY_AUTOMATION_AVAILABLE` in the environment), read flag, then property, then environment. A capability that
  is not available cannot be switched on by anything: `Sockets.enable` throws, a launch asking for it opens nothing
  and says so, the debug run's menu says *not available in this run*, and with neither available there is no plug.
  A value that is not one of those words offers nothing, since whoever wrote it meant to shut something.
- **On at launch**, for an agent that means to: `--automation=0` and the others above, or `VEXELRAY_AUTOMATION`.

#### Lamps

A debug run's button carries a lamp per capability, remote control's (red, `Role.DANGER`) left of the bug and
diagnostics' (blue) right (`AutomationButton`). `Sockets.state(c)` is what they read:

| `Sockets.State` | Lamp | Means |
|---|---|---|
| `OFF` | none | closed, and nothing has connected this session (never opened, or opened and closed unused) |
| `LISTENING` | ring | open, nothing connected yet |
| `CONNECTED` | filled ring | open, something connected now |
| `WAS_CONNECTED` | ring with a grey dot | open; something was connected, and none is now |
| `CLOSED_AFTER_USE` | grey dot | closed, after something had connected this session |
| `UNAVAILABLE` | none, and no switch | this run does not offer it |

Shape carries the state as well as colour: the ring is *open*, the centre is *connected now, earlier, or never*. A
connection counts for every capability its socket serves while it is open, so diagnostics joining a socket someone
is on lights both. An Inspector on the link counts as a diagnostics connection (`AutomationServer.linked`). Every
connection opening or closing tells `Sockets`' watchers, which is how the button redraws
(`WindowInstrument.Live`). The tooltip says it in words, with the port and how many are connected.

**The probe does not switch.** `Probe.ON` is fixed when the class loads, so that the JIT folds every guard out of a
run that is not profiling. A launch that asks for either socket runs in automation mode, which arms the probe. That
is why a running application becomes a debug run by restarting rather than by switching a socket on.

### Finding a running application

Every server writes a file saying where it listens, and removes it when it stops
([discovery.md](../plans/discovery.md)). So a client can find an application it did not launch, and tell two
applications on free ports apart.

- **Where.** One file per process, `<app>-<pid>.auto`, in `~/.vexelray/automation/`. `-Dvexelray.home=<dir>` on the
  application moves the folder, as it moves any `AppHome`. The environment variable `VEXELRAY_HOME` moves it for
  every process at once, and wins.
- **What.** `key=value` lines: `protocol`, `app`, `title`, `pid`, `started`, `port`, `cwd`, `command` (only the
  executable, on Windows), `build`; from `Sockets`, `control` and `diagnostics` (a port, or `off`), with `port` the
  control socket's, or the diagnostics one's while control is off. The process facts hold for the whole run. The
  endpoints change only when one is switched, and each switch rewrites the file to a temporary name and moves it
  into place. The file is written before `start` returns, so it exists by the time the host prints its
  `automation: localhost:<port>` line. It goes when the last socket closes.
- **The name.** `AutomationServer.start(commands, port, app, title)` names the application. The framework's driver
  passes `@VexelApp`'s name and title. The other `start` overloads name it after the main class (`demo`, for the gui
  demo).
- **Live or stale.** The process holds an OS lock on `<app>-<pid>.lock` for as long as it runs. A reader that can
  take the lock knows the process has gone, by a crash or otherwise, and deletes both files.
- **`hello`** gives the protocol version (`major.minor`, `AutomationServer.PROTOCOL`), this process and its
  connections, then one line each for the windows, the probe, the recorder and the domains. A domain that is not built
  yet shows as `missing(<what it waits for>)`. Its verb answers `err not available yet: <verb> needs <what>` from the
  same table (`Domains`). `bus.graph=yes` or `no` says whether the program declared its bus graph (*The declared bus
  graph* below). It sits beside `bus=missing(...)`, because the graph landed before the registry and tap that `bus`
  waits for. The transcript below is from before it.

`ottermate list` reads the folder, and `--app` or `--pid` choose from it. With none of `--port`, `--app`, `--pid` or
`--launch`, ottermate drives the one application running. If several are running, it refuses and lists them. If none
is, it uses 7654. These are real runs, with the gui demo and calculator-vexel-demo both started with a free port:

```
$ ottermate list
ok apps=2 folder=C:\Users\User\.vexelray\automation
app                    pid    port   protocol  started                   cwd
demo                   26356  61184  1.0       2026-10-17T22:54:06.804Z  C:\Users\User\Documents\GitHub\vexelray-gui\vexelray-gui-demo
calculator-vexel-demo  25984  61183  1.0       2026-10-17T22:54:07.181Z  C:\Users\User\Documents\GitHub\calculator-vexel-demo

$ ottermate size
ottermate: 2 applications are running; choose one with --app or --pid:
  ottermate --pid 26356    demo (pid 26356, port 61184), started 2026-10-17T22:54:06.804Z
  ottermate --pid 25984    calculator-vexel-demo (pid 25984, port 61183), started 2026-10-17T22:54:07.181Z

$ ottermate --app demo hello
ok protocol=1.0 app=demo pid=26356 port=61184 connections=1/4 hand=free this=1 file=C:\Users\User\.vexelray\automation\demo-26356.auto
windows main
probe   on
recorder on rows=6772 capacity_rows=262144 lost_total=0 frame_stamp=yes
domain  loop=yes recorder=yes stream=text bus=missing(the atchung topic registry and tap) kron=missing(the kronometer snapshot) gpu=missing(presenter statistics from GuiApp) ledger=missing(the public probe Ledger snapshot) threads=missing(ProcessCpu in vexelray-os) heap=missing(the native-image heap and GC spike (flight-recorder.md Q5)) meter=missing(the meter verbs (meter.md)) diagnose=missing(the domain verbs it reads)
see     help
```

The demo was then killed with `Stop-Process -Force`, so it could not remove its file. The next `list` cleared the
file and showed only the calculator, and with one application left, a bare command went to it:

```
$ ottermate size
ottermate: driving calculator-vexel-demo (pid 25984, port 61183)
ok 700x600 zoom=1.25 dpi=1 em=20px
```

ottermate reads the protocol version from the file before it sends anything, and refuses a major it does not speak.

### Many observers, one hand

The socket takes **several connections at once — four by default**, each on its own `vexelray-automation-<n>`
daemon thread. `-Dautomation.connections=<n>` raises or lowers the cap for any host, because
`AutomationServer.start(commands, port)` reads it; `start(commands, port, n)` sets it in code. A connection over
the cap is answered, line by line, with `err the automation socket is full: 4 connections are open
(-Dautomation.connections=<n> raises the cap)` and closed when it goes quiet — answered rather than dropped,
because a client that has already written its first command would otherwise see a reset and not a reason.

Every connection is an **observer**: any verb that reads works on any of them, at any time, including while
another connection's `click` or `await` is in flight. Only the connection holding **the hand** may run a verb
that drives: `move`, `click`, `rightclick`, `drag`, `down`, `up`, `scroll`, `touch`, `tap`, `type`, `key`,
`keydown`, `keyup`, `go`, `hide`, `show`, and `zoom`, `dpi` and `resize` when given a value. `go` is on the list although it is not input: it moves focus and scrolls,
which is where the holder's next `type` lands and what its next `click` aims through. Each verb surface answers
for its own verbs (`Commands.drives`), so the server holds no list.

- **Taking it.** The first driving verb from a connection takes the hand if it is free. `hand take` does so
  explicitly; `hand` reports the holder, when it took the hand, and which connection is asking.
- **Refusing.** A driving verb from anyone else is refused, and nothing of it runs:
  `err the hand is held by connection 2 (since +4.1s)`. The time is on the server's clock, so every connection
  reads the same `+s` for the same event.
- **Releasing.** `hand release`, or the connection closing — `quit`, a clean close, or a script that died. A
  crashed script cannot leave the pointer locked. Letting go also **releases any button a `down` left held**,
  where the pointer is: otherwise the next holder's first `move` would carry on a drag nobody is performing.

This keeps what the old single-connection rule protected — two agents cannot interleave pointer paths into a
gesture neither asked for — and lifts what it cost: something can watch while something else drives.

What each connection has of its own, and what is shared:

| State | Decision | Why |
|---|---|---|
| The chosen `window` | **Per connection.** Each starts on the main window. | An Inspector looking at the settings window must not move the window an agent's next `click` lands in. `Windows.session()` gives each connection its own choice over shared drivers. |
| The pointer (`Cursor`) | **Shared**, one per window. | It *is* the hand. `where` from any connection reads the one pointer; only the holder moves it. |
| `log` levels | **Process-wide**, any connection may change them, last change wins. | Logging is the process's, not a connection's, and turning it up changes what is recorded, not what the application does — so it needs no hand. The change is a probe-marked command like any other, so the log says who. |
| `zoom`, `dpi`, `resize` | Per window, and only the holder sets them. | They change what the holder's next target resolves to. Reading them is free. |

Every command is a probe mark (`agent.command`) whose detail begins with the connection's number — `#2 click
save` — so a recording of two clients reads apart. The hand's changes are marks too (`agent.hand`: `#2 take`,
`#2 release (disconnect)`), as are connections opening and closing (`agent.connection`).

The verbs are also callable directly — `driver.command("click 41")`, or `driver.cursor()` for the pointer — so
a test can drive the same surface with no socket in the way.

### The flight recorder

With the probe on, the application keeps its most recent probe rows in a ring
(`vexelray-gui-recorder`, [flight-recorder.md](../plans/flight-recorder.md) §3), and six verbs read it:
`recorder`, `rows`, `gaps`, `frames`, `why`, and `stream`, which follows it live (*Streaming the ring*, below).
They all read, so any connection may use them and none needs the hand. None needs a window either: the ring
belongs to the process, like the probe that feeds it.

- **Switching it on.** `Windows` starts the recorder when the probe is on. A driven run already turns the probe
  on (`Logging.probeLanes()`), in CSV and so with tracing. Asking for lanes yourself switches that default off: use
  `-Dprobe=all -Dprobe.format=csv` (or `-Dprobe.trace=true`), because an untraced probe hands the ring nothing but
  its `SLOW` rows, and `recorder` then shows a ring that hardly grows. `-Drecorder=<rows>` sizes the ring (rounded up
  to a power of two, default 2^18, about 32 MB of heap, measured in flight-recorder.md Q2). With the probe off,
  every ring verb answers `err the recorder is off: start with -Dprobe=...`.
- **The frame stamp.** Each row carries the loop's frame in progress (`LoopCounters.frame()`), which the host
  hands over as the third argument of `new Windows(app::windows, timeline, app)`. A host that hands over no
  counters still records rows, `gaps` and `rows` still work, and `frames` and `why` say what is missing.
- **Every reply states its span and its loss.** `over=+a..+b` gives the ring span read, on the probe's clock
  (seconds since the probe started, the same clock as `t_mono_ns`). `lost=` counts the `seq` numbers missing
  inside that span. Overwriting the oldest row is not loss: the span shrinks instead. Every list ends its first
  line with `more=N`.
- **Answers are records.** Each verb computes a plain record (`RecorderStatus`, `RowList`, `GapList`,
  `FrameList`, `FrameExplanation`), and `Replies` renders it. The text is a pure function of the record, so
  another transport can carry the same record and mean the same thing.

A real run against the gui demo (`-Dprobe=all -Dprobe.format=csv`), with a `mark` and a click on the rail opening a panel:

```
$ ottermate recorder
ok rows=10687 capacity_rows=262144 over=+1.412s..+8.966s lost=0 lost_total=0 rate_rows_per_s=4 frames=1..97 frame_stamp=yes ring_mb=12.0 sink_failures=0 lanes=bus,state,time,anim,input,frame,layout,draw,gpu,shader,app,trace

$ ottermate frames worst 5
ok frames=5 order=worst held=1..97 over=+1.412s..+9.154s lost=0 more=92
frame  at       took_us  stage     stage_us  woke  drawn  presented  park
1      +1.413s  96760    present   15380     -     yes    yes        0ms
2      +1.511s  31960    emit      21400     wake  yes    yes        0ms
85     +6.552s  20930    gpu_wait  11773     wake  yes    yes        0ms
88     +6.613s  20750    gpu_wait  16706     wake  yes    yes        0ms
89     +6.634s  20640    gpu_wait  16637     wake  yes    yes        0ms

$ ottermate why 88
ok frame=88 at=+6.613s took_us=20750 drawn=yes presented=yes over=+6.594s..+6.634s lost=0
woke    wake: paint motion on Gui@2133c8f8 (main; 1 wake mark)
stage   before=780 update=294 draw=19580 other=97   (us; together they make up took_us)
inside  input=174 clock=491 dispatch=22 drain=52 layout=0 resolve=0 emit=1570 gpu_wait=16706 submit=134 present=609   (us)
layout  0 passes
bus     drained=2 published=vexelray.gui.mutations x2 (shred-0)
park    0ms waited_us=-
also    slow_spans=0
unknown budget_us (the loop marks no frame budget, so over_us cannot be given); gc (the probe has no gc rows); layout cause (flex layout rows do not say what made the tree dirty)
see     rows --frame 88 --lane gpu

$ ottermate rows --frame 88 --lane gpu
ok rows=5 over=+1.412s..+15.571s lost=0 more=0
seq,t_mono_ns,t_wall,thread,lane,kind,detail,frame
34963,6630598300,2026-10-17T22:00:26.074572200Z,main,gpu,dxgi wait,16.61ms,88
34964,6630740000,2026-10-17T22:00:26.074572200Z,main,gpu,wait fence,96.2us,88
34968,6632871900,2026-10-17T22:00:26.076138Z,main,gpu,queue submit,133.9us,88
34969,6633513700,2026-10-17T22:00:26.077466Z,main,gpu,dxgi copy+present,608.8us,88
34970,6633535400,2026-10-17T22:00:26.077466Z,main,gpu,present frame,19.58ms,88

$ ottermate gaps
ok gaps=16 stalls=0 threshold_ms=100 over=+1.412s..+9.343s lost=0 more=0
frame  at       gap_ms  verdict   park     last
4      +1.561s  201.0   expected  200ms    main frame frame 1.67ms
5      +1.762s  167.3   expected  200ms    main frame frame 11.36ms
...
74     +4.709s  215.8   expected  forever  main frame frame 2.02ms
75     +4.924s  1483.2  expected  forever  main frame frame 3.34ms
```

Read in order: the slowest frames after startup are the panel's slide, and `why 88` puts 16.7 ms of its 20.8 ms
in `gpu_wait`. Following the `see` line shows all of it is `dxgi wait`, the presenter blocking on the swap
chain's frame-latency waitable. So those frames were held by presentation, not by layout or by drawing. (The
`gaps` table is cut here; the run printed all 16 rows.)

How each verb reads the rows the probe already writes:

- **`frames`, `why`.** A frame is the set of rows stamped with its number. The stages are the loop thread's span
  names: `before` (`before frame`, the input pump and clock), `update` (`tree update`), `draw` (`present frame` or
  `offscreen frame`) and `other`, the rest of the `frame` span. These four add up to `took_us`. `inside` names
  the pieces within those, which need not add up. A stage whose lane recorded nothing in the ring prints `-`,
  never 0. The first frame stamp in the ring is never listed, because the ring may hold only its tail.
- **What woke a frame** is read from the frame before. A `wake` mark posted during frame F-1 is owed to frame
  F. Without one: key edges in F's own input pump give `input`. A park that ran its whole budget gives
  `timeout`, and a park on 0 ms gives `unparked`. Anything else ended the park without a row, for example an OS
  message such as pointer motion, so `woke` says `-` and gives that reason.
- **`gaps`** is `sibarum.probe.CsvView.gapsOf`, the rule `csvview --gaps` prints, applied to the ring. A gap
  is a stall when nothing said the loop would sleep, or when the part the park leaves uncovered is itself at
  least the threshold. Frame to frame is the park plus the next frame's work, so a 200 ms idle park shows
  200.6 ms gaps, and those are `expected`.
- **The `unknown` line** names what `why` would need that no row records today: the loop's frame budget, GC,
  and what made layout dirty.

`ottermate` hands options it does not know to the command once the command has begun, so
`ottermate rows --frame 88 --lane gpu`, the line `why` prints, runs as written.

#### Streaming the ring

`stream [lanes|all] [from <seq>] [for <ms>]` writes the rows as the recorder takes them, on the connection that
asked. It is the text form of the stream ([flight-recorder.md](../plans/flight-recorder.md) §6), for agents,
scripts and files. The Inspector will use the typed elektro-Q link instead.

The reply has this shape:

- **The first line** is `ok stream`, followed by the lanes, the `seq` asked for (`from_seq`), the `seq` it really
  started at (`start_seq`), and the oldest and newest `seq` the ring held when it began.
- **The header row** comes next, then the rows: correlation-log lines with the frame stamp last, exactly as `rows`
  prints them.
- **`#` lines** sit between the rows. A reader of the correlation log, `csvview` and the Inspector's `--replay`
  included, skips them as not-a-row.
  - `# lost a..b rows=n` says those rows never reached this stream. It appears where the gap happened.
  - `# idle quiet_ms=n` appears after 2 s with nothing to send.
- **`# end`** is the last line before the terminator. It gives the rows delivered, their span and loss, the `seq`
  range, and why the stream stopped.

How it behaves:

- **Where it starts.** With no `from`, it starts with the next row published. With `from <seq>`, it starts at that
  row if the ring still holds it, and otherwise at the oldest row there is. Rows overwritten before the stream
  began are the ring's span shrinking, not loss, so they show as `start_seq` later than `from_seq` rather than as a
  `# lost` line.
- **The connection is the sender.** The stream runs on the connection's own thread through one cursor into the
  ring (`dev.vexelray.gui.recorder.Follower`), copying and writing what has arrived every 10 ms. A slow reader slows
  only itself. One that falls a whole ring behind gets a `# lost` line for what it missed. Nothing runs on the
  probe's thread. The other connections carry on meanwhile, and the stream needs no hand.
- **Ending.** With `for <ms>` (at most 600000), it ends when that has passed and ignores input until then. Without
  `for`, it ends when any line arrives, and that line is then answered as the next command, so `quit` ends both the
  stream and the session. A reader that goes away is noticed at the next write, which the idle line guarantees
  within 2 s even when the application is parked.
- **Loss means only the `# lost` lines.** A stream of some lanes leaves the other lanes' rows out, so its `seq`
  column skips numbers by design. `csvview` reads such a file but reports those skips as missing rows. The `# end`
  line's `lost=`, and the `# lost` lines, are the stream's real loss. `stream all` writes a file whose `seq` is
  gapless unless rows were really lost.

A real run against the gui demo (started as for the transcripts above, with `-Dautomation=7655`). One connection
streams for 1.5 s, while another clicks the panel button and settles. The middle of the stream is cut here; the
run printed 247 rows:

```
$ ottermate --port 7655 stream frame,gpu for 1500
ok stream lanes=frame,gpu from_seq=- start_seq=- oldest_seq=24747 head_seq=31651
seq,t_mono_ns,t_wall,thread,lane,kind,detail,frame
31660,10913348400,2026-10-17T22:37:34.576867100Z,vexelray-automation-5,frame,wake,injected input on Gui@2133c8f8,6
31661,10913375500,2026-10-17T22:37:34.576867100Z,vexelray-automation-5,frame,wake.post,nudging the OS message queue,6
31663,10913524800,2026-10-17T22:37:34.576867100Z,main,frame,wait for events,8.96s,6
31677,10915037600,2026-10-17T22:37:34.577867100Z,main,frame,before frame,1.41ms,7
...
33298,11290365100,2026-10-17T22:37:34.954206600Z,main,frame,frame.present,#29,29
33299,11290368100,2026-10-17T22:37:34.954206600Z,main,frame,loop.park,forever,29
# end rows=247 over=+10.913s..+11.290s lost=0 seq=31660..33299 why=for elapsed
```

The first rows are the click arriving: the other connection's thread posts a wake for injected input, and the
loop leaves a park of 8.96 s. Asking again with `from` replays the same stretch from the ring:

```
$ ottermate --port 7655 stream gpu from 31660 for 100
ok stream lanes=gpu from_seq=31660 start_seq=31660 oldest_seq=24747 head_seq=33308
seq,t_mono_ns,t_wall,thread,lane,kind,detail,frame
31920,10997220900,2026-10-17T22:37:34.660288700Z,main,gpu,dxgi wait,16.6us,12
31921,10997296900,2026-10-17T22:37:34.660288700Z,main,gpu,wait fence,63.0us,12
...
# end rows=85 over=+10.997s..+11.279s lost=0 seq=31920..33271 why=for elapsed
```

#### The Inspector link

The Inspector does not read the text stream. It reads the same ring as typed intrabuf records over elektro-Q, from
the module `vexelray-gui-inspector-link` ([flight-recorder.md](../plans/flight-recorder.md) §6). The automation
socket and ottermate do not depend on that module.

- **Where.** The host starts it next to the server (`InspectorLink.start(server, windows)`). The framework's driver
  and the gui demo both do, whenever the socket is on. It listens on a Unix domain socket beside the discovery
  file, `<app>-<pid>.sock`, and the discovery file says so in a `stream=uds:<path>` line. It uses a loopback TCP
  port (`stream=tcp:<port>`) when the path is too long for `AF_UNIX`. `-Dautomation.link=off` leaves it out.
- **What.**
  - `StreamOpen(lanes, fromSeq, snapEveryMs)` opens the stream, and `StreamOpened` answers it with the same facts as
    `ok stream`, or an `err`.
  - `RowBatch` follows every 10 ms. It carries rows with `detail` raw, and the `seq` range lost before them.
  - `Snap` follows on its schedule, with the loop's counters and the ring's.
  - `StreamClose` ends the stream. Leaving ends it too.
  - `Query(line)` asks any read verb in the socket's words. `QueryReply` carries the socket's text. A verb that
    drives is refused, because the hand stays the socket's. The Inspector asks `bus graph` this way once, on
    attaching.
- **How it behaves.** Each Inspector gets its own sender thread and cursor into the ring, so a slow one slows only
  itself. A send blocked for 2 s drops that Inspector.
- **The client.** The Inspector's end is `LinkClient`: `connect(discoveryFile)`, then `open`, `query` and
  `closeStream`.

#### The declared bus graph

The program's message graph, as its declarations state it: the components and other publishers, each `@Subscribe`
mailbox with its topic, payload type, overflow policy and capacity, and each topic a type declares it publishes. The
format, version 1, is fixed by the Inspector's contract
([vexelray-inspector docs/bus-circuit.md](../../../vexelray-inspector/docs/bus-circuit.md) §1). The Inspector draws
the bus as a circuit from it.

- **Where it comes from.** vexelray-framework's processor writes it as a `BUS_GRAPH` constant, and the generated
  wiring hands it to `BusGraph.declare(String)` at startup, the way the build identity reaches `Discovery.build`.
  `declare` refuses, at once, any text whose first line is not `# vexelray bus graph 1`. An application not built by
  the processor declares none.
- **How to read it.** `bus graph` is a diagnostics read. It answers `ok bus graph lines=<n>`, then the `n` lines as
  declared, header first, or `err no bus graph: <why>` when none was declared. The link answers the same verb as a
  `Query`. `BusGraph.text()` gives it in-process.

The reply's shape, for the contract's sample graph. This is not a transcript: no application declares a graph until
the framework's processor emits one.

```
$ ottermate --app witness bus graph
ok bus graph lines=9
# vexelray bus graph 1
component  dev.example.Worker      lane=worker
...
publishes  dev.example.Worker       topic=witness.ping
```

The rest of `bus` (the topic registry and the tap) is still `missing`, and `bus` alone or with any other word
answers so.

### The protocol

One command per line in; one reply out, terminated by a line containing only `.`. Replies begin `ok` or `err`,
so a script can branch on the first two characters without parsing anything.

```bash
printf 'find Save\nclick save\nsettle\nshot after.png\n' | ottermate --script -
```

The client is `vexelray-gui-automation-cli` ([automation-cli.md](automation-cli.md)); it is worth using
rather than `nc` even where `nc` exists, because it carries the verdict in its exit status — an `err` reply
fails the run. Anything that can open a loopback socket and read to a `.` speaks this, which is the point of
a terminator rather than a length, but a caller that reimplements the frame also reimplements deciding
whether the run succeeded.

| Command | Meaning |
|---|---|
| `tree` | The joined semantic + layout snapshot as an indented outline: ref, role, name, `@landmark`, box, plus `clipped to […]` / `offscreen` / `hidden` / `focused`. |
| `find <text>` | Refs whose role, name or landmark contains the text, case-insensitively. |
| `where` | Where the pointer is now. |
| `go <landmark>` | The framework's own navigation: reveal whatever conceals it, scroll it into view, focus it. |
| `move <ref\|landmark\|x,y>` | Travel there. Hover happens on the way, and is recorded. |
| `click` / `rightclick <target>` | Travel there, then press and release. |
| `drag <target> <target>` | Press, travel, release. |
| `down <target>` / `up [target]` | A drag held open: travel there and press; then `move` and `shot` as often as wanted; then let go, where the pointer is or after travelling. `drag` is over before anything could photograph it, and the middle of a gesture — a card half-carried, a gap half-open — is often what needs looking at. |
| `scroll <target> <dx> <dy>` | Wheel notches there. |
| `touch <target> <n> <dx> <dy> [scale] [turn] [ms]` | `n` fingers on the touchpad, the pointer there: they land in a ring, then over `ms` (200) their centre moves `dx`, `dy` mm, their spread grows by `scale` and they turn `turn` degrees, and they lift. A swipe is `touch @x 3 -30 0`; a pinch, `touch @x 2 0 0 2`. Published as the touchpad's driver path publishes contacts, so the recognizer, the dispatcher's claims and the widget are all exercised. |
| `tap <target> <n> [times]` | `n` fingers touch down together and lift without moving, `times` over (once by default; 2 is a double tap). |
| `type <text>` | One code point at a time, through the ordinary character channel. |
| `key <NAME>`, `key <MOD+...+NAME>` | Press and release a named `Key`; or a chord (`key CTRL+SHIFT+W`): each modifier's left-hand key down in the order written, the key down and up, the modifiers up in reverse — the dispatcher derives what is held from the modifier keys it has seen go down, so this is how a chord reaches a claim. Modifiers `CONTROL`\|`CTRL`, `SHIFT`, `ALT`, `SUPER`\|`META`\|`WIN`\|`CMD`, any case. A modifier `keydown` already holds is left held. |
| `keydown <[MOD+...+]NAME>` / `keyup [MOD+...+NAME]` | Hold keys across commands: `keydown SHIFT`, then `click`, `key` or `shot` as often as wanted, then `keyup SHIFT`. `keyup` releases in reverse; alone, it releases everything held. Refuses a key already held (down) or not held (up) before touching anything. The reply ends `held: <keys>`. **Everything held is released when the hand is let go** — `quit`, a dropped connection, `hand release` — last pressed first. A held non-modifier key auto-repeats as a real one does (the dispatcher's own repeat). |
| `settle` | Wait for the loop to catch up with everything published so far, and any animation to finish. |
| `await <landmark> <text>` | Wait until that landmark's name contains the text — how an application's own readiness is waited on. |
| `await <landmark>` | Wait until a node with that landmark exists (hidden counts), and answer `ok <ref> "<name>" @<landmark>`. The first wait of a scene, where `settle` straight after launch can answer before the tree is built. Same 30 s bound and refusal as the form with text. |
| `shot [path]` | PNG of the window this driver is attached to: its tree presented, then that frame read back from the presenter's image, so it is the screen's pixels, not a second drawing. Not the desktop compositor's: the window border and anything the compositor does are outside it. Clears the target, waits for the file, `err` if none arrives. |
| `record <path.mkv>` / `record stop` / `record` | A video of the window this driver is attached to, from its presenter's frames as they are shown: the camera's *Record video to file…*, told where instead of asking (`WindowRecording`). `record <path>` answers once frames flow, with the canvas every frame is drawn on (the monitor's usable area); `record stop` answers once the file is whole, with its size; `record` alone says what is recording. One recording per process, so a person can stop from the camera what a script started. `err` when no module records video (`vexelray-codec-record` is the one that does). |
| `size` | The window's pixels, zoom, dpi and em, in one line. |
| `zoom [factor]`, `dpi [factor]` | Read, or set and wait for layout. Answer with the value the window *became*, and say when a clamp changed it. |
| `resize <w>x<h>` | Size the drawable area — pixels, or em with a unit (`46emx30em`), which an em resolves at the zoom and density in force. Answers with the size reached. |
| `hide`, `show` | Take this window off the screen, as the application's `hide()` does; bring a hidden window back. Answer `ok hidden` / `ok shown`, or `err` when there is no window. |
| `windows`, `window <name\|n>` | List the application's open windows; choose the one the other verbs act on (default: the main one). Each window has its own driver. |
| `log [logger] [level]` | Read how much is logged and where the file is; or set a level for both sinks, or for one logger and everything beneath it. To the running process only. A driven run is already the loudest mode (`TRACE` file, probe on) — see `atchung/docs/logging.md`. |
| `mark <note>` | Write a row into the correlation log saying *why*. Changes nothing else. |
| `hand`, `hand take`, `hand release` | Who holds the hand and which connection this is; take it if free; let it go. See *Many observers, one hand* above. |
| `hello` | The protocol version, this process, what this socket `serves=`, its connections and the hand; then the windows, the probe, the recorder and each domain, present or `missing(...)`. See *Finding a running application* above. Answered on every socket. |
| `bus graph` | The program's declared message graph, `ok bus graph lines=<n>` and then its lines, or `err no bus graph: <why>`. See *The declared bus graph* above. |
| `bus`, `kron`, `gpu`, `ledger`, `threads`, `heap`, `meter`, `diagnose` | Not built yet: each answers `err not available yet: <verb> needs <what>`, in the words `hello`'s `domain` line uses. |
| `recorder` | The flight recorder: rows held, the span they cover, loss, row rate, frame range, lanes on. See *The flight recorder* below. |
| `rows [--lane L] [--kind K] [--thread T] [--grep S] [--frame N] [n]` | The last `n` (50) matching rows from the ring, as correlation-log lines with the frame stamp last. |
| `gaps [ms]` | Silences between frames of `ms` (100) or more, each judged against the park before it — `csvview --gaps` over the ring. |
| `frames [last N \| worst N \| since <mark>]` | One line per frame: start, time taken, slowest stage, what woke it, drawn, presented, park. Default `last 20`. |
| `why <frame>` | One frame explained: what woke it, time per stage, layout passes, bus traffic, the park after; ends with a `see` line. |
| `stream [lanes\|all] [from <seq>] [for <ms>]` | The rows as the recorder takes them, written as they happen: correlation-log lines with the frame stamp, `# lost` and `# idle` lines between, `# end` last. Without `for`, until any line arrives. See *Streaming the ring* above. |
| `help`, `quit` | The list; and end the session. |

**Two ways to name a node, and the difference is durability.** A **ref** is a node id: minted per run, resolved
to a box at the moment of acting — never a coordinate read a frame earlier, which is the classic automation
flake and cannot be retried, because the click has already landed. A **landmark** (`Gui.landmark(name, node)`)
is the name that still means something tomorrow, and unlike an id it can be *navigated* to, so a concealed
target is revealed rather than refused. `tree` and `find` show it as `@name`. Anything written down — a script,
a recorded session — should say the landmark.

**A target is resolved to where it is, not to where it was put** — the centre of `NodeLayout.visibleRect`, its
box less every clip its ancestors impose. The two differ exactly where automation is hardest: a virtualised
list keeps rows it has scrolled past, lays each out where its index says, and a row above the window has a rect
whose every pixel belongs to the sticky header drawn over it. Aiming at that rect sorted a table this driver
had been told to select a row of, and answered `ok` — the one outcome an instrument may never produce.

So a target that cannot be reached where it is gets the same answer as a concealed one, because there is only
one answer worth giving: **reveal it, or refuse and say why**. A landmark is navigated to (`go` scrolls it into
view) and then rechecked once the frame carrying that scroll has been published; a node with no landmark is
refused, naming which kind of unreachable it was — `is not visible`, `has no laid-out box`, `is scrolled out of
view`. A row half out of the window is neither: it is clicked on the half that is there.

**What `settle` does and does not promise.** It waits until the loop owes nothing (`Gui.frameOwed`): the wake,
the frame that took it until that frame has published, and every handler the frame dispatched until it has
returned, so a click's own handler, which posts its mutation after the frame that dispatched it, is waited for
too. It is woken by the debt being paid, not by a layout commit, because a frame that changed nothing publishes
nothing (2 s, then `err did not settle`). Then, when the host handed over a `Timeline`, it waits for the
timeline to go quiet (10 s; a repeating cue is named rather than waited out), and for the loop once more. So it
is exact about the frame loop, its handlers and the clock, and never a sleep. It cannot see work the
application started for itself, on `Gui.async` or a thread of its own: that is not the loop's debt. An agent
needing "the application has finished thinking" has to wait on something the application names.

**`await` is how it waits on that**, and deliberately without an application-specific hook. An accessible name
is published for every node and a landmark is already the durable way to say *which* node, so an application
declares readiness by writing it down where it is legible — a landmark whose name says what state it is in —
and `await <landmark> <text>` blocks on the layout commit signal until it does. The rejected alternative was a
readiness predicate supplied by the host, which would have made each application's synchronisation a private
arrangement with `Automation` instead of a fact in the read-model that `tree` and `find` can already see.

**`shot` waits for the picture, and can fail.** `WindowControls.capture` is asynchronous and cannot report, so
a `shot` that replied on return said `ok` for a minimized window, for a host whose capture sink does nothing,
and for a write that failed to `System.err` where no agent on a socket can see it — §6's exact prohibition, on
the one command whose entire value is that its answer can be looked at. It now deletes the target first (a path
photographed earlier in the session already holds a complete PNG, and "wait for it to appear" would be
satisfied instantly by the previous run's picture), then waits for the file, which is sound rather than a sleep
because `GuiWindow.capture` writes beside the path and moves it into place for precisely this consumer.

Main-thread discipline: `shot` posts to `GuiApp`'s existing `tasks` queue via `WindowControls`. Reads post
nothing — snapshots are lock-free by design — and input goes on the bus, so no command touches GUI-thread state.

**And putting input on the bus is not enough on its own**, which is the one place the "nearly free" story in §1
has a cost. A host loop that parks when nothing is looking at the window has three reasons to wake, and injected
input is none of them: it is not a mutation, no clock has run out, and no OS event arrived carrying it. So the
publish succeeds, every command answers `ok`, and nothing happens — no click lands, no key is typed, the tree
never changes — until any real event arrives and it all comes right at once. That presents as *the application
ignoring the clicks*, which is a bug hunt in the wrong program entirely. `Gui.wakeForInput()` is the fix and it
is deliberately named for its caller's situation rather than made general: an application never needs it,
because everything an application does to a tree goes through a mutation, and a mutation already wakes the loop.

---

## 6. What makes it trustworthy

An instrument used to troubleshoot other bugs must not have interesting failure modes of its own.

1. **No second input path.** Injection is the production path. There is no mode to drift.
2. **No polling for readiness.** `settle` is exact, off a counter the framework already publishes. Automation
   flakiness is overwhelmingly "slept long enough?" — that failure mode is structurally absent here.
3. **No synthesized consequences.** Hover, enter, leave, focus and repeat are all *provoked*. The module
   publishes only what a device backend publishes: motion, buttons, keys, wheel.
4. **Deterministic paths.** Same inputs, same event sequence, every run.
5. **Detectable loss.** `seq` gaps.
6. **Self-describing runs.** The CSV records the agent's own commands in the same order as their effects, so a
   run can be read back without the transcript that produced it.

---

## 7. Window instruments: the same tool, driven by hand

An agent is not the only thing that wants to screenshot a window or record what happened in it. A person
troubleshooting wants exactly that, on the window in front of them, without a socket or a CLI. So the same
capabilities surface as **instruments in the title bar** — and where they live is a design decision, not a
placement one.

### Chrome belongs to the framework

A title bar is window chrome, and chrome belongs to whoever owns the window. An application contributes
**identity** — its title, and an icon only as identity. It never contributes **controls** there; a control an
application needs belongs in the application's own UI.

This is what makes a utility free everywhere. The moment one app puts its own button in the caption, the strip
is app-addressable, and no framework instrument can rely on the space existing or on its meaning being the same
from one window to the next. "Screenshot this window" is only free if the framework owns the place it lives.

### Four zones

`TitleBar` is `[identity | caption | buttons]` today. It becomes:

```
[ identity | caption (flex, DRAG) | instruments | window controls ]
```

- **identity** — application-supplied, declarative, no handlers of the application's. The one handler there is the
  framework's: `TitleBar.onMark`, which `vexelray-framework` uses to open the application's About window from the
  mark. The mark is `WindowRegion.INTERACTIVE` only while it has one, and a script reaches it as the landmark
  `titlebar-mark`.
- **caption** — the flexing drag region, still `WindowRegion.DRAG`.
- **instruments** — framework-owned, each `WindowRegion.INTERACTIVE`, with the gaps between them left draggable
  exactly as the caption buttons already manage.
- **window controls** — minimize, maximize, close. Rightmost, and maximize keeps `MAXIMIZE_BUTTON` so the
  platform's own snap affordance still works.

### Registered, never enumerated

An instrument is a value — mark, tooltip, role, action — contributed to a registry that `TitleBar` renders
without inspecting. The framework registers screenshot and macro-record; `TitleBar` branches on nothing, so the
automation module can add its own without `TitleBar` knowing it exists. It does: `WindowInstrument.automation` and
`WindowInstrument.debugging` (role, and landmark, `instrument-automation`) open the framework's restart menu, after
the key bindings. An instrument with a `menu` opens it at the click instead of running an action, and the menu's
items are landmarks by what they say (`menu.restart-in-debug-mode`), so a script can choose one. The menu is
handed the window's own `WindowControls`, as an action is, so what it does is to the window it was opened in.

**The camera records, where something can.** `SaveScreenshot.instrument()` is the plain screenshot button, unless a
`WindowRecorder` is on the class path (`vexelray-codec-record` puts one there). Then it is `WindowInstrument.camera`,
the same role, opening a menu: `menu.take-screenshot`, and `menu.record-video-to-file` (a save dialog, then
`WindowRecording.start`) or, while a recording runs in any window, `menu.stop-recording` first. The lens is filled
while recording, a shape and not a colour, in every window's bar, through `WindowRecording.LIVE`. Frames reach the
recorder through `WindowControls.tap`, the presenter's `FrameTap`, so a recording never causes a frame and never makes
one wait. The recording ends with its window: `GuiWindow.close` finishes the file once the presenter has handed over
its last frames. A
contributor that arrives after the framework's tools appends to `TitleBar.instruments()` and states the whole strip
again, so nobody replaces anybody else's button. The same shape `WindowControls` already
has: the bar commands an interface and knows no implementations.

### Three constraints

- **Never revealed on hover.** Instruments are always present and reserve their width. Where the bar is too
  narrow they collapse into an overflow instrument that is itself always present. Nothing in this strip may
  appear, move or grow under the pointer.
- **Marks, not glyphs.** The primary atlas carries no camera or record symbol, so instrument marks are `Picture`
  geometry, which also stays crisp under zoom. They are drawn on `CaptionGrid` — the same unit, the same single
  pixel-snapped stroke, the same 16 × 12 unit envelope as the window controls, which `TitleBar` now draws the same
  way — so the strip reads as one hand. Every button paints through one path: secondary ink at rest, full ink on
  a rounded plate under the pointer, the danger colour on close.
- **Windows an instrument opens are readable too.** They take Ctrl+= / Ctrl+- / Ctrl+0 (`ZoomKeys`), and open at
  the zoom of the window they were opened from, at the size that zoom implies.
- **Opt-in per window.** A shipped application must not carry a macro-record button it never asked for. The
  framework supplies a default set; a `WindowSpec` may take fewer, or none. Free to *enable* — not present
  unconditionally.

### Why this shapes A0

The screenshot instrument is A0's first consumer, and it settles A0's scope: capture is **per-window**, not
main-window-only. Building it for the CLI alone would have produced a method on the application that then had
to be widened. Each window owning its own bar means each instrument captures its own window.

**That took one seam more than this section originally claimed, and the gap was silent.** `GuiWindow.capture`
existed and was simply unreachable for a popup: a bar built its own `WindowControls` from the `NativeWindow`
it was handed at `onCreated`, and a native window cannot photograph itself — so `WindowControls.of(window)`
bound a capture sink that did nothing, and the host wired a real one for the main window only. Every other
window had a screenshot button that neither worked nor complained, which is the exact failure this document
spends §6 arguing against. The controls are now handed down *by the host* through `WindowSpec.onControls`:
only `GuiApp` owns a window's render bundle, so only `GuiApp` can make working controls, and nothing else is
allowed to try.

Macro-record lands later for the same reason it belongs here at all: recording a macro *is* the input stream
§4's log already records, and playing one back *is* §2's path synthesis. The person and the agent drive the one
instrument, which is the strongest available guarantee that what the agent synthesizes matches what a hand does.

## 8. Milestones

- **A1 — `SemanticSnapshot`.** The read-model, published from the compute phase, joined by id and version;
  architecture-guard rule added. **The bulk of the work, and owed to C4 regardless.** *(Landed — see
  reference/semantic-read-model.md. Widget and demo roles landed with it.)*
- **A0 — Live capture.** Capture on the running app, **per window** (§7), posted to the `tasks` queue so it
  happens on the main thread inside the frame loop. Keep or delete the static per its remaining CI value.
  *(Prereq for `shot` and for the screenshot instrument.)*
- **A2 — The writer.** *(Landed: `probe.format=csv` in atchung — `seq`, two clocks, one row per physical line.)*
  Core, input and frame-loop events instrumented — including the unconditional `frame.present` heartbeat and
  `loop.park`, without which a stall is indistinguishable from an idle window.
- **A3 — Virtual cursor + paths.** *(Landed: `vexelray-gui-automation`, `Cursor` — stateful, stepped at 125Hz, real-time paced, `move`/`click`/`drag`/`scroll`.)*
- **A4 — Protocol + CLI.** *(Protocol landed: `Automation` + `AutomationServer`, a loopback line protocol. `go` reuses the framework's own navigation, so a concealed target is revealed rather than refused. `csvview` in atchung-probe.)*
  **The CLI half landed later**, in `vexelray-gui-automation-cli` — `ottermate`, an empty-dependency-block client
  that attaches to a running application or launches one and reads the port off its own output, and whose
  exit status is the verdict. It was missing for long enough that the usage example above was the format's
  documentation rather than a runnable command, and an agent needing a picture rebuilt the protocol in
  PowerShell from these notes. Designed in [automation-cli.md](automation-cli.md), together with the
  retirement of per-application `--capture` that it unblocks. That retirement has happened: the calculator's on
  2026-09-10, ahead of the `zoom` and `resize` verbs of its §5 (its §6 records the cost), and
  `vexelray-framework`'s template on 2026-09-30.
- **A6 — Clipping, found by driving a real table.** *(Landed: `NodeLayout.visibleRect` + `Clip` in core;
  reveal-or-refuse in `Automation.target`; `table` / `row` / `columnheader` / `columngrip` / `rowgroup` roles on
  `Table` and `list` / `listitem` on `ListView`.)* The driver clicked a ref for a row a hundred thousand-row
  table had scrolled past, hit the header drawn over it, sorted the table, and said `ok`. Two gaps, one session:
  a virtualised table was an anonymous box tree with no way to say "row" or "the Name column", and the geometry
  read-model published where a node was put with nothing to say none of it was on screen.
- **A5 — Prove it.** *(Landed: `HoverPathDiagnosisTest`.)* A hover-path bug diagnosed from the CSV alone.
  The bug is **planted**, and that is stated rather than glossed over: the historical one is not in the tree to
  reproduce, so the test plants one of the same class — a control that resizes on hover, which this framework's
  own UX rule forbids — and proves the log explains it. An instrument that cannot diagnose a defect placed in
  front of it certainly cannot diagnose one nobody placed, so passing this is **necessary and not sufficient**.
  The sufficient version is a real application nobody has instrumented for the occasion; the calculator is the
  obvious first one.

## 9. Non-goals

An MCP server (the CLI is drivable today; MCP is a later wrapper, not a dependency). Visual diffing or image
assertions. Recording and replaying human sessions. Multi-peer fan-out. Making automation a supported product
feature of the framework — it is a development instrument that happens to be built from published contracts.

## 10. No new debt

Explicitly, this module may not: add a second input path; duplicate any part of the layout read-model; build a
bespoke logging framework beyond the one writer described in §4; introduce a sealed switch or a throwing default
over node kinds; or reach into `RetainedNode` or any GUI-thread state from outside the compute phase. If a
requirement here resists those constraints, that is a design finding about the framework, and it gets solved
there — not worked around in the instrument.
