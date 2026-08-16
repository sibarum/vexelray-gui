# Proving the architecture end-to-end

Status: **plan**. The framework's bet is that routing everything through Atchung — input, GUI mutations, the
layout read-model, app events — buys three things at once: (1) **transport-agnostic** messaging that always
takes the fastest available path, (2) **relocatable** components (same code in-VM, cross-process, or across the
wire), and (3) **testability**, including of the transport layers themselves. Elektro-Q supplies the wire
(`local`, `uds`, `tcp`, `udp`+netcode) and the bridge; it is already native-image-proven on its own.

None of that is proven *for our system* yet. This document is the plan to prove it — each claim as a falsifiable
test, automated, with a hard separation-of-concerns contract and a native-image track that must stay easy.

---

## 1. The claims to falsify

| # | Claim | How it's proven |
|---|---|---|
| **C1** | **Transport-agnostic.** Widget + GUI-core code is *unchanged* whether the bus is in-VM or bridged to a remote peer. | The same headless TextField scenarios pass with events crossing an `ElektroBridge` (M4). **Precondition:** no behaviour may live in the renderer — `CaretScrollTest` currently proves some does (layout-read-model.md §11.4), and must be green before M4 means anything. |
| **C2** | **Always-fastest.** Enabling remote transport adds *zero* cost to the in-VM path. | Structural (dependency test, M0) + a micro-benchmark: in-VM publish/deliver latency is unchanged with a bridge attached to unrelated topics (M-perf). |
| **C3** | **Transports are logically correct**, including under adverse networks. | One conformance suite run against every transport (`local`/`uds`/`tcp`/`udp`), and under `SimTransport` loss/latency/reorder (M2). |
| **C4** | **The read-model reconstructs over the wire** — a peer with no atlas rebuilds GUI state and round-trips input. | A remote consumer rebuilds `LayoutSnapshot` and drives the field via bridged input (M4); visual thin client (M5). **Now materially closer:** every text node — labels included — has its glyph positions, alignment, caret geometry and line numbers baked into `TextMetrics` at publish, and `TreeRenderer` measures nothing. A peer with no atlas has, in principle, everything it needs to draw. |
| **C5** | **Separation of concerns** holds: each layer builds/tests without the layers above it; only bus message types cross. | An automated architecture (dependency) test that fails on an upward or illegal dependency (M0), enforced forever. |
| **C6** | **Native-image is easy** — a representative binary builds native with no hand-written reachability config and passes the conformance suite. | `mvn -Pnative verify` on the host/conformance target (M6), reusing elektro-Q's proven approach. |

"Proven" = a green, automated check a CI can run — not a one-off manual demo (except the visual thin client M5,
which is corroborating, not the proof).

---

## 2. Separation-of-concerns contract (the thing M0 enforces)

Dependencies flow strictly downward; the **only** things that cross a process boundary are message types.

```
 application (demo / host / client)   ── owns transport choice + bridge wiring
   ├─ vexelray-gui-widget             ── depends on gui-core only
   ├─ vexelray-gui-core               ── depends on atchung-core, vexelray, tactroller-api  (NOT elektroq)
   ├─ vexelray-gui-architecture       ── test-only; reads the compiled classes of the two above (M0)
   ├─ tactroller-*                    ── input; knows nothing of the GUI
   ├─ atchung-core                    ── pure in-VM bus; zero deps
   ├─ elektroq-*                      ── wire stack; knows nothing of the GUI or atchung
   └─ atchung-elektroq (ElektroBridge)── depends on atchung-core + elektroq only
```

Rules the architecture test asserts:
1. **`vexelray-gui-core` and `-widget` never import `elektroq` or `atchung.elektroq`.** The GUI speaks only
   `atchung-core` topics/State. Transport is chosen at the application edge.
2. **The wire contract is a dedicated set of message types**, not the internal model. Internal records
   (`Mutation`, `RetainedNode`, `NodeLayout`, `TextMetrics`, `TextEdit`, tactroller `InputEvent`) stay free of
   elektro-Q annotations; the application (or a small `*-wire` contract module) defines `@Message` DTOs and maps
   to/from them. This keeps `gui-core` decoupled from the wire and lets the DTOs version independently.
3. **`atchung-core` has no dependency on `elektroq`** and vice-versa — the bridge is the only meeting point, so
   "the fast in-VM path never pays for the network" is structural, not disciplined.
