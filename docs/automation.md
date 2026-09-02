# Driving the app for real: `vexelray-gui-automation`

Status: **plan**. A module that lets an out-of-process agent run the actual application — real window, real
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

```
seq,t_mono_ns,t_wall,frame,version,stream,level,kind,node,detail
```

| Column | What it is for |
|---|---|
| `seq` | Total order from the single writer. Monotonic, no gaps — **a gap is detectable loss**, which is the whole reason to have it. Not the sort key; it breaks ties when two rows share a nanosecond. |
| `t_mono_ns` | Monotonic nanos. **The sort key**, and the only one — see below. |
| `t_wall` | ISO-8601 UTC. For humans, and for joining against anything outside this process. |
| `frame` | GUI frame number the event was observed in. |
| `version` | `LayoutSnapshot.version` current at emit — **the causality key**. "Did my click land before or after that layout?" is answered here, not by timestamps. |
| `stream` | `agent` / `input` / `gui` / `app` / `log` / `perf`. What a derived view filters on. |
| `level` | `trace`…`fatal`, plus `watchdog`. Projects out the warn/error view. |
| `kind` | Event type: `pointer.move`, `hover.enter`, `key.down`, `mutation.drain`, `layout.publish`, `frame.present`, `agent.click`. |
| `node` | Node id, or empty. Joins a log line to a widget. |
| `detail` | Free-form, escaped, **always last** so a naive split keeps working when it contains commas. |

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
- Written from a single thread that owns the file. Producers hand it a record; they never touch the writer.
- **Flushed per line, not buffered.** An instrument used to debug crashes must have already written the last
  line before the crash. This costs throughput and is worth it.
- Emitting must never be able to fail the app: a full disk degrades to a dropped record and a `watchdog` line,
  and `seq` makes the drop visible.

### Derived views

A small `csvview` command projects `--stream`, `--level`, `--node`, `--frame` ranges. These are generated on
demand from the one file, so they are consistent with it by construction. No parallel writers, ever.

---

## 5. Module shape

`vexelray-gui-automation`, depending on `-core` only, **speaking nothing but bus message types**. That is the
invariant that makes the module worth having rather than a pile of helpers: it is a bus peer that happens to be
in the same JVM today. When M1–M3 land it becomes a remote peer across an `ElektroBridge` with no change to its
own code — which makes this a more demanding **M4** than the planned one, and one that gets used daily.

Agent-facing surface: a line protocol on a socket, plus a thin CLI so it is drivable from a shell.

| Command | Meaning |
|---|---|
| `tree` | The joined semantic + layout snapshot, as an indented, ref-tagged outline. |
| `find <query>` | Refs whose role/name/text match. Refs are node ids — no coordinate guessing, no OCR. |
| `where` | Current virtual cursor position. |
| `move <ref or x,y>` | Path the cursor there. Hover consequences are real. |
| `click <ref or x,y>` | `move`, then press/release. |
| `drag <from> <to>` | Press, path, release. |
| `type <text>` / `key <chord>` | Through the ordinary key path. |
| `scroll <ref> <dx> <dy>` | Wheel deltas. |
| `settle` | Block until `version` advances with no pending mutations. |
| `shot [path]` | PNG of the live window. |
| `mark <text>` | Write an `agent` row into the CSV — how an agent annotates *why* it did something. |

Main-thread discipline: `shot` and any window command post to `GuiApp`'s existing `tasks` queue. Reads post
nothing — snapshots are lock-free by design.

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
to be widened. Each window owning its own bar means each instrument captures its own window, and multi-window
support is not a feature anyone has to add.

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
- **A2 — The writer.** Single-threaded correlation CSV, `seq`, per-line flush, drop-and-watchdog on failure.
  Core, input and frame-loop events instrumented — including the unconditional `frame.present` heartbeat and
  `loop.park`, without which a stall is indistinguishable from an idle window.
- **A3 — Virtual cursor + paths.** Stateful cursor, stepped motion, real-time pacing, `move`/`click`/`drag`.
- **A4 — Protocol + CLI.** Socket line protocol, `tree`/`find`/`settle`/`shot`/`mark`, `csvview`.
- **A5 — Prove it.** Reproduce a known hover-path bug from the CSV alone, without the app in front of us.
  Until A5 passes, the instrument is not trusted for troubleshooting.

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
