# Capture and export: what just happened, in one file

Status: **proposed 2026-10-23; step 1 (the mutation lane) done 2026-10-23. Revised 2026-10-24: M2 is correlation
IDs (atchung's [correlation.md](../../../atchung/docs/correlation.md)), and video is out.** Ctrl+Shift+E in a debug
run captures the last stretch of time: its probe rows, the log lines of the same span and the tree as it was at
that moment. The Inspector, or the application's own debug menu, exports what was captured as one zip that a person
or an agent can read cold. Spans this repo (the recorder, the reconciler, the keymap), `atchung` (the causes and the
log), `vexelray` (`@Trace`), `vexelray-framework` (the debug menu) and `vexelray-inspector` (its M8 save).

## Why

Found the hard way on 2026-10-23. Pix playing a 1080p video, with nobody touching it, posted 400–600 GUI mutations a
second. The Inspector showed the rate and nothing else: no node, no property, no sender, no outcome. Finding the
cause meant reading code across four repos. `PictureViewer.showTransport` rewrote the whole transport on every
video frame, about 28 writes, of which the reconciler dropped 23 as changing nothing. The seek bar's spacers and
the clock's text laid out the whole tree on every frame (fixed in vexelray-gui `4139748` and vex-suite-common
`4110c71`). A recording of that run would not have helped either, because a mutation's row today is only this:

```
3298,1201812200,...,main,bus,vexelray.gui.mutations,25.1us
```

The aim: the person reproduces the problem, presses one chord, and hands over one file. Whoever reads it, the person
or an agent, can name the code responsible from that file alone.

## The principle: the capture changes the timing, not what happens

Recording costs time, and the slowest machines are the ones worth recording. The capture is still faithful
because the framework slows its clock rather than skipping frames. A run that is recorded stretches, but it shows
the same frames carrying the same mutations as one that is not, so only the timings change. Two rules follow:

- **The dilation is recorded.** A lane says, frame by frame, how fast the application's clock ran against the
  wall, and how much of each frame was the recorder's own. A frame time in the export can be read as measured or
  with the recorder taken out.
