# Flight recorder: the automation socket as an instrument panel

Status: **In progress: §9 step 2 (`LoopCounters` and the frame counter) landed 2026-10-17.** This plan extends the automation socket
([automation.md](../reference/automation.md)) so a running application can report what its frame loop, bus, clock,
GPU and threads are doing, both to a person and to an agent. It is the first step on the
[Inspector](../../../vexelray-inspector/docs/roadmap.md) roadmap. It folds in [meter.md](meter.md): that plan's
verbs, counters and CPU readings become part of this one unchanged. meter.md stays the specification for them, and
§9 here replaces its order of work.

There are four additions:

1. **A recorder.** A ring of recent probe rows held inside the application. Today those rows go only to a file.
2. **Query verbs over the ring.** They answer what happened, in replies an agent can read and a script can assert on.
3. **Many observers, one hand.** Several connections may watch at once. Only the one holding the pointer can act.
4. **A stream.** The same rows, pushed live to whoever asks. The Inspector draws from it.

---

## 1. What is missing

Everything the Inspector wants to show is already measured somewhere, and nothing outside the process can read any
of it while the application runs.

- **The probe writes only to a file.** `sibarum.probe.Probe` records every span, mark and counter on eleven lanes,
  but its `Sink` is package-private and can only write to a file or stdout. To ask "why was frame 18,342 slow", you
  stop the run, find the CSV and run `csvview` over it. Nothing can ask the running application.
- **The socket takes one connection.** [automation.md](../reference/automation.md) §5 limits it to one connection
  because a pointer is one hand. That reason is about input. Because of it, an Inspector cannot watch while
  `ottermate` drives, which is exactly when watching matters most.
- **Every reply is a reply to a request.** Nothing is pushed. Polling `tree` at frame rate would cost the frames it
  is trying to observe.
- **The numbers the loop already keeps are private.** meter.md §1 tells the story: every question needed code
  added to the application under test.

## 2. Two readers, one source

The Inspector and an agent ask different questions of the same facts:

| | The Inspector | An agent |
|---|---|---|
| Wants | Everything, live, in order | The answer to one question |
| Volume | Thousands of rows a second | A few kilobytes per reply, because every byte costs context |
| Shape | Rows it can lay on a timeline | Facts in a fixed shape, plus enough explanation to act on |
| Time | Now, continuously | A window: "the last five seconds", "since my mark", "frame N" |
| Loss | Must be visible, so a gap can be drawn as a gap | Must be stated, so a conclusion is not drawn from a hole |

So there is **one source and two shapes**. The recorder (§3) holds the rows. Query verbs (§5) summarise them for an
agent. The stream (§6) hands them over raw to the Inspector. Both read the same ring, so an agent's `why 18342` and
the frame the Inspector highlights cannot disagree.

The rules from [otter.md](otter.md) §2 apply to every new verb:

- **A reply is a fact in a fixed shape.** The first line is `ok` or `err` followed by `key=value` pairs. Any table
  follows with a header row.
- **Units are part of the key**, as in `took_us=21700` or `heap_mb=212`. A reader should never have to guess a unit.
- **Every reply states what it covers**: the ring span it read (`over=+3.2s..+12.4s`), and `lost=` when rows are
  missing from that span. This applies the rule from [automation.md](../reference/automation.md) §4, *a reader
  who is not told about the absent rows is being invited to conclude something from a hole*, to every verb.
- **Every reply is bounded.** A list has a default limit and says `more=N` when it stops early. No verb can flood
  an agent's context by accident.
- **The probe and log state appear where they change the numbers**, as meter.md §2 requires.

## 3. The recorder

The recorder is a fixed-size ring in the application, fed by the probe.

- **Feed.** It takes rows from a **live sink**, a new atchung addition (§8): `Probe` hands each row to the
  registered sinks as well as to the file. The row is the `correlations.csv` row, with the same fields and meaning.
- **Frame stamp.** The recorder adds one field to each row: the loop's current frame number, read from a volatile
  counter that `GuiApp` advances at the top of each frame. This turns "which frame was this in" from an inference
  ([automation.md](../reference/automation.md) §4, *weaker than a column would have been*) into a field. Rows
  from other threads get the frame in progress when they were written, which is honest to within one frame. The
  file format keeps its schema unless Q1 decides otherwise.
