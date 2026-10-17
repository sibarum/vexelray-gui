# Meter: measuring the frame loop from the socket

Status: **Proposed 2026-10-17, not started.** Three new verbs on the automation server (`meter`, `wake` and
`focus loop`) and one new field in `size`'s reply. Together they let a script ask the questions
render on demand is judged by (how many frames, why the loop parked, what a wake earns, what idling costs) with no
code in the application under test. `ottermate` relays verbs and knows none
([ottermate.md](../guides/ottermate.md) §7), so it gets all of this unchanged. The work is in `vexelray-gui-core`
(the counters), `vexelray-gui-automation` (the verbs) and `vexelray-os` (CPU by thread).

---

## 1. What went wrong, from the session that motivated this

On 2026-10-17 a session checked the render-on-demand claims (`kronometer/docs/render-on-demand.md`; the figures in
`vexelray-framework/docs/architecture.md`, *what the pacing measurements found*) against a real window. It
confirmed most of them, corrected one, and spent most of its effort on the instrument rather than on the question:

- **Every question needed code in the application.** Counting frames meant a `@BeforeFrame` hook with a ring of
  frame starts. Counting what one wake earns meant a component on a lane of its own that wrote a node and slept.
  Reading CPU meant `ThreadMXBean` in the same component. Each answer then needed a button to start it and a text
  readout for `await` to wait on, and the witness application had to be regenerated and rebuilt each time. The loop
  already knows every one of these numbers, and none of them can be read from outside it.
- **Whether the loop was focused was luck.** The idle floor applies only while focused, so the same probe
  measured two different regimes on two runs: 0 frames in 4 s on one and 20 on the next. Which was which could
  only be worked out afterwards, from the count. Windows will not let a background process take the foreground
  without injecting input into the user's session, and [ottermate.md](../guides/ottermate.md) §5 (*a window
  behind others*) rules out raising one anyway.
- **A measurement was capped at ten seconds.** It ran as one delivery on a component lane, so the framework's
  stall watchdog bounded it. A minute of idling, which is what the CPU question needed, would have had to be
  stitched together from pieces.
- **The CPU figure could not answer its own question.** Windows accounts thread time in 15.6 ms ticks, so four
  seconds resolve to a handful of ticks, and the JVM reports only its own threads. The unfocused run's 31 ms could
  not be attributed to any thread, and in the focused run the process total was about 80 ms above the sum of the
  Java threads, with nothing to say whose those 80 ms were.
- **The run disturbed what it measured, and said so nowhere.** A driven application is in its loudest mode:
  probe on, `TRACE` to a file ([automation.md](../reference/automation.md) §4). Every frame writes probe rows, so
  idle CPU measured under automation is the idle CPU of a heavily instrumented process. Nothing in the readings
  recorded that.

---

## 2. The verbs

### `meter`

A measurement window over the loop. While one is open, every counter in §3 accumulates; the reply is the change
since it opened.

| Form | Does |
| --- | --- |
| `meter start` | Opens a window. A second `start` while one is open is `err a meter is already open`, never a silent restart |
| `meter read` | The figures so far, leaving the window open |
| `meter stop` | The figures, and closes the window |
| `meter <ms>` | `start`, then waits `ms` on the driver's own thread, then `stop`. Bounded at 10 minutes |

The reply is one line of `key=value` pairs, so a script can assert on a field without parsing prose, followed by
the per-thread table:

```
> meter 30000
ok ms=30001 focus=off(simulated) probe=on log=TRACE iterations=1 drawn=0 presented=0 gap=- wakes=0 coalesced=0 parks=forever:1 cpu=0.04% cycles=2.1e7
thread cycles share name
  9120   1.2e7  57% G1 Service
  4416   4.0e6  19% vexel-watchdog
  ...
```

- **`iterations`, `drawn` and `presented` are three numbers, not one.** Since steps 1 and 2 of
  [frame-loop.md](frame-loop.md) a pass of the loop can update without drawing, so "frames" is ambiguous. The
  2026-10-17 witness counted `@BeforeFrame` calls, which are iterations, and called them frames.
