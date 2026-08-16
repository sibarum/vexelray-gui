# Keyboard, Focus, Shortcuts, and Text Input — design

The last major slice of the input system, and the widget that depends on all of it. This is the target
and the record of decisions; it builds on the input dispatch already in place (`InputDispatcher`,
`onClick`/`onState`/`onDrag`, wheel + scroll) and consumes tactroller over Atchung like everything else
(architecture.md §8).

Status: **largely shipped.** Keyboard routing, focus, claims (§3), and the text field through multiline —
wrap, vertical navigation, line numbers, spans, undo/redo, clipboard — are all in. What remains is called
out per-section below; the short list is hyperlink/tooltip spans (needs an overlay layer), real
bold/italic (needs multi-atlas), the `Disabled` property, and hard tabs.

Two sections were **superseded by implementation** rather than merely completed, and are rewritten in
place: §3 (shortcuts became claims) and §4.2 (the Tab question is resolved). Where this doc and
docs/layout-read-model.md overlap on text geometry, that one is authoritative.

---

## 0. Substrate: what tactroller gives us, and the one gap

Tactroller publishes discrete input on the `"tactroller.input"` topic. For keyboard it emits
`KeyPressed(Key)` / `KeyReleased(Key)` with a **rich `Key` enum** (A–Z, 0–9, punctuation, F1–F12,
arrows, Home/End/PageUp/Down, Insert/Delete/Backspace, Tab/Enter/Escape, numpad, and the modifier keys
LEFT/RIGHT × SHIFT/CONTROL/ALT/SUPER). Modifiers are derivable from held modifier keys (`Modifier` =
SHIFT/CONTROL/ALT/SUPER).

**The gap: no character event.** Tactroller has no `WM_CHAR`/IME path — it reports key *codes*, not the
*characters* a keystroke produces under the OS keyboard layout, dead keys, or IME composition. Two ways
forward, in order of correctness:

1. **Proper (target): add a character event to tactroller.** A `CharTyped(int codepoint)` event fed by
   Win32 `WM_CHAR` (and the equivalent elsewhere), plus IME composition events later. Locale- and
   layout-correct; the only real answer for international text. This is an engine-side addition to
   tactroller/`tactroller-atchung`, mirroring how E1/E2/E3 landed in VexelRay.
2. **Interim: US-layout translation in the GUI.** Map `Key` + Shift → a character for the US layout
   (`A`→`a`/`A`, `DIGIT_1`→`1`/`!`, …). Serviceable for a first editable field and for ASCII, but wrong
   for other layouts and no IME. Ship it behind the same `CharTyped` abstraction so swapping in tactroller's
   real event later is a no-op for the widget.

The widget consumes an abstract **`CharTyped`**; where it originates (tactroller `WM_CHAR` vs. interim
translation) is hidden behind that seam.

---

## 1. Keyboard events

Framework-level, delivered off the raw tactroller key stream:

- The dispatcher tracks **held modifiers** (updates a `Set<Modifier>` from modifier-key press/release).
- `KeyPressed`/`KeyReleased` carry `Key` + the current modifier set to handlers.
- `CharTyped(codepoint)` (see §0) is the separate text-entry channel; **shortcuts and edit commands are
  keyed off `KeyPressed`, text content off `CharTyped`** — never conflate the two (the classic bug where
  Ctrl+C also inserts a character).

Dispatch order per `KeyPressed`, on the GUI thread during the frame drain:
1. **Claims** (§3) — the applicable claim with the most specific scope runs, and the key reaches nothing
   else. Framework defaults (Tab traversal) are themselves claims, so a focused element can outrank them.
2. If nothing claimed the chord, route to the focused node's key handler (caret movement, backspace,
   selection, …). Core takes nothing for itself here.
3. `CharTyped` (if any) goes only to the focused, editable node.
4. Regardless of 1–3, the press is reported on `gui.keyRoutes()` — every key, who had focus, whether a
   claim preempted it. Observation only (§3).