- **Size.** It holds a fixed number of rows, set with `--recorder=<rows>` and defaulting to 2^18 (Q2). When full,
  it overwrites the oldest rows. Overwriting is not loss: the span it covers simply shrinks, and every reply says
  what span it read. Loss is a `seq` gap, which the recorder counts and reports.
- **Hot path.** Writing a row is one slot claim and some stores into arrays allocated at startup. There are no
  locks, no allocation and no logging (`vexelray-framework/CLAUDE.md`). A row's text fields are the strings the
  probe has already built.
- **Always-on counters live beside it.** `LoopCounters` (meter.md §3) is always on and costs almost nothing. The
  recorder exists only when the probe is on. A driven run turns the probe on already (`Logging.probeLanes()`), so
  in practice the recorder is there whenever the socket is. Verbs that need it answer
  `err the recorder is off: start with -Dprobe=...` when it is not.

## 4. Many observers, one hand

The socket accepts several connections, four by default. Each one is an **observer**: it can use any verb that
reads.

Only one connection at a time holds **the hand**, which means it can drive input. The input verbs are `move`,
`click`, `rightclick`, `drag`, `down`, `up`, `scroll`, `type` and `key`, plus `resize`, `zoom` and `dpi` when they
are given a value.

- **Taking it.** The first input verb from any connection takes the hand if it is free. `hand take` and
  `hand release` do the same explicitly, and `hand` on its own reports who holds it.
- **Refusing.** An input verb from a connection without the hand is refused, and the refusal names the holder:
  `err the hand is held by connection 2 (since +4.1s)`.
- **Releasing.** The hand is released when its connection closes. A crashed script therefore cannot leave the
  pointer locked, which is the same reason meter.md §2 puts focus back on disconnect.

This keeps exactly what the single-connection rule protected: two agents cannot interleave pointer paths into a
gesture neither asked for. It lifts what that rule cost: being able to watch while something else drives.

Each connection runs on its own `vexelray-automation-<n>` daemon thread. These belong with the existing automation
thread, outside the closed lane list (`vexelray-framework/docs/threading.md` T1.1).

## 5. Query verbs

All of these read. None of them changes the application, with one exception: `focus loop` (from meter.md) changes
what the loop believes about focus.

The transcripts below are **illustrative**. They show the intended reply shapes, not runs captured from a real
application.

### Over the ring

| Verb | Answers |
|---|---|
| `recorder` | Rows held, the span they cover, rows lost, the current row rate, and the probe lanes that are on |
| `frames [last N \| worst N \| since <mark>]` | One line per frame: number, start, time taken, the stage it spent most time in, what woke the loop, and whether the frame was drawn and presented. Defaults to `last 20` |
| `why <frame>` | One frame explained: what woke it, the messages that reached it, time per stage, layout passes and their cause, and any GC, stall or overrun inside it |
| `gaps [ms]` | `csvview --gaps` over the ring: silences longer than `ms`, each judged against the park before it |
| `rows [--lane L] [--kind K] [--thread T] [--grep S] [--frame N] [limit]` | Raw rows, projected the way `csvview` projects a file. Defaults to the last 50 |

```
> why 18342
ok frame=18342 at=+12.401s took_us=21700 budget_us=16600 over_us=5100 drawn=yes presented=yes lost=0
woke    mutation: vexelray.gui.mutations, 3 messages from vexel-handler
stage   input=90 clock=310 drain=420 layout=14210 resolve=2080 draw=3890 present=700   (us)
layout  2 passes: full (layoutDirty), reveal (scrollTo node 4411)
also    gc=none stall=none overrun=none
see     rows --frame 18342 --lane layout
```

The `see` line names the verb that shows the evidence. An agent should always be able to check an explanation, not
just accept it.

### Over the loop (from meter.md)

`meter`, `wake`, `focus loop`, and the `focus=` field in `size`, exactly as [meter.md](meter.md) §2–§4 specify,
including CPU per native thread.

### Snapshots, one verb per domain

Each verb lands when the upstream hook it reads lands (§8). Until then it answers
`err not available yet: <what is missing>`, so the verb list in `help` stays honest.

