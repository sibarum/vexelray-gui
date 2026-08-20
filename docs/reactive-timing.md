# Reactive timing — derivation, conduits, and logical time

> **Status: design note. None of this is implemented.** It records decisions reached in discussion,
> separates the ones that must be made *now* from the ones that can wait, and names the open questions
> honestly rather than pretending they are settled. Nothing here changes current behaviour.

## Why this exists

A two-window bug motivated it. A chord (`Ctrl+Shift+O`) opened a modal native dialog, which blocks the
frame loop; the user released both modifiers while it was open; a second window took focus before polling
next ran, so the *release* edges were attributed to a frame in which this window was already unfocused, and
the focal gate discarded them. `CONTROL` and `SHIFT` stayed latched forever, and from then on every
Ctrl-only global shortcut silently stopped matching — while the focused text field's own `Ctrl+A/C/V` kept
working, because a shortcut compares its modifier set *exactly* and a widget merely asks whether `CONTROL`
is *among* them.

That is not really a focus bug. **The temporal truth was destroyed before any logic ran:** the release
happened at a time when the window *was* focused, and nothing in the system could say so. Edge detection by
polling can only report "different from last glance" — never *when*, and never a transition that begins and
ends between two glances.

So the target is a model in which that class of bug is unrepresentable rather than patched.

## The two parents

- **Vue** answers *what depends on what* — fine-grained reactivity, automatic dependency tracking,
  derivation you never invalidate by hand.
- **ChucK** answers *when things happen* — time as a first-class value, advanced explicitly, with
  computation taking zero logical time.

Neither answers the other's question, which is why the pair covers the space.

## What already exists here

The spine both parents need is largely built:

| Piece | Today | Reads as |
|---|---|---|
| `State<T>` + declared mutations + bounded history | atchung-core | a `ref`, and an MVCC version store |
| Mutation `Topic` drained once per frame | vexelray-gui-core | a batched scheduler (`nextTick`) |
| `Document.apply(Edit)` over *relative* edits | vexelray-gui-core | a reducer whose edits commute |
| Broadcast subscribe/publish | atchung-core | record and replay, for free |

Two organs are missing: **auto-tracked derivation** (today consumers subscribe by hand and layout
recomputes wholesale) and **logical time** (with shreds). This is an addition to an existing structure, not
a rewrite.

Note also that glitch-freedom has already been discovered the hard way: `Document` groups text, caret,
anchor and spans into one atomic value precisely because publishing them separately let a frame boundary
observe a half-updated state. A topologically flushed dependency graph is that fix generalised.

## The model: two rules

1. **Intra-conduit — Vue semantics.** Derivation is instantaneous and glitch-free, flushed in dependency
   order. Reading a derived value inside a conduit always yields a consistent one. This is exactly ChucK's
   "computation takes zero virtual time", so the two models agree here rather than compete.
2. **Inter-conduit — MVCC snapshot reads.** No ambient tracking crosses a conduit boundary. A read is
   recorded as an explicit edge: *conduit A read conduit B as of T*, carrying `(conduit, version, stamp)`.

Consequence to state plainly: **glitch-freedom is per-conduit; across conduits you get snapshot consistency
instead.** You never observe a torn value, but you may observe a stale one. Global glitch-freedom across
threads and machines would require global barriers, and is not on offer.

## Cross-conduit reads are strictly in the past

`T_read < T_now`. Same-time cross-conduit reads are forbidden. This single rule buys three things:

- boundary glitches become unrepresentable, rather than being scheduled around;
- **feedback loops become well-founded** — `A@T` depending on `B@T−1` is how you express a spring, a
  simulation step, or two windows observing each other, and it cannot deadlock;
- **the local design and the distributed design are the same design.** A remote read is *necessarily* in the
  past, because latency. Going over the wire does not change the discipline; it only widens epsilon.

That last point is what makes "ready for the network later" achievable cheaply instead of aspirational.

## Conduits

A conduit is the unit of *consistency*, of *scheduling*, and of *tracking confinement*. Ambient dependency
tracking works by a "currently evaluating" stack, so it needs confinement — but only to whoever is
**advancing that conduit**, one advancer at a time. It does **not** need a single global GUI thread, which
means conduits may advance in parallel (this matters for a game engine, and is a weaker constraint than it
first appears).

Windows, workers, input devices and — eventually — remote peers are all conduits, described by one
abstraction.

## Decide now

These five are representational. Each is cheap today and diffuses to every call site later.

1. **Stamps are opaque, not arithmetic.** The moment code writes `t2 - t1 < 100ms` over raw longs, a single
   global scalar clock is baked into every call site. A `Stamp` type whose only cross-conduit operation is
   *ordering* is the type-level encoding of "there is no global time".
2. **Every stamp carries its origin conduit.** A stamp without provenance cannot be interpreted relative to
   a peer later, and adding the field afterwards means touching every event and every commit.
   `(conduitId, counter, wallHint)` is HLC-shaped without being an HLC yet.
3. **Expose `happensBefore`, never `compareTo`.** Publish a total order and callers will assume totality,
   making *concurrency* unrepresentable exactly when it starts to exist. Partial-order API now; a totally
   ordered implementation underneath is fine.
4. **Cross-conduit reads go through an explicit "as of" call from day one**, even while the answer is
   trivially at hand in-process. Lock the shape; keep the body dumb. An implicit read today is a migration
   site tomorrow.
5. **Physical for magnitude, logical for order.** Animation and input genuinely need real durations;
   *ordering* must never depend on them. In-process the two coincide — which is precisely why this has to be
   written down, because nothing local will ever punish a violation. **This is the rule that rots
   silently.**

