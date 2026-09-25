# Driving an app from the command line: `vexelray-gui-automation-cli`

Companion to [automation.md](automation.md), which designs the instrument this document ships. Two things are
proposed together because neither is worth doing alone: **a client for the automation protocol**, and **the
retirement of per-application `--capture`**.

The argument for pairing them is not tidiness. Today the stack offers two ways to get a picture of a running
application, and **discoverability runs opposite to correctness**: `--capture` is named in every application's
usage line and cannot photograph what the application draws, while `shot` over the automation socket is correct
and has no shipped client. Removing the first without shipping the second replaces "easy to find but wrong"
with "right but unreachable", which is the same defect wearing different clothes.

> **Status.** Step 1 of §6 has landed: `vexelray-gui-automation-cli`, the client, with `--launch` — and,
> since it was reachable only by typing the full path of a versioned jar, an `ottermate` wrapper beside it
> (§10). Step 2 has not: none of the verbs of §5 exist.
>
> **Step 3 happened anyway, which is the sequence §6 says to avoid.** On 2026-09-10 `calculator-vexel-demo`
> removed `Capture.java`, its `CaptureTreeTest`, the `--capture` pre-dispatch and the scene ladder, on §2's
> reasoning rather than on this document's ordering — and the cost §6 predicted is the cost that arrived:
> `smallest` and `zoom` have no replacement, so the minimum-size picture that has twice caught a clipped
> bottom row cannot be taken at all until V2 exists. `mainframe-template`'s `vexel-desktop` still carries its
> copy, so §7's removal list is half done and the stack sits in the state that section was written to keep it
> out of: one application with no capture and no `resize`, one with both.
>
> Everything below is the design; §10 records what shipped and where it differs.

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
- **R4 — Both input forms.** Commands as arguments (`ottermate shot out.png`) and a script file or stdin
  (`ottermate --script panels.txt`), because a scene ladder is a file and a one-off is not.
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
`minSize`), `panels` (every rail panel), and one named panel. Three capabilities were missing before those can
move to the socket; V3 has landed, and V1 and V2 remain.

- **V1 — `zoom <factor>`.** `Gui.zoom(float)` already exists; there is no verb. **It must clamp to the
  application's own `Appearance.ZoomRange`**, not the library default — a verb that photographs a zoom level
  the application refuses is the same class of lie as §2, and `Capture`'s own javadoc records nearly shipping
  exactly that when it built its tree by a second route.
- **V2 — `resize <w> <h>`.** **This is new capability, not just a verb**: `WindowControls` today offers
  `minimize`, `toggleMaximize`, `maximized`, `close` and `capture`, and nothing that sets a size.
  - Units are an open question (§9). `minSize` is declared in **em**, so a pixel-only `resize` cannot express
    "the tree at exactly its minimum" without the caller duplicating the em→px conversion — which is the
    two-literals hazard `Calculator.MIN_W_EM` exists to avoid.
- ~~**V3 — `settle` must consult the timeline.**~~ **Landed.** `Automation` takes a `Timeline` — this module
  still depends on `gui-core` alone — and `settle` waits for the frame loop, then for the timeline to go quiet,
  then for the loop once more. A host hands over `krono::quiescentAtLastTick`, and `vexelray-framework`'s driver
  does. Two things the implementation had to get right, recorded because both are easy to lose:
  - **The sample, not the question.** `Kron.isQuiescent` walks effects' dependencies the timeline mutates, and
    the driver answers on its own thread; so `KronoGui` takes the answer at the end of each tick and publishes
    it.
  - **A post since the tick counts as busy.** The field case is a click handler starting a fade on a worker and
    changing nothing else: no frame is owed, the last sample says quiet, and the fade is sitting in the kernel's
    inbox. `KronoGui` counts every post through `onTimeline` — `ramp` included, which used to post straight to
    the kernel — and a sample stands only while no post has arrived since.

  A timeline that never goes quiet — a repeating cue — is an `err` after 10 s naming `await` as the
  alternative, which is the socket's form of "bounded rather than looped until quiet" below. What was found, as
  it was found: Since `Rail` began animating selection, `click rail.layers` → `settle` → `shot` therefore
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
    for quiescence instead of advancing time. `isQuiescent()` is the predicate either way; `Automation` reaches
    it now as a `Timeline`.

---

## 6. Ordering, and the one sequence to avoid

1. ~~**The client** (§3, §4)~~ — **landed**; see §10. Fixed the reachability defect, changed no existing
   behaviour.