- **What cannot slow down is named and counted.** Sound is the outside world's clock. `Playback` takes a video's
  position from the audio clock, and a movie that drops frames shows the newest one made when it falls behind.
  That is right, but it is the exception to the principle. The same goes for vsync, input timestamps, and anything
  that arrives from the network or from another process. The export counts each of these in its span ("`Playback`
  dropped 37 frames to stay with the sound"), so a difference between a recorded run and an unrecorded one is
  visible and explained.

## What exists, verified 2026-10-23

- **The ring.** `Recorder` holds `-Drecorder=<rows>` rows, 2^18 by default, rounded up to a power of two. It is
  started by `Windows` when the probe is on, as it is in a debug run, where the probe is armed from frame 0. A
  full trace peaks at about 95k rows/s and sits nearer 20k, so the default holds roughly 13 s of a busy run and a
  minute or more of a quiet one.
- **The verbs** (flight-recorder.md §5): `recorder`, `frames`, `why`, `rows`, `diagnose`, `bus`, `tree`, `shot`,
  `mark`. Each answers with a record from `vexelray-gui-recorder` and renders it as text through `Replies`.
- **The Inspector's tape and Pause.** The tape keeps every row's header (not its detail) for a reach of 5 s to
  5 min. Pause freezes show time while the target runs on. The tape keeps no detail, so it cannot be the export's
  source: a mutation's content is all detail.
- **The keymap.** `Keymap.action(name, label, shortcut)` declares a rebindable action, stores only what the user
  changed, and refuses two defaults on one chord in one place when they are declared.

## What the rows must start saying

### M1. A mutation lane, recorded where each write is judged

**Done 2026-10-23.** `Reconciler` already decides each write's fate: `setProp` and `setText` drop a write of the
value already there, and set `drawDirty`, `geometryDirty` or `layoutDirty` otherwise. While the layout lane is
traced, `Reconciler.apply` hands each mutation to a sink nested in it (`Traced`), which clears the three flags
around each sink call, reads what the call set, and puts them back. Every edit is therefore judged on its own,
even after an earlier edit in the same frame has set the flags. One row per sink call, so a `Batch` is reported
edit by edit:

```
layout,mutation draw,SetProp node=2 TRANSLATE_X=1.0
layout,mutation unchanged,SetProp node=2 TRANSLATE_X=1.0
layout,mutation geometry,SetProp node=2 CARET=3
layout,mutation layout,SetText node=2 chars=5
layout,mutation missing,SetProp node=99 TRANSLATE_X=1.0
layout,mutation queued,Reveal node=2
```

As built, it differs from the first draft of this section in two ways:

- **It is the `layout` lane, not a lane of its own.** `Lane` is atchung's, kept small and named after layers, and
  its `LAYOUT` is already "reconcile, measure, arrange". `-Dprobe=layout` turns it on.
- **The outcome is in the row's kind, not its detail.** The rollup then counts each outcome with nothing added,
  and `rows --kind "mutation unchanged"` filters on it. The outcomes are `layout`, `geometry`, `draw`, `queued` (a
  reveal, answered after the layout), `missing` (the node is not in the tree) and `unchanged`.

The detail names the edit. A boolean, number or enum value is written out, and anything larger is named by its key
alone. Text is reported by its length (`chars=5`), never its content, so the rows carry none of what the interface
shows. The detail is built only while the probe is tracing (`Probe.tracing`, added to atchung for this), so a run
that only rolls up pays for the counter. Tested by `MutationLaneTest`, in a surefire execution of its own that
starts tracing.

### M2. What caused each write

**Revised 2026-10-24: correlation IDs, not stack walks.** The first draft sampled the stack at each post and
carried the first frame outside the GUI to the row. A stack says where a write was sent from, not why that code
ran, and it ends at the thread's run loop, so a write posted from a decoder thread or a bus handler has lost its
cause. The design is now atchung's [correlation.md](../../../atchung/docs/correlation.md):

- **Every write carries the cause it was made in**, a `long` written `3f2a1.t07.4f2`: the process, the source
  that began it (a timer, an input backend, an agent's command, …) and that source's sequence number. Causes begin where the outside world comes
  in and pass through the bus by themselves, so a write posted from a handler three mailboxes away from a playback
  tick carries that tick's cause. The mutation rows get it from the probe's new `cause` column, with no change to
  `Traced` or to anything that posts.
- **Methods annotated `@Trace` write a row naming themselves** (`vexelray`'s
  [trace.md](../../../vexelray/docs/trace.md)) in whatever cause they run in, by a site id that
  resolves to their fully qualified line: `dev.sibarum.suite.view.PictureViewer.showTransport(PictureViewer.java:1584)`.
  So a cause's rows say which traced code it passed through on its way to its writes.
- **The log carries the cause too.** The recorder stays light because it holds IDs, and the story of a cause is
  its rows joined with its log lines, which is why the export ships both.

For the Pix run: the transport's writes and `showTransport`'s site rows would share the playback clock's causes,
and the resize's writes would carry input causes, so "which cause grows when the window is small" is one table.

### M3. Why layout ran

The first mutation in a frame that set `layoutDirty` is kept, with its cause, and reported by `why <frame>` on its
`layout` line, with the traced sites that cause passed through:

```
layout  1 pass: full, first dirtied by SetText node 4502 (@transport.position), cause 3f2a1.t07.4f2 (timer)
        via dev.sibarum.suite.view.PictureViewer.showTransport(PictureViewer.java:1584)
```

That line alone would have found the clock's relayout. (The cure for that relayout is
[incremental-layout.md](incremental-layout.md): one label's new text re-measures every node today.)

### M4. Dilation and what could not slow

A `time` row per frame: the application's clock advance against the wall's, and the recorder's own share (the
probe's sink and the `@Trace` rows). Next to it, a counter for each exception in the
principle above. `Playback` reports a dropped frame with its reason (behind the sound), the loop reports a missed
vblank, and input rows already carry their device timestamps.

## Capture: Ctrl+Shift+E

**One chord VexelRay reserves, and only in a debug run.** It is `debug.capture`, declared through the keymap like
any action, so an application can rebind it and the keymap editor lists it. Outside a debug run it is not declared, so it takes nothing from
the application.

**Pressing it, in the application, with the application focused.** That is why the chord lives in the
application and not in the Inspector. Switching windows first would add a second or two of nothing to the end of
the span, and at a short reach the problem would already be scrolling away. The chord, in this order:

1. writes a `capture` mark row, which is the span's end;
2. freezes the span. It copies the rows from (mark − reach) to the mark out of the ring into a capture buffer,
   copying column slices with nothing encoded, so nothing written afterwards can lap them. If the ring had already
   lost part of the reach, the capture says how much rather than starting later without saying so;
3. takes the `tree` snapshot at that moment, so a node id in the span resolves against the tree as it was, not as
   it is at export time.

The span's log lines need nothing of their own: every log record is already a row on the `app` lane, with its
cause, so freezing the rows freezes the log.

The application keeps running. A second press replaces the capture, and the menu says so.

**The reach.** The Inspector's tape reach while one is attached, otherwise a debug setting (30 s by default). It
is bounded by the ring. `recorder` already reports the span the ring holds, and the debug menu shows it next to the
reach so the person can see when the reach is longer than what the ring keeps.

**In the Inspector.** It sees the `capture` mark on the stream, pauses at it and shows the captured span in the
frozen tape it already has. A Shift+drag on the tape narrows it to a part of the span. Space keeps pausing as it
does now, for a person who is already watching the Inspector.

## Export

From the Inspector's *Export…*, or *Export diagnostics…* in the application's debug menu. Both call one recorder
verb, `export <path> [from <seq> to <seq>]`, so an agent can call it too. The export holds only the captured span,
or the part of it the tape narrowed to: nothing before it and nothing after the mark. Everything derived is
computed from those rows alone.

`<app>-<build>-<time>.zip`:

| File | What |
|---|---|
| `summary.md` | Read first. The build (the source stamps history-by-build already reads), OS, GPU, presenter and scale. The span, rows lost, and the dilation. Frame time p50/p95/p99, stalls, `diagnose`'s findings. Then four tables: **writes by cause** (the cause's kind, the traced sites it passed through, writes/s, % unchanged, % that laid out), **layout causes**, **topics by rate**, and **what could not slow**. Last, the writes with no cause (`0`), by thread: the boundaries nobody wrapped |
| `rows.csv` | The span in the probe CSV format with the frame and cause columns, so the Inspector replays an export as it replays any recording |
| `log.txt` | The span's log records, rendered from its `app` rows as the log file renders them, each with its cause |
| `sites.txt` | The `@Trace` sites the span's rows name: site id, fully qualified method with file and line, and label |
| `tree.txt` | `tree` as it was at the mark: ids, roles, landmarks, sizes |
| `frames.txt` | `frames worst 20`, then `why` for the worst five |

For the Pix run, `summary.md`'s first table would have read:

```
cause kind   via                                                                     writes/s  unchanged  laid out
timer        dev.sibarum.suite.view.PictureViewer.showTransport(PictureViewer.java:1584)      540        82%        8%
```

**Text in the tree.** `tree.txt` holds what the interface showed: file names, document text. By default each text
is replaced by its length and only landmarks and roles are kept. A checkbox in the export dialog includes the
text, and `summary.md` says which was chosen.

## Steps

Each step lands, and is useful, on its own.

1. **M1, the mutation lane. Done 2026-10-23** (`MutationLaneTest`).
2. **M2, causes.** atchung's [correlation.md](../../../atchung/docs/correlation.md) steps 1–3 (the ID, the bus, the
   log, elektro-Q's envelope) and `vexelray`'s [trace.md](../../../vexelray/docs/trace.md), then correlation.md's
   step 4 here and in the siblings: causes begun at the input
   backends, the frame clock and timers, the automation server and the offload pool. Verified by a test that posts
   a write from a handler two mailboxes downstream of a timer and finds the timer's cause on its mutation row.
3. **M3, the layout cause in `why`.** Verified by a test whose frame lays out because of one known text change.
4. **The capture.** `debug.capture` in the keymap of a debug run, the mark, the frozen span and the tree. Verified
   by a test that captures, keeps writing past the ring's size, and still reads the whole span.
5. **The export verb and the zip.** Verified by a test that exports a capture and reads `summary.md` back, and by
   the Inspector replaying `rows.csv`.
6. **The Inspector:** pausing at the mark, narrowing with Shift+drag, *Export…*. This is the start of its M8.
7. **M4, dilation and what could not slow.**

Video was step 8 until 2026-10-24, and is out: the rows, the log and the tree say what happened, and recording
the screen was the most expensive thing the recorder would have done.

The acceptance test for the whole plan: play a video in Pix built at the commit before `4110c71`, with
`showTransport` annotated `@Trace`, press Ctrl+Shift+E, export, and read a timer cause through
`PictureViewer.showTransport` off the top of the first table in `summary.md`.

## Follow-ups outside this plan

- **The file tree's Ctrl+Shift+E.** The `vexel-desktop` template's `Actions.java:89` and
  `text-editor-vexel-demo`'s `Actions.java:138` bind it to focusing the tree, through `gui.shortcut` rather than
  the keymap. Both move to a keymap line on another chord, still to be chosen. Until they move, a debug run of
  either would have two claims on the chord.
- **Other things that capture.** Anything that later captures (a screen recording on its own, a GPU frame
  capture) should hang off `debug.capture` rather than claim a chord of its own.