4. **Only the declared stages write the retained model** (layout-read-model.md §2.1): `TreeRenderer` and the
   widget module never assign to a `RetainedNode` field, and `publishLayout` only copies. Enforced by
   `ModelWriterGuardTest` (M0), which is what keeps C1 honest: behaviour that lives in the renderer cannot
   survive a host that has no renderer. The one part that stays a convention is "publish performs no
   arithmetic" — not mechanically checkable, so it lives in layout-read-model.md §9 as a rule.

The test is cheap (parse module POMs / package imports) and, once in place, guards C5 permanently.

---

## 3. Milestones

Each milestone states what it **proves**, its **deliverable**, and its **automated acceptance**. They build in
order; every one leaves the tree green.

### M0 — Contracts & the architecture guard *(proves C5, cheaply, first)* — **LANDED**
- **Deliverable:** `vexelray-gui-architecture`, a test-only module (no main sources, test-scope deps only, so
  nothing reaches a runtime or a native image). It reads the **compiled classes** of `gui-core` and `gui-widget`
  rather than their source: a source scan is defeated by an alias, a fully-qualified name or a wildcard import,
  whereas the constant pool records what the compiler actually emitted.
  - `LayeringGuardTest` — §2 rules 1 and 3: no GUI class references `sibarum/elektro` or `sibarum/atchung/elektroq`.
  - `ModelWriterGuardTest` — §2 rule 4: only the five declared stages write `RetainedNode` fields, detected as
    `PUTFIELD`/`PUTSTATIC` against that owner. The allowlist is the phase list of `Gui.frame`; widening it is an
    architectural decision, never a way to fix a red build.
- **Acceptance:** green, and *demonstrated* red. Each guard carries self-tests that run its detector over
  synthesized bytecode — a violation must be reported, a legitimate write must not — because a detector that
  silently matches nothing is indistinguishable from a clean codebase. Verified end-to-end by reintroducing
  `n.scrollX = 0f` into `TreeRenderer`: the guard failed with
  `TreeRenderer.drawSelf() writes RetainedNode.scrollX`, naming the exact method of the original bug.
- **Anti-vacuity:** `Bytecode.classFiles` throws when a scan finds no classes, and modules resolve by path from
  the reactor root rather than off the classpath — otherwise the guard could quietly inspect a stale installed
  jar and pass while the working tree violates the rule.
- **Why first:** it makes every later step's separation claim self-enforcing instead of aspirational.

### M1 — Wire contract for the bus types *(foundation for C4)*
- **Deliverable:** `@Message` DTOs for the types that must cross — start with the read-model (`LayoutSnapshot`,
  `NodeLayout`, `Rect`, `TextMetrics`) and input (`InputEvent`, incl. `CharTyped`) — plus adapters mapping
  internal ↔ wire. Codecs are generated by `elektroq-codegen` (no hand-written serialization).
- **Acceptance:** a wire round-trip test per type (`serialize → deserialize → equals`), mirroring elektro-Q's
  `WireRoundTripTest`. Confirms the read-model + input are transport-ready and that codegen covers them.
- **Design note:** prefer DTOs + adapters over annotating internal records, to honor §2 rule 2.

### M2 — Transport conformance suite *(proves C3)*
- **Deliverable:** one abstract conformance suite parameterized by a `Transport`/`Conduit` factory, asserting the
  delivery contract: reliable ordered delivery, request/reply with correlation + timeout, backpressure, and
  clean disconnect/reconnect.
- **Run it against:** `elektroq-transport-local`, `-uds`, `-tcp` (loopback), and `udp`+reliable channel. Then
  re-run the reliable cases under `SimTransport` with injected loss/latency/reorder.
- **Acceptance:** identical suite passes on every transport; the reliable channel still delivers correctly under
  simulated loss. This is the "verify the transport layers are logically functional, automated" goal.

### M3 — Bridge conformance *(proves the C1 mechanism)*
- **Deliverable:** tests for `ElektroBridge`: a topic published on bus A reaches bus B and back; inbound
  re-publications are not echoed to the wire; no peer-to-peer relay.
- **Run it over:** `transport-local` (in-process, fully deterministic) and `tcp` loopback.
- **Acceptance:** message identity preserved both directions, loop-prevention holds.

### M4 — Headless remote GUI *(the end-to-end proof: C1 + C4)*
- **Deliverable:** extend the existing headless harness into a two-bus setup — a **host** `Gui` (logic + layout,
  no renderer) bridges its layout Topic + input Topic to a second bus; a **client** side subscribes to
  `LayoutSnapshot` and publishes input — wired over `transport-local` for determinism.
