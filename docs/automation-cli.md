# Driving an app from the command line: `vexelray-gui-automation-cli`

Companion to [automation.md](automation.md), which designs the instrument this document ships. Two things are
proposed together because neither is worth doing alone: **a client for the automation protocol**, and **the
retirement of per-application `--capture`**.

The argument for pairing them is not tidiness. Today the stack offers two ways to get a picture of a running
application, and **discoverability runs opposite to correctness**: `--capture` is named in every application's
usage line and cannot photograph what the application draws, while `shot` over the automation socket is correct
and has no shipped client. Removing the first without shipping the second replaces "easy to find but wrong"
with "right but unreachable", which is the same defect wearing different clothes.

---

## 1. The gap, and the evidence for it

[automation.md](automation.md) §8 marks **A4 — Protocol + CLI** as landed. What landed is the protocol and the
server: `Automation` (the verbs), `AutomationServer` (loopback, one connection at a time), and
`vexelray-framework-automation`'s `Driver` (the wiring, off unless asked). There is **no client** anywhere on
the stack — no module, no jar, no script. The section's own usage example is the client:

```bash
printf 'find Save\nclick save\nsettle\nshot after.png\nquit\n' | nc localhost 7654
```

`nc` is not present on the Windows development machine this stack is built on, and the example is the only
description of the wire format outside the server's source.

**The evidence is a real session.** On 2026-09-10, an agent repairing the calculator's build against a changed
`SdfScene` needed to prove the marched viewport still rendered. `--capture` could not answer — it draws the
placeholder for a marched target, by construction (§2) — so the automation socket was the only route. Reaching
it took: discovering the port from `netstat`, reading this repo's docs to learn that a reply is terminated by a
line containing only `.`, and hand-rolling a `System.Net.Sockets.TcpClient` client in PowerShell. It worked
first time. **That is the problem rather than the reassurance**: the instrument was reachable only by
reconstructing it from its own design notes.

> **Rule: an instrument that has to be rebuilt at the point of use is not shipped.** The protocol being simple
> enough to reimplement in twenty lines is an argument for writing those twenty lines once, in this repo, not
> for making every caller write them.

---

## 2. The other half: `--capture` is the discoverable thing that does not work

The framework has already reached this conclusion and acted on it. `RunMode` once had a third constant, and its
javadoc records the removal and the reason: a capture is

> correct about the chrome and silently wrong about the content [...] worse than no capture at all: a visual
> record that lies is one somebody will trust.

The mechanism, for the record, because it is not a bug to be fixed: `GuiApp.capture` is `static` and builds its
**own** instance and device for the occasion, while a `SampledColorTarget` belongs to a `GuiApp` **instance** on
that application's device. A target from another device yields a descriptor set the pipeline cannot bind, so any
tree carrying a marched or otherwise device-bound viewport photographs the framework's placeholder. Nothing
fails. `WindowInstrument.screenshot()` replaces it: a real window on the application's own device, per-window,
reachable by hand from the framework-owned title bar, and the same capability the `shot` verb calls.

### Where it still lives

| Place | State |
|---|---|
| `vexelray-framework` `RunMode` | `CAPTURE` removed, with the reasoning above recorded in place |
| `vexelray-designer`, `text-editor-vexel-demo` | clean — their javadoc speaks of `--capture` in the past tense |
| **`calculator-vexel-demo`** | `Capture.java` (4 scenes), `CaptureTreeTest` (3 tests), `--capture` pre-dispatch ahead of `VexelApplication.run` |
| **`mainframe-template`** (`vexel-desktop`) | ships `Capture.java` and the `App.java` dispatch that calls it |
| `vexelray-gui-demo` `Demo.java` | calls `GuiApp.capture` directly — chrome-only by nature, and **staying** (§7) |

**The template is why this keeps coming back.** Deleting the calculator's copy alone leaves the next scaffolded
application born with one, carrying the same javadoc explaining why it is richer than the framework's — an
argument that was true when the framework had a capture mode to be richer than.

---

## 3. Requirements — the client

- **R1 — Attach by default.** Connect to a running application's socket, run commands, print replies. Default
  `AutomationServer.DEFAULT_PORT` (7654), `--port` to override.
- **R2 — Optionally launch.** `--launch <command…>` starts the application, drives it, and shuts it down: one
  command in, pictures out. This is the affordance that made `--capture` attractive and it is the reason
  removing `--capture` is not a regression in convenience.
  - **The port is read from the child's stdout.** `Driver.open` already prints `automation: localhost:<port>`,
    so launch mode needs **no new framework API** and works with `--automation=0` (a free port), which is what
    makes concurrent runs safe.