Note that a newline cannot be *typed*: `'\n'` is a control character, so it rides the key channel and is
filtered out of `CharTyped`. Multi-line text is built through Enter, which is the only path a real
keyboard has.

---

## 2. Focus

- Exactly one **focused node** at a time (or none). Focus is GUI-thread state on the tree.
- **Acquire:** click focuses the clicked focusable node (or nearest focusable ancestor); `Gui.focus(node)`
  sets it programmatically.
- **Tab order:** `Tab` / `Shift+Tab` move focus to the next/previous focusable node in tree (DFS) order.
  A node opts into focusability (text fields, buttons, inputs); plain boxes/text don't.
- **Focus events** publish on the bus (`FocusGained`/`FocusLost` per node) so widgets restyle (focus
  ring) and workers can react. Honors the pointer-target rule: focus changes appearance, never geometry
  or hit-targets.
- A **disabled** node is never focusable and drops out of the Tab order.

---

## 3. Claims — preemption declared in advance

*Supersedes the original "shortcuts get first refusal" design. Kept as a heading because the shortcut API
survives; it is now one scope of a more general thing.*

**Core does not eat input events.** A chord reaches the focused element unless something has *declared*,
ahead of time, that it takes that chord:

```java
gui.claim(node, Shortcut.of(Key.TAB), ClaimScope.FOCUSED, editor::indent);
gui.shortcut(Shortcut.of(Key.S, Modifier.CONTROL), app::save);   // == a GLOBAL claim owned by no node
```

- **Scopes, most specific first:** `FOCUSED` (only while the claiming node holds focus) → `VISIBLE` (while
  it is in the tree — a dialog taking Esc while it is up) → `GLOBAL` (always). When a claim applies its
  command runs and nothing else sees the key.
- **Framework defaults are claims, not interception.** Tab/Shift+Tab traversal is registered as a `GLOBAL`
  claim by the dispatcher, so a focused multiline editor overrides it simply by claiming Tab at `FOCUSED`
  scope — no property, no special case, and core needs no knowledge of what a text field is.
- Commands run on the worker executor like other handlers. The framework's own claims run inline on the
  GUI thread because they mutate dispatch state (focus); that variant is internal, not offered to apps.
- Claims do **not** arm auto-repeat (§8.4), so a held Tab inserts one soft tab rather than a stream.

**Why declared rather than cancelled.** `preventDefault` requires a handler to answer *synchronously* that
it consumed the event. Handlers here run on worker threads through the bus — by the time one could answer,
the frame is over. Bubbling and cancellation are quietly paid for by an isolated GUI thread, which this
framework gave up on purpose (architecture.md §5). Declaring preemption up front buys the same power back:
the dispatcher reads claims on the GUI thread and decides immediately, while the command runs wherever.

**Observation is separate and cannot veto.** `gui.keyRoutes()` reports every press with the focused node
and whether a claim took it. If observing could cancel, this would be `preventDefault` again with the
synchronous assumption in tow. Authority lives in claims; the channel only reports. (The *raw* stream was
never consumed either — `"tactroller.input"` is a plain topic and the dispatcher is one subscriber among
any number.)

---

## 4. Text input widget — requirements

The user-facing spec, covering everything from a single-line field to a code editor. Shipped as
`TextField` in `vexelray-gui-widget` — notably a *pure widget*: every geometric question (where a click
landed, where the line above is, how far a page scrolls) is a lookup on the published read-model, so it
never sees a measurer or a glyph atlas, and the same code drives a field on screen, headless in a test,
or on a remote client with no fonts of its own.

### 4.1 Properties
- **Selectable** — text can be selected (drag / shift+arrows) and copied, even when not editable.
  *[selection + clipboard shipped for editable; non-editable selection not yet]*
- **Editable** — content can be modified. When false, the widget is read-only (still selectable if
  Selectable). *[shipped]*
- **Disabled** — no focus, no interaction, dimmed. Distinct from read-only (a disabled field is inert).
  *[not yet]*
- **Multiline** — single-line (Enter submits / is ignored) vs. multi-line (Enter inserts a newline).
  *[shipped: `TextField.multiline`, with vertical caret-follow scroll and Up/Down over the read-model]*
