# Reactive timing — how this GUI meets Kronometer

> **Status: design note. Nothing here is implemented.** Kronometer is the substrate that owns this
> problem; this note records only what is left for the GUI and input side, and how the seams meet.
> See `kronometer/docs/architecture.md` for the model itself — it is the source of truth.

## What this note is now

An earlier revision derived a design from first principles: Vue's dependency graph for *what depends on
what*, ChucK's strong timing for *when things happen*. That design already exists and is further along
than the derivation was — Kronometer's own tagline is "**ChucK's clock, Vue's graph**", and M0–M4 are
done with tests. The model sections have therefore been replaced by pointers, and what remains is the
**residue**: the parts Kronometer deliberately does not cover, which are ours.

Recording that here rather than quietly deleting it, because the residue is easy to lose sight of once a
substrate looks like it covers everything.

## Answered by Kronometer

| Question we were carrying | Kronometer's answer |
|---|---|
| Where the instantaneous/scheduled line falls | Sharper than we framed it: nothing is *declared*. The graph classifies a value **predictable** or **effectful** from its inputs, and computes a **horizon** (how far it is determined) and **varyingUntil** (how far it still changes). The baton guards side effects, not computation. |
| Time-debt policy (our hardest open question) | `Settlement` per rate domain — `SLIP`, `CATCH_UP`, `SKIP`, `STRETCH`, over `wall(m) = m + slip`, with `maxSlip` → hard resync and `degrade()` as the real remedy. It also reaches our conclusion independently: **slip on an input-driven signal *is* input lag**, so pointer-following motion must not slip while audio must not skip. |
| Clock authority, locally | The `Clock` seam: `Kron.realtime()`, `Kron.virtual()`, `Kron.driven()`. |
| Deterministic tests | `Kron.virtual()` makes a ten-minute scenario a microsecond test, and determinism is *enforced* — anything nondeterministic must declare its logical arrival time or be rejected. `Trace` is the assertion target. |
| Animation as a function of time | `Curve`/`Interp`/`Ease`, plus a distinction we had not drawn: **closed-form** motion is precomputed in one shot, **integrated** motion has a horizon of `now` and is framerate-dependent, so binding it to a dynamic domain is *rejected* rather than merely discouraged. |
| Shreds | Implemented, with the cost measured (577 ns per baton handoff under native-image) and the guidance that follows from it: **anything pure should be a `Signal`, not a `Shred`** — ten thousand simultaneous animations cost zero handoffs. |
| Multi-rate sampling | `Rate` domains with independent grids, `Tempo`/`Ratio` for nested and scaled time, and `Sampled` for cross-domain reads with its one-step latency stated rather than hidden. |
| Per-conduit tracking with MVCC snapshot reads | **Superseded in-process.** One timeline, one baton, one slip means domains cannot drift apart at all, which is a stronger guarantee than snapshot consistency between conduits. The conduit/MVCC model becomes relevant only across processes — see the residue below. |

## The residue — what is ours

### 1. True source timestamps for input

Kronometer can stamp when an event was *observed*; it cannot recover when the key physically went down.
Tactroller currently derives key edges by diffing polled `GetAsyncKeyState`, which can only report
"different from last glance" — never *when*, and never a transition that begins and ends between two
glances.

Feeding a strongly-timed kernel from poll-derived edges wastes its precision, and this is not
theoretical: it is exactly how a modifier latched. Message-based keyboard input (`WM_KEYDOWN`/`WM_KEYUP`
or RawInput with timestamps) in the Windows backend is a **prerequisite for the GUI getting value from
Kronometer's timing**, not an optimisation.

### 2. Paired-edge invariants

That `KeyPressed`/`KeyReleased` are a pair, and that focus gates them, is domain semantics. Neither the
bus nor the kernel should learn it, and neither will. A gate on *delivery* must not desynchronise
*state* — the rule that the latch bug broke — and the invariant that enforces it belongs in tactroller.

### 3. Distributed time

Kronometer is explicitly one timeline: one `m`, one slip, so domains cannot drift. Across processes that
premise fails — there is no global time, only order relative to peers. The representational rules from
the earlier revision survive, but they are **atchung/elektro-Q concerns, not GUI ones**:

1. stamps are opaque and comparable, never arithmetic on raw longs;
2. every stamp carries its origin;
3. expose `happensBefore`, never `compareTo`, so concurrency stays representable;
4. cross-process reads go through an explicit "as of" call from the start;
5. physical for magnitude, logical for order — **the rule that rots silently**, because nothing local
   ever punishes a violation.

None of this is needed while everything is same-machine. It is listed so the door stays open, and so
rule 5 is written down somewhere before it is quietly violated.

### 4. Two standing test rules, learned expensively

- **`HeadlessGui` publishes events past tactroller**, so the routing gates are not in the test path at
  all — which is precisely how the latch bug slipped through a green suite. A scripted user must drive
  `InputPublisher`, not the bus directly.
- **Tests must never inject desktop-wide input.** `SendInput` delivers to whatever window has focus — the
  developer's, not the test's — so it can operate other applications. `RawInputFanoutTest` is opt-in
  behind `-Dtactroller.injectInput=true` for that reason.

## Integration shape (Kronometer M8)

Kronometer's §13 already specifies the seam, and nothing here needs a `kronometer-vexelray` module:

- this repo constructs the `Kron`, ticks it once per presented frame in `INLINE` (so effects have run
  before the frame is submitted), exposes `gui.kron()`, and closes it with the window;
- live input enters the graph through `kronometer-atchung`: a `Topic` **drives a `Cell`**, whose horizon
  is `now`, which is exactly right for input;
- a shred may **await a `Topic`** as a yield point, which is what turns gesture recognition from a state
  machine scattered across callbacks into straight-line code;
- `State<T>` stays the answer to "what is true now"; Kronometer times *when* it changes.

## On Kronometer §15.4 — which repo owns the scenario harness

Recommendation: **split it**, on the evidence of the latch bug.

- **Tactroller** owns the paired-edge invariants and the scripted user's model of the *physical* keyboard
  and per-window focus, with `kronometer-core` as a **test-scope** dependency. Test scope answers the
  hesitation in §15.4 — a shipping repo gains no runtime dependency. The reason it must live there: the
  latch bug was a tactroller-internal invariant violation, and the invariant has to be able to fail a
  tactroller build with no GUI installed. Downstream-only tests mean tactroller can ship broken and only
  a consumer notices, which is what happened.
- **This repo** owns scenarios that need widgets and windows — "open the folder window, focus it, press
  Ctrl+W" — composing tactroller's scripted user with `HeadlessGui` and `Kron.virtual()`.

The division follows the same rule as the invariants themselves: each layer tests what it can break alone.

## What we should do before M8

1. Message-based keyboard input in the Windows backend (residue 1). Without it the kernel's precision
   stops at the process boundary.
2. Paired-edge invariant watchdog in tactroller (residue 2), so the class stays extinct.
3. Route `HeadlessGui` through `InputPublisher` (residue 4), closing the gap that hid the latch bug.
4. Then adopt: `Kron.driven()` per frame, one real interaction moved onto the graph — which is exactly
   what M8 asks a first consumer to prove.