| Verb | Shows | Reads |
|---|---|---|
| `bus [top N]` | Topics, subscriber counts, publish rate, mailbox depth, drops and backpressure policy. `top` sorts by rate | An atchung topic registry and tap |
| `kron` | Live shreds and their state, the next scheduled entries, effects and their dependencies, running animations, `whyBusy`, slip and slack | A kronometer snapshot |
| `gpu` | Presenter (DXGI or Vulkan swapchain), present mode, buffer count and size, the fence values in flight, submit→fence latency, rebuilds, validation message count | `GuiApp` and presenter statistics |
| `ledger` | Open resources by kind: count, bytes, the oldest one, and opened-minus-closed over the span | A public `Ledger` snapshot |
| `threads` | Every native thread: lane, busy, parked or stalled, and CPU cycles over the span | meter.md §4 `ProcessCpu`, plus `Lanes` gauges |
| `heap` | Occupancy, allocation rate, and GCs with their pause times, linked to the frames they fell in | JVM management beans or JFR events, depending on the Q5 spike |

### Diagnosis

`diagnose [since <mark>]` runs named rules over the ring and lists what they find. Every finding names its frame or
span and the verb that shows the evidence:

```
> diagnose
ok over=+0.0s..+31.2s lost=0 rules=9 findings=2
rule              where             what                                        see
frame-over-budget frames 18342,18350 2 frames over 16.6ms, both in layout        why 18342
mailbox-growth    vexelray.gui.clicks depth 0 -> 41 over 3.0s, still rising      bus
```

The rules: frame over budget, a wake that drew nothing, not idling while focus is off, mailbox growth, a message
storm, a main-thread stall, a GC during an animation, a missed vblank, and a resource leak.

meter.md §6 keeps thresholds out of the protocol. `diagnose` is the one place that holds opinions, and it keeps
them in plain view: each rule has a name, every finding prints the threshold it applied, and the thresholds can
be set per call (`diagnose frame-over-budget=8ms`). A finding is reported as an `ok` fact and never turns a reply
into `err`. Deciding whether a finding fails a run is the caller's job, with `expect` once otter.md lands.

### Describing itself

`help <verb>` describes a verb's forms, fields and units. `help --schema` prints every verb as one line of
structured data (name, arguments, reply fields, whether it reads or acts). An adapter can generate tool
definitions from that, which is §7's MCP route.

## 6. The stream

`stream <lanes|all> [from <seq>] [for <ms>]` switches the connection into push mode.

- **What it sends.** First a header row naming the columns, then each row as the recorder takes it, in the same
  CSV row format with the frame stamp.
- **Backfill.** `from <seq>` first sends what the ring still holds from that row onward, so an Inspector that
  reconnects loses nothing the ring kept.
- **Snapshot rows.** Every 100 ms the stream also sends `snap` rows: loop counters, mailbox depths, kronometer
  counts and heap figures. The Inspector can then draw its dials without polling.
- **Ending.** Without `for`, the stream lasts until the client closes the connection. With `for <ms>`, it ends
  after that long with the `.` terminator, which makes it an ordinary bounded reply that an agent can use through
  `ottermate`.
- **Backpressure.** Each connection has a bounded queue. A slow reader loses rows, never time on the loop. The
  stream sends a `lost` row giving the `seq` range dropped, and the recorder counts the drop as well.
- **Encoding.** Text, deliberately. The stream, a saved recording and `correlations.csv` are one format, so
  `csvview` reads all three and the Inspector opens files and live streams the same way. A binary encoding
  (intrabuf) waits until a measured run shows text cannot keep up (Q3).

## 7. How an agent uses it

**Through ottermate, with nothing new.** ottermate relays verbs it does not know
([ottermate.md](../guides/ottermate.md) §7), so every verb above works from a shell as soon as the server has it.
An agent attaches to a running application, or launches one, exactly as it does today:

```
ottermate recorder
ottermate frames worst 5
ottermate why 18342
ottermate diagnose
ottermate stream frame,gpu for 2000
```

Because the socket allows several observers (§4), an agent can read `diagnose` while a person, or the Inspector,
drives the application. An agent can also drive the application in one connection and watch it in another.

**Later, through MCP, if wanted.** A small `ottermate mcp` mode could serve the socket's verbs as typed tools over
stdio, generated from `help --schema` (§5). It is cheap once the verbs describe themselves and it is not needed
first, because the shell route already works. It waits until an agent's experience with plain verbs shows that
typed tools would help.