- **Font (which atlas)** — the glyph atlas to render with. Implies **multi-atlas support** and a per-run
  font selection (ties into formatting spans and bold/italic, §4.4 and §5). *[parked behind multi-atlas]*
- **Size** — text size in **em/rem** (no px, per the layout rule); scales with root em/zoom/DPI. *[shipped]*
- **Display Line Numbers** — a gutter with line numbers (multi-line/code use). *[shipped: numbers count
  hard lines, so a wrapped line is numbered once and its continuations are blank]*
- **Word Wrap** — wrap long lines to the width (reusing `TextLayout` wrapping) vs. horizontal scroll.
  *[shipped, and they are exclusive: a wrapped node never scrolls horizontally]*

### 4.2 Tab key — **resolved and shipped**
- **Soft tabs inside a multiline field, focus traversal everywhere else.** Four spaces; the document holds
  no tab characters, so nothing downstream has to agree about tab width. Hard tabs and elastic tab stops
  stay deferred.
- **No modifier distinction — the focused node decides.** A multiline `TextField` registers a `FOCUSED`
  claim on Tab (§3); a single-line field claims nothing, so Tab traverses as always. This is why the
  resolution needed the claim model rather than a widget-level flag: `InputDispatcher` used to consume Tab
  before `onKey` ever saw it.
- **Shift+Tab is deliberately left unclaimed**, so there is always a keyboard way out of the field.
  (Shift+Tab as outdent is a later option, and would be a second claim.)

### 4.3 Text model, editing, undo/redo
- Content storage: the rope/piece-table is **deferred**, and shipped as a plain `StringBuilder` +
  `List<Span>` (architecture.md §9). The load-bearing idea was the diff, not the storage; a rope is a
  large-document optimization to add when there is a large document.
- **Undo/redo** is built on a **diff** between snapshots. **The same diff drives auto-diff spans (§4.4)** —
  one edit-diff mechanism, reused: an edit produces a diff (inserted/removed ranges); undo/redo replay it,
  and every span remaps its offsets through it.

### 4.4 Spans
A span is a `[start, end)` range over the content carrying attributes. Kinds:

- **Formatting spans** — visual only: **background color, text color, underline, bold, italics**.
  (Bold/italics: see §5 — faux via MSDF/shear or real via bold/italic atlases.)
- **Hyperlink spans** — emit **hover and click events** for the spanned text.
  - If the input is **not editable**: events fire **whenever** the pointer hovers/clicks the span (while
    the span is active).
  - If the input **is editable**: events fire **only while Control is held** (so links don't hijack normal
    text editing — Ctrl+click / Ctrl+hover to activate, matching editors like VS Code).
- **Tooltip spans** — a hyperlink span that additionally **spawns a tooltip** (needs the overlay layer,
  §6). Same editable/Control gating as hyperlink spans.
- **Auto-diff spans** — **all spans automatically update when the content changes**, remapping their
  offsets through the edit diff (§4.3). An insert before a span shifts it; an edit inside it grows/shrinks
  or splits it per well-defined rules.
- **Span management** — spans can be updated **one at a time** (add/remove/replace a single span) **or
  cleared and refreshed all at once** (replace the whole span set — e.g. re-running a syntax highlighter).

### 4.5 Selection, caret, clipboard
- Caret (blink), selection highlight (a formatting-like background over the selected range), standard
  caret motion (arrows, word-jump with Ctrl, Home/End, PageUp/Down), selection via Shift+motion and
  pointer drag (reuses `onDrag` capture).
- Clipboard cut/copy/paste via `tactroller-clipboard` (already available, standalone).

---

## 5. Bold / italics — a rendering dependency

The atlas is currently a single **Regular** face (Noto Sans). Bold and italic formatting spans need
glyph coverage. Two options, not exclusive:

- **Faux (no new atlas):** MSDF supports a **weight/edge-shift** for faux-bold (architecture.md §9), and a
  **shear transform** in the emitter gives faux-italic. Cheap, immediate, approximate.