- **R3 — Zero dependencies.** A socket, line I/O, and `ProcessBuilder`. Nothing from `-core`, nothing from the
  framework, no Vulkan. So it runs as a standalone executable jar with none of the stack on its classpath —
  the difference between a tool and a test fixture.
- **R4 — Both input forms.** Commands as arguments (`vexel shot out.png`) and a script file or stdin
  (`vexel --script panels.txt`), because a scene ladder is a file and a one-off is not.
- **R5 — Exit status must carry the verdict.** Non-zero if any reply began `err`, and `settle`'s timeout is an
  `err` (see `Automation.settle`, which says so rather than swallowing it). A CLI that returns 0 after a failed
  `shot` recreates the exact failure mode §2 is about: a script that reports success for a picture nobody took.
- **R6 — The reader lives beside the writer.** Same repo as the protocol it speaks (§4).
- **R7 — Never a runtime dependency of a shipped application.** automation.md §9 keeps automation a
  development instrument; a separate zero-dep artifact is what enforces that structurally rather than by
  convention.

---

## 4. Where it lives, and why not elsewhere

**A new sibling module, `vexelray-gui-automation-cli`, with an empty dependency block.**

*Beside the protocol, because this repo has already made this exact call once.* automation.md §4, on derived
views: `csvview` comes from `sibarum.probe.CsvView`, **beside the writer, so it cannot drift from the format**.
A protocol client is a reader of the automation wire format in precisely the way `csvview` is a reader of the
CSV format. One repo means a protocol change is one commit touching both sides, and the client cannot advertise
a verb the server does not implement.

*Not inside `vexelray-gui-automation`, because that module has an invariant worth keeping.* §5: it depends on
`-core` only and speaks "nothing but bus message types", so that when M1–M3 land "it becomes a remote peer
across an `ElektroBridge` with no change to its own code". A `main`, argv parsing and `ProcessBuilder` in there
is weight linked into every application that merely wants to *be* driven. The server is a bus peer; the client
is a tool.

*Zero dependencies is an existing deliberate pattern here* — `vexelray-gui-plot` has a genuinely empty
dependency block for its own reasons.

| Alternative | Why not |
|---|---|
| Inside `vexelray-gui-automation` | Breaks the §5 minimality invariant; links a client into every driveable app |
| `vexelray-framework-automation` | Wrong layer — that module is the *wiring* and pulls in shell + gui-automation. The client need not know the app uses the framework at all, only that it speaks the socket |
| `mainframe` | Splits client from protocol — the drift `csvview` is co-located to prevent. Invert it: mainframe's console may *depend on* this module |
| A standalone repo | Guarantees drift; a seventh checkout for a few hundred lines |

---

## 5. Requirements — the verbs `--capture` has and automation does not

The calculator's `Capture` has four scenes: `zoom` (a 7-step ladder), `smallest` (the tree at exactly
`minSize`), `panels` (every rail panel), and one named panel. Three capabilities are missing before those can
move to the socket.

- **V1 — `zoom <factor>`.** `Gui.zoom(float)` already exists; there is no verb. **It must clamp to the
  application's own `Appearance.ZoomRange`**, not the library default — a verb that photographs a zoom level
  the application refuses is the same class of lie as §2, and `Capture`'s own javadoc records nearly shipping
  exactly that when it built its tree by a second route.
- **V2 — `resize <w> <h>`.** **This is new capability, not just a verb**: `WindowControls` today offers
  `minimize`, `toggleMaximize`, `maximized`, `close` and `capture`, and nothing that sets a size.
  - Units are an open question (§9). `minSize` is declared in **em**, so a pixel-only `resize` cannot express
    "the tree at exactly its minimum" without the caller duplicating the em→px conversion — which is the
    two-literals hazard `Calculator.MIN_W_EM` exists to avoid.
