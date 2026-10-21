# The documents

Three kinds of document live here, and the folder they are in is the claim they make:

- **`reference/`** — how the framework works, as built. If one of these describes something that is not
  there, that is a defect in the document.
- **`guides/`** — how to *use* a thing, written for someone who has not read the design.
- **`plans/`** — work that is decided but not done. A plan says so in its own first screen, with the
  measurement that makes the claim checkable.

[README.md](../README.md) at the repo root is the tour and the fastest way in.
[CLAUDE.md](../CLAUDE.md) is the map of the sibling checkouts this repo sits on top of.

## Section numbers are an API

Javadoc throughout the source cites these documents **by section** — `docs/reference/automation.md §3`,
`docs/reference/layout-read-model.md §2.1` — 128 times, across 45 distinct sections. So renumbering a section
silently breaks references that no build will catch. Add sections; do not renumber them, and do not fuse two
documents that are cited this way. See [plans/reference-rewrite.md](plans/reference-rewrite.md).

---

## reference/

### The spine

| | |
| --- | --- |
| [architecture.md](reference/architecture.md) | The full design: the one rule, the substrate contracts, the retained model, threading, layout, dispatch, the frame loop, and the twenty-three modules. Start here after the root README |

### The read-models — one pattern, two instances

Computed state that only core knows after it lays the tree out, published on the bus as a versioned snapshot
rather than handed back through per-feature callbacks. Same node ids, same version, same frame — they are two
halves of one mechanism and are best read together.

| | |
| --- | --- |
| [layout-read-model.md](reference/layout-read-model.md) | **Landed.** Where a node *is* — rect, content, scroll, overflow, and text metrics rich enough that `TextField` does its own caret arithmetic without ever seeing a measurer |
| [semantic-read-model.md](reference/semantic-read-model.md) | **Landed.** What a node *is* — role, name, structure, interaction state. The half that readers which are not the renderer actually want |

### Input, and what the user does with it

| | |
| --- | --- |
| [keyboard-focus-text.md](reference/keyboard-focus-text.md) | **Landed.** Keys, focus, claims declared in advance, and the text-editing widget that depends on all of it — including real bold and italic, chosen per character (§5) |
| [keymap.md](reference/keymap.md) | **Landed.** Rebindable key bindings: actions by name with chords the user owns, one chord per action per place, stored as deltas — and the claim on every chord that lets an editor record Ctrl+S instead of saving |
| [navigation.md](reference/navigation.md) | **Landed.** Landmarks, addresses and reveals: naming a node rather than a route, and arriving by the widgets' own commands rather than synthesised input |
| [transfer.md](reference/transfer.md) | **Landed.** Drag and drop and cut and paste as one mechanism with two ways in — one resolution, several sources, and why what is shown is always what will happen |

### Putting marks on the screen

| | |
| --- | --- |
| [drawing.md](reference/drawing.md) | **Landed**, §8 still ahead. `Picture`, the four-mark alphabet and what bounds it, and the two sinks that make one drawing honest on screen and in a file. §7 is the decision of when it should be a marched surface instead |
| [typeset.md](reference/typeset.md) | **P0–P4 landed; P5's mechanism landed, its gate waits on an italic or math font; P6 (demo panel) outstanding.** Structured non-editable rich text over an open set of composable boxes, sized as ratios and tone-mapped in log space into a legible pixel range |
| [reliable-plotting.md](reference/reliable-plotting.md) | **Four of five units landed; drawing is the fifth and belongs to the consumer.** Why point sampling cannot be made honest, and the interval substrate that replaces it |

### Time

| | |
| --- | --- |
| [reactive-timing.md](reference/reactive-timing.md) | **Integration landed as `-krono`; the residue has not.** How this GUI meets Kronometer, and the parts neither repo covers — most load-bearingly that tactroller still derives key edges by polling |

### Driving the app from outside

The server, its client, and the guide to the client. Three documents because they have three audiences, not
because the feature is in three pieces.

| | |
| --- | --- |
| [automation.md](reference/automation.md) | **A0–A6 landed.** The instrument: an out-of-process agent running the real window, real Vulkan and real frame loop, with a pointer that never teleports and one correlation log that explains what happened |
| [automation-cli.md](reference/automation-cli.md) | **Landed** (§10). The design behind the socket client, and the retirement of per-application `--capture` that shipping it unblocked |
| [../guides/ottermate.md](guides/ottermate.md) | The user guide for that client → |

---

## guides/

| | |
| --- | --- |
| [ottermate.md](guides/ottermate.md) | **`ottermate`** — driving a running application from a shell. Worked examples captured against a real app, the exit statuses, and the three things that will bite you |
| [no-native-vulkan.md](guides/no-native-vulkan.md) | **A machine with no native Vulkan driver**, reproduced on this one: taking Vulkan away with loader variables, running on dzn (Vulkan over D3D12) and WARP, capping the CPU to imitate older hardware, and reading the D3D12 debug layer. The scripts are beside it |

---

## plans/

A plan marked **Done** has fully landed. It stays here as the record of what was decided and why, rather than
moving; what was built is described by the reference documents.