2. **V1–V3** (§5) — closes the capability gap. **V3 landed; V1 and V2 next.**
3. **Remove `--capture`** (§7) — calculator and template together. **Half done, out of order**: the
   calculator's went on 2026-09-10; the template's is still there.

**3 before 2 is the sequence to avoid.** The calculator's `smallest` scene has twice caught a defect nothing
else did — most recently a bottom key row clipped at minimum size after a tab strip was added, found because
the minimum is photographed rather than chosen by eye. Deleting that before `resize` exists trades a working
instrument for a flakier one and loses a regression test that has earned its place.

**And it is the sequence that was taken**, so this paragraph is now a record rather than a warning. The
removal was argued on §2 — a capture of the calculator's marched viewport is a picture that lies, and that is
true — but §2 is the case for *retiring* the instrument, not for retiring it *first*. The two scenes that had
earned their place were not device-backed and were not lying about anything; they went with the scene ladder
they happened to be written in. Nothing photographs the calculator at its minimum size today, which
`Calculator.MIN_W_EM` records at the constant V2 will have to be handed. **The order this section argued for
is still the right one for the template**, which is the copy still standing.

---

## 7. What removal touches

**Goes:**

- ~~`calculator-vexel-demo`: `Capture.java`, `CaptureTreeTest`, the `--capture` pre-dispatch in
  `Calculator.main` and the javadoc defending it, and the capture commands in `docs/`~~ — **done, 2026-09-10,
  ahead of V1–V3.** All three `CaptureTreeTest` assertions were re-homed rather than deleted, as this section
  asked — though into the calculator's own `WiringTreeTest` and not where it guessed: the panel-name one did
  not die with the scene list, because the names a driving script types are the same names, and the other two
  reach the real wiring through `VexelApplication.tree` from here as well as they would from the framework
- `mainframe-template` `vexel-desktop`: `Capture.java`, the `App.java` dispatch, the usage lines — **still
  there**, and §6 is why it should stay there until V2 exists

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
- ~~**Distribution.**~~ **Settled at the floor, and the floor turned out to be enough.** With an empty
  dependency block the ordinary jar *is* the executable one — a `Main-Class` manifest, no shade, no assembly,
  no `Class-Path` to go stale. **Still open:** a `ottermate` / `ottermate.cmd` wrapper to make it a command rather
  than an incantation, and where such a script lives given this repo ships none today. Nothing about the jar
  forecloses one.
- ~~**Whether the client should offer a `capture`-shaped convenience**~~ — **no.** A ladder is a script file
  (R4), and the one concession is that the client skips blank lines and `#` comments so the file can say what
  each scene is for; neither ever reaches the application. Naming scenes in the client is how `--capture`
  grew the first time, and a scene name is the application's knowledge rather than the tool's.
- **New, from building it: what a script should do after an `err`.** Shipped as stop-at-the-first, with
  `--keep-going` to override, on the grounds that a ladder is a sequence in which each step assumes the last
  one worked — carrying on past a `click` that hit nothing leaves every later step acting on a window
  that is not in the state the script assumes, and photographs it. Unproven against a real ladder, because the real ladders still live in
  `Capture.java` (§7) and have not moved yet.

---

## 10. What landed

`vexelray-gui-automation-cli`, a sibling module with an empty dependency block, main class
`dev.vexelray.gui.automation.cli.Ottermate`. Six classes and no surprises: `AutomationClient` (the wire, and the
part worth depending on), `Reply`, `Options`, `Launch`, `Session`, `Ottermate`.

```
ottermate [options] [command...]     one command from the remaining arguments
ottermate [options] --script <file>  one command per line; - is stdin
ottermate [options]                  commands from stdin (a prompt, at a terminal)

  --port <n>            attach to this port (default 7654)
  --script <file|->     read commands from a file, or - for stdin
  --launch <command...> start the application, drive it, shut it down; takes the rest of the line
  --launch-timeout <s>  how long it may take to announce a port (default 60)
  --timeout <s>         how long one reply may take (default 120)
  --keep-going          run the rest after a reply that begins err (still exits non-zero)
  --quiet, -q           print only err replies
```

**Requirements, against §3.** R1 attach-by-default, R2 `--launch` reading the port off the child's stdout,
R3 an empty dependency block, R4 both input forms, R5 the exit status, R6 beside the writer, R7 never a
runtime dependency of a shipped application — all as designed. 40 tests, against a hand-written fake that
speaks the frame rather than against the real server, because depending on `vexelray-gui-automation` to test
this would put `-core` on the test classpath of the one module whose whole claim is that it needs none of the
stack.

