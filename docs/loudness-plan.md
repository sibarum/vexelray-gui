# The loudness plan

`calculator-vexel-demo/docs/retrospective.md` is four milestones of a real application read back over the
framework it was built on. Twenty-six findings, and **not one of them an architectural problem** — the seams
are right. What is missing is a layer of *loudness* over them: the framework explains itself very well to a
reader and does not yet complain to a consumer who has asked for something it cannot do.

This is the plan for that layer, plus the three structural fixes the retrospective identified alongside it. It
spans four repos (`vexelray`, `vexelray-gui`, `supirvast`, `calculator-vexel-demo`) and lives here because this
is where the consumer-facing surface is.

**One correction to the retrospective's own framing**, resolved in §D3: its proposal 4 cites `gui.input()` as
the discoverability win, but [gui-decomposition.md](gui-decomposition.md) §2 deliberately leaves input on
`Gui`, for a good reason. The decomposition as planned does not close FN-1's discovery problem, and needs a
smaller, different answer beside it.

---

## The ordering, and why it is not the retrospective's

The six proposals do not all serve the same end, and three of them are *instruments*. An instrument is not one
item among six — it is the thing the other five are checked against, and the retrospective's own last section
is three stories about trusting a measurement that could not have detected anything. So:

**A → B, then C, D and E in parallel.** Stage A first because B2 and B5 cannot be verified without A2. If
appetite runs out, **A + B4 + B5** is the smallest set that pays for itself, because those two fix a class of
afternoons for every future consumer rather than one finding each.

---

## Stage A — the instruments

Blocking. Nothing downstream is trustworthy until these exist.

**Status: done.** `ConeMarchSmoke` draws 12,403 px of an 18-cone chain from a storage buffer, with both controls
at zero — the first execution of that chain inside this stack.

| | Work | Where | Size |
|---|---|---|---|
| **A1** ✅ | `OffscreenRenderer.render` gains `setLayouts` + `descriptorSets`. New overload; the existing ones delegate. | `vexelray-vulkan` | S |
| **A2** ✅ | `ConeMarchSmoke` beside `StrokeMarchSmoke` — pack a known cone run, march it, count non-sky pixels. | `vexelray-vulkan` test tree | M |
| **A3** ✅ | The negative control becomes part of a smoke's *shape*: `Smoke`, `SmokeTest`, and `StrokeMarchSmoke` retrofitted onto it. | same | S |
| **A4** ✅ | `vexelray-gui-demo`'s `commandlineArgs` gains `${demo.jvmArgs}`. | `vexelray-gui-demo/pom.xml` | S |

**A1** is FN-18. `OffscreenRenderer.render` takes no descriptor set, so the one render-and-count-pixels path in
the stack cannot exercise a buffer-driven field — the instrument that exists for exactly this question could
not be pointed at it. Two parameters.

**A2** is FN-13. `ConeField`, `GuiApp.storage` and the push-constant form of `SampledColorTarget.renderInto`
are a chain built one link at a time, each naming this consumer in its javadoc, and the calculator is the first
program to execute any of it. `ConeField`'s five existing tests prove the SPIR-V is valid, geometry-independent
and correctly packed; none of them draws.

**A3 is the item to argue for hardest.** The retrospective derives exactly the right rule from three of its own
failures — *before trusting a measurement, move one knob to an absurd value and confirm the number moves* — and
then leaves it as discipline. Three out of three were discipline failures. So encode it in the shape of a smoke
instead: **every smoke runs twice, once real and once with one knob absurd, and fails if the two numbers
agree.** A smoke that reports the same count with the camera turned away is not reporting anything, and it
should say so on the run where that is cheap to notice rather than on the afternoon where it is not.

**A4** is the same trap that produced the lying perf sweep, still live in this repo: a `-D` on the `mvn` command
line sets a *Maven* property, and `exec:exec`'s `commandlineArgs` is a fixed string, so nothing reaches the JVM.
`calculator-vexel-demo`'s pom already carries the fix and its comment explains it.

### What building it taught, which is the argument for A3 restated

The first `ConeMarchSmoke` control was "camera turned away", written as `yaw + π`. It drew **11,990 px against
the subject's 12,403** — because adding π to a single yaw orbits the eye to the far side *and* turns it back
towards the origin. That is not a turned-away camera, it is a valid second view of the same subject.

`Smoke` passed it, correctly by its own rule: the number moved. So the rule as the retrospective states it —
*confirm the number moves* — is necessary and not sufficient, and the gap between "different" and "absurd" is
where a control quietly stops being one. The fix separates eye placement from view direction so the knob can be
absurd, and the reasoning is recorded on `camera(...)` where the next person to add a control will read it.

Two other things the run said out loud, both by eye, which is what having the instrument buys: the marched
chain is **grey** (FN-25, §B2) and it is **dark** (FN-19, §B5).

---