## Deliberately deferred

Everything is in-process or same-machine, so none of this is needed yet, and the five rules above are what
keep the door open:

- hybrid logical clocks, clock skew, drift;
- cross-peer retention and distributed low-water marks;
- conflict *resolution* beyond single-writer (in-process, each `State` has one writer);
- rollback / re-simulation (later, the same machinery as netcode).

## Open questions

- **Clock authority.** In-process the frame loop can be the single authority — ChucK's VM model, simple and
  exact. Across processes it cannot be, and stamps need an HLC to stay comparable. Deciding this late means
  retrofitting every timestamp comparison, so decide it *before the wire* — not before now.
- **Time-debt policy — the hard one.** ChucK may pretend computation is instantaneous; a GUI laying out a
  large tree, tokenising a big file, or blocking on a native modal dialog may not. When logical time falls
  behind real time, the correct response *differs per concern*: input wants **order preserved** (replay the
  buffered interval in true order), animation wants **catch-up** (sample at real time; do not replay three
  seconds of easing in one frame). Conflating them would create a fresh bug class.
- **Retention.** Answering "B as of T" requires B to still hold T, so retention must be reader-driven — a
  low-water mark over the oldest timestamp any conduit might still read — not a fixed depth. And **"read too
  old" must fail loudly**; silently clamping to the oldest retained version produces exactly the
  intermittent-staleness bug that is hardest to diagnose.
- **Conflict policy per state.** Last-write-wins is right for pointer position or an animation target, and
  wrong for anything a human authored, where it discards intent. `Document`'s relative `Edit`s already
  commute, which is the better answer where it applies.

## Staging

Each stage is independently useful; nothing here requires the whole vision to land first.

| # | Work | Payoff on its own |
|---|---|---|
| 1 | Logical clock; events carry stamps; delivered in stamp order within a frame | Fixes the attribution class that caused the latch bug |
| 2 | Message-based keyboard input in the Windows backend | Retires edge-loss; makes stage 1's stamps *true* |
| 3 | Animation as `f(logical time)` | No drift, frame-rate independence, assertable motion |
| 4 | Shreds (virtual threads + `advance(dur)`) | Gestures as straight-line code **and** the virtual-user harness |
| 5 | Stamps on `State<T>` commits | Multi-window/remote ordering; later, rollback |

Stage 2 is not an optimisation. Polling destroyed the input conduit's history before any reader could ask
"as of T" — `GetAsyncKeyState` diffing cannot answer that question in principle, so without stage 2 there is
no input conduit to read.

Java 25 is what makes stage 4 practical: a shred is a virtual thread parked until the scheduler's clock
reaches its wake time, cheap enough to have thousands, with structured concurrency scoping its lifetime to a
widget or window. The discipline it inherits from ChucK is that a shred must not block on real I/O, or it
breaks timing for everyone.

## Consequences for testing

This is where the model pays for itself soonest, because a virtual user *is* a shred:

```
press(CTRL); advance(120::ms); press(W); advance(200::ms); release(W)
```

The same primitive expresses the app's gesture recognisers and the test's synthetic user. With a **virtual**
clock, `advance` is instantaneous and exact — no sleeps, no flakes, and the "press and release faster than
the poll can see" failure mode cannot exist, because there is no poll.

Two further mechanisms, which fail differently and are both wanted:

- **A model-based virtual user catches what you thought to model.** It would have caught the latch bug —
  *if* the model included "the user releases modifiers while another window has focus", which is exactly the
  scenario nobody writes by hand.
- **An invariant watchdog catches what you did not think to model.** A subscriber asserting paired-edge
  sanity (no release without a press; nothing latched across a focus transition) fires on every harness run
  whatever the test was about. This is the backstop, and it is why example-based gate tests were not enough:
  they asserted steady states, and the corruption happened *at the transition*.

Two standing notes, learned expensively:

- **`HeadlessGui` currently publishes events past tactroller**, so the routing gates are not in the test path
  at all — which is precisely how the latch bug slipped through a green suite. A virtual user must drive
  `InputPublisher`, not the bus directly.
- **Tests must never inject desktop-wide input.** `SendInput` delivers to whatever window has focus — the
  developer's, not the test's — so it can operate other applications. `RawInputFanoutTest` is therefore
  opt-in behind `-Dtactroller.injectInput=true`.

## Where the pieces would live

| Layer | Home | Why there |
|---|---|---|
| Record/replay of a topic | `atchung-core` (or test scope until proven) | Generic and semantics-free; a recorder is just a subscriber |
| `Stamp`, conduit identity, `happensBefore` | `atchung-core` | Both the bus and `State` need them; no domain knowledge |
| Paired-edge invariants; `VirtualUser` / `VirtualDesktop` | tactroller | Owns input semantics; the gates must be in the test path |
| Logical clock driven by the frame loop; derivation; shreds | vexelray-gui-core | Owns the frame, the retained tree, the single-writer reconciler |
| "Why didn't this fire?" reporting | vexelray-gui-core | Builds on the `KeyRouted` observation channel |

The bus must not learn that `KeyPressed`/`KeyReleased` are a pair, or that focus gates them. That is domain
semantics, and keeping the core a dumb transport is why it is fast and portable.

## The diagnostic payoff

With read edges recorded as `(conduit, version, stamp)`, the dependency log *is* the diagnostic. "Why didn't
this update?" stops being an afternoon of hypotheses and becomes a query:

> you read `files-window@1.20s`; it committed at `1.55s`; nothing invalidated you.

Given how the motivating bug was actually found — by reading code after several wrong guesses, and one
detour that injected keystrokes into the developer's desktop — that is the single largest improvement on
offer here.