- **V3 — `settle` must consult the timeline.** Today it gates entirely on `gui.frameOwed()` and a layout-commit
  signal; it never asks the Kron clock. **An application mid-fade with no frame currently owed returns `ok`
  immediately.** Since `Rail` began animating selection, `click rail.layers` → `settle` → `shot` therefore
  photographs a panel part-way through its fade and reports success — verified in the field, three seconds
  before the transition completed. `Automation` holds a `Gui` and not a `KronoGui`, so closing this needs new
  API in this module rather than a fix inside `settle`.
  - **The reference implementation, preserved here because its source has been deleted.** The calculator's
    `Capture` solved the headless half of this before it was retired (§7), and the shape is what matters:

    ```java
    /** One step of settle, about a frame at 60Hz. */
    private static final Dur SETTLE_STEP = Dur.ms(16);
    /** How many of those before a still is taken anyway — a second of logical time. */
    private static final int SETTLE_STEPS = 64;

    private static void settle(Shell shell) {
        KronoGui krono = shell.krono();
        for (int step = 0; step < SETTLE_STEPS && !krono.isQuiescent(); step++) {
            krono.tick(Dur.ns(krono.kron().now().nanos() + SETTLE_STEP.nanos()));
        }
    }
    ```

    **Step, do not jump**: `Driven.maxAdvance` bounds how far one tick may carry logical time, so a single
    large tick is a *forgiven* gap rather than a replayed one and leaves the ramp part-way. **Bounded rather
    than looped until quiet**: a tree that never goes quiet is a bug the instrument should photograph rather
    than hang on. Both properties were learned the hard way and are easy to lose on a rewrite.

    A socket `settle` differs in one respect — it drives a *live* clock rather than a `Driven` one, so it waits
    for quiescence instead of advancing time. `isQuiescent()` is the predicate either way, and it is the thing
    `Automation` currently cannot reach.

---

## 6. Ordering, and the one sequence to avoid

1. **The client** (§3, §4) — fixes the reachability defect, changes no existing behaviour.
2. **V1–V3** (§5) — closes the capability gap.
3. **Remove `--capture`** (§7) — calculator and template together.

**3 before 2 is the sequence to avoid.** The calculator's `smallest` scene has twice caught a defect nothing
else did — most recently a bottom key row clipped at minimum size after a tab strip was added, found because
the minimum is photographed rather than chosen by eye. Deleting that before `resize` exists trades a working
instrument for a flakier one and loses a regression test that has earned its place.

---

## 7. What removal touches

**Goes:**

- `calculator-vexel-demo`: `Capture.java`, `CaptureTreeTest`, the `--capture` pre-dispatch in
  `Calculator.main` and the javadoc defending it, and the capture commands in `docs/`
- `mainframe-template` `vexel-desktop`: `Capture.java`, the `App.java` dispatch, the usage lines

**Stays, deliberately:**

- **`GuiApp.capture`.** `vexelray-gui-demo` uses it for chrome ladders of a tree with no device-bound content,
  which is exactly the case where it tells the truth. `RunMode`'s javadoc already reserves it for an
  application that "specifically wants a chrome-only still, knowing what it is getting". Removing the *mode*
  and the *per-application instruments* is the proposal; removing the *primitive* is not.
- **`WindowInstrument.screenshot()`** and the title-bar affordance — the by-hand half of the same capability.

The three `CaptureTreeTest` assertions are worth re-homing rather than deleting: they check that a tree built
through the wiring carries the application's own zoom range, its theme, and that every scene name matches a
real rail panel. The first two are statements about `VexelApplication.tree` and belong to the framework's
tests; the third dies with the scene list.

---

## 8. Non-goals

Carried from automation.md §9 and repeated because a CLI invites all of them: **an MCP server** (a later
wrapper, not a dependency); **visual diffing or image assertions**; **recording and replaying human sessions**;
**multi-peer fan-out**; and **making automation a supported product feature** — it stays a development
instrument built from published contracts.

Also out of scope here: **an application lifecycle manager.** `--launch` runs a command line it is given and
reads a port off its stdout. Knowing how to start each application on the stack — Maven exec, jar, native
binary — is `mainframe`'s territory if it is anyone's, and R3 exists so this tool never grows that knowledge.

---

## 9. Open questions

- **`resize` units.** em, px, or both with a suffix (`resize 46em 30em`)? em is what makes `smallest`
  expressible without duplicating a conversion, and px is what a bug report quotes.
- **Whether `zoom` and `resize` belong to `WindowControls` or to a wider window API.** Both are things a person
  can already do by dragging a frame, which argues they are window capabilities that automation merely reaches,
  not automation features.
- **Distribution.** Executable jar is the floor. A `vexel` / `vexel.cmd` wrapper makes it a command rather than
  an incantation — worth deciding where such a script lives, given this repo ships no scripts today.
- **Whether the client should offer a `capture`-shaped convenience** — a named ladder of shots in one
  invocation — or leave that to script files (R4). A convenience that reintroduces scene names in the client is
  how `--capture` grew the first time.