## Stage B — the loudness layer

The five silent-degradation findings (FN-14, FN-16, FN-19, FN-22, FN-25). Their common factor is not that they
are bugs; it is that **each one produces a plausible picture** — nothing throws, nothing warns, and the output
is wrong in a way that reads as a taste decision.

Each fix is one boolean and one warning. Five ad-hoc `System.err.println`s across three repos is five things to
keep in step, so they share a mechanism.

**Status: B1–B4 and B6 done; B5 not started.** The colour-space change is held deliberately — see below.

### B1 · `vexelray-diagnostics`

A new zero-dependency module, one class:

```java
Diagnostics.dropped(String key, String what, String why)   // warn-once per key
Diagnostics.recorded()                                     // what fired, for tests
```

On by default, silenced by `-Dvexelray.diag=off`. Reachable everywhere it is needed along the existing
`gui-core → vulkan → technique-sdf` chain. Roughly eighty lines.

Not in `vexelray-core`: nothing depends on that module today, and a dependency on it drags `FrameGraph` and its
neighbours in for one utility.

### B2 · FN-25 — a technique that cannot render the colour it was handed

`ConeField.compose` passes `null` as the albedo function unconditionally. The check is
`SurfaceCompiler.compile(scene.surface()).hasAlbedo()`. `compose` deliberately does not compile the surface —
but it runs once per *pipeline*, not per frame, so a diagnostic can afford it. If it ever cannot, the cheaper
answer is a `Surface.declaresColour()` predicate over the tree.

**This downgrades FN-25 from silently wrong to loudly refused. It does not make the cone field carry colour**,
which is a real feature — a colour channel in the buffer layout and an albedo function that reads it. FN-25
cost more than anything else in the notes, so that feature is scheduled immediately after this stage rather
than folded into it.

### B3 · FN-14 — a capture of another device's image

`GuiApp.bind` already substitutes the placeholder for a handle that is not a `SampledImage`. It must also catch
a `SampledImage` belonging to a *different device* — which is the capture case — and say so. Small
prerequisite: `SampledImage` exposes device identity.

### B4 · FN-5 and FN-16 — glyphs the font does not have

The MSDF mojo already writes the requested charset and reads the produced JSON. Diff them, log the uncovered
codepoints, and **run the diff on the `isUpToDate` short-circuit path too** — otherwise the warning vanishes on
the second build, which is the build anyone is actually looking at. Add `<failOnMissingGlyphs>`, default false.

Highest-leverage item in the stage: a set difference over data the plugin already holds, closing two findings
for every consumer of the framework, permanently. `x → (Re, Im)` drew as `x □ (Re, Im)` because nothing said
Noto Sans has no arrow.

**What it says about the primary atlas, on the first run:** 806 of 1,727 requested codepoints are absent —
arrows (`U+2190..U+21FF`), box drawing (`U+2500..U+257F`) and dingbats (`U+2700..U+27BF`) *entirely*, and all
but two of the maths operators. The mono face is complete at 191.

The pom comment claimed all of those. It has been corrected in the same commit, because a comment that outran
its implementation is the same defect as a doc that does — and this one is the direct cause of FN-16. The
ranges stay in the request: asking costs nothing, a face that carries them can be added later, and the build
now prints the difference on every run rather than the claim living in a comment nobody can check.

### B5 · FN-19 — the colour space

Where a doc and the code disagree, pick one, and prefer changing the code. `SdfScene.Rgb` says linear; nothing
downstream applies an OETF and the attachment is `_UNORM`. Apply the OETF in `SdfComposer`'s fragment before the
write, because everything downstream — canvas blend, swapchain — reads those bytes as display-space.

**Flag:** every marched picture in every repo gets brighter, the designer's reference screenshots included.
That is the correction landing rather than a regression, but it wants to be a deliberate commit with the
re-takes in it.

**Held, and not only for that reason.** `SdfComposer` is being actively rewritten for `Scalar`/`ParamBlock` at
the time of writing, and the OETF goes in the fragment this composes. Two people editing one shader-building
method is how a colour-space change and a parameter-block change become one unreviewable diff. It is the last
item in the stage and the only one that changes every existing picture, so it loses nothing by waiting for that
work to land.

### B6 · each warning gets a test that asserts it fired

`Diagnostics.recorded()` exists for this. A warning nobody has watched fire is one more instrument trusted
without proof, which is the exact failure the whole stage exists to stop.

---

## Stage C — one fact, one implementation

FN-12 and FN-22. `SdfComposer.cameraBytes` hands six floats to generated SPIR-V and nothing exposes the inverse,
so anything drawn *over* a marched viewport re-derives the projection in Java. The calculator's `Lens` is that
re-derivation: a transcription of a shader expression, in a different language, in a different repo, with
nothing holding the two together. A mismatch does not fail — labels drift off their axes as the camera turns,
which reads as a rendering glitch and is a units bug.