- **Real (multi-atlas):** bake **bold** and **italic** atlases and select the face per run (the "Font"
  property, §4.1). Correct shapes, needs multi-atlas + font-fallback plumbing (also what icon glyphs
  need — see the font-atlas notes).

Ship faux first; add real faces with the multi-atlas work.

---

## 6. Dependencies / prerequisites

| Need | For | Where |
|------|-----|-------|
| **`CharTyped` event** (WM_CHAR/IME) | typing real text | tactroller (proper) or interim US-layout map in GUI (§0) |
| **Overlay / popup layer** | tooltip spans, and menus/dialogs generally | new GUI layer (z-above the tree; consumes E3 clip) |
| **Multi-atlas + font fallback** | `Font` property, real bold/italic, icon glyphs | vexelray-text + GUI (faux bold/italic is the interim) |
| **Focus + shortcuts** | everything editing | this doc, foundation first |

---

## 7. Build sequence

1. **Keyboard routing + modifiers + focus + claims** — the foundation (§1–3). **[done]**
2. **`CharTyped` seam** — interim US-layout translation now; swap to tactroller `WM_CHAR` when it lands.
   **[done, interim]**
3. **Text render** — formatting spans (fg/bg/underline), word wrap, line numbers, clipboard copy.
   **[done; faux bold/italic still pending §5]**
4. **Editing** — caret, insert/delete, the edit-diff, undo/redo. **[done]**
5. **Auto-diff spans** — remap all spans through the edit-diff; single + bulk span updates. **[done]**
6. **Multiline** — Enter, wrap, Up/Down with a sticky desired column, visual Home/End, PageUp/Down,
   vertical scroll, soft tabs, line numbers. **[done — docs/layout-read-model.md §11]**
7. **Hyperlink / tooltip spans** — hover/click emission with the editable/Control gating; tooltip needs
   the overlay layer (§6). **[not started — the overlay layer is the blocker]**
8. **Remaining polish** — `Disabled`, non-editable selection, hard tabs, Shift+Tab outdent.

---

## 8. Refinements (post-slice requirements)

Landed on top of the single-line editable field + selection/clipboard slices:

8.1 **`Ctrl+Backspace` / `Ctrl+Delete` = word delete.** Ctrl+Backspace deletes to the previous word
   boundary and Ctrl+Delete to the next — the same boundaries `Ctrl+←/→` navigate (§8.2). Widget-level.

8.2 **Word motion is boundary-stopping, not greedy.** A *word char* is a letter, digit, `-`, or `_`;
   everything else (whitespace, punctuation) is a separator. `Ctrl+←/→` moves to the nearest boundary:
   it skips leading whitespace, then consumes a single run of *either* word chars *or* non-space
   separators — so it stops at punctuation clusters instead of leaping over them. `-` and `_` are kept
   inside words on purpose (identifiers like `foo_bar-baz` are one word).

8.3 **I-beam cursor over selectable/editable text.** While the pointer is over a selectable or editable
   text node, the OS cursor becomes the text-placement I-beam; elsewhere it is the default arrow. The GUI
   computes the desired shape from the hovered node and drives it through a cursor seam (`CursorShape` +
   an app-installed setter over the window); honors the pointer-target rule (appearance only).

8.4 **Held-key auto-repeat.** Holding a command key (arrows, Backspace, Delete, Home/End, …) fires once,
   waits an initial delay, then auto-repeats at a fixed interval until release — synthesized by the
   dispatcher off its held-key state and the per-frame `dispatch()` clock (tactroller reports the OS key
   *edge*, not repeats, since it polls state). Repeats replay only the focused-node key route, never
   one-shot shortcuts or Tab traversal.

8.5 **Scroll-lock (log tailing) on non-editable scrollers.** A scroll container can be locked to TOP or
   BOTTOM. While *attached*, it stays pinned to that edge as content grows (the tail-the-log case). If the
   user scrolls away from the edge it *detaches*; scrolling back onto the edge re-attaches. Purely a
   scroll-offset behavior over the existing overflow/scroll model — no geometry change (pointer-target
   rule).
