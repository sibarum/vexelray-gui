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
`docs/reference/layout-read-model.md §2.1` — 175 times at the last count. So renumbering a section silently
breaks references that no build will catch. Add sections; do not renumber them, and do not fuse two documents
that are cited this way. See [plans/reference-rewrite.md](plans/reference-rewrite.md).

---

## reference/

### The spine

| | |
| --- | --- |
| [architecture.md](reference/architecture.md) | The full design: the one rule, the substrate contracts, the retained model, threading, layout, dispatch, the frame loop, and the twelve modules. Start here after the root README |

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
| [keyboard-focus-text.md](reference/keyboard-focus-text.md) | **Landed.** Keys, focus, claims declared in advance, and the text-editing widget that depends on all of it |
| [navigation.md](reference/navigation.md) | **Landed.** Landmarks, addresses and reveals: naming a node rather than a route, and arriving by the widgets' own commands rather than synthesised input |
| [transfer.md](reference/transfer.md) | **Landed.** Drag and drop and cut and paste as one mechanism with two ways in — one resolution, several sources, and why what is shown is always what will happen |

### Putting marks on the screen

| | |
| --- | --- |
| [drawing.md](reference/drawing.md) | **Landed**, §8 still ahead. `Picture`, the four-mark alphabet and what bounds it, and the two sinks that make one drawing honest on screen and in a file. §7 is the decision of when it should be a marched surface instead |
| [typeset.md](reference/typeset.md) | **P0–P4 landed; P5 (atlas face) and P6 (demo panel) outstanding.** Structured non-editable rich text over an open set of composable boxes, sized as ratios and tone-mapped in log space into a legible pixel range |
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
| [automation.md](reference/automation.md) | **A0–A5 landed.** The instrument: an out-of-process agent running the real window, real Vulkan and real frame loop, with a pointer that never teleports and one correlation log that explains what happened |
| [automation-cli.md](reference/automation-cli.md) | **Landed** (§10). The design behind the socket client, and the retirement of per-application `--capture` that shipping it unblocked |
| [../guides/ottermate.md](guides/ottermate.md) | The user guide for that client → |

---

## guides/

| | |
| --- | --- |
| [ottermate.md](guides/ottermate.md) | **`ottermate`** — driving a running application from a shell. Worked examples captured against a real app, the exit statuses, and the three things that will bite you |

---

## plans/

| | |
| --- | --- |
| [todo.md](plans/todo.md) | The register of deferred work, each item with enough context to pick up cold. Nothing in it is a bug in shipped behaviour |
| [gui-decomposition.md](plans/gui-decomposition.md) | **Not started, and the cost is measured.** `Gui` was 1,929 lines when the plan was written and is 2,210 now. The target is under 400, with every other concern a component under 300 |
| [loudness.md](plans/loudness.md) | **Stage A done; B1–B4 and B6 done, B5 held deliberately; C–E not started.** The framework explains itself well to a reader and does not yet complain to a consumer who asked for something it cannot do |
| [architecture-proof.md](plans/architecture-proof.md) | **Plan, not started, and currently blocked:** it rests on Elektro-Q for the wire, which has no sibling checkout on this machine |
| [reference-rewrite.md](plans/reference-rewrite.md) | **The locator.** Every rename and move from the 2026-09-14 restructure, with the inbound-reference count for each, so the javadoc and comment pass can be done deliberately rather than by blind find-and-replace |