| | |
| --- | --- |
| [todo.md](plans/todo.md) | The register of deferred work, each item with enough context to pick up cold |
| [frame-loop.md](plans/frame-loop.md) | **Approved 2026-10-05; steps 1 and 2 of five done.** Draw on change, not on wake: the application chooses which OS messages wake the loop, a frame is redrawn only when a change says so, and coalescing is a per-message-kind choice. After steps 1–2, 400 pointer moves over empty space presented 5 frames against ~307 |
| [undo.md](plans/undo.md) | **Done: all eight steps built 2026-10-14.** One history per window, bound to Ctrl+Z and Ctrl+Y: an undo first reveals and focuses what it is about to change, value controls and `TextField` record into the nearest history, and a command records through `Button.perform` or `MenuSink.edit`. `docs/scenes/undo.txt` drives it |
| [otter.md](plans/otter.md) | **Proposed 2026-10-10, not started.** The line protocol `ottermate` speaks, grown into a language: naming a node by what it is, reading a node and asserting on it, and looking at the screen as text. Every command that works today is already valid Otter |
| [font-families.md](plans/font-families.md) | **Approved; steps 1–3 and 5 done (plugin, `FontSet`, multi-atlas draw, styled spans in measurement).** Font families baked at build time — every face, every glyph, one atlas each — and all loaded at startup, because VRAM that varies with what is opened is instability. Spans gain weight and slope, and so become part of measurement |
| [focus-ring.md](plans/focus-ring.md) | **F1–F7 done; F8 (default to keyboard-only) waits on a look at the demo chapter.** Two of twelve focusable widgets show that they have focus. One ring drawn by the renderer, configured on the `Theme` and overridable per node, replaces per-widget border recolouring, plus a demo chapter that tabs through every control |
| [tab-overflow.md](plans/tab-overflow.md) | **Step 1 done (reveal on select); the rest paused.** `Tabs` has no answer to more headers than bar: the extras clip off the edge and Left/Right walks onto tabs nobody can see. One `Overflow` seam with three policies — `CLIP` (today, plus reveal-on-select), `wrap()` (needs flex-wrap in `FlexLayout`, the plan's only core change) and `menu()` (a scrolling row and an always-present button over `Select`'s list) |
| [runtime-themes.md](plans/runtime-themes.md) | **R0 and R1 landed 2026-10-19 (measurements and node paints); R2–R6 not started.** A theme is part of the ubershader: values are a write to a paint table any mark, tint or span can index, and code is a composition contribution lowered on a worker and swapped in with a cross-fade. Done when a swapped capture diffs empty against a fresh build in the same theme |
| [gui-decomposition.md](plans/gui-decomposition.md) | **Done 2026-09-14: all seven steps of §3.** Seven components hold what were `Gui`'s concerns, each tested with no `Gui` in the room; fields went from about 68 to 22. The 400-line target was not met, and the plan says why |
| [loudness.md](plans/loudness.md) | **Stage A done; B1–B4 and B6 done, B5 held deliberately; C–E not started.** The framework explains itself well to a reader and does not yet complain to a consumer who asked for something it cannot do |
| [flight-recorder.md](plans/flight-recorder.md) | **In progress: §9 steps 1–5 landed 2026-10-17 (live sink, `LoopCounters`, the recorder and its verbs, many observers and one hand, both forms of the stream); steps 6–9 remain.** The automation socket as an instrument panel: a ring of probe rows inside the application, query verbs an agent can read (`frames`, `why`, `diagnose`, and one per domain: `bus`, `kron`, `gpu`, …), many observers but one hand, and a live stream for the Inspector. Folds in meter.md |
| [background-runs.md](plans/background-runs.md) | **Done: landed 2026-10-18 on Windows** (Linux and macOS have no background window yet). An application an agent starts is a background run: its windows are real and drawn, cloaked, never activated and unreachable by the desk's devices, decided from `CLAUDECODE`/`AI_AGENT` in its environment rather than a flag. `VEXELRAY_FOREGROUND=1` is the person's way out |
| [check-in.md](plans/check-in.md) | **Proposed 2026-10-18, not started.** The person as a holder of the hand: Check-in on a background run's tray icon uncloaks it and pauses the agent at its next command boundary (instantly during a `settle`), and Check-out hands it back. An agent's `hand give` offers the hand without taking the foreground. One rule: whoever holds the hand is the only input, and the window's presence follows the hand |
| [discovery.md](plans/discovery.md) | **Done: landed 2026-10-17.** Finding running applications: one `key=value` file per process in `~/.vexelray/automation/`, kept honest by an OS lock, and a `hello` verb that reports the protocol version (`major.minor`) and which domains this build and run offer. `ottermate list`, `--app` and `--pid`, and the Inspector's attach picker read it |
| [meter.md](plans/meter.md) | **In progress: step 2 (the counters, `LoopCounters`) landed 2026-10-17; no verb yet. Folded into flight-recorder.md**, which owns the order of work. `meter`, `wake` and `focus loop` on the automation socket: frames, parks by kind, what a wake earns and CPU by native thread, read from the loop's own always-on counters, so a render-on-demand claim is checked by an ottermate script instead of code in the application |
| [architecture-proof.md](plans/architecture-proof.md) | **Plan, not started.** It rests on Elektro-Q for the wire, which lives in the atchung checkout under `elektroq/` (installed as `sibarum.elektro.queue:*`) and already carries the Inspector link |
| [reference-rewrite.md](plans/reference-rewrite.md) | **Done** &mdash; the record of the 2026-09-14 restructure: every rename and move, with the inbound-reference count for each. All 99 source references were rewritten against it, and every `§N` citation still resolves |