- **Acceptance:** the *same* TextField scenarios from `TextFieldTest` (type, undo/redo, selection, click-to-caret,
  span auto-diff) pass with every event crossing the bridge, and the client reconstructs the field's rendered
  state (text + caret geometry) purely from the received snapshot — **no atlas on the client**. Then re-run the
  core scenarios over `tcp` loopback and under `SimTransport` loss to prove robustness.
- **Why it's the proof:** it demonstrates identical widget behavior over a real transport, the read-model
  reconstructing remotely, and it reuses the deterministic harness so it's CI-able — no window, no GPU.

### M5 — Live thin client *(corroborating, visual)*
- **Deliverable:** the demo split into two processes: **client** = Vulkan renderer + tactroller (subscribes to
  `LayoutSnapshot`, draws; publishes input) ↔ **host** = GUI logic + layout, over `tcp`/`uds`.
- **Acceptance:** interact with the field from the client process; capture/inspection confirms parity with the
  in-VM demo. Not the formal proof (manual), but the convincing one.

### M-perf — Always-fastest *(proves C2 empirically)*
- **Deliverable:** a micro-benchmark of in-VM publish→inline-deliver latency for a hot topic, measured with and
  without an `ElektroBridge` attached to *unrelated* topics.
- **Acceptance:** no measurable regression; combined with M0's structural guarantee that `atchung-core` never
  references `elektroq`.

---

## 4. Native-image track *(proves C6 — and that it stays easy)*

Elektro-Q already ships a self-verifying native binary (`mvn -Pnative verify`, zero `reflect-config`). The plan
extends that guarantee across the boundary we care about, in increasing difficulty:

1. **Wire + conformance native.** Build a native binary that runs the M2 conformance suite (codegen codecs, no
   reflection). Acceptance: `mvn -Pnative verify` green with **empty** reachability config. (Low risk — this is
   elektro-Q's home turf.)
2. **Headless host native.** Native-build the M4 host (Gui logic + layout + bridge, no rendering). The GUI core
   is pure Java + FFM-free; the only native concern is FFM in tactroller if the host owns input. Acceptance:
   native host runs the M4 headless scenario against a JVM client. Document any `--enable-native-access` flags.
3. **Thin client native (stretch).** Native-build the M5 client (Vulkan + FFM via Panama). This is the hard part;
   FFM downcalls are native-image-supported but Vulkan/window bring-up may need config. Acceptance: it runs, with
   the reachability config documented and justified. Treated as a stretch goal, explicitly *not* gating C6.

**"Easy" is itself an acceptance criterion:** each native build is a single `mvn -Pnative` invocation; any
required config is checked in, minimal, and commented. If a step needs hand-maintained reflection/JNI config, that
is a finding to fix (e.g., replace reflection with codegen), not something to paper over.

---

## 5. Cross-cutting notes

- **Traffic sorts by transport class.** Mutations, input edges, and `TextEdit`s are lossless-required → reliable
  ordered channel. The read-model is a `State<T>` (latest-wins) → safe over lossy/`COALESCE_LATEST`, since a
  dropped intermediate snapshot is superseded; the `version` field is the replication + delta-encoding hook.
- **Deterministic network testing.** `SimTransport` + the manual-drain headless executor = replay a scenario
  under injected loss/latency and assert the outcome, with no real threads or sockets. This is the automation the
  whole bet hinges on.
- **Snapshot volume** over the wire is a *performance* concern, not correctness: full-tree per changed frame is
  fine to prove C4; delta encoding (keyed by `version`) is a later optimization, out of scope here.

## 6. Risks / unknowns
- **Vulkan + native-image** (M-native step 3) is the real unknown; keep it off the critical path for C6.
- **FFM in native-image** (tactroller/vexelray) needs `--enable-native-access` and possibly foreign config;
  document precisely.
- **DTO duplication:** wire DTOs + adapters add boilerplate. Mitigate by generating/deriving them where possible;
  accept a little duplication as the price of keeping `gui-core` off the wire (§2).
- **Coordinate space / DPI** differ between a thin client and host; input must be mapped at the edge (M5 concern).

## 7. Non-goals (for this proof)
Performance tuning, wire security/auth, multi-peer fan-out/relay (the bridge deliberately doesn't relay), and
delta encoding. The goal is to prove the architecture is *correct, relocatable, testable, and native-easy* — not
to ship a production remote GUI.

---

## 8. Suggested order
~~`M0` (guard)~~ **done** → `M1` (wire types) → `M2` (transport conformance) → `M3` (bridge) → `M4` (headless
remote — the proof) → `M-perf` + native steps 1–2 in parallel → `M5`/native step 3 as corroboration. Each is
independently green and independently useful.