- **`gap` is frame start to frame start over presented frames**: median, p99 and max in microseconds, or `-` with
  fewer than two.
- **`parks` counts each park by what bounded it**: `forever`, `deadline` (the application's pacing), `floor` (the
  idle refresh), `ceiling` (the gap held against a wake) and `zero` (no park, because something was owed).
  Together with the counts, this is the reason the loop ran as often as it did.
- **`wakes` and `coalesced`**: `postWake` calls, and how many of them found a frame already owed.
- **`focus`** is what the loop believed for the whole window: `on`, `off`, `mixed`, each suffixed `(simulated)`
  while `focus loop` holds it (below).
- **`probe` and `log` are in every reply**, because they change the CPU figure and the gaps (§1, last point).
  A reader comparing two runs can then see whether they were comparable.

**`meter <ms>` is not the `sleep` verb this protocol refused** (ottermate.md §5, *`settle` waits for animation*).
That `sleep` guessed how long the application needs to reach a state, which is the flake. Here the duration is the
quantity being measured, as in "frames in 30 s", and nothing waits on the application to be ready.

### `wake [n]`

`n` (default 10, at most 100) bare `postWake` calls, each made only once the loop is parked, at a random phase of
its park. Each is followed by 100 ms of watching:

```
> wake 10
ok n=10 earned=1,1,1,1,1,1,1,1,1,1 latency median=640us p95=1010us max=1010us
```

- **`earned` is how many frames each wake bought** in the 100 ms after it. Render on demand claims exactly one;
  more means something the frame started keeps the loop awake.
- **`latency` is from the wake to the start of the frame it earned**, not to the glass (§6).

A bare wake carries no mutation, so this measures the loop's half alone. The 2026-10-17 witness wrote a node to
make each wake, which measured the tree's half as well. That remains a component's job when it is the question,
and a `wake` with no write is the baseline to compare it with.

"Parked" means the loop's park counter has advanced and no frame has started since. The driver waits up to a
second for that before each trial, and an `err the loop did not park within 1000ms` says why the trial was refused
rather than reporting a wake that landed mid-frame.

### `focus loop on|off|os`

Overrides what the loop believes about focus: whether the idle floor applies, and nothing else. `os` hands the
question back to the windows. The reply says the override is a simulation, the way `dpi` says its factor is not a
real surface:

```
> focus loop off
ok focus=off(simulated): the loop parks as if no window had focus; the windows themselves are unchanged
```

- **It sets a policy input; it does not take the foreground.** Taking the foreground would need injected input,
  and raising a window is ruled out in ottermate.md §5. The claims at stake are about the loop's policy, so
  overriding the loop's own focus check tests exactly them.
- **The override is loop-wide.** `GuiApp.isFocused` already answers for every window on the loop at once, since
  they share one wait, so there is nothing per-window to override.
- **It lapses with the session.** A driver that disconnects with an override in force is put back to `os`, so a
  crashed script cannot leave an application that never idles at the floor.

### `size`, one field more

`size` gains `focus=on|off` (and `(simulated)` while overridden), so a script can tell which regime a run landed in
before measuring it, not afterwards.

---

## 3. What the loop counts

`GuiApp` keeps the counters as plain fields, written on the loop thread and read by the driver:

- iterations, frames drawn and frames presented, each a `long`;
- a fixed ring of the last 1,024 presented frame starts, as `long[]`, for the gaps;
- one counter per park kind (§2), written where the budget is decided (`GuiApp.run`, the block after
  `isFocused()`), plus `postWake` calls and wakes coalesced;
- the focus the last park decided on.

**The constraints are the hot path's** (`vexelray-framework/CLAUDE.md`): no
allocation, no boxing, no lambdas and no logging in the loop. These are increments and one array store, cheap
enough to leave on in a release build. That is the point of them: **the probe already records most of this**
(`frame.present`, `loop.park` and its budget), but the probe costs time and is only on when asked for. The
pacing test's own comment says its gaps are "a picture of where time goes and not a second measurement". A
counter that is always on is a measurement.

