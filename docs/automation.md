# Driving the app for real: `vexelray-gui-automation`

Status: **A0–A5 landed** (see §8). A module that lets an out-of-process agent run the actual application — real window, real
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
| **See** — pixels | `GuiApp` offscreen capture on the live device. | Exists (see §8 A0) |
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
it full control of the application's input, so it is a debugging instrument and not a service. It takes **one
connection at a time** — a pointer is one hand, and two agents interleaving paths would produce a gesture
neither asked for and a log of a run nobody performed. Pass `0` as the port to be given a free one and ask
`server.port()`.

The verbs are also callable directly — `driver.command("click 41")`, or `driver.cursor()` for the pointer — so
a test can drive the same surface with no socket in the way.

### The protocol

One command per line in; one reply out, terminated by a line containing only `.`. Replies begin `ok` or `err`,
so a script can branch on the first two characters without parsing anything.

```bash
printf 'find Save\nclick save\nsettle\nshot after.png\nquit\n' | nc localhost 7654
```

| Command | Meaning |
|---|---|
| `tree` | The joined semantic + layout snapshot as an indented outline: ref, role, name, `@landmark`, box, plus `hidden` / `focused`. |
| `find <text>` | Refs whose role, name or landmark contains the text, case-insensitively. |
| `where` | Where the pointer is now. |
| `go <landmark>` | The framework's own navigation: reveal whatever conceals it, scroll it into view, focus it. |
| `move <ref\|landmark\|x,y>` | Travel there. Hover happens on the way, and is recorded. |
| `click` / `rightclick <target>` | Travel there, then press and release. |
| `drag <target> <target>` | Press, travel, release. |
| `scroll <target> <dx> <dy>` | Wheel notches there. |
| `type <text>` | One code point at a time, through the ordinary character channel. |
| `key <NAME>` | Press and release a named `Key`. |
| `settle` | Wait for the loop to catch up with everything published so far. |
| `await <landmark> <text>` | Wait until that landmark's name contains the text — how an application's own readiness is waited on. |
| `shot [path]` | PNG of the window this driver is attached to. Clears the target, waits for the file, `err` if none arrives. |
| `mark <note>` | Write a row into the correlation log saying *why*. Changes nothing else. |
| `help`, `quit` | The list; and end the session. |

**Two ways to name a node, and the difference is durability.** A **ref** is a node id: minted per run, resolved
to a box at the moment of acting — never a coordinate read a frame earlier, which is the classic automation
flake and cannot be retried, because the click has already landed. A **landmark** (`Gui.landmark(name, node)`)
is the name that still means something tomorrow, and unlike an id it can be *navigated* to, so a concealed
target is revealed rather than refused. `tree` and `find` show it as `@name`. Anything written down — a script,
a recorded session — should say the landmark.

**What `settle` does and does not promise.** It waits on the layout commit signal until no frame is owed, so it
is exact about the frame loop and never a sleep. It cannot see a handler still running on a worker: that
handler has published nothing yet, so nothing reports it owed. An agent needing "the application has finished
thinking" has to wait on something the application names.

**`await` is how it waits on that**, and deliberately without an application-specific hook. An accessible name
is published for every node and a landmark is already the durable way to say *which* node, so an application
declares readiness by writing it down where it is legible — a landmark whose name says what state it is in —
and `await <landmark> <text>` blocks on the layout commit signal until it does. The rejected alternative was a
readiness predicate supplied by the host, which would have made each application's synchronisation a private
arrangement with `Automation` instead of a fact in the read-model that `tree` and `find` can already see. The
calculator's ray-marched preview is the first consumer: see
[calculator-vexel-demo/docs/driving-the-preview.md](../../calculator-vexel-demo/docs/driving-the-preview.md).

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

- **identity** — application-supplied, declarative, no handlers.
- **caption** — the flexing drag region, still `WindowRegion.DRAG`.
- **instruments** — framework-owned, each `WindowRegion.INTERACTIVE`, with the gaps between them left draggable
  exactly as the caption buttons already manage.
- **window controls** — minimize, maximize, close. Rightmost, and maximize keeps `MAXIMIZE_BUTTON` so the
  platform's own snap affordance still works.

### Registered, never enumerated

An instrument is a value — mark, tooltip, role, action — contributed to a registry that `TitleBar` renders
without inspecting. The framework registers screenshot and macro-record; `TitleBar` branches on nothing, so the
automation module can add its own without `TitleBar` knowing it exists. The same shape `WindowControls` already
has: the bar commands an interface and knows no implementations.

### Three constraints

- **Never revealed on hover.** Instruments are always present and reserve their width. Where the bar is too
  narrow they collapse into an overflow instrument that is itself always present. Nothing in this strip may
  appear, move or grow under the pointer.
- **Marks, not glyphs.** The primary atlas carries no camera or record symbol, and `TitleBar` already draws its
  icons as rectangles rather than font characters. Instrument marks are `Picture` geometry, which also stays
  crisp under zoom.
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
  docs/semantic-read-model.md. Widget and demo roles landed with it.)*
- **A0 — Live capture.** Capture on the running app, **per window** (§7), posted to the `tasks` queue so it
  happens on the main thread inside the frame loop. Keep or delete the static per its remaining CI value.
  *(Prereq for `shot` and for the screenshot instrument.)*
- **A2 — The writer.** *(Landed: `probe.format=csv` in atchung — `seq`, two clocks, one row per physical line.)*
  Core, input and frame-loop events instrumented — including the unconditional `frame.present` heartbeat and
  `loop.park`, without which a stall is indistinguishable from an idle window.
- **A3 — Virtual cursor + paths.** *(Landed: `vexelray-gui-automation`, `Cursor` — stateful, stepped at 125Hz, real-time paced, `move`/`click`/`drag`/`scroll`.)*
- **A4 — Protocol + CLI.** *(Landed: `Automation` + `AutomationServer`, a loopback line protocol. `go` reuses the framework's own navigation, so a concealed target is revealed rather than refused. `csvview` in atchung-probe.)*
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