**And the agent shows up in the recording.** Each command is already a probe mark (`agent.command`), so an agent's
actions appear in the recorder, in the stream and in the Inspector, in order with the effects they caused.

## 8. What other repositories must supply

| Repository | Addition | Needed by |
|---|---|---|
| atchung (`atchung-probe`) | A live sink SPI: a registered sink receives each row, allocation-free, alongside the file sink | §3, and everything after it |
| atchung (`atchung-probe`) | A public `Ledger` snapshot, with sizes | `ledger` |
| atchung (`atchung-core`) | A topic registry, a tap, and per-owner mailbox names (todo.md §6.12) | `bus`, and the storm rule |
| vexelray-gui-core | `LoopCounters` (meter.md §3) and a public frame counter | §3 frame stamp, `meter`, `frames` |
| vexelray-gui-core | `GuiApp` publishing frame, resize, present and rebuild events, plus presenter statistics | `gpu` |
| kronometer | A read-only snapshot (shreds, scheduled entries, effects, animations), a bounded trace ring, and more than one work listener | `kron` |
| vexelray-os | `ProcessCpu` (meter.md §4) | `threads`, `meter` |
| vexelray / supirvast | A validation message listener, and GPU timestamp queries | `gpu` |

## 9. Order of work

1. **The live sink** in atchung-probe, with a test that a registered sink receives exactly the rows the file
   receives, in the same order.
2. **`LoopCounters` and the frame counter**, which is meter.md step 2. **Landed 2026-10-17**: the frame stamp is `LoopCounters.frame()`, the frame in progress, advanced before anything in the frame runs (a frame pulled during a move or resize counts as one).
3. **The recorder and its first verbs:** `recorder`, `rows`, `gaps`, `frames`, `why`. After this step an agent can
   ask why a frame was slow.
4. **Many observers, one hand** (§4), with a test that two connections cannot interleave input and that the hand
   is released on disconnect.
5. **The stream** (§6). The Inspector's first light (roadmap M1) depends on this step.
6. **The rest of meter.md:** the CPU spike, then `meter`, `focus loop` and `wake` (meter.md steps 1 and 3–5). Retire
   the pacing witness code (meter.md step 6).
7. **The domain verbs**, each landing with its upstream hook: `bus`, `kron`, `gpu`, `ledger`, `threads`, then
   `heap` after the Q5 spike.
8. **`diagnose`.**
9. **`help --schema`.** An `ottermate mcp` adapter only if §7's condition is met.

Each step updates [automation.md](../reference/automation.md) §5 and the ottermate guide's worked examples in the
same change. Those examples are real runs, which the transcripts in this plan are not.

## 10. What it deliberately does not do

- **No writes to application state**, apart from meter.md's focus override. Observing must not change what is
  observed, beyond the cost of observing, and that cost is reported.
- **No switching the probe on or off from the socket.** meter.md §6 leaves that question to
  `atchung/docs/probe.md`, and so does this plan.
- **No non-loopback binding.** The socket gives control of the application's input. More connections do not change
  that.
- **No second log.** The recorder holds the probe's rows. It is not a new writer, so the one-writer rule
  ([automation.md](../reference/automation.md) §4) holds.

## 11. Open questions

- **Q1. Should the frame stamp become a column in the file?** The recorder and the stream carry it. Putting it in
  `correlations.csv` too would make all three identical, but Probe is the whole stack's seam and a message bus has
  no frames ([automation.md](../reference/automation.md) §4). Proposed: a header row names the columns, and
  `csvview` accepts either schema.
- **Q2. Ring size.** 2^18 rows covers somewhere between seconds and minutes, depending on which lanes are on. The
  right default depends on how many bytes a row costs in practice, so measure it before choosing.
- **Q3. When the stream needs a binary encoding**, if ever. Measure full-trace text throughput over loopback
  first.
- **Q4. How the hand interacts with otter.md sessions.** A session that outlives one command (otter.md §1) should
  probably hold the hand for its whole life.
- **Q5. Heap and GC data under native-image.** Which management bean notifications and JFR events work in a
  GraalVM native binary? Spike this before `heap` is scoped.