**The driver reads, and never resets.** `meter start` snapshots every counter, and later replies are differences
against that snapshot. Two drivers (two windows) can therefore meter at once without disturbing each other, and
nothing on the loop thread has to know a meter exists. Readers see each field through a `VarHandle` acquire, which
is not reflection (it is resolved once, in a static initialiser). A reply is a set of fields read at slightly
different instants, which is acceptable to within one frame and says so in the reference.

**The seam is a read-only view, `LoopCounters`**, in `vexelray-gui-core`, which `GuiApp` implements and hands to
the automation `Windows` registry next to `controls()` and the timeline. The focus override is one setter on it,
the only write.

---

## 4. CPU by thread

`meter` reports process CPU, and CPU by native thread, from cycle counters rather than tick-accounted times:
`QueryProcessCycleTime` and `QueryThreadCycleTime` on Windows, with the thread list from
`CreateToolhelp32Snapshot` and names from `GetThreadDescription`. These are downcalls, so they go in
`vexelray-os-windows` behind a `vexelray-os-api` interface (`ProcessCpu`), and every one is added to the native
metadata (the stack's rule: native-image metadata covers every downcall).

- **Cycles are exact; the conversion to time is not.** The reply gives cycles, and estimates a share of one core
  from the TSC's nominal rate, labelled as an estimate. Over a window long enough to matter the error is small,
  and the cycles are what two runs are compared on.
- **Every thread appears, not only the JVM's.** The 80 ms nobody owned on 2026-10-17 belonged to GC, JIT, Vulkan
  or DXGI threads, or the compositor's hooks. A native list shows whichever it was.
- **Whether HotSpot sets a Windows thread description for a Java thread is unknown.** Step 1 checks this. If it
  does not, Java threads appear by TID, and the driver joins them to `Thread` names where it can.
- **Other platforms answer `cpu=-` until ported**, and say so. A figure from `ProcessHandle` that silently had
  15.6 ms resolution would be the instrument that misled this session.

---

## 5. Order of work

1. **Spike the CPU calls.** A throwaway that prints process and thread cycles for a running witness, and settles
   the thread-name question (§4). Everything in §4 depends on its answer.
2. **The counters and `LoopCounters`** (§3), with a harness test that drives a fake window through each park kind
   and checks the counts. No socket yet.
3. **`meter`** on the automation server, reading `LoopCounters` and `ProcessCpu`. Document it in
   [automation.md](../reference/automation.md) §5 and the guide's worked examples.
4. **`focus loop` and `size`'s field.** Includes putting focus back when a driver disconnects (§2).
5. **`wake`.**
6. **Retire the witness code.** In `vexelray-framework`, `PacingMeasurementTest`'s idle probe becomes an ottermate
   script (below) and the `Idle` component, button and readout come out. The animation, tap and storm probes stay
   as they are: they time what an application's own handler sees, which no driver can see for it.

What the idle probe becomes:

```
focus loop off
settle
meter 30000
focus loop on
settle
meter 30000
wake 10
focus loop os
```

---

## 6. What it deliberately does not do

- **No thresholds.** Verbs report numbers; a test asserts on them. A verb that answered `err` at 1 % CPU would bake
  one machine's opinion into the protocol.
- **No glass latency.** Wake to frame start is measured; frame start to photons is not, and cannot be from inside
  the process. With FIFO present the picture is a swapchain queue behind. A field that implied otherwise would
  mislead, so there is none.
- **No turning the probe off from here.** `log` already lowers the logging. Whether the probe can be stopped in a
  running process is a question for `atchung/docs/probe.md`, and is not invented here. `meter` reports the probe
  state instead, so a disturbed run is at least labelled as one.
- **No taking the foreground** (§2, `focus loop`).

---

## 7. Open questions

- **Q1. Should `wake` also offer a write?** For example, `wake 10 tick` would write a landmark's text, so the
  tree's half is measured without a component. That needs a writable landmark, which the protocol does not have.
  Left out until a second application wants it.
- **Q2. Should the ring be sized by time, not by count?** 1,024 frames is seven seconds at 144 Hz, so a
  thirty-second `meter` gets gap statistics for its last seven seconds only. A running median and max over every
  gap avoids the ring entirely, at the price of no p99. The answer depends on whether anyone reads p99 from a long
  run.