The retrospective defends `LensTest` as a good test that is still two transcriptions. It does not have to be:
**`supirvast` has `CoreToTruffle`, a second backend that runs the same `Function` the SPIR-V is lowered from, on
the CPU.**

- **C1.** `SdfComposer.project(world…) → uv` beside `cameraBytes`. The framework hands out the inverse it
  already implies.
- **C2.** `LensTest`'s hand-written Java `primaryRay` goes away; the round-trip runs against the *shipping*
  expression through the CPU backend. The forward transcription stops existing rather than being tested around.
- **C3.** The calculator's `Lens` becomes a call into C1.
- **C4.** The rule, into [architecture.md](architecture.md): **any value the framework serialises into generated
  code ships with a framework-side inverse**, because the consumer will need it and will otherwise write it
  themselves, once per application, slightly differently. The inventory says `cameraBytes` is the only
  production case with a non-trivial inverse; `MsdfShader`'s push constant is a screen scale the canvas already
  holds.

---

## Stage D — discoverability, and two house rules

Ten of the twenty-six findings are "this doesn't exist". Each is individually reasonable; the cost is not the
gap but **finding out**. FN-1 was established by reading all 91 public methods on `Gui`, because there is no
smaller place to look.

### D1–D2 · finish the decomposition, and cap it

Steps 1–4 of [gui-decomposition.md](gui-decomposition.md) — roughly 650 lines out and the two concerns that make
`frame()` unreadable — then expose `gui.metrics()`, `gui.layoutModel()`, `gui.nav()`, `gui.trees()`. The
method-and-field cap guard is nearly free: `vexelray-gui-architecture` already has `Bytecode`.

### D3 · the input facade, which the decomposition deliberately will not give

FN-1's cost was reading 91 methods to establish that `onWheel` does not exist, and the ~30 input methods are
precisely what §2 says should *not* move — correctly, since they hold no state and make no decisions, and moving
them achieves nothing.

So add `gui.input()` as a **facade view**: a grouping accessor that delegates and owns nothing. That is a
different thing from an extraction, it does not contradict §2's reasoning, and it is what actually turns "does
the framework do X?" into a five-method scan.

### D4 · `Node.name(String)`

An explicit accessible name overriding the derivation in `Gui.accessibleName`. Today a name is
own-text-else-descendant-text, so an icon tile is named after its glyph. With this, `Rail.item(key, icon, title,
page)` names its tile with the `title` it was already given, `find Layers` works with nothing added, and FN-23
closes.

### D5 · the two house rules, written down

- **Every widget hands out a handle for every distinct surface it composes** — to style, not restructure. `Tabs`
  does this; `Rail` does it for two of three. Add `Rail.tile(key)` and `Card.headerControl(Node)`.
- **Every interactive element a widget creates gets an accessible name from whatever the application called
  it.**

Into the widget package javadoc as a checklist, not a convention.

### D6 · the remaining gaps, ranked by evidence

`Gui.onWheel` (hit twice), `Button`/`Toolbar`, per-edge border, pointer-anchored readout, driver modifiers,
published headless fixture, per-surface material, icon vocabulary — the last gated on B4, since there is no
point specifying icons before the atlas can report what it covers.

---

## Stage E — timing

### E1 · FN-24, structurally rather than by documentation

`settle` is blind to a handler in flight because `frameOwed()` only knows about published mutations, and the
first `find` inside any `Rail`/`Tabs`/`Popout` page therefore races: all three build lazily inside a
worker-thread handler.

`InputDispatcher` funnels every handler through one field, `handlerExecutor`. Wrap it to count
submitted-but-not-returned handlers and fold that into a new `Gui.quiet()`, leaving `frameOwed()`'s current
meaning intact. Settle then covers the lazily-built page instead of the doc apologising for it — and a handler
that hangs makes settle time out loudly, which is the correct answer.

### E2 · FN-11, the fence on the calling thread

Measured at **13.4 ms of stalled GUI thread per marched frame at 640×400** — 80% of a 60 Hz budget, serially,
for work that would cost latency rather than frame rate if it were asynchronous. The source comment on
`SampledColorTarget.renderInto` already names the fix and names the obstacle: the contract promises the image is
ready when it returns.

So the contract changes visibly — `renderIntoAsync` plus `ready()` — rather than the same method quietly meaning
something else. Persist the pool and fence per target, double-buffer the image, sample the last completed one.
Worth roughly a doubling of the plot's resolution.

---

## What this plan does not touch

The retrospective's last section, and it is the reason the rest of it is worth acting on: `Rail` + `Inspector` +
`Property`, the lock-free read-model, the automation socket, and `Surface.Stroke`'s geometric guarantee carried
the project and are not on this list. Four milestones of a real application produced twenty-six findings and no
architectural problems. This is a layer to add, not a design to revisit.