### The four decisions the design left to the implementation

- **The exit status is five-valued, not two.** `0` all ok, `1` something answered `err`, `2` the command
  line, `3` nothing to drive, `4` it went away mid-run. R5 asked only for non-zero-on-`err`, but a script
  that cannot tell "the application refused" from "the application was never running" has to guess, and the
  guess it makes is usually "retry", which is wrong for the first and right for the second.
- **stdout is replies and nothing else.** This tool's own complaints and a launched application's relayed
  output both go to stderr, so `ottermate tree > tree.txt` gets a tree. That is what makes `--launch` composable
  rather than a demo.
- **`err` is read off the first line only.** A `tree` listing a node whose accessible name contains the word
  is a successful `tree`. Tested, because the obvious `contains("err")` reading passes every hand-written
  example and fails the first real window.
- **Printed text is ASCII.** The em dash in the usage line came out as a replacement character on the Windows
  console this stack is built on, which is a small thing that makes a tool look broken at the first
  impression it makes. The prose in the source keeps its dashes; what reaches a console does not.

### Reachable as a command

`ottermate` and `ottermate.cmd`, beside the module's `pom.xml`. Put that directory on `PATH` once and every
example in this document runs as written.

**They exist because the tool had the defect it was built to fix.** §1 is about an instrument reachable only
by reconstructing it from its own design notes; a jar whose only invocation is
`java -jar .../vexelray-gui-automation-cli-0.1.0-SNAPSHOT.jar` is a weaker version of the same thing — the
version number is in the command, so the command goes stale on a bump, and nothing in the docs matches what
anyone can type. The scripts glob for the jar rather than naming it, for that reason.

Neither is a launcher in the wrapper-script sense: no classpath assembly, no `JAVA_OPTS`, no config file.
That is R3 still paying — an empty dependency block is what makes the plain jar executable, so there is
nothing for a wrapper to do but find it. Anything that accumulated in one would be state `java -jar` does not
have, and the two would start behaving differently.

Two things they do carry, both because getting either wrong reads as a broken tool rather than as a mistake:
the exit status is passed through rather than replaced (`exec` and `exit /b %ERRORLEVEL%`) — the status *is*
the verdict, and a wrapper returning its own would silently decide that every run succeeded — and a missing
jar exits **127** with the one-line build command, that being the shell's own "not installed" rather than any
of the tool's five statuses, none of which fits a socket that was never reached.

### Verified against a real application

Driven end to end against `calculator-vexel-demo` — the real `Driver`, a real window, a real socket:

```bash
ottermate --launch mvn exec:exec -Dautomation=0
# settle / find Save / shot out.png  ->  ok, 132904 bytes, exit 0
```

The picture contains the marched helix rather than the framework's placeholder, which is §2's whole argument
in one file: this route photographs what the application draws. A deliberately wrong path in an earlier
attempt produced `err no picture appeared` and **exit 1**, which is R5 doing the thing it exists for.

Re-run on 2026-09-13 against the calculator as it stands now — `--capture` gone, so this is the only route
left to a picture of it — and it still answers: `ok ...calc-shot.png 206465 bytes`, exit 0, the marched plot
in the frame. What cost that run time was the command line around the tool rather than the protocol; those
traps belong to the user guide and are now in [ottermate.md](../guides/ottermate.md) §3 and §6.

**One of them is a question for this module rather than for its reader.** Every `--launch mvn ...` line in
these docs is a Unix line: on Windows the program is `mvn.cmd`, and `Launch.start` hands the command
straight to `ProcessBuilder`, which does not consult `PATHEXT` — so a bare `mvn` never starts. That much is
just a fact about the platform. What is arguably wrong is the report: it arrives as `Cannot run program`,
routed through the same "nothing to drive" status as a socket that was never opened, when the two have
nothing in common and only one of them is worth retrying. §8 keeps this tool out of knowing how applications
start, and resolving an extension is not that knowledge — but the fix that matters is telling "not found"
apart from "started and said nothing", which is `Launch`'s to make either way.

### The one thing the zero-dependency rule costs

`AutomationClient.DEFAULT_PORT` restates `AutomationServer.DEFAULT_PORT` rather than importing it — two
literals, of the kind this stack usually refuses. It is the price of R3, and it is the sharpest argument for
§4: the two are one commit apart, in one repo, and a change to either without the other is visible in the
same diff.
